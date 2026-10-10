package com.alpdroid.browser.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

// The design direction settled on the canvas (Terminal/Browser/Repeater/Agent mockups):
// near-black ground, a teal accent, an amber secondary for tool/status highlights. Replaces
// the earlier indigo pass — dark-only, no light variant was ever designed for this app, and
// every mockup this was built from assumed dark.
private val RoninColors = darkColorScheme(
    primary = Color(0xFF3ED0B8),
    onPrimary = Color(0xFF06231D),
    primaryContainer = Color(0xFF123832),
    onPrimaryContainer = Color(0xFFB8F5EA),

    secondary = Color(0xFFF2A65A),
    onSecondary = Color(0xFF2B1B08),
    secondaryContainer = Color(0xFF3A2A18),
    onSecondaryContainer = Color(0xFFFAD9B8),

    tertiary = Color(0xFF60A5FA),
    onTertiary = Color(0xFF07223F),
    tertiaryContainer = Color(0xFF12304F),
    onTertiaryContainer = Color(0xFFCBE1FF),

    error = Color(0xFFF87171),
    onError = Color(0xFF2E0A0A),
    errorContainer = Color(0xFF4A1515),
    onErrorContainer = Color(0xFFFFD9D9),

    background = Color(0xFF0B0C10),
    onBackground = Color(0xFFECEEF3),

    surface = Color(0xFF14161C),
    onSurface = Color(0xFFECEEF3),
    surfaceVariant = Color(0xFF1B1E26),
    onSurfaceVariant = Color(0xFFA7ACBA),

    outline = Color(0xFF262A34),
    outlineVariant = Color(0xFF33384A),
)

// Material3's ColorScheme only has four semantic slots (primary/secondary/tertiary/error) —
// not enough for the status vocabulary this app actually needs (2xx succeeded, 3xx redirected,
// 4xx/5xx failed, as three distinct hues, plus a fourth for JSON numeric literals). These sit
// alongside the scheme rather than replacing any of its roles.
val RoninOk = Color(0xFF4ADE80)
val RoninWarn = Color(0xFFFBBF24)
val RoninNumber = Color(0xFFB78CF2)

private fun TextStyle.sora() = copy(fontFamily = RoninSans)

private val RoninTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.sora(),
        displayMedium = base.displayMedium.sora(),
        displaySmall = base.displaySmall.sora(),
        headlineLarge = base.headlineLarge.sora(),
        headlineMedium = base.headlineMedium.sora(),
        headlineSmall = base.headlineSmall.sora(),
        titleLarge = base.titleLarge.sora(),
        titleMedium = base.titleMedium.sora(),
        titleSmall = base.titleSmall.sora(),
        bodyLarge = base.bodyLarge.sora(),
        bodyMedium = base.bodyMedium.sora(),
        bodySmall = base.bodySmall.sora(),
        labelLarge = base.labelLarge.sora(),
        labelMedium = base.labelMedium.sora(),
        labelSmall = base.labelSmall.sora(),
    )
}

@Composable
fun RoninTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RoninColors,
        typography = RoninTypography,
        content = content,
    )
}
