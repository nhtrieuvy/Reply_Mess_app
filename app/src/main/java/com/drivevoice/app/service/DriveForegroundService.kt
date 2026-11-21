package com.drivevoice.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.drivevoice.app.MainActivity
import com.drivevoice.app.R
import com.drivevoice.app.data.notification.NotificationInbox
import com.drivevoice.app.domain.queue.MessageQueueManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class ReadingStatus(val active: Boolean = false) {
    IDLE, STARTING(true), WAITING(true), SPEAKING(true), FOCUS_UNAVAILABLE(true),
    PLAYBACK_ERROR(true), NOT_CONNECTED, NO_OFFLINE_VOICE, FAILED,
}

@AndroidEntryPoint
class DriveForegroundService : Service() {
    @Inject internal lateinit var inbox: NotificationInbox
    @Inject internal lateinit var voice: VoiceEngineManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queue: MessageQueueManager? = null
    private var closing = false
    private var endStatus = ReadingStatus.IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!closing && queue == null) startReading()
            else -> finish(ReadingStatus.IDLE)
        }
        return START_NOT_STICKY
    }

    private fun startReading() {
        try {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else 0
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
        if (!inbox.start(readingQueue)) {
            finish(ReadingStatus.NOT_CONNECTED)
            return
        }

        scope.launch {
            MessageNotificationListener.isConnected.first { !it }
            finish(ReadingStatus.NOT_CONNECTED)
        }
        scope.launch {
            try {
                if (!voice.initialize()) {
                    finish(ReadingStatus.NO_OFFLINE_VOICE)
                    return@launch
                }
                status.value = ReadingStatus.WAITING
                readingQueue.batches.collect { batch ->
                    val phrase = getString(
                        R.string.reading_announcement, batch.displayName, batch.sender.app.label, batch.text,
                    )
                    when (voice.speak(phrase) { status.value = ReadingStatus.SPEAKING }) {
                        SpeechResult.DONE -> status.value = ReadingStatus.WAITING
                        SpeechResult.FOCUS_UNAVAILABLE -> status.value = ReadingStatus.FOCUS_UNAVAILABLE
                        SpeechResult.FAILED -> status.value = ReadingStatus.PLAYBACK_ERROR
                        SpeechResult.OFFLINE_VOICE_UNAVAILABLE -> finish(ReadingStatus.NO_OFFLINE_VOICE)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                finish(ReadingStatus.FAILED)
            }
        }
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
        scope.cancel()
        voice.close()
    }

    companion object {
        private const val ACTION_START = "com.drivevoice.app.action.START_READING"
        private const val ACTION_STOP = "com.drivevoice.app.action.STOP_READING"
        private const val CHANNEL_ID = "drive_reading"
        private const val STATUS_ID = 21
        private val status = MutableStateFlow(ReadingStatus.IDLE)
        internal val readingStatus = status.asStateFlow()

        internal fun start(context: Context) {
            if (status.value.active) return
            status.value = ReadingStatus.STARTING
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, DriveForegroundService::class.java).setAction(ACTION_START),
                )
            } catch (_: IllegalStateException) {
                status.value = ReadingStatus.FAILED
            } catch (_: SecurityException) {
                status.value = ReadingStatus.FAILED
            }
        }

        internal fun stop(context: Context) {
            context.startService(Intent(context, DriveForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
