package com.drivevoice.app.data.model

import java.text.Normalizer
import java.util.Locale

internal data class VipContact(val app: MessageApp, val name: String) {
    val normalizedName: String get() = normalizeSender(name)
}

internal data class AppConfig(
    val sources: Set<MessageApp> = MessageApp.entries.toSet(),
    val headsetAddress: String? = null,
    val autoStart: Boolean = false,
    val handsFree: Boolean = false,
    val autoSend: Boolean = false,
    val vipOnly: Boolean = false,
    val vips: Set<VipContact> = emptySet(),
) {
    fun accepts(message: MessageItem): Boolean {
        if (message.sender.app !in sources) return false
        if (!vipOnly) return true
        val sender = normalizeSender(message.displayName)
        return vips.any { it.app == message.sender.app && it.normalizedName == sender }
    }
}

private val spaces = Regex("[\\p{Z}\\s]+")

private fun normalizeSender(name: String): String =
    Normalizer.normalize(name.trim().replace(spaces, " ").lowercase(Locale.ROOT), Normalizer.Form.NFC)
