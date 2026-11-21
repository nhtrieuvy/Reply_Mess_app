package com.drivevoice.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.drivevoice.app.domain.state.DriveState
import kotlin.math.min

internal val DriveState.accent: Color
    get() = when (this) {
        DriveState.IDLE -> Color(0xFF2C2C2E)
        DriveState.READY, DriveState.SENDING -> Color(0xFF00E676)
        DriveState.SPEAKING_MSG, DriveState.CONFIRMING_REPLY -> Color(0xFF00E5FF)
        DriveState.LISTENING_REPLY -> Color(0xFFFF3D00)
    }

@Composable
internal fun VoiceOrbVisualizer(state: DriveState, audioAmplitude: Float, modifier: Modifier = Modifier) {
    val still = state == DriveState.IDLE || state == DriveState.READY
    val breathing = if (still) {
        val scale by rememberInfiniteTransition(label = "Orb breathing").animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(tween(2_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "Breathing scale",
        )
        scale
    } else 1f
    val amplitude by animateFloatAsState(
        if (still) 0f else audioAmplitude.coerceIn(0f, 1f),
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "Audio intensity",
    )
    val shape by animateFloatAsState(
        when (state) {
            DriveState.LISTENING_REPLY -> 1.14f
            DriveState.SPEAKING_MSG, DriveState.CONFIRMING_REPLY -> 0.91f
            else -> 1f
        },
        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "Orb shape",
    )
    val color by animateColorAsState(state.accent, tween(320), label = "Orb status color")
    Canvas(modifier) {
        val base = min(size.width, size.height) * 0.27f * breathing
        val energy = amplitude.coerceIn(0f, 1.1f)
        val halo = base * (1.76f + energy * 0.08f)
        drawCircle(
            Brush.radialGradient(
                0f to color.copy(alpha = 0.23f + energy * 0.14f),
                0.60f to color.copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = center,
                radius = halo,
            ),
            radius = halo,
        )
        for (index in 1..3) {
            val radius = base * (1f + index * (0.18f + energy * 0.09f))
            drawCircle(
                color.copy(alpha = (0.30f - index * 0.065f) + energy * 0.19f),
                radius = radius,
                style = Stroke(1.dp.toPx()),
            )
        }
        val radius = base * (1f + energy * 0.16f)
        val width = radius * 2f * shape
        val height = radius * 2f / shape
        val origin = Offset(center.x - width / 2f, center.y - height / 2f)
        val orbSize = Size(width, height)
        drawOval(
            Brush.radialGradient(
                0f to color.copy(alpha = 0.35f),
                0.65f to color.copy(alpha = 0.10f),
                1f to color.copy(alpha = 0.04f),
                center = center,
                radius = radius * 1.15f,
            ),
            topLeft = origin, size = orbSize,
        )
        drawOval(color.copy(alpha = 0.86f), topLeft = origin, size = orbSize, style = Stroke(1.5.dp.toPx()))
        drawCircle(color.copy(alpha = 0.77f), radius = 3.dp.toPx())
    }
}
