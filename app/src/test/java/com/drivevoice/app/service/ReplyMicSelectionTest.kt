package com.drivevoice.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyMicSelectionTest {
    @Test
    fun selectedHeadsetWinsOverCurrentHeadsetAndPhone() {
        assertEquals(
            ReplyMicSelection.Selected(ReplyMic(2, bluetooth = true)),
            selectReplyMic(listOf(1, 2), listOf(2), currentId = 1, phoneId = 3, headsetOnly = false),
        )
    }

    @Test
    fun currentOrOnlyHeadsetWinsOverPhone() {
        val headset = ReplyMicSelection.Selected(ReplyMic(2, bluetooth = true))
        assertEquals(headset, selectReplyMic(listOf(1, 2), emptyList(), 2, 3, headsetOnly = false))
        assertEquals(headset, selectReplyMic(listOf(2), emptyList(), 3, 3, headsetOnly = false))
    }

    @Test
    fun ambiguousHeadsetsRequireSelectionEvenIfPhoneIsAvailable() {
        assertEquals(
            ReplyMicSelection.ChooseHeadset,
            selectReplyMic(listOf(1, 2), emptyList(), 3, 3, headsetOnly = false),
        )
    }

    @Test
    fun onlyFlexibleModeCanUsePhoneWithoutBluetooth() {
        assertEquals(
            ReplyMicSelection.Selected(ReplyMic(3, bluetooth = false)),
            selectReplyMic(emptyList(), emptyList(), null, 3, headsetOnly = false),
        )
        assertEquals(
            ReplyMicSelection.Unavailable,
            selectReplyMic(emptyList(), emptyList(), null, 3, headsetOnly = true),
        )
    }

    @Test
    fun flexibleModeRequiresAnAvailablePhoneRouteWhenNoHeadsetIsAvailable() {
        assertEquals(
            ReplyMicSelection.Unavailable,
            selectReplyMic(emptyList(), emptyList(), null, null, headsetOnly = false),
        )
    }
}
