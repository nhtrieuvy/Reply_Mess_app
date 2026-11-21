package com.drivevoice.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.drivevoice.app.MainActivity
import com.drivevoice.app.R
import com.drivevoice.app.data.bluetooth.BluetoothDevices
import com.drivevoice.app.data.model.MessageBatch
import com.drivevoice.app.data.model.NotificationLifetime
import com.drivevoice.app.data.model.ReplyTarget
import com.drivevoice.app.data.model.isAvailable
import com.drivevoice.app.data.notification.NotificationInbox
import com.drivevoice.app.data.repository.SettingsRepository
import com.drivevoice.app.domain.queue.MessageQueueManager
import com.drivevoice.app.domain.state.DriveInteraction
import com.drivevoice.app.domain.state.DrivePrompt
import com.drivevoice.app.domain.state.DriveResult
import com.drivevoice.app.domain.state.DriveState
import com.drivevoice.app.domain.state.DriveStateMachine
import com.drivevoice.app.domain.state.ReplyWindow
import com.drivevoice.app.domain.state.SpeechResult
import com.drivevoice.app.domain.state.VoiceInput
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ReadingStatus(val active: Boolean = false) {
    IDLE, STARTING(true), WAITING(true), SPEAKING(true), FOCUS_UNAVAILABLE(true),
    LISTENING(true), CONFIRMING(true), SENDING(true), HANDED_OFF(true),
    REPLY_UNAVAILABLE(true), CHOOSE_REPLY_HEADSET(true), REPLY_FAILED(true), PLAYBACK_ERROR(true), NOT_CONNECTED, NO_OFFLINE_VOICE, FAILED,
    HEADSET_DISCONNECTED, IDLE_TIMEOUT,
}

@AndroidEntryPoint
class DriveForegroundService : Service() {
    @Inject internal lateinit var inbox: NotificationInbox
    @Inject internal lateinit var voice: VoiceEngineManager
    @Inject internal lateinit var recognition: SpeechRecognitionManager
    @Inject internal lateinit var settings: SettingsRepository
    @Inject internal lateinit var bluetooth: BluetoothDevices
    @Inject internal lateinit var activation: BluetoothActivation

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val replayRequests = Channel<MessageBatch>(Channel.CONFLATED)
    private var lastBatch: MessageBatch? = null
    private var currentBatch: MessageBatch? = null
    private var turn: Job? = null
    private var idleTimer: Job? = null
    private var replayExpiry: Job? = null
    private var sessionHeadset: String? = null
    private var queue: MessageQueueManager? = null
    private var closing = false
    private var released = false
    private var endStatus = ReadingStatus.IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!closing && queue == null) {
                sessionHeadset = intent.getStringExtra(EXTRA_HEADSET)
                startReading(intent.getBooleanExtra(EXTRA_REPLIES, false), intent.getBooleanExtra(EXTRA_AUTO_SEND, false))
            }
            ACTION_REPLAY -> if (!closing && status.value.replayAllowed) lastBatch?.let { replayRequests.trySend(it) }
            ACTION_STOP -> {
                activation.suppress(sessionHeadset)
                finish(ReadingStatus.IDLE)
            }
            else -> finish(ReadingStatus.IDLE)
        }
        return START_NOT_STICKY
    }

    private fun startReading(requestReplies: Boolean, autoSend: Boolean) {
        val enableMicrophone = requestReplies && SpeechRecognitionManager.hasPermissions(this)
        try {
            var serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enableMicrophone) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(
                this, STATUS_ID, statusNotification(), serviceType,
            )
        } catch (_: IllegalStateException) {
            finish(ReadingStatus.FAILED)
            return
        } catch (_: SecurityException) {
            finish(ReadingStatus.FAILED)
            return
        }

        val readingQueue = MessageQueueManager(scope)
        queue = readingQueue
        scope.launch {
            var buttons: MediaButtonHandler? = null
            try {
                val address = sessionHeadset
                if (address != null) {
                    val connected = withTimeoutOrNull(5_000) {
                        bluetooth.snapshots.first { devices -> devices.outputs.any { it.address == address } }
                    }
                    if (connected == null) {
                        finish(ReadingStatus.HEADSET_DISCONNECTED)
                        return@launch
                    }
                    scope.launch {
                        bluetooth.snapshots.first { devices -> devices.outputs.none { it.address == address } }
                        finish(ReadingStatus.HEADSET_DISCONNECTED)
                    }
                }
                if (!MessageNotificationListener.isConnected.value) {
                    NotificationListenerService.requestRebind(
                        ComponentName(this@DriveForegroundService, MessageNotificationListener::class.java),
                    )
                    withTimeoutOrNull(4_000) { MessageNotificationListener.isConnected.first { it } }
                }
                var config = settings.config.first()
                if (!inbox.start(readingQueue, enableMicrophone, ::onWithdrawal) { config.accepts(it) }) {
                    finish(ReadingStatus.NOT_CONNECTED)
                    return@launch
                }
                scope.launch {
                    settings.config.collect { updated ->
                        config = updated
                        if (currentBatch?.messages?.any { !updated.accepts(it) } == true) turn?.cancel()
                        if (lastBatch?.messages?.any { !updated.accepts(it) } == true) clearReplay()
                    }
                }
                scope.launch {
                    MessageNotificationListener.isConnected.first { !it }
                    finish(ReadingStatus.NOT_CONNECTED)
                }
                if (!voice.initialize()) {
                    finish(ReadingStatus.NO_OFFLINE_VOICE)
                    return@launch
                }
                val replies = enableMicrophone && try {
                    recognition.hasVietnameseModel()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    false
                }
                if (replies) buttons = MediaButtonHandler(this@DriveForegroundService)
                val waiting = if (requestReplies && !replies) ReadingStatus.REPLY_UNAVAILABLE else ReadingStatus.WAITING
                status.value = waiting
                armIdleTimer()
                val interaction = SessionInteraction(buttons, config.headsetOnlyReplies, config.headsetAddress)
                val machine = DriveStateMachine(interaction) { state ->
                    when (state) {
                        DriveState.SPEAKING_MSG -> status.value = ReadingStatus.SPEAKING
                        DriveState.LISTENING_REPLY -> status.value = ReadingStatus.LISTENING
                        DriveState.CONFIRMING_REPLY -> status.value = ReadingStatus.CONFIRMING
                        DriveState.SENDING -> status.value = ReadingStatus.SENDING
                        DriveState.IDLE, DriveState.READY -> Unit
                    }
                }
                merge(
                    readingQueue.batches.map { it to false },
                    replayRequests.receiveAsFlow().map { it to true },
                ).buffer(0).collect { (queued, replay) ->
                    val now = SystemClock.elapsedRealtime()
                    val maxAge = if (replay) MAX_REPLAY_AGE_MS else MAX_QUEUE_AGE_MS
                    val allowed = queued.messages.filter { config.accepts(it) && it.isAvailable(now, maxAge) }
                    if (allowed.isEmpty()) return@collect
                    val batch = MessageBatch(allowed)
                    if (address != null && !bluetooth.canPlayOn(address)) {
                        status.value = ReadingStatus.FOCUS_UNAVAILABLE
                        return@collect
                    }
                    idleTimer?.cancel()
                    clearReplay()
                    currentBatch = batch
                    coroutineScope {
                        val processing = launch(start = CoroutineStart.LAZY) {
                            senderName.value = batch.displayName
                            interaction.resetMic()
                            when (machine.read(batch, replies && !replay && config.handsFree, autoSend && config.autoSend)) {
                                DriveResult.DONE -> status.value = waiting
                                DriveResult.FOCUS_UNAVAILABLE -> status.value = ReadingStatus.FOCUS_UNAVAILABLE
                                DriveResult.PLAYBACK_ERROR -> status.value = ReadingStatus.PLAYBACK_ERROR
                                DriveResult.OFFLINE_VOICE_UNAVAILABLE -> finish(ReadingStatus.NO_OFFLINE_VOICE)
                                DriveResult.RECOGNITION_UNAVAILABLE -> status.value = if (interaction.chooseHeadset) {
                                    ReadingStatus.CHOOSE_REPLY_HEADSET
                                } else ReadingStatus.REPLY_UNAVAILABLE
                                DriveResult.REPLY_FAILED -> {
                                    status.value = ReadingStatus.REPLY_FAILED
                                    speakOnSessionRoute(getString(R.string.reply_failed_spoken))
                                }
                                DriveResult.SENT -> status.value = ReadingStatus.HANDED_OFF
                            }
                        }
                        turn = processing
                        processing.join()
                        turn = null
                        currentBatch = null
                        if (processing.isCancelled) {
                            clearReplay()
                            status.value = waiting
                        } else {
                            val latest = SystemClock.elapsedRealtime()
                            if (batch.messages.all { config.accepts(it) && it.isAvailable(latest, MAX_REPLAY_AGE_MS) }) {
                                rememberReplay(batch, latest)
                            } else clearReplay()
                        }
                    }
                    armIdleTimer()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                finish(ReadingStatus.FAILED)
            } finally {
                buttons?.close()
            }
        }
    }

    private fun onWithdrawal(notification: NotificationLifetime) {
        if (currentBatch?.messages?.any { it.notification === notification } == true) turn?.cancel()
        if (lastBatch?.messages?.any { it.notification === notification } == true) clearReplay()
    }

    private fun rememberReplay(batch: MessageBatch, now: Long) {
        replayExpiry?.cancel()
        lastBatch = batch
        val oldest = batch.messages.mapNotNull { it.receivedAtMs }.minOrNull() ?: now
        replayExpiry = scope.launch {
            delay((MAX_REPLAY_AGE_MS - (now - oldest)).coerceAtLeast(1))
            if (lastBatch === batch) {
                lastBatch = null
                if (currentBatch == null) senderName.value = null
            }
        }
    }

    private fun clearReplay() {
        replayExpiry?.cancel()
        lastBatch = null
        if (currentBatch == null) senderName.value = null
    }

    private fun armIdleTimer() {
        idleTimer?.cancel()
        idleTimer = scope.launch {
            delay(IDLE_LIMIT_MS)
            activation.suppress(sessionHeadset)
            finish(ReadingStatus.IDLE_TIMEOUT)
        }
    }

    private suspend fun speakOnSessionRoute(text: String): SpeechResult = coroutineScope {
        val address = sessionHeadset
        if (address == null) return@coroutineScope voice.speak(text) { meter.value = VoiceMeter(it, false) }
        if (!bluetooth.canPlayOn(address)) return@coroutineScope SpeechResult.FOCUS_UNAVAILABLE
        val playback = async { voice.speak(text) { meter.value = VoiceMeter(it, false) } }
        var routeLost = false
        val monitor = launch {
            while (bluetooth.canPlayOn(address)) delay(150)
            routeLost = true
            playback.cancel()
        }
        try {
            playback.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (routeLost) SpeechResult.FOCUS_UNAVAILABLE else throw cancelled
        } finally {
            monitor.cancel()
        }
    }

    private inner class SessionInteraction(
        private val buttons: MediaButtonHandler?,
        private val headsetOnly: Boolean,
        private val preferredAddress: String?,
    ) : DriveInteraction {
        private var replyMic: ReplyMic? = null
        var chooseHeadset = false
            private set

        fun resetMic() {
            replyMic = null
            chooseHeadset = false
        }

        override suspend fun speak(prompt: DrivePrompt): SpeechResult = speakOnSessionRoute(
            when (prompt) {
                is DrivePrompt.Message -> getString(
                    R.string.reading_announcement, prompt.batch.displayName, prompt.batch.sender.app.label, prompt.batch.text,
                )
                is DrivePrompt.Confirm -> getString(
                    if (prompt.autoSend) R.string.reply_confirm_auto else R.string.reply_confirm,
                    prompt.label, prompt.draft,
                )
            },
        )

        override suspend fun listen(window: ReplyWindow): VoiceInput {
            val mic = replyMic ?: when (
                val selection = recognition.selectMic(
                    preferredAddress, headsetOnly, sessionHeadset.takeIf { headsetOnly },
                )
            ) {
                is ReplyMicSelection.Selected -> selection.mic.also { replyMic = it }
                ReplyMicSelection.ChooseHeadset -> {
                    chooseHeadset = true
                    return VoiceInput.Unavailable
                }
                ReplyMicSelection.Unavailable -> return VoiceInput.Unavailable
            }
            return recognition.listen(window, mic, buttons.takeIf { mic.bluetooth }) { amplitude ->
                meter.value = if (amplitude == null) VoiceMeter() else VoiceMeter(amplitude, true, mic.bluetooth)
            }.also { currentCoroutineContext().ensureActive() }
        }

        override fun canReply(target: ReplyTarget): Boolean =
            replyMic?.let(recognition::isMicAvailable) != false &&
                inbox.canReply(this@DriveForegroundService, target)

        override fun send(target: ReplyTarget, text: String): Boolean {
            val mic = replyMic ?: return false
            return !closing && sessionHeadset?.let(bluetooth::connected) != false &&
                recognition.isMicAvailable(mic) &&
                inbox.sendReply(this@DriveForegroundService, target, text)
        }

        override suspend fun acknowledge() = playTone(AudioManager.STREAM_MUSIC, ToneGenerator.TONE_PROP_ACK)
    }

    private fun statusNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.reading_channel), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DriveForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.reading_notification))
            .setSmallIcon(R.drawable.ic_reading)
            .setContentIntent(open)
            .addAction(0, getString(R.string.reading_stop), stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun finish(reason: ReadingStatus) {
        if (closing) return
        closing = true
        endStatus = reason
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        finish(ReadingStatus.FAILED)
    }

    override fun onDestroy() {
        release()
        status.value = endStatus
        super.onDestroy()
    }

    private fun release() {
        if (released) return
        released = true
        inbox.stop()
        queue?.close()
        queue = null
        replayRequests.cancel()
        lastBatch = null
        currentBatch = null
        senderName.value = null
        meter.value = VoiceMeter()
        scope.cancel()
        try {
            voice.close()
        } catch (_: RuntimeException) {
            // The engine process may already have died.
        }
    }

    companion object {
        private const val ACTION_START = "com.drivevoice.app.action.START_READING"
        private const val ACTION_STOP = "com.drivevoice.app.action.STOP_READING"
        private const val ACTION_REPLAY = "com.drivevoice.app.action.REPLAY"
        private const val EXTRA_REPLIES = "enable_replies"
        private const val EXTRA_AUTO_SEND = "enable_auto_send"
        private const val EXTRA_HEADSET = "headset_address"
        internal const val CHANNEL_ID = "drive_reading"
        private const val STATUS_ID = 21
        private const val IDLE_LIMIT_MS = 30 * 60_000L
        private const val MAX_QUEUE_AGE_MS = 60_000L
        private const val MAX_REPLAY_AGE_MS = 120_000L
        private val status = MutableStateFlow(ReadingStatus.IDLE)
        internal val readingStatus = status.asStateFlow()
        private val senderName = MutableStateFlow<String?>(null)
        internal val latestSender = senderName.asStateFlow()
        private val meter = MutableStateFlow(VoiceMeter())
        internal val voiceMeter = meter.asStateFlow()

        internal fun dismissVoiceError() {
            status.compareAndSet(ReadingStatus.NO_OFFLINE_VOICE, ReadingStatus.IDLE)
        }

        internal fun start(
            context: Context,
            replies: Boolean = false,
            autoSend: Boolean = false,
            headsetAddress: String? = null,
            automatic: Boolean = false,
        ): Boolean {
            if (status.value.active) return true
            status.value = ReadingStatus.STARTING
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, DriveForegroundService::class.java).setAction(ACTION_START)
                        .putExtra(EXTRA_REPLIES, replies)
                        .putExtra(EXTRA_AUTO_SEND, replies && autoSend)
                        .putExtra(EXTRA_HEADSET, headsetAddress),
                )
                return true
            } catch (_: IllegalStateException) {
                status.value = if (automatic) ReadingStatus.IDLE else ReadingStatus.FAILED
            } catch (_: SecurityException) {
                status.value = if (automatic) ReadingStatus.IDLE else ReadingStatus.FAILED
            }
            return false
        }

        internal fun stop(context: Context) {
            if (status.value.active) command(context, ACTION_STOP)
        }

        internal fun replay(context: Context) {
            if (status.value.replayAllowed) command(context, ACTION_REPLAY)
        }

        private fun command(context: Context, action: String) {
            try {
                context.startService(Intent(context, DriveForegroundService::class.java).setAction(action))
            } catch (_: IllegalStateException) {
                if (action == ACTION_STOP) context.stopService(Intent(context, DriveForegroundService::class.java))
            } catch (_: SecurityException) {
                if (action == ACTION_STOP) context.stopService(Intent(context, DriveForegroundService::class.java))
            }
        }
    }
}

internal data class VoiceMeter(
    val amplitude: Float = 0f,
    val listening: Boolean = false,
    val bluetoothMic: Boolean = false,
)

internal val ReadingStatus.replayAllowed: Boolean
    get() = active && this !in setOf(
        ReadingStatus.STARTING, ReadingStatus.SPEAKING, ReadingStatus.LISTENING,
        ReadingStatus.CONFIRMING, ReadingStatus.SENDING,
    )
