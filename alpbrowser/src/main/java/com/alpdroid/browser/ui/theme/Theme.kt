package com.alpdroid.browser.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// "Sheets and actions": a warm near-black ground, one orange accent for the thing you act on, yellow for methods,
// and green/blue/red for the status of a response. Dark only — it was never designed in a light variant.
val Ground = Color(0xFF0F1012)
val Surface1 = Color(0xFF17181B)
val Surface2 = Color(0xFF1C1D20)
val SheetBg = Color(0xFF1A1B1E)
val FieldBg = Color(0xFF101113)
val FieldBorder = Color(0xFF2A2B2F)
val Hairline = Color(0xFF232427)
val NavBg = Color(0xFF0B0B0C)
val Accent = Color(0xFFFF7A45)
val OnAccent = Color(0xFF1C0B03)
val AccentSoft = Color(0xFF1C1714)
val TextMain = Color(0xFFF3F1EE)
val TextSoft = Color(0xFFCFCCC6)
val TextMuted = Color(0xFF8B8780)
val TextFaint = Color(0xFF6D6A65)
val Method = Color(0xFFFFD166)
val StatusOk = Color(0xFF5AD18A)
val StatusRedirect = Color(0xFF5AA9FF)
val StatusError = Color(0xFFFF6B6B)
val DangerBg = Color(0xFF2A1B1B)
val DangerText = Color(0xFFFF8A80)
val SecondaryBg = Color(0xFF26272B)

private val AlpColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = Color(0xFF3A1F12),
    onPrimaryContainer = Color(0xFFFFD8C4),

    secondary = Method,
    onSecondary = Color(0xFF2A2000),
    secondaryContainer = Color(0xFF3A3010),
    onSecondaryContainer = Color(0xFFFFE9A8),

    tertiary = StatusRedirect,
    onTertiary = Color(0xFF07223F),
    tertiaryContainer = Color(0xFF12304F),
    onTertiaryContainer = Color(0xFFCBE1FF),

    error = StatusError,
    onError = Color(0xFF2E0A0A),
    errorContainer = DangerBg,
    onErrorContainer = DangerText,

    background = Ground,
    onBackground = TextMain,

    surface = Surface1,
    onSurface = TextMain,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = FieldBg,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = SheetBg,
    surfaceContainerHighest = Surface2,

    outline = Hairline,
    outlineVariant = FieldBorder,
)

// Status vocabulary used by older call sites.
val RoninOk = StatusOk
val RoninWarn = Method
val RoninNumber = Color(0xFFB78CF2)

private fun TextStyle.sans() = copy(fontFamily = RoninSans)

// Compact scale: this is a tool used one-handed on a phone, so type and spacing are a notch smaller than Material's defaults.
private val AlpTypography = Typography().let { b ->
    b.copy(
        displayLarge = b.displayLarge.sans(), displayMedium = b.displayMedium.sans(), displaySmall = b.displaySmall.sans(),
        headlineLarge = b.headlineLarge.sans(), headlineMedium = b.headlineMedium.sans(), headlineSmall = b.headlineSmall.sans(),
        titleLarge = TextStyle(fontFamily = RoninSans, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
        titleMedium = TextStyle(fontFamily = RoninSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = RoninSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = RoninSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontFamily = RoninSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = TextStyle(fontFamily = RoninSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontFamily = RoninSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = TextStyle(fontFamily = RoninSans, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 16.sp),
        labelSmall = TextStyle(fontFamily = RoninSans, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 14.sp),
    )
}

private val AlpShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun RoninTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AlpColors, typography = AlpTypography, shapes = AlpShapes, content = content)
}
