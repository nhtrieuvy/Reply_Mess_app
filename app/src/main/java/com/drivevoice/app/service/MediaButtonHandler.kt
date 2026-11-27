package com.drivevoice.app.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.content.IntentCompat

internal class MediaButtonHandler(context: Context) {
    private val session = MediaSessionCompat(context, "DriveVoice reply")
    private var accept: (() -> Unit)? = null
    private val press = ReplyMediaPress()

    init {
        session.setMediaButtonReceiver(null)
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                val action = accept ?: return false
                val event = IntentCompat.getParcelableExtra(mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    ?: return false
                if (event.keyCode !in replyKeys) return false
                if (press.handle(event.action, event.repeatCount, event.keyCode, event.downTime)) {
                    accept = null
                    action()
                }
                return true
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun open(action: () -> Unit) {
        press.open(SystemClock.uptimeMillis())
        accept = action
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        session.isActive = true
    }

    fun endWindow() {
        accept = null
        session.isActive = false
    }

    fun close() {
        endWindow()
        session.release()
    }

    private companion object {
        val replyKeys = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
}

internal class ReplyMediaPress {
    private var openedAt = 0L
    private var pressed: Pair<Int, Long>? = null

    fun open(now: Long) {
        openedAt = now
        pressed = null
    }

    fun handle(action: Int, repeats: Int, key: Int, downTime: Long): Boolean {
        if (downTime < openedAt) return false
        val pressId = key to downTime
        if (action == KeyEvent.ACTION_DOWN && repeats == 0) pressed = pressId
        if (action != KeyEvent.ACTION_UP || pressed != pressId) return false
        pressed = null
        return true
    }
}
