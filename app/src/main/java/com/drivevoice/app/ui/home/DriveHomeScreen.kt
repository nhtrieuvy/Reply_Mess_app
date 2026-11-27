package com.drivevoice.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivevoice.app.R
import com.drivevoice.app.domain.state.DriveState
import com.drivevoice.app.service.ReadingStatus
import com.drivevoice.app.service.VoiceMeter
import com.drivevoice.app.service.replayAllowed

@Composable
internal fun DriveHomeScreen(
    deviceName: String?,
    listenerConnected: Boolean,
    hasSources: Boolean,
    status: ReadingStatus,
    sender: String?,
    meter: VoiceMeter,
    onSettings: () -> Unit,
    onAction: () -> Unit,
) {
    val state = when {
        meter.listening -> DriveState.LISTENING_REPLY
        status == ReadingStatus.SPEAKING -> DriveState.SPEAKING_MSG
        status == ReadingStatus.CONFIRMING -> DriveState.CONFIRMING_REPLY
        status == ReadingStatus.SENDING -> DriveState.SENDING
        status.active || deviceName != null -> DriveState.READY
        else -> DriveState.IDLE
    }
    val accent by animateColorAsState(state.accent, tween(320), label = "Drive accent")
    val corner by animateDpAsState(
        if (state == DriveState.LISTENING_REPLY) 36.dp else 28.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "Action shape",
    )
    val haptics = LocalHapticFeedback.current
    var previous by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        if (state != previous) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            previous = state
        }
    }
    val action = when {
        !listenerConnected && !status.active -> R.string.drive_enable_access
        !hasSources && !status.active -> R.string.drive_choose_source
        status == ReadingStatus.NO_OFFLINE_VOICE -> R.string.drive_voice_settings
        status.replayAllowed && sender != null -> R.string.drive_replay
        status.active -> R.string.drive_stop
        else -> R.string.drive_start
    }
    val title = stringResource(when {
        meter.listening && status == ReadingStatus.CONFIRMING -> R.string.drive_confirm
        meter.listening -> R.string.drive_listen
        status == ReadingStatus.SPEAKING -> R.string.drive_speak
        status == ReadingStatus.CONFIRMING -> R.string.drive_readback
        status == ReadingStatus.LISTENING || status == ReadingStatus.STARTING -> R.string.drive_preparing
        status == ReadingStatus.SENDING -> R.string.drive_sending
        status == ReadingStatus.HANDED_OFF -> R.string.drive_sent
        status == ReadingStatus.REPLY_CANCELLED -> R.string.drive_reply_cancelled
        status == ReadingStatus.DICTATION_UNCLEAR -> R.string.drive_dictation_unclear
        status == ReadingStatus.CONFIRMATION_UNCLEAR -> R.string.drive_confirmation_unclear
        status == ReadingStatus.TARGET_EXPIRED -> R.string.drive_target_expired
        status == ReadingStatus.AUDIO_UNSUPPORTED -> R.string.drive_audio_unsupported
        status == ReadingStatus.SILENCE_UNCONFIRMED -> R.string.drive_silence_unconfirmed
        status == ReadingStatus.NO_OFFLINE_VOICE -> R.string.drive_no_voice
        status == ReadingStatus.CHOOSE_REPLY_HEADSET -> R.string.drive_choose_reply_headset
        status == ReadingStatus.REPLY_MODEL_REQUIRED -> R.string.drive_reply_model_required
        status == ReadingStatus.REPLY_MODEL_UNSUPPORTED -> R.string.drive_reply_model_unsupported
        status == ReadingStatus.REPLY_UNAVAILABLE -> R.string.drive_reply_unavailable
        status == ReadingStatus.FOCUS_UNAVAILABLE || status == ReadingStatus.PLAYBACK_ERROR -> R.string.drive_audio_unavailable
        status == ReadingStatus.REPLY_FAILED -> R.string.drive_reply_failed
        status == ReadingStatus.HEADSET_DISCONNECTED -> R.string.drive_disconnected
        status == ReadingStatus.IDLE_TIMEOUT -> R.string.drive_timeout
        status == ReadingStatus.FAILED -> R.string.drive_start_failed
        status.active || deviceName != null -> R.string.drive_ready
        else -> R.string.drive_idle
    })

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        val compact = maxHeight < 420.dp
        val topHeight = (maxHeight * 0.10f).coerceAtLeast(48.dp)
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().height(topHeight),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(18.dp)) {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.6f), Color.Transparent), center, size.minDimension / 2,
                            ),
                        )
                        drawCircle(accent, radius = 3.dp.toPx())
                    }
                    Text(
                        when {
                            meter.listening -> stringResource(
                                if (meter.bluetoothMic) R.string.drive_mic_bluetooth else R.string.drive_mic_phone,
                            )
                            else -> deviceName ?: stringResource(R.string.drive_no_headset)
                        },
                        Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.drive_settings))
                }
            }

            Column(
                Modifier.weight(55f).fillMaxWidth().padding(bottom = if (compact) 8.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(contentAlignment = Alignment.Center) {
                        val diameter = minOf(maxWidth, maxHeight, 310.dp)
                        if (diameter > 0.dp) VoiceOrbVisualizer(state, meter.amplitude, Modifier.size(diameter))
                    }
                }
                AnimatedDriveText(
                    title, if (compact) 18 else 28, if (state == DriveState.IDLE) Color(0xFFE5E5E7) else accent,
                    tracking = 2f, announce = true,
                )
                AnimatedDriveText(sender, if (compact) 19 else 30, Color.White, tracking = 0.25f)
            }

            Column(Modifier.weight(35f).fillMaxWidth().padding(bottom = if (compact) 8.dp else 24.dp)) {
                HorizontalDivider(thickness = 1.dp, color = Color.White.copy(alpha = 0.15f))
                val shape = RoundedCornerShape(corner)
                Box(
                    Modifier.padding(top = if (compact) 8.dp else 16.dp).fillMaxSize().clip(shape)
                        .border(1.dp, if (state == DriveState.IDLE) Color(0xFF454547) else accent.copy(alpha = 0.8f), shape)
                        .background(Color(0xFF090909))
                        .clickable(
                            interactionSource = null,
                            indication = ripple(bounded = true, color = accent),
                            role = Role.Button,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAction()
                        }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedDriveText(
                        stringResource(action), if (compact) 21 else 28,
                        if (state == DriveState.IDLE) Color.White else accent, tracking = 1.6f,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimatedDriveText(value: String?, size: Int, color: Color, tracking: Float, announce: Boolean = false) {
    AnimatedContent(
        targetState = value,
        modifier = if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
        transitionSpec = {
            (slideInVertically(tween(250)) { it / 5 } + fadeIn(tween(250))) togetherWith
                (slideOutVertically(tween(180)) { -it / 5 } + fadeOut(tween(180)))
        },
        label = "Drive text",
    ) { text ->
        if (text != null) {
            Text(
                text, color = color, style = MaterialTheme.typography.headlineMedium,
                fontSize = size.sp, lineHeight = (size + 5).sp, letterSpacing = tracking.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
