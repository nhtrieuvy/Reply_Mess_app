package com.drivevoice.app.service

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyMediaPressTest {
    @Test
    fun onlyNewCompletePressCountsInTheConfirmationWindow() {
        val button = ReplyMediaPress()
        val key = KeyEvent.KEYCODE_HEADSETHOOK
        button.open(100)
        assertFalse(button.handle(KeyEvent.ACTION_DOWN, 0, key, 110))
        assertFalse(button.handle(KeyEvent.ACTION_DOWN, 1, key, 110))
        assertTrue(button.handle(KeyEvent.ACTION_UP, 0, key, 110))
        button.open(200)
        assertFalse(button.handle(KeyEvent.ACTION_UP, 0, key, 110))
        assertFalse(button.handle(KeyEvent.ACTION_DOWN, 1, key, 110))
        assertFalse(button.handle(KeyEvent.ACTION_DOWN, 0, key, 210))
        assertTrue(button.handle(KeyEvent.ACTION_UP, 0, key, 210))
        assertFalse(button.handle(KeyEvent.ACTION_UP, 0, key, 210))
    }
}
