package com.drivevoice.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.drivevoice.app.R

private val ElectricMint = Color(0xFF00E676)
private val TitaniumGrey = Color(0xFF71717A)
@OptIn(ExperimentalTextApi::class)
private val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val DriveTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontFamily = SpaceGrotesk),
        headlineMedium = headlineMedium.copy(fontFamily = SpaceGrotesk),
        headlineSmall = headlineSmall.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = SpaceGrotesk),
        bodyLarge = bodyLarge.copy(fontFamily = SpaceGrotesk),
        bodyMedium = bodyMedium.copy(fontFamily = SpaceGrotesk),
        bodySmall = bodySmall.copy(fontFamily = SpaceGrotesk),
        labelLarge = labelLarge.copy(fontFamily = SpaceGrotesk),
        labelMedium = labelMedium.copy(fontFamily = SpaceGrotesk),
    )
}

private val DriveColors = darkColorScheme(
    primary = ElectricMint,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF121214),
    surfaceVariant = Color(0xFF121214),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF18181B),
    surfaceContainerHighest = Color(0xFF202024),
    surfaceTint = Color.Transparent,
    onSurface = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFFABABB5),
    outline = TitaniumGrey,
    outlineVariant = Color(0xFF27272A),
    error = Color(0xFFE8E8EC),
)

@Composable
fun DriveVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DriveColors, typography = DriveTypography) {
        CompositionLocalProvider(LocalContentColor provides DriveColors.onBackground, content = content)
    }
}
