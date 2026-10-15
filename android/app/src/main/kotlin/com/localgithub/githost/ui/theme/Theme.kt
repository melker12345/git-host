package com.localgithub.githost.ui.theme

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

/** GitHub's dark "Primer" palette. */
object GitHub {
    val Canvas        = Color(0xFF0D1117)  // page background
    val CanvasSubtle  = Color(0xFF161B22)  // cards, bars
    val CanvasInset   = Color(0xFF010409)  // deepest inset surfaces
    val CodeHeader    = Color(0xFF1C2128)  // language bar above a code block
    val TabUnderline  = Color(0xFFF78166)  // selected tab marker
    val InlineCode    = Color(0x33656C76)  // `inline code` background, 20% gray
    val Border        = Color(0xFF30363D)
    val BorderMuted   = Color(0xFF21262D)
    val Fg            = Color(0xFFE6EDF3)
    val FgSecondary   = Color(0xFFC9D1D9)
    val FgMuted       = Color(0xFF8B949E)
    val FgSubtle      = Color(0xFF6E7681)
    val Accent        = Color(0xFF58A6FF)
    val AccentEmphasis= Color(0xFF1F6FEB)
    val Link          = Color(0xFF79C0FF)
    val Success       = Color(0xFF3FB950)
    val SuccessEmphasis = Color(0xFF238636)
    val Attention     = Color(0xFFD29922)
    val Danger        = Color(0xFFFF7B72)
    val DangerSubtle  = Color(0xFF3B1210)
    val OnDanger      = Color(0xFFFFDAD6)
}

private val GitHubDarkScheme = darkColorScheme(
    primary = GitHub.Accent,
    onPrimary = GitHub.Canvas,
    primaryContainer = GitHub.AccentEmphasis,
    onPrimaryContainer = GitHub.Fg,
    secondary = GitHub.FgMuted,
    onSecondary = GitHub.Canvas,
    secondaryContainer = GitHub.BorderMuted,
    onSecondaryContainer = GitHub.Fg,
    tertiary = GitHub.Success,
    onTertiary = GitHub.Canvas,
    tertiaryContainer = GitHub.SuccessEmphasis,
    onTertiaryContainer = GitHub.Fg,
    background = GitHub.Canvas,
    onBackground = GitHub.Fg,
    surface = GitHub.Canvas,
    onSurface = GitHub.Fg,
    surfaceVariant = GitHub.CanvasSubtle,
    onSurfaceVariant = GitHub.FgSecondary,
    surfaceContainerLowest = GitHub.CanvasInset,
    surfaceContainerLow = GitHub.Canvas,
    surfaceContainer = GitHub.CanvasSubtle,
    surfaceContainerHigh = GitHub.BorderMuted,
    surfaceContainerHighest = GitHub.Border,
    surfaceTint = GitHub.CanvasSubtle,
    inverseSurface = GitHub.Fg,
    inverseOnSurface = GitHub.Canvas,
    outline = GitHub.Border,
    outlineVariant = GitHub.BorderMuted,
    error = GitHub.Danger,
    onError = GitHub.Canvas,
    errorContainer = GitHub.DangerSubtle,
    onErrorContainer = GitHub.OnDanger,
    scrim = Color.Black,
)

private val GitHubShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

private val GitHubTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
)

/** Applies the GitHub dark scheme, shapes and type scale. The app has no light theme. */
@Composable
fun GitHostTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GitHubDarkScheme,
        shapes = GitHubShapes,
        typography = GitHubTypography,
        content = content,
    )
}
