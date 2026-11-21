package com.drivevoice.app.service

internal data class ReplyMic(val id: Int, val bluetooth: Boolean)

internal sealed interface ReplyMicSelection {
    data class Selected(val mic: ReplyMic) : ReplyMicSelection
    data object ChooseHeadset : ReplyMicSelection
    data object Unavailable : ReplyMicSelection
}

internal fun selectReplyMic(
    headsetIds: List<Int>,
    preferredIds: List<Int>,
    currentId: Int?,
    phoneId: Int?,
    headsetOnly: Boolean,
): ReplyMicSelection {
    val headsetId = preferredIds.firstOrNull { it == currentId }
        ?: preferredIds.firstOrNull()
        ?: headsetIds.firstOrNull { it == currentId }
        ?: headsetIds.singleOrNull()
    return when {
        headsetId != null -> ReplyMicSelection.Selected(ReplyMic(headsetId, bluetooth = true))
        headsetIds.isNotEmpty() -> ReplyMicSelection.ChooseHeadset
        !headsetOnly && phoneId != null -> ReplyMicSelection.Selected(ReplyMic(phoneId, bluetooth = false))
        else -> ReplyMicSelection.Unavailable
    }
}
