package dev.animeruu.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.animeruu.AppContainer
import dev.animeruu.core.formatDuration
import dev.animeruu.data.local.FavoriteEntity
import dev.animeruu.data.local.HistoryEntity
import dev.animeruu.domain.Anime
import dev.animeruu.domain.AnimeKind
import dev.animeruu.download.DownloadItem
import dev.animeruu.ui.components.AnimeArt
import dev.animeruu.ui.components.ArtKind
import dev.animeruu.ui.components.GlassIconButton
import dev.animeruu.ui.components.PosterCard
import dev.animeruu.ui.components.StateMessage
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val favorites = c.library.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = c.library.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Unduhan yang "dihapus" tapi masih bisa diurungkan: disembunyikan dari daftar, file baru
     * benar-benar dihapus saat snackbar hilang (atau paling lambat [UNDO_WINDOW_MS], walau layar
     * sudah ditinggal — dijalankan di appScope).
     */
    private val pendingDownloads = MutableStateFlow<Set<String>>(emptySet())
    private val removalJobs = ConcurrentHashMap<String, Job>()
    val downloads = combine(c.downloads.items, pendingDownloads) { l, p -> l.filter { it.meta.episodeId !in p } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Riwayat dihapus langsung; baris lama dikembalikan bila diurungkan. */
    fun removeHistory(h: HistoryEntity) = viewModelScope.launch { c.library.removeHistory(h.animeId) }
    fun restoreHistory(h: HistoryEntity) = c.appScope.launch { c.library.restoreHistory(h) }

    fun removeDownload(id: String) {
        pendingDownloads.update { it + id }
        removalJobs.remove(id)?.cancel()
        removalJobs[id] = c.appScope.launch { delay(UNDO_WINDOW_MS); commitDownloadRemoval(id) }
    }

    fun undoDownloadRemoval(id: String) {
        removalJobs.remove(id)?.cancel()
        pendingDownloads.update { it - id }
    }

    /** Snackbar ditutup tanpa "Urungkan" → hapus sekarang. */
    fun commitDownloadRemoval(id: String) {
        val job = removalJobs.remove(id)
        c.appScope.launch {
            c.downloads.remove(id)
            pendingDownloads.update { it - id }
        }
        job?.cancel()
    }

    fun retryDownload(d: DownloadItem) = c.downloads.retry(d.meta)

    companion object {
        /** Sedikit lebih lama dari SnackbarDuration.Long (10 dtk). */
        const val UNDO_WINDOW_MS = 11_000L
    }
}

data class LibraryActions(
    val openAnime: (Anime, String?) -> Unit,
    val play: (animeId: String, episodeId: String) -> Unit,
    val openSettings: () -> Unit,
)

fun FavoriteEntity.toAnime() = Anime(
    id = animeId, title = title, posterUrl = posterUrl, coverUrl = coverUrl,
    kind = runCatching { AnimeKind.valueOf(kind) }.getOrDefault(AnimeKind.SERIES),
    genres = genres.split(',').filter { it.isNotBlank() }, year = year,
)

@Composable
fun LibraryScreen(vm: LibraryViewModel, a: LibraryActions) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle()
    val dls by vm.downloads.collectAsStateWithLifecycle()
    val tabs = listOf("Favorit", "Riwayat", "Unduhan")
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    /** Snackbar "Urungkan"; snackbar sebelumnya ditutup (= dianggap tidak diurungkan). */
    fun offerUndo(message: String, onUndo: () -> Unit, onExpire: () -> Unit = {}) {
        snack.currentSnackbarData?.dismiss()
        scope.launch {
            when (snack.showSnackbar(message, actionLabel = "Urungkan", withDismissAction = true, duration = SnackbarDuration.Long)) {
                SnackbarResult.ActionPerformed -> onUndo()
                SnackbarResult.Dismissed -> onExpire()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding()) {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Koleksi", style = MaterialTheme.typography.headlineMedium, color = Palette.Text, modifier = Modifier.weight(1f))
                GlassIconButton(Icons.Rounded.Settings, "Pengaturan", a.openSettings, size = 40.dp)
            }
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(Palette.Surface).border(0.5.dp, Palette.Stroke, RoundedCornerShape(14.dp)).padding(3.dp)
            ) {
                val w = maxWidth / tabs.size
                val x by animateDpAsState(w * (pager.currentPage + pager.currentPageOffsetFraction), Motion.snappy(), label = "tab")
                Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(Palette.SurfaceHigher))
                Row(Modifier.fillMaxSize()) {
                    tabs.forEachIndexed { i, t ->
                        val count = when (i) { 0 -> favs.size; 1 -> hist.size; else -> dls.size }
                        Box(Modifier.weight(1f).fillMaxHeight().springClick(haptic = true) { scope.launch { pager.animateScrollToPage(i, animationSpec = Motion.gentle()) } }, contentAlignment = Alignment.Center) {
                            Text(if (count > 0) "$t  $count" else t, style = MaterialTheme.typography.labelLarge, color = if (pager.currentPage == i) Palette.Text else Palette.TextDim)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> if (favs.isEmpty()) StateMessage(Icons.Rounded.FavoriteBorder, "Belum ada favorit", "Ketuk ikon hati di halaman anime untuk menyimpannya di sini.", Modifier.padding(top = 60.dp))
                    else LazyVerticalGrid(
                        GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 130.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(favs, key = { it.animeId }) { f ->
                            val k = "poster-fav-${f.animeId}"
                            PosterCard(f.toAnime(), null, onClick = { a.openAnime(f.toAnime(), k) }, sharedKey = k, modifier = Modifier.animateItem())
                        }
                    }
                    1 -> if (hist.isEmpty()) StateMessage(Icons.Rounded.History, "Riwayat kosong", "Episode yang kamu tonton akan muncul di sini, lengkap dengan posisi terakhir.", Modifier.padding(top = 60.dp))
                    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 130.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(hist, key = { it.animeId }) { h -> HistoryRow(h, { a.play(h.animeId, h.episodeId) }, {
                            vm.removeHistory(h)
                            offerUndo("${h.animeTitle} dihapus dari riwayat", onUndo = { vm.restoreHistory(h) })
                        }, Modifier.animateItem()) }
                    }
                    else -> if (dls.isEmpty()) StateMessage(Icons.Rounded.DownloadForOffline, "Belum ada unduhan", "Ketuk ikon unduh di daftar episode untuk menonton tanpa internet.", Modifier.padding(top = 60.dp))
                    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 130.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(dls, key = { it.meta.episodeId }) { d ->
                            val id = d.meta.episodeId
                            DownloadRow(d, { a.play(d.meta.animeId, id) }, {
                                vm.removeDownload(id)
                                val what = if (d.meta.isMovie) d.meta.animeTitle else "${d.meta.animeTitle} · Ep ${d.meta.episodeLabel}"
                                offerUndo("Unduhan $what dihapus", onUndo = { vm.undoDownloadRemoval(id) }, onExpire = { vm.commitDownloadRemoval(id) })
                            }, { vm.retryDownload(d) }, Modifier.animateItem())
                        }
                    }
                }
            }
        }
        // Di atas bilah navigasi bawah yang melayang.
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 96.dp))
    }
}

@Composable
private fun HistoryRow(h: HistoryEntity, onPlay: () -> Unit, onRemove: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().springClick(pressedScale = 0.97f, onClick = onPlay).clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface).border(0.5.dp, Palette.Stroke, RoundedCornerShape(16.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            AnimeArt(h.animeTitle, h.episodeThumb ?: h.coverUrl, ArtKind.COVER, Modifier.fillMaxSize())
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(5.dp))
            if (h.durationMs > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f)).height(3.dp).background(Palette.AccentBrush))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.animeTitle, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (h.isMovie) "Film" else "Episode ${h.episodeLabel}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            if (h.durationMs > 0) Text("${formatDuration(h.positionMs)} / ${formatDuration(h.durationMs)}", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
        Box(Modifier.size(36.dp).springClick(pressedScale = 0.85f, onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Hapus dari riwayat", tint = Palette.TextFaint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DownloadRow(d: DownloadItem, onPlay: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val m = d.meta
    Row(
        modifier.fillMaxWidth().springClick(pressedScale = 0.97f) { if (d.completed) onPlay() }.clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface).border(0.5.dp, Palette.Stroke, RoundedCornerShape(16.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
            AnimeArt(m.animeTitle, m.posterUrl, ArtKind.POSTER, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.animeTitle, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(if (m.isMovie) "Film" else "Episode ${m.episodeLabel}", m.quality, m.sizeMb?.let { "%.0f MB".format(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Spacer(Modifier.height(6.dp))
            when {
                d.completed -> Text("Siap ditonton offline", style = MaterialTheme.typography.labelMedium, color = Palette.Success)
                d.failed -> Text("Gagal — ketuk ulang", style = MaterialTheme.typography.labelMedium, color = Palette.Danger)
                else -> {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Palette.SurfaceHigher)) {
                        Box(Modifier.fillMaxWidth((d.percent / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(Palette.AccentBrush))
                    }
                    Text(if (d.percent > 0) "%.0f%%".format(d.percent) else "Menunggu…", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        when {
            d.completed -> Box(Modifier.size(38.dp).springClick(pressedScale = 0.85f, onClick = onPlay).clip(CircleShape).background(Palette.AccentBrush), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Putar", tint = Color.White)
            }
            d.failed -> Box(Modifier.size(38.dp).springClick(pressedScale = 0.85f, onClick = onRetry), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Refresh, "Ulangi", tint = Palette.Text)
            }
            d.active -> CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
            else -> Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.TextFaint)
        }
        Box(Modifier.size(36.dp).springClick(pressedScale = 0.85f, onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Delete, "Hapus unduhan", tint = Palette.TextFaint, modifier = Modifier.size(18.dp))
        }
    }
}
