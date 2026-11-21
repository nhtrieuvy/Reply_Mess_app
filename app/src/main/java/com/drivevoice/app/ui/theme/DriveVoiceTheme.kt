package com.drivevoice.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ElectricMint = Color(0xFF00E676)
private val TitaniumGrey = Color(0xFF71717A)

private val DriveColors = darkColorScheme(
    primary = ElectricMint,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF121214),
    onSurface = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFFABABB5),
    outline = TitaniumGrey,
    outlineVariant = Color(0xFF27272A),
)

@Composable
fun DriveVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DriveColors, content = content)
}
