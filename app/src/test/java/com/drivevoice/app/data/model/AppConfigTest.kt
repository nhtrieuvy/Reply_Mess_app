package com.drivevoice.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {
    @Test
    fun disablingSourceOverridesVipMembership() {
        val config = AppConfig(sources = setOf(MessageApp.ZALO), vips = setOf(VipContact(MessageApp.MESSENGER, "An")))
        assertTrue(config.accepts(message(MessageApp.ZALO, "An")))
        assertFalse(config.accepts(message(MessageApp.MESSENGER, "An")))
        assertFalse(config.copy(sources = emptySet()).accepts(message(MessageApp.ZALO, "An")))
    }

    @Test
    fun vipMatchesNameAndAppWhilePreservingVietnameseAccents() {
        val config = AppConfig(vipOnly = true, vips = setOf(VipContact(MessageApp.ZALO, "Hoàng  An")))
        assertTrue(config.accepts(message(MessageApp.ZALO, "HOA\u0300NG AN")))
        assertFalse(config.accepts(message(MessageApp.ZALO, "Hoang An")))
        assertFalse(config.accepts(message(MessageApp.ZALO, "Hoàng Anh")))
        assertFalse(config.accepts(message(MessageApp.MESSENGER, "Hoàng An")))
        assertFalse(config.copy(vips = emptySet()).accepts(message(MessageApp.ZALO, "Hoàng An")))
    }

    private fun message(app: MessageApp, name: String): MessageItem =
        MessageItem(MessageSender(app, 0, name, name), name, "Tin nhắn thử")
}
