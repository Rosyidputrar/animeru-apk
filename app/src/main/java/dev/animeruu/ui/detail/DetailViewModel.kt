package dev.animeruu.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.animeruu.AppContainer
import dev.animeruu.core.Load
import dev.animeruu.data.local.DownloadEntity
import dev.animeruu.data.local.EpisodeProgressEntity
import dev.animeruu.data.local.HistoryEntity
import dev.animeruu.domain.Anime
import dev.animeruu.domain.AnimeDetail
import dev.animeruu.domain.Episode
import dev.animeruu.domain.EpisodeLocator
import dev.animeruu.domain.Page
import dev.animeruu.domain.PosterArt
import dev.animeruu.download.DownloadItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class EpisodeOrder { NEWEST, OLDEST }

data class EpisodeListState(
    val order: EpisodeOrder = EpisodeOrder.NEWEST,
    val query: String = "",
    val items: List<Episode> = emptyList(),
    val initialLoading: Boolean = true,
    val loadingAfter: Boolean = false,
    val loadingBefore: Boolean = false,
    val canLoadAfter: Boolean = false,
    val canLoadBefore: Boolean = false,
    val error: Throwable? = null,
    /** Nomor episode terbaru (dari halaman 0) — dasar perkiraan halaman & rentang lompat. */
    val latest: Double? = null,
    val jumping: Boolean = false,
)

class DetailViewModel(private val c: AppContainer, val animeId: String) : ViewModel() {

    var header by mutableStateOf(c.anime.peek(animeId) ?: c.anime.cachedDetail(animeId)?.anime)
        private set
    var detail by mutableStateOf<Load<AnimeDetail>>(c.anime.cachedDetail(animeId)?.let { Load.Ready(it) } ?: Load.Loading)
        private set
    var art by mutableStateOf<PosterArt?>(null)
        private set
    var episodes by mutableStateOf(EpisodeListState())
        private set

    val isFavorite: StateFlow<Boolean> = c.library.isFavorite(animeId).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val history: StateFlow<HistoryEntity?> = c.library.historyFor(animeId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val progress: StateFlow<Map<String, EpisodeProgressEntity>> = c.library.progressFor(animeId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val downloads: StateFlow<Map<String, DownloadItem>> = c.downloads.items
        .map { l -> l.filter { it.meta.animeId == animeId }.associateBy { it.meta.episodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val _scrollTo = Channel<String>(Channel.CONFLATED)
    /** Id episode yang harus digulir ke tampilan (hasil "lompat"). */
    val scrollTo = _scrollTo.receiveAsFlow()

    // Halaman dicache per (query, page) supaya ganti urutan/lompat tidak memuat ulang.
    private val pageCache = HashMap<Pair<String, Int>, Page<Episode>>()
    private var lo = 0
    private var hi = 0
    private var listJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            if (detail !is Load.Ready) detail = Load.Loading
            try {
                val d = c.anime.detail(animeId)
                header = d.anime
                detail = Load.Ready(d)
                launch { art = c.posters.get(d.anime.title) }
                if (d.anime.kind.hasEpisodeList && episodes.items.isEmpty()) resetEpisodes()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (detail !is Load.Ready) detail = Load.Failed(t)
            }
        }
        header?.let { h -> viewModelScope.launch { art = c.posters.get(h.title) } }
    }

    private suspend fun page(p: Int, q: String = episodes.query): Page<Episode> {
        val key = q.trim() to p
        pageCache[key]?.let { return it }
        val r = c.anime.episodes(animeId, p, q.trim().ifEmpty { null })
        if (!r.stale) pageCache[key] = r
        return r
    }

    private fun recompute(extra: EpisodeListState.() -> EpisodeListState = { this }) {
        val q = episodes.query.trim()
        val loaded = (lo..hi).mapNotNull { pageCache[q to it] }
        val newestFirst = episodes.order == EpisodeOrder.NEWEST || q.isNotEmpty()
        val items = if (newestFirst) loaded.flatMap { it.items }
        else loaded.asReversed().flatMap { it.items.asReversed() }
        val hiHasNext = pageCache[q to hi]?.hasNext == true
        episodes = episodes.copy(
            items = items.distinctBy { it.id },
            canLoadAfter = if (newestFirst) hiHasNext else lo > 0,
            canLoadBefore = if (newestFirst) lo > 0 else hiHasNext,
            initialLoading = false,
        ).extra()
    }

    private fun resetEpisodes() {
        listJob?.cancel()
        episodes = episodes.copy(items = emptyList(), initialLoading = true, error = null, canLoadAfter = false, canLoadBefore = false)
        listJob = viewModelScope.launch {
            try {
                val first = page(0)
                val latest = episodes.latest ?: first.items.firstOrNull()?.number
                episodes = episodes.copy(latest = latest)
                // Sudah melihat halaman ini → episode terbaru dianggap diketahui (tidak dinotifikasi lagi).
                if (latest != null && episodes.query.isBlank()) c.episodeWatch.markSeen(animeId, latest)
                if (episodes.order == EpisodeOrder.OLDEST && episodes.query.isBlank() && first.hasNext && latest != null) {
                    val last = EpisodeLocator(load = { page(it) }).lastPage(latest)
                    lo = last; hi = last
                } else {
                    lo = 0; hi = 0
                }
                recompute()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                episodes = episodes.copy(initialLoading = false, error = t)
            }
        }
    }

    fun setOrder(o: EpisodeOrder) {
        if (o == episodes.order) return
        episodes = episodes.copy(order = o)
        resetEpisodes()
    }

    private var searchJob: Job? = null
    fun setQuery(q: String) {
        episodes = episodes.copy(query = q)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            resetEpisodes()
        }
    }

    /** Muat ke arah bawah daftar (sesuai urutan tampilan). */
    fun loadAfter() {
        val s = episodes
        if (!s.canLoadAfter || s.loadingAfter || s.initialLoading) return
        val newestFirst = s.order == EpisodeOrder.NEWEST || s.query.isNotBlank()
        val target = if (newestFirst) hi + 1 else lo - 1
        episodes = s.copy(loadingAfter = true, error = null)
        listJob = viewModelScope.launch {
            try {
                page(target)
                if (newestFirst) hi = target else lo = target
                recompute { copy(loadingAfter = false) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                episodes = episodes.copy(loadingAfter = false, error = t)
            }
        }
    }

    /** Muat ke arah atas (episode yang ada "sebelum" jendela saat ini setelah melompat). */
    fun loadBefore() {
        val s = episodes
        if (!s.canLoadBefore || s.loadingBefore) return
        val newestFirst = s.order == EpisodeOrder.NEWEST || s.query.isNotBlank()
        val target = if (newestFirst) lo - 1 else hi + 1
        episodes = s.copy(loadingBefore = true)
        viewModelScope.launch {
            try {
                page(target)
                if (newestFirst) lo = target else hi = target
                recompute { copy(loadingBefore = false) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                episodes = episodes.copy(loadingBefore = false)
                _events.send("Gagal memuat episode. Coba lagi.")
            }
        }
    }

    /** Lompat ke episode bernomor [n]: tebak halaman, koreksi, lalu gulir ke sana. */
    fun jumpTo(n: Double) {
        val latest = episodes.latest ?: return
        if (episodes.query.isNotBlank()) episodes = episodes.copy(query = "")
        episodes = episodes.copy(jumping = true)
        listJob?.cancel()
        listJob = viewModelScope.launch {
            try {
                val p = EpisodeLocator(load = { page(it, "") }).pageFor(n, latest)
                lo = p; hi = p
                recompute { copy(jumping = false) }
                val target = episodes.items.minByOrNull { abs((it.number ?: Double.MAX_VALUE) - n) }
                if (target != null) _scrollTo.send(target.id)
                if (target?.number != n) _events.send("Episode ${formatNum(n)} tidak ada — menampilkan yang terdekat")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                episodes = episodes.copy(jumping = false)
                _events.send("Gagal melompat. Periksa koneksi.")
            }
        }
    }

    fun retryEpisodes() = if (episodes.items.isEmpty()) resetEpisodes() else loadAfter()

    fun toggleFavorite() {
        val a = header ?: return
        viewModelScope.launch {
            val now = !isFavorite.value
            c.library.setFavorite(a, now)
            _events.send(if (now) "Ditambahkan ke Favorit" else "Dihapus dari Favorit")
        }
    }

    fun download(ep: Episode) {
        val a = header ?: return
        viewModelScope.launch {
            try {
                val existing = c.downloads.find(ep.id)
                if (existing != null) {
                    _events.send("Sudah ada di Unduhan")
                    return@launch
                }
                val s = c.settings.settings.first()
                val stream = c.anime.stream(ep.id)
                val src = dev.animeruu.domain.SourceChain.order(stream.sources, s.downloadHeight, true).firstOrNull()
                if (src == null) {
                    _events.send("Episode ini tidak punya file yang bisa diunduh")
                    return@launch
                }
                c.downloads.enqueue(
                    DownloadEntity(
                        episodeId = ep.id, animeId = a.id, animeTitle = a.title, episodeLabel = ep.numberLabel,
                        episodeTitle = ep.title, posterUrl = a.posterUrl, url = src.url, quality = src.qualityLabel,
                        sizeMb = src.sizeMb, isMovie = a.kind == dev.animeruu.domain.AnimeKind.MOVIE,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                _events.send("Mengunduh ${if (a.kind == dev.animeruu.domain.AnimeKind.MOVIE) "film" else "Ep ${ep.numberLabel}"} · ${src.qualityLabel}" + if (s.wifiOnlyDownloads && !c.isUnmetered()) " (menunggu Wi‑Fi)" else "")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _events.send("Gagal memulai unduhan")
            }
        }
    }

    companion object {
        fun formatNum(n: Double) = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
    }
}

val Anime.kindLabel: String
    get() = when (typeLabel) {
        "SERIES" -> "Serial"
        "MOVIE" -> "Film"
        null -> "Serial"
        else -> typeLabel
    }
