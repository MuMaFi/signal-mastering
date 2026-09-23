package app.signal.isolate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.signal.isolate.R

/**
 * Apple's system colours, as tokens.
 *
 * These are the same values as `web/app.css`, and the same audit covers both:
 * `web/contrast.mjs` composites every translucent token over the surface it is drawn
 * on and checks each pair against WCAG AA. Two tokens depart from Apple's published
 * palette to pass it — the tint (systemMint carries white text at 2.2:1) and the light
 * secondary label (Apple's 60 % is 3.4:1 on white).
 */
@Immutable
data class AppleColors(
    val groupedBackground: Color,
    val elevated: Color,
    val pressed: Color,
    val bar: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val tint: Color,
    val tintFill: Color,
    val onTint: Color,
    val red: Color,
    val orange: Color,
    val fill: Color,
    val segmentThumb: Color,
    val isDark: Boolean,
)

private val Light = AppleColors(
    groupedBackground = Color(0xFFF2F2F7),
    elevated = Color(0xFFFFFFFF),
    pressed = Color(0xFFD1D1D6),
    bar = Color(0xF0F9F9F9),
    label = Color(0xFF000000),
    // 76 %, not Apple's 60 %: the published value is 3.4:1 on white.
    secondaryLabel = Color(0xC23C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    tint = Color(0xFF00665F),
    tintFill = Color(0xFF00665F),
    onTint = Color(0xFFFFFFFF),
    red = Color(0xFFD70015),
    orange = Color(0xFFC93400),
    fill = Color(0x33787880),
    segmentThumb = Color(0xFFFFFFFF),
    isDark = false,
)

private val Dark = AppleColors(
    groupedBackground = Color(0xFF000000),
    elevated = Color(0xFF1C1C1E),
    pressed = Color(0xFF2C2C2E),
    bar = Color(0xF01C1C1E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0xA6545458),
    tint = Color(0xFF5CD0C8),
    tintFill = Color(0xFF5CD0C8),
    onTint = Color(0xFF00332F),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    fill = Color(0x52787880),
    // The card colour would vanish on the dark track; iOS lifts the segment instead.
    segmentThumb = Color(0xFF636366),
    isDark = true,
)

/**
 * The iOS text styles, set in Inter.
 *
 * SF Pro's licence forbids shipping it outside Apple platforms; Inter was drawn to sit
 * close to it and, like SF, has an optical-size axis. Each style pins `opsz` to its own
 * point size, which is what separates SF Display (tighter, for titles) from SF Text
 * (more open, for body copy) — one variable font standing in for both.
 */
@Immutable
data class AppleType(
    val largeTitle: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val subheadline: TextStyle,
    val footnote: TextStyle,
    val caption: TextStyle,
)

// Variable-font settings on a resource font are still marked experimental in Compose;
// the behaviour itself has been stable since API 26, which is this app's minimum.
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int, opticalSize: Float): FontFamily = FontFamily(
    Font(
        resId = R.font.inter,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.Setting("opsz", opticalSize),
        ),
    ),
)

private val Type = AppleType(
    largeTitle = TextStyle(
        fontFamily = inter(700, 32f),
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-0.028).em,
    ),
    headline = TextStyle(
        fontFamily = inter(600, 17f),
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.02).em,
    ),
    body = TextStyle(
        fontFamily = inter(400, 17f),
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.02).em,
    ),
    subheadline = TextStyle(
        fontFamily = inter(400, 15f),
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.01).em,
    ),
    footnote = TextStyle(
        fontFamily = inter(400, 14f),
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.005).em,
    ),
    caption = TextStyle(
        fontFamily = inter(500, 14f),
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
)

val LocalAppleColors = staticCompositionLocalOf { Light }
val LocalAppleType = staticCompositionLocalOf { Type }

object Apple {
    val colors: AppleColors
        @Composable get() = LocalAppleColors.current
    val type: AppleType
        @Composable get() = LocalAppleType.current
}

@Composable
fun SignalIsolateTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) Dark else Light
    // Material stays underneath only for what it still draws (text selection, the
    // system's own dialogs); everything on screen reads from the Apple tokens.
    val material = if (dark) {
        darkColorScheme(
            primary = colors.tint,
            onPrimary = colors.onTint,
            background = colors.groupedBackground,
            surface = colors.elevated,
            onSurface = colors.label,
            error = colors.red,
        )
    } else {
        lightColorScheme(
            primary = colors.tint,
            onPrimary = colors.onTint,
            background = colors.groupedBackground,
            surface = colors.elevated,
            onSurface = colors.label,
            error = colors.red,
        )
    }
    CompositionLocalProvider(LocalAppleColors provides colors, LocalAppleType provides Type) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
