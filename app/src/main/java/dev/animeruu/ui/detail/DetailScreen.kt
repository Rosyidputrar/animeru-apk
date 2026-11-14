package dev.animeruu.ui.detail

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.animeruu.core.Load
import dev.animeruu.core.compact
import dev.animeruu.core.userMessage
import dev.animeruu.data.local.EpisodeProgressEntity
import dev.animeruu.domain.Anime
import dev.animeruu.domain.AnimeDetail
import dev.animeruu.domain.AnimeKind
import dev.animeruu.domain.Episode
import dev.animeruu.download.DownloadItem
import dev.animeruu.ui.components.AnimeArt
import dev.animeruu.ui.components.ArtKind
import dev.animeruu.ui.components.CardShape
import dev.animeruu.ui.components.ErrorState
import dev.animeruu.ui.components.GlassIconButton
import dev.animeruu.ui.components.MetaChip
import dev.animeruu.ui.components.Pill
import dev.animeruu.ui.components.PosterCard
import dev.animeruu.ui.components.PrimaryButton
import dev.animeruu.ui.components.RatingChip
import dev.animeruu.ui.components.SectionHeader
import dev.animeruu.ui.components.StateMessage
import dev.animeruu.ui.components.sharedBounds
import dev.animeruu.ui.components.shimmer
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import kotlinx.coroutines.launch

data class DetailActions(
    val back: () -> Unit,
    val play: (animeId: String, episodeId: String) -> Unit,
    val openAnime: (Anime, String?) -> Unit,
    val openGenre: (String) -> Unit,
)

/** Jumlah item LazyColumn sebelum baris episode pertama — dipakai untuk menggulir ke hasil "lompat". */
private const val HEADER_ITEMS = 4

@Composable
fun DetailScreen(vm: DetailViewModel, sharedKey: String?, a: DetailActions) {
    val header = vm.header
    val detail = vm.detail
    val fav by vm.isFavorite.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showJump by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.events.collect { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it) } }
    LaunchedEffect(vm) {
        vm.scrollTo.collect { id ->
            val idx = vm.episodes.items.indexOfFirst { it.id == id }
            if (idx >= 0) list.animateScrollToItem(HEADER_ITEMS + idx + (if (vm.episodes.canLoadBefore) 1 else 0), scrollOffset = -220)
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        if (header == null && detail !is Load.Ready) {
            when (detail) {
                is Load.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorState(detail.error, vm::load) }
                else -> DetailSkeleton()
            }
        } else {
            val anime = (detail as? Load.Ready)?.value?.anime ?: header!!
            val d = (detail as? Load.Ready)?.value
            val eps = vm.episodes
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
                item(key = "hero") { Hero(anime, vm, sharedKey, list) }
                item(key = "actions") {
                    Actions(anime, d, history, fav, vm, a, downloads)
                }
                item(key = "info") {
                    when (detail) {
                        is Load.Failed -> ErrorState(detail.error, vm::load)
                        else -> Info(anime, d, vm, a)
                    }
                }
                item(key = "eps-header") {
                    if (anime.kind.hasEpisodeList && d != null) {
                        EpisodeHeader(eps, vm, onJump = { showJump = true })
                    } else Spacer(Modifier.height(1.dp))
                }
                if (anime.kind.hasEpisodeList && d != null) {
                    if (eps.canLoadBefore) item(key = "before") {
                        LoadMoreRow(if (eps.order == EpisodeOrder.NEWEST) "Tampilkan episode yang lebih baru" else "Tampilkan episode sebelumnya", eps.loadingBefore, vm::loadBefore)
                    }
                    when {
                        eps.initialLoading || eps.jumping -> items(6, key = { "sk$it" }) { EpisodeSkeleton() }
                        eps.items.isEmpty() && eps.error != null -> item(key = "eperr") { ErrorState(eps.error, vm::retryEpisodes) }
                        eps.items.isEmpty() -> item(key = "epempty") {
                            StateMessage(Icons.Rounded.SearchOff, "Episode tidak ditemukan", if (eps.query.isNotBlank()) "Tidak ada episode yang cocok dengan “${eps.query}”." else "Belum ada episode untuk judul ini.")
                        }
                        else -> {
                            items(eps.items, key = { it.id }, contentType = { "ep" }) { ep ->
                                EpisodeRow(anime, ep, progress[ep.id], downloads[ep.id], history?.episodeId == ep.id,
                                    onPlay = { a.play(anime.id, ep.id) }, onDownload = { vm.download(ep) },
                                    modifier = Modifier.animateItem(fadeInSpec = Motion.gentle(), placementSpec = Motion.snappy()))
                            }
                            item(key = "after") {
                                LaunchedEffect(eps.items.size, eps.canLoadAfter) { if (eps.canLoadAfter && eps.error == null) vm.loadAfter() }
                                when {
                                    eps.error != null -> Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(eps.error.userMessage(), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.height(8.dp))
                                        PrimaryButton("Coba lagi", vm::retryEpisodes)
                                    }
                                    eps.loadingAfter || eps.canLoadAfter -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(24.dp), color = Palette.Accent, strokeWidth = 2.5.dp)
                                    }
                                    else -> Text(
                                        "— akhir daftar —", style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint,
                                        modifier = Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            TopBar(anime, fav, list, a.back, vm::toggleFavorite)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }

    if (showJump) {
        val latest = vm.episodes.latest
        JumpSheet(latest, onDismiss = { showJump = false }) { n ->
            showJump = false
            scope.launch { vm.jumpTo(n) }
        }
    }
}

@Composable
private fun TopBar(anime: Anime, fav: Boolean, list: androidx.compose.foundation.lazy.LazyListState, back: () -> Unit, toggleFav: () -> Unit) {
    val density = LocalDensity.current
    val t by remember {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f
            else ((list.firstVisibleItemScrollOffset - with(density) { 200.dp.toPx() }) / with(density) { 80.dp.toPx() }).coerceIn(0f, 1f)
        }
    }
    val ctx = LocalContext.current
    Box(
        Modifier.fillMaxWidth()
            .background(Palette.Bg.copy(alpha = t))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", back, size = 42.dp)
            Text(
                anime.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).graphicsLayer { alpha = t; translationY = (1f - t) * 16f },
            )
            GlassIconButton(Icons.Rounded.Share, "Bagikan", {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Nonton ${anime.title} di Animeruu")
                ctx.startActivity(Intent.createChooser(i, "Bagikan"))
            }, size = 42.dp)
            Spacer(Modifier.width(8.dp))
            val s by animateFloatAsState(if (fav) 1f else 0f, Motion.press(), label = "fav")
            GlassIconButton(
                if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorit", toggleFav,
                tint = if (fav) Palette.Accent else Palette.Text, size = 42.dp,
                modifier = Modifier.graphicsLayer { val k = 1f + 0.15f * (s * (1 - s)) * 4; scaleX = k; scaleY = k },
            )
        }
    }
}

@Composable
private fun Hero(anime: Anime, vm: DetailViewModel, sharedKey: String?, list: androidx.compose.foundation.lazy.LazyListState) {
    val art = vm.art
    Box(Modifier.fillMaxWidth().height(430.dp)) {
        AnimeArt(
            anime.title, anime.coverUrl ?: anime.posterUrl, ArtKind.COVER, small = false, showMonogram = false,
            modifier = Modifier.fillMaxWidth().height(330.dp).graphicsLayer {
                if (list.firstVisibleItemIndex == 0) {
                    val off = list.firstVisibleItemScrollOffset.toFloat()
                    translationY = off * 0.5f
                    alpha = 1f - (off / 700f).coerceIn(0f, 0.8f)
                }
            },
        )
        Box(Modifier.fillMaxWidth().height(332.dp).background(Brush.verticalGradient(0f to Palette.Bg.copy(alpha = 0.5f), 0.35f to Color.Transparent, 1f to Palette.Bg)))
        Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.width(128.dp).aspectRatio(2f / 3f).sharedBounds(sharedKey)
                    .clip(RoundedCornerShape(16.dp)).border(0.5.dp, Palette.StrokeStrong, RoundedCornerShape(16.dp))
            ) {
                AnimeArt(anime.title, anime.posterUrl, ArtKind.POSTER, Modifier.fillMaxSize(), small = false)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                Text(anime.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                anime.synonyms?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Rating Kitsu hanya bila judulnya cocok persis — "closest" bisa judul lain.
                    art?.takeIf { it.exact }?.rating?.let { RatingChip(it) }
                    anime.year?.let { MetaChip(it) }
                    MetaChip(anime.kindLabel)
                    anime.status?.let { st ->
                        val ongoing = st.equals("ONGOING", true)
                        MetaChip(if (ongoing) "Ongoing" else st.lowercase().replaceFirstChar { it.uppercase() }, tint = if (ongoing) Palette.Success else Palette.TextDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun Actions(
    anime: Anime,
    d: AnimeDetail?,
    history: dev.animeruu.data.local.HistoryEntity?,
    fav: Boolean,
    vm: DetailViewModel,
    a: DetailActions,
    downloads: Map<String, DownloadItem>,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 20.dp)) {
        val lead = d?.leadEpisode
        val (label, target) = when {
            history != null -> (if (anime.kind == AnimeKind.MOVIE) "Lanjutkan Film" else "Lanjutkan Ep ${history.episodeLabel}") to history.episodeId
            anime.kind == AnimeKind.MOVIE -> "Putar Film" to lead?.id
            lead != null -> "Mulai Ep ${lead.numberLabel}" to lead.id
            else -> "Memuat…" to null
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(label, Modifier.weight(1f), transitionSpec = { fadeIn(Motion.gentle()) togetherWith fadeOut(Motion.gentle()) }, label = "cta") { l ->
                PrimaryButton(l, { target?.let { a.play(anime.id, it) } }, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
            }
            if (anime.kind == AnimeKind.MOVIE && lead != null) {
                Spacer(Modifier.width(10.dp))
                val dl = downloads[lead.id]
                SquareAction(
                    icon = if (dl?.completed == true) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                    desc = when { dl?.completed == true -> "Terunduh"; dl?.active == true -> "Mengunduh"; else -> "Unduh film" },
                    tint = if (dl?.completed == true) Palette.Success else Palette.Text,
                    progress = dl?.takeIf { it.active }?.percent,
                ) { vm.download(lead) }
            }
            Spacer(Modifier.width(10.dp))
            SquareAction(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (fav) Palette.Accent else Palette.Text, desc = if (fav) "Hapus dari favorit" else "Tambah ke favorit") { vm.toggleFavorite() }
        }
        if (history != null && history.durationMs > 0) {
            Spacer(Modifier.height(10.dp))
            val frac = (history.positionMs.toFloat() / history.durationMs).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Palette.SurfaceHigher)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Palette.AccentBrush))
            }
        }
    }
}

@Composable
private fun SquareAction(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, desc: String, progress: Float? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).springClick(pressedScale = 0.88f, haptic = true, onClick = onClick)
            .clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).border(0.5.dp, Palette.Stroke, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) CircularProgressIndicator(progress = { progress / 100f }, Modifier.size(34.dp), color = Palette.Accent, strokeWidth = 2.dp, trackColor = Palette.SurfaceHigher)
        Icon(icon, desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Info(anime: Anime, d: AnimeDetail?, vm: DetailViewModel, a: DetailActions) {
    Column(Modifier.padding(top = 22.dp)) {
        // Statistik
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(anime.views?.compact() ?: "—", "Ditonton", Modifier.weight(1f))
            Stat(anime.favorites?.compact() ?: "—", "Favorit", Modifier.weight(1f))
            Stat(if (anime.kind == AnimeKind.MOVIE) "1" else vm.episodes.latest?.let { DetailViewModel.formatNum(it) } ?: vm.art?.takeIf { it.exact }?.episodes?.toString() ?: "—", if (anime.kind == AnimeKind.MOVIE) "Film" else "Episode", Modifier.weight(1f))
        }
        if (anime.genres.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(anime.genres) { g -> Pill(g, false, { a.openGenre(g) }) }
            }
        }
        // Sinopsis
        val synopsis = anime.synopsis ?: vm.art?.takeIf { it.exact }?.synopsisEn
        if (synopsis != null) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Spacer(Modifier.height(22.dp))
            SectionHeader("Sinopsis", subtitle = if (anime.synopsis == null) "dari Kitsu (Inggris)" else null)
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.padding(horizontal = 20.dp).animateContentSize(Motion.gentle()).springClick(pressedScale = 0.99f) { expanded = !expanded }
            ) {
                Text(synopsis, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                Text(if (expanded) "Tutup" else "Selengkapnya", style = MaterialTheme.typography.labelLarge, color = Palette.Accent, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // Detail
        Spacer(Modifier.height(22.dp))
        SectionHeader("Informasi")
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface)
                .border(0.5.dp, Palette.Stroke, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            val rows = listOfNotNull(
                anime.studio?.let { "Studio" to it },
                "Tipe" to anime.kindLabel,
                anime.status?.let { "Status" to it.lowercase().replaceFirstChar { c -> c.uppercase() } },
                (anime.airedStart?.let { s -> "Tayang" to listOfNotNull(prettyDate(s), anime.airedEnd?.let(::prettyDate)).distinct().joinToString(" – ") }),
                anime.day?.let { "Hari tayang" to it.lowercase().replaceFirstChar { c -> c.uppercase() } },
                anime.year?.let { "Tahun" to it },
            )
            if (d == null) repeat(4) { Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).shimmer()) }
            else rows.forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = Palette.TextFaint, modifier = Modifier.width(110.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Palette.Text)
                }
                if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Palette.Stroke))
            }
        }
        // Season terkait
        val seasons = d?.seasons.orEmpty()
        if (seasons.size > 1 || seasons.any { it.id != anime.id }) {
            Spacer(Modifier.height(26.dp))
            SectionHeader("Season & Film Terkait")
            Spacer(Modifier.height(12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(seasons, key = { it.id }) { s ->
                    val current = s.id == anime.id
                    val k = "poster-season-${s.id}"
                    PosterCard(
                        s, 112.dp, onClick = { if (!current) a.openAnime(s, k) }, sharedKey = if (current) null else k,
                        badge = if (current) "DILIHAT" else s.seasonLabel,
                        subtitle = listOfNotNull(s.kindLabel, s.year).joinToString(" · "),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface).border(0.5.dp, Palette.Stroke, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Palette.Text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint)
    }
}

private val months = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
private fun prettyDate(s: String): String? {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(s) ?: return s
    val (y, mo, d) = m.destructured
    val mi = mo.toIntOrNull()?.takeIf { it in 1..12 } ?: return y
    return if (d == "00") "${months[mi - 1]} $y" else "${d.trimStart('0')} ${months[mi - 1]} $y"
}

@Composable
private fun EpisodeHeader(s: EpisodeListState, vm: DetailViewModel, onJump: () -> Unit) {
    Column(Modifier.padding(top = 30.dp, bottom = 8.dp)) {
        SectionHeader("Episode", subtitle = s.latest?.let { "Terbaru: Episode ${DetailViewModel.formatNum(it)}" })
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            OrderToggle(s.order, enabled = s.query.isBlank(), onChange = vm::setOrder, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.height(40.dp).springClick(haptic = true, onClick = onJump).clip(RoundedCornerShape(12.dp))
                    .background(Palette.SurfaceHigh).border(0.5.dp, Palette.Stroke, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.SwapVert, null, tint = Palette.Text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Lompat", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                .background(Palette.SurfaceHigh).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = Palette.TextFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (s.query.isEmpty()) Text("Cari nomor episode…", style = MaterialTheme.typography.bodyMedium, color = Palette.TextFaint)
                BasicTextField(
                    s.query, vm::setQuery, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
                    cursorBrush = SolidColor(Palette.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(s.query.isNotEmpty(), enter = scaleIn(Motion.press()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                Icon(Icons.Rounded.Close, "Hapus", tint = Palette.TextDim, modifier = Modifier.size(24.dp).springClick { vm.setQuery("") })
            }
        }
    }
}

@Composable
private fun OrderToggle(order: EpisodeOrder, enabled: Boolean, onChange: (EpisodeOrder) -> Unit, modifier: Modifier) {
    BoxWithConstraints(
        modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh)
            .border(0.5.dp, Palette.Stroke, RoundedCornerShape(12.dp)).padding(3.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
    ) {
        val half = maxWidth / 2
        val x by androidx.compose.animation.core.animateDpAsState(if (order == EpisodeOrder.NEWEST) 0.dp else half, Motion.snappy(), label = "ord")
        Box(Modifier.offset(x = x).width(half).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigher))
        Row(Modifier.fillMaxSize()) {
            listOf(EpisodeOrder.NEWEST to "Terbaru", EpisodeOrder.OLDEST to "Dari awal").forEach { (o, l) ->
                Box(Modifier.weight(1f).fillMaxHeight().springClick(haptic = true) { if (enabled) onChange(o) }, contentAlignment = Alignment.Center) {
                    Text(l, style = MaterialTheme.typography.labelLarge, color = if (o == order) Palette.Text else Palette.TextDim)
                }
            }
        }
    }
}

@Composable
private fun LoadMoreRow(text: String, loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(44.dp).springClick(onClick = onClick).clip(RoundedCornerShape(12.dp))
                .border(0.5.dp, Palette.StrokeStrong, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
            else Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
        }
    }
}

@Composable
private fun EpisodeRow(
    anime: Anime,
    ep: Episode,
    progress: EpisodeProgressEntity?,
    dl: DownloadItem?,
    isLast: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().springClick(pressedScale = 0.975f, onClick = onPlay).padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(136.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).border(0.5.dp, Palette.Stroke, RoundedCornerShape(12.dp))) {
            AnimeArt(anime.title, ep.thumbUrl, ArtKind.COVER, Modifier.fillMaxSize(), showMonogram = false)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            Text(
                ep.numberLabel, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
            )
            if (ep.isNew) Text(
                "BARU", style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Palette.AccentBrush).padding(horizontal = 5.dp, vertical = 2.dp),
            )
            if (progress != null && progress.durationMs > 0) {
                val f = (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(3.dp).background(Palette.AccentBrush))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, style = MaterialTheme.typography.titleSmall, color = if (isLast) Palette.Accent else Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ep.releasedAt?.let {
                    Icon(Icons.Rounded.CalendarMonth, null, tint = Palette.TextFaint, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                    Spacer(Modifier.width(10.dp))
                }
                ep.views?.let {
                    Icon(Icons.Rounded.Visibility, null, tint = Palette.TextFaint, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(it.compact(), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                }
            }
            if (progress != null && progress.durationMs > 0 && progress.positionMs > progress.durationMs * 0.9) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Palette.Success, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Selesai ditonton", style = MaterialTheme.typography.bodySmall, color = Palette.Success)
                }
            }
        }
        Box(Modifier.size(40.dp).springClick(pressedScale = 0.85f, haptic = true, onClick = onDownload), contentAlignment = Alignment.Center) {
            when {
                dl == null -> Icon(Icons.Rounded.Download, "Unduh", tint = Palette.TextDim, modifier = Modifier.size(20.dp))
                dl.completed -> Icon(Icons.Rounded.DownloadDone, "Terunduh", tint = Palette.Success, modifier = Modifier.size(20.dp))
                dl.failed -> Icon(Icons.Rounded.ErrorOutline, "Gagal", tint = Palette.Danger, modifier = Modifier.size(20.dp))
                else -> CircularProgressIndicator(progress = { dl.percent / 100f }, Modifier.size(22.dp), color = Palette.Accent, strokeWidth = 2.dp, trackColor = Palette.SurfaceHigher)
            }
        }
    }
}

@Composable
private fun EpisodeSkeleton() {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(136.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).shimmer())
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmer())
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.4f).height(11.dp).clip(RoundedCornerShape(4.dp)).shimmer())
        }
    }
}

@Composable
private fun DetailSkeleton() {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(330.dp).shimmer())
        Spacer(Modifier.height(20.dp))
        Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).shimmer())
        repeat(4) { EpisodeSkeleton() }
    }
}

/** Lembar "Lompat": input nomor langsung + kisi rentang (per 50) yang dihitung dari episode terbaru. */
@Composable
private fun JumpSheet(latest: Double?, onDismiss: () -> Unit, onJump: (Double) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = Palette.Surface, dragHandle = {
        Box(Modifier.padding(top = 10.dp, bottom = 6.dp).width(40.dp).height(5.dp).clip(CircleShape).background(Palette.StrokeStrong))
    }) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Lompat ke episode", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Text(latest?.let { "Tersedia sampai episode ${DetailViewModel.formatNum(it)}" } ?: "", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text("Nomor episode", color = Palette.TextFaint, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(
                        text, { v -> text = v.filter { it.isDigit() || it == '.' }.take(6) }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
                        cursorBrush = SolidColor(Palette.Accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(10.dp))
                PrimaryButton("Buka", { text.toDoubleOrNull()?.let(onJump) })
            }
            if (latest != null && latest > 1) {
                Spacer(Modifier.height(18.dp))
                Text("Rentang", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
                Spacer(Modifier.height(10.dp))
                val step = if (latest > 400) 100 else 50
                val ranges = (0 until ((latest.toInt() - 1) / step + 1)).map { i -> (i * step + 1) to minOf((i + 1) * step, latest.toInt()) }
                LazyVerticalGrid(
                    GridCells.Fixed(3), Modifier.height(if (ranges.size > 9) 280.dp else ((ranges.size + 2) / 3 * 52).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(ranges) { (s, e) ->
                        Box(
                            Modifier.height(44.dp).springClick(haptic = true) { onJump(s.toDouble()) }.clip(RoundedCornerShape(12.dp))
                                .background(Palette.SurfaceHigh).border(0.5.dp, Palette.Stroke, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("$s–$e", style = MaterialTheme.typography.labelLarge, color = Palette.Text) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
