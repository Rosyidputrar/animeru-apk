package dev.animeruu.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import dev.animeruu.data.repo.PosterRepository
import dev.animeruu.domain.PosterArt
import java.util.concurrent.atomic.AtomicInteger

val LocalPosters = staticCompositionLocalOf<PosterRepository?> { null }

enum class ArtKind { POSTER, COVER }

/**
 * Pemutus sirkuit untuk CDN sumber (*.animein.net, di balik Cloudflare): bila beberapa
 * gambar pertama gagal dan belum ada satu pun yang berhasil, CDN dilewati selama sesi
 * (dicoba lagi tiap 5 menit) → kartu langsung memakai Kitsu tanpa menunggu 403 dulu.
 */
object CdnHealth {
    private val fails = AtomicInteger(0)
    private val successes = AtomicInteger(0)
    @Volatile private var trippedAt = 0L

    val usable: Boolean
        get() = successes.get() > 0 || fails.get() < 4 || System.currentTimeMillis() - trippedAt > 5 * 60_000

    fun success() { successes.incrementAndGet() }
    fun failure() {
        if (fails.incrementAndGet() >= 4 && successes.get() == 0) trippedAt = System.currentTimeMillis()
    }
}

/**
 * Gambar anime dengan fallback berjenjang: CDN sumber → poster/cover Kitsu (/api/poster)
 * → placeholder monogram bergradien. Placeholder selalu digambar di bawah sehingga tidak
 * pernah ada kotak kosong atau tata letak yang rusak, apa pun hasil jaringannya.
 *
 * @param small minta varian kecil (thumbnail kartu) — dimuat cepat, dipakai juga sebagai
 *   placeholder memori untuk versi besar di layar detail (pemuatan progresif).
 */
@Composable
fun AnimeArt(
    title: String,
    cdnUrl: String?,
    kind: ArtKind,
    modifier: Modifier = Modifier,
    small: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    showMonogram: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val posters = LocalPosters.current
    var stage by remember(cdnUrl, title) { mutableIntStateOf(if (cdnUrl != null && CdnHealth.usable) 0 else 1) }
    // Kitsu diresolusi paralel sejak awal → fallback selalu siap begitu CDN gagal.
    val lookup: ArtLookup by produceState(
        posters?.peek(title)?.let { ArtLookup(true, it) } ?: ArtLookup(posters == null, null), title,
    ) {
        if (!value.resolved) value = ArtLookup(true, posters?.get(title))
    }
    fun pick(a: PosterArt) = if (kind == ArtKind.COVER) a.coverUrl ?: a.posterUrl else a.posterUrl ?: a.coverUrl
    val kitsu = lookup.art?.let { PosterRepository.sized(pick(it), small) }
    val kitsuSmall = lookup.art?.let { PosterRepository.sized(pick(it), true) }
    val model: String? = when (stage) {
        0 -> cdnUrl
        1 -> kitsu
        else -> null
    }
    val ctx = LocalContext.current
    val request = remember(model) {
        model?.let {
            ImageRequest.Builder(ctx).data(it)
                .placeholderMemoryCacheKey(if (stage == 1 && !small && kitsuSmall != it) kitsuSmall else null)
                .build()
        }
    }
    val painter = rememberAsyncImagePainter(
        model = request,
        onState = { s ->
            when (s) {
                is AsyncImagePainter.State.Success -> if (stage == 0) CdnHealth.success()
                is AsyncImagePainter.State.Error -> when (stage) {
                    0 -> { CdnHealth.failure(); stage = 1 }
                    1 -> stage = 2
                }
                else -> Unit
            }
        },
    )
    val settledEmpty = stage == 2 || (stage == 1 && lookup.resolved && kitsu == null)
    Box(modifier) {
        ArtPlaceholder(title, showMonogram && settledEmpty, Modifier.fillMaxSize())
        if (!settledEmpty && painter.state !is AsyncImagePainter.State.Success) {
            Box(Modifier.fillMaxSize().shimmer(base = Color.Transparent, highlight = Color.White.copy(alpha = 0.06f)))
        }
        if (request != null) {
            Image(painter, contentDescription = title, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
        overlay()
    }
}

private data class ArtLookup(val resolved: Boolean, val art: PosterArt?)

/** Gradien deterministik dari judul + monogram — terlihat disengaja, bukan "gambar rusak". */
@Composable
fun ArtPlaceholder(title: String, showText: Boolean, modifier: Modifier = Modifier) {
    val hue = remember(title) { (title.hashCode().toLong() and 0xffff).toFloat() % 360f }
    val c1 = remember(hue) { Color.hsl(hue, 0.45f, 0.20f) }
    val c2 = remember(hue) { Color.hsl((hue + 40f) % 360f, 0.55f, 0.10f) }
    Box(
        modifier.drawWithCache {
            val b = Brush.linearGradient(listOf(c1, c2), Offset.Zero, Offset(size.width, size.height))
            onDrawBehind { drawRect(b) }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (showText) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black, fontSize = 13.sp, lineHeight = 16.sp),
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
fun SkeletonBox(modifier: Modifier) = Box(modifier.background(Color.Transparent).shimmer())
