package dev.animeruu.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palet "Midnight Sakura": hitam pekat bernuansa biru, aksen sakura→ungu. */
@Immutable
object Palette {
    val Bg = Color(0xFF07070B)
    val Surface = Color(0xFF111118)
    val SurfaceHigh = Color(0xFF191922)
    val SurfaceHigher = Color(0xFF22222E)
    val Stroke = Color(0x14FFFFFF)
    val StrokeStrong = Color(0x26FFFFFF)
    val Text = Color(0xFFF4F4F7)
    val TextDim = Color(0xFFA3A3B2)
    val TextFaint = Color(0xFF6C6C7C)
    val Accent = Color(0xFFFF4D7E)
    val Accent2 = Color(0xFF8B5CF6)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)

    val AccentBrush = Brush.linearGradient(listOf(Accent, Accent2), start = Offset.Zero, end = Offset(600f, 200f))
    val ScrimBottom = Brush.verticalGradient(0f to Color.Transparent, 0.55f to Bg.copy(alpha = 0.55f), 1f to Bg)
}

/** Kamus gerak: semua transisi memakai pegas, bukan durasi tetap. */
object Motion {
    /** Respons sentuhan: cepat, sedikit memantul. */
    fun <T> press() = spring<T>(dampingRatio = 0.6f, stiffness = 900f)
    /** Perpindahan tata letak/indikator. */
    fun <T> snappy() = spring<T>(dampingRatio = 0.82f, stiffness = 520f)
    /** Transisi layar & elemen besar. */
    fun <T> gentle() = spring<T>(dampingRatio = 0.92f, stiffness = Spring.StiffnessMediumLow)
    val screenOffset = spring(dampingRatio = 0.95f, stiffness = 420f, visibilityThreshold = IntOffset(1, 1))
    val screenFloat = spring<Float>(dampingRatio = 1f, stiffness = 420f)
}

private val Sans = FontFamily.SansSerif

val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.2).sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp),
)

private val Scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    secondary = Palette.Accent2,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.SurfaceHigh,
    surfaceContainerHigh = Palette.SurfaceHigher,
    surfaceContainerLow = Palette.Surface,
    outline = Palette.StrokeStrong,
    outlineVariant = Palette.Stroke,
    error = Palette.Danger,
)

@Immutable
data class Spacing(val gutter: androidx.compose.ui.unit.Dp = 20.dp)
val LocalSpacing = staticCompositionLocalOf { Spacing() }

@Composable
fun AnimeruuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
