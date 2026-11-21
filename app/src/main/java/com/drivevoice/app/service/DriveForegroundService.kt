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
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.drivevoice.app.MainActivity
import com.drivevoice.app.R
import com.drivevoice.app.data.bluetooth.BluetoothDevices
import com.drivevoice.app.data.model.MessageBatch
import com.drivevoice.app.data.model.ReplyTarget
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
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
    REPLY_UNAVAILABLE(true), REPLY_FAILED(true), PLAYBACK_ERROR(true), NOT_CONNECTED, NO_OFFLINE_VOICE, FAILED,
    HEADSET_DISCONNECTED,
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
    private var sessionHeadset: String? = null
    private var queue: MessageQueueManager? = null
    private var closing = false
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
                if (!inbox.start(readingQueue, enableMicrophone) { config.accepts(it) }) {
                    finish(ReadingStatus.NOT_CONNECTED)
                    return@launch
                }
                scope.launch { settings.config.collect { config = it } }
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
                val interaction = SessionInteraction(buttons)
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
                    val allowed = queued.messages.filter(config::accepts)
                    if (allowed.isEmpty()) return@collect
                    val batch = MessageBatch(allowed)
                    if (address != null && !bluetooth.canPlayOn(address)) {
                        status.value = ReadingStatus.FOCUS_UNAVAILABLE
                        return@collect
                    }
                    lastBatch = batch
                    senderName.value = batch.displayName
                    interaction.headsetId = null
                    when (machine.read(batch, replies && !replay && config.handsFree, autoSend && config.autoSend)) {
                        DriveResult.DONE -> status.value = waiting
                        DriveResult.FOCUS_UNAVAILABLE -> status.value = ReadingStatus.FOCUS_UNAVAILABLE
                        DriveResult.PLAYBACK_ERROR -> status.value = ReadingStatus.PLAYBACK_ERROR
                        DriveResult.OFFLINE_VOICE_UNAVAILABLE -> finish(ReadingStatus.NO_OFFLINE_VOICE)
                        DriveResult.RECOGNITION_UNAVAILABLE -> status.value = ReadingStatus.REPLY_UNAVAILABLE
                        DriveResult.REPLY_FAILED -> {
                            status.value = ReadingStatus.REPLY_FAILED
                            voice.speak(getString(R.string.reply_failed_spoken))
                        }
                        DriveResult.SENT -> status.value = ReadingStatus.HANDED_OFF
                    }
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

    private inner class SessionInteraction(private val buttons: MediaButtonHandler?) : DriveInteraction {
        var headsetId: Int? = null

        override suspend fun speak(prompt: DrivePrompt): SpeechResult {
            if (sessionHeadset?.let(bluetooth::canPlayOn) == false) {
                return SpeechResult.FOCUS_UNAVAILABLE
            }
            return voice.speak(
            when (prompt) {
                is DrivePrompt.Message -> getString(
                    R.string.reading_announcement, prompt.batch.displayName, prompt.batch.sender.app.label, prompt.batch.text,
                )
                is DrivePrompt.Confirm -> getString(
                    if (prompt.autoSend) R.string.reply_confirm_auto else R.string.reply_confirm,
                    prompt.label, prompt.draft,
                )
                },
                onAmplitude = { meter.value = VoiceMeter(it, false) },
            )
        }

        override suspend fun listen(window: ReplyWindow): VoiceInput {
            val handler = buttons ?: return VoiceInput.Unavailable
            val capture = recognition.listen(window, handler, headsetId, sessionHeadset) {
                meter.value = if (it == null) VoiceMeter() else VoiceMeter(it, true)
            }
            currentCoroutineContext().ensureActive()
            headsetId = capture.headsetId
            return capture.input
        }

        override fun canReply(target: ReplyTarget): Boolean =
            headsetId?.let(recognition::isHeadsetConnected) != false &&
                inbox.canReply(this@DriveForegroundService, target)

        override fun send(target: ReplyTarget, text: String): Boolean {
            val device = headsetId ?: return false
            return !closing && recognition.isHeadsetConnected(device) &&
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
        inbox.stop()
        queue?.close()
        queue = null
        replayRequests.cancel()
        lastBatch = null
        senderName.value = null
        meter.value = VoiceMeter()
        scope.cancel()
        voice.close()
    }

    companion object {
        private const val ACTION_START = "com.drivevoice.app.action.START_READING"
        private const val ACTION_STOP = "com.drivevoice.app.action.STOP_READING"
        private const val ACTION_REPLAY = "com.drivevoice.app.action.REPLAY"
        private const val EXTRA_REPLIES = "enable_replies"
        private const val EXTRA_AUTO_SEND = "enable_auto_send"
        private const val EXTRA_HEADSET = "headset_address"
        private const val CHANNEL_ID = "drive_reading"
        private const val STATUS_ID = 21
        private val status = MutableStateFlow(ReadingStatus.IDLE)
        internal val readingStatus = status.asStateFlow()
        private val senderName = MutableStateFlow<String?>(null)
        internal val latestSender = senderName.asStateFlow()
        private val meter = MutableStateFlow(VoiceMeter())
        internal val voiceMeter = meter.asStateFlow()

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
            context.startService(Intent(context, DriveForegroundService::class.java).setAction(ACTION_STOP))
        }

        internal fun replay(context: Context) {
            context.startService(Intent(context, DriveForegroundService::class.java).setAction(ACTION_REPLAY))
        }
    }
}

internal data class VoiceMeter(val amplitude: Float = 0f, val listening: Boolean = false)

internal val ReadingStatus.replayAllowed: Boolean
    get() = active && this !in setOf(
        ReadingStatus.STARTING, ReadingStatus.SPEAKING, ReadingStatus.LISTENING,
        ReadingStatus.CONFIRMING, ReadingStatus.SENDING,
    )
