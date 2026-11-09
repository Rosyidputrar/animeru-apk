package dev.animeruu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.animeruu.core.AppError
import dev.animeruu.core.userMessage
import dev.animeruu.domain.Anime
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette

val CardShape = RoundedCornerShape(14.dp)

/** Kartu poster 2:3 dengan judul; [rank] menampilkan nomor besar untuk rail peringkat. */
@Composable
fun PosterCard(
    anime: Anime,
    width: Dp?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedKey: String? = null,
    badge: String? = null,
    subtitle: String? = anime.genres.take(2).joinToString(" · ").ifEmpty { anime.year },
) {
    val m = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Column(m.springClick(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .sharedBounds(sharedKey)
                .clip(CardShape)
                .border(0.5.dp, Palette.Stroke, CardShape)
        ) {
            AnimeArt(anime.title, anime.posterUrl, ArtKind.POSTER, Modifier.fillMaxSize())
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Palette.AccentBrush)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            anime.title,
            style = MaterialTheme.typography.titleSmall,
            color = Palette.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PosterSkeleton(width: Dp?, modifier: Modifier = Modifier) {
    val m = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Column(m) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(CardShape).shimmer())
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(0.9f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmer())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.55f).height(10.dp).clip(RoundedCornerShape(4.dp)).shimmer())
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, onMore: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        if (onMore != null) {
            Row(
                Modifier.springClick(onClick = onMore).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Semua", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = Palette.TextDim, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) Palette.Text else Palette.SurfaceHigh
    val fg = if (selected) Palette.Bg else Palette.TextDim
    Box(
        modifier
            .springClick(haptic = true, onClick = onClick)
            .clip(CircleShape)
            .background(bg)
            .border(0.5.dp, if (selected) Color.Transparent else Palette.Stroke, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

@Composable
fun MetaChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Palette.TextDim) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@Composable
fun RatingChip(rating: Double, modifier: Modifier = Modifier) =
    MetaChip("%.1f".format(rating), modifier, Icons.Rounded.Star, Palette.Warning)

/** Tombol utama bergradien. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    brush: Brush = Palette.AccentBrush,
    contentColor: Color = Color.White,
) {
    Row(
        modifier
            .springClick(haptic = true, onClick = onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(brush)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = contentColor, maxLines = 1)
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.Text, size: Dp = 44.dp) {
    Box(
        modifier
            .size(size)
            .springClick(pressedScale = 0.88f, haptic = true, onClick = onClick)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.48f))
    }
}

/** Keadaan kosong / error yang dirancang: ikon dalam lingkaran bergradien, judul, deskripsi, aksi. */
@Composable
fun StateMessage(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        shown,
        modifier = modifier,
        enter = fadeIn(Motion.gentle()) + scaleIn(Motion.snappy(), initialScale = 0.92f) + slideInVertically(Motion.gentle()) { it / 8 },
        exit = fadeOut(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(76.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Palette.Accent.copy(alpha = 0.22f), Palette.Accent2.copy(alpha = 0.22f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Palette.Text, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, textAlign = TextAlign.Center)
            }
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                PrimaryButton(actionText, onAction, icon = Icons.Rounded.Refresh)
            }
        }
    }
}

@Composable
fun ErrorState(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val offline = error is AppError.Offline
    StateMessage(
        icon = if (offline) Icons.Rounded.WifiOff else Icons.Rounded.CloudOff,
        title = if (offline) "Kamu sedang offline" else "Gagal memuat",
        body = error.userMessage(),
        modifier = modifier,
        actionText = "Coba lagi",
        onAction = onRetry,
    )
}

/** Pita kecil saat data berasal dari cache (offline). */
@Composable
fun StaleBanner(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut()) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                .clip(CircleShape)
                .background(Palette.Warning.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.WifiOff, null, tint = Palette.Warning, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Offline — menampilkan data tersimpan", style = MaterialTheme.typography.labelMedium, color = Palette.Warning)
        }
    }
}

@Composable
fun GradientText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium.copy(brush = Palette.AccentBrush, fontWeight = FontWeight.Black, fontSize = 26.sp),
    )
}

val ContentPaddingBottom = PaddingValues(bottom = 120.dp)
