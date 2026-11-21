package dev.animeruu.data.repo

import dev.animeruu.core.AppError
import dev.animeruu.core.Day
import dev.animeruu.data.remote.ApiClient
import dev.animeruu.data.remote.NanimeAnimeDto
import dev.animeruu.data.remote.NanimeEpisodeDto
import dev.animeruu.data.remote.NanimeScheduleDayDto
import dev.animeruu.data.remote.toAnime
import dev.animeruu.data.remote.toAnimeList
import dev.animeruu.data.remote.toDetail
import dev.animeruu.data.remote.toEpisode
import dev.animeruu.data.remote.toStream
import dev.animeruu.domain.Anime
import dev.animeruu.domain.AnimeDetail
import dev.animeruu.domain.Episode
import dev.animeruu.domain.HomeFeed
import dev.animeruu.domain.Page
import dev.animeruu.domain.StreamInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Satu pintu ke anime-r (https://anime-r.rosdiyttaa.workers.dev — passthrough mentah
 * API Nanime). Semua JSON berhenti di sini — UI hanya melihat model domain.
 * Setiap Anime yang pernah lewat disimpan di [known] supaya layar detail bisa langsung
 * menampilkan judul/poster (dan transisi elemen bersama) sebelum detail selesai dimuat.
 *
 * Perbedaan bentuk dengan backend lama (rest-anim) yang dipetakan di sini:
 * - Tidak ada `/api/home`: beranda disusun dari `/anime/latest` (baru + panas),
 *   `/anime` (populer) dan `/anime/schedule` (tayang hari ini + segera tayang).
 *   Rail trailer / acak / untukmu tidak ada padanannya di anime-r → kosong (UI menyembunyikannya).
 * - Tidak ada `/api/poster` (Kitsu): poster memakai `gambar_anime` Nanime langsung.
 * - Episode tidak berpaginasi di server: `/anime/:id/episodes` (dan detail) mengembalikan
 *   SEMUA episode sekaligus (One Piece: 1.177 episode, ~1,2 MB). Daftar penuh di-cache
 *   di memori per anime dan dipotong client-side 30/halaman menurun, supaya
 *   [dev.animeruu.domain.EpisodeLocator] dan UI lama tetap jalan tanpa perubahan.
 * - Stream tidak punya `episode_next`: episode berikutnya dicari dari daftar episode
 *   yang sudah di-cache (ada bila layar detail sudah dibuka); bila belum ada, null.
 */
class AnimeRepository(private val api: ApiClient) {

    private val known = ConcurrentHashMap<String, Anime>()
    private val detailCache = ConcurrentHashMap<String, AnimeDetail>()
    /** Daftar episode lengkap per anime, menaik berdasarkan nomor. */
    private val episodesCache = ConcurrentHashMap<String, List<Episode>>()
    /** Peta jadwal 10 hari + kapan diambil (TTL 30 menit). */
    private var scheduleCache: Pair<Long, Map<String, NanimeScheduleDayDto>>? = null

    fun peek(id: String): Anime? = known[id]

    private fun remember(list: List<Anime>): List<Anime> {
        list.forEach { a -> known.merge(a.id, a) { old, new -> if ((new.synopsis?.length ?: 0) >= (old.synopsis?.length ?: 0)) new else old } }
        return list
    }

    suspend fun home(day: Day? = null): Pair<HomeFeed, Boolean> = coroutineScope {
        val today = day ?: Day.today()
        val latestDeferred = async { api.getList("anime/latest", mapOf("page" to "1", "limit" to HOME_LIMIT.toString()), NanimeAnimeDto.serializer()) }
        val listDeferred = async {
            runCatching { api.getList("anime", mapOf("page" to "1", "limit" to HOME_LIMIT.toString()), NanimeAnimeDto.serializer()) }.getOrNull()
        }
        val scheduleDeferred = async { runCatching { scheduleMap() }.getOrNull() }
        val latest = latestDeferred.await()
        val newest = remember(latest.data.toAnimeList())
        val hot = remember(newest.sortedByDescending { it.views ?: 0 })
        val popular = remember(listDeferred.await()?.data.orEmpty().toAnimeList().sortedByDescending { it.views ?: 0 })
        val (schedule, scheduleStale) = scheduleDeferred.await() ?: (emptyMap<String, NanimeScheduleDayDto>() to false)
        val todayList = remember(scheduleFor(schedule, today))
        val waiting = remember(
            listOf(1, 2).flatMap { offset ->
                val d = Day.entries[(today.ordinal + offset) % Day.entries.size]
                scheduleFor(schedule, d)
            }.distinctBy { it.id }.take(HOME_LIMIT),
        )
        val feed = HomeFeed(
            promos = emptyList(),
            today = todayList,
            hot = hot,
            new = newest,
            popular = popular,
            random = emptyList(),
            trailer = emptyList(),
            waiting = waiting,
            fyp = emptyList(),
        )
        feed to (latest.stale || scheduleStale)
    }

    private suspend fun scheduleMap(): Pair<Map<String, NanimeScheduleDayDto>, Boolean> {
        val now = System.currentTimeMillis()
        scheduleCache?.let { (at, map) -> if (now - at < SCHEDULE_TTL_MS) return map to false }
        val r = api.getObject(
            "anime/schedule",
            serializer = MapSerializer(String.serializer(), NanimeScheduleDayDto.serializer()),
        )
        scheduleCache = now to r.data
        return r.data to r.stale
    }

    /** Daftar anime untuk [day]: cari tanggal dalam jendela 10 hari yang hari minggunya cocok. */
    private fun scheduleFor(map: Map<String, NanimeScheduleDayDto>, day: Day): List<Anime> {
        val entry = map.entries.sortedBy { it.key }.firstOrNull { (k, v) ->
            runCatching { LocalDate.parse(v.date ?: k).dayOfWeek == day.dow }.getOrDefault(false)
        } ?: return emptyList()
        val date = entry.value.date ?: entry.key
        return entry.value.anime.mapNotNull {
            it.toAnime(dayLabel = day.label, airDate = date, airTime = it.jam)
        }
    }

    suspend fun schedule(day: Day): Pair<List<Anime>, Boolean> {
        val (map, stale) = scheduleMap()
        return remember(scheduleFor(map, day)) to stale
    }

    suspend fun search(keyword: String, genre: String?, page: Int): Page<Anime> {
        val q = keyword.trim()
        val page1 = page + 1
        val r = when {
            q.isEmpty() && genre == null ->
                api.getList("anime", mapOf("page" to page1.toString(), "limit" to SEARCH_LIMIT.toString()), NanimeAnimeDto.serializer())
            q.isEmpty() ->
                // Cabang sebelumnya sudah menyaring genre == null, jadi di sini genre pasti ada.
                api.getList("anime/genre/${encodePathSegment(requireNotNull(genre))}", mapOf("page" to page1.toString(), "limit" to SEARCH_LIMIT.toString()), NanimeAnimeDto.serializer())
            else ->
                api.getList("anime/search", mapOf("q" to q, "page" to page1.toString(), "limit" to SEARCH_LIMIT.toString()), NanimeAnimeDto.serializer())
        }
        var items = r.data.toAnimeList()
        // anime-r tidak punya pencarian gabungan kata kunci + genre: saring di client.
        if (q.isNotEmpty() && genre != null) {
            items = items.filter { a -> a.genres.any { it.equals(genre, ignoreCase = true) } }
        }
        items = remember(items)
        val p = r.page
        val hasNext = p?.hasNext ?: false
        return Page(items, page, hasNext, if (hasNext) page + 1 else null, r.stale)
    }

    fun cachedDetail(id: String): AnimeDetail? = detailCache[id]

    suspend fun detail(id: String): AnimeDetail {
        val r = api.getObject("anime/$id", serializer = NanimeAnimeDto.serializer())
        val dto = r.data ?: throw AppError.NotFound("Anime tidak ditemukan")
        val d = dto.toDetail() ?: throw AppError.NotFound("Anime tidak ditemukan")
        remember(listOf(d.anime))
        detailCache[id] = d
        // Detail sudah membawa semua episode → simpan supaya episodes() tak perlu mengunduh ulang.
        cacheEpisodes(id, dto.episodes)
        return d
    }

    private fun cacheEpisodes(animeId: String, dtos: List<NanimeEpisodeDto>) {
        if (dtos.isEmpty()) return
        val list = dtos.mapNotNull { it.toEpisode() }.sortedBy { it.number ?: Double.MAX_VALUE }
        if (list.isNotEmpty()) episodesCache[animeId] = list
    }

    private suspend fun fullEpisodes(animeId: String): Pair<List<Episode>, Boolean> {
        episodesCache[animeId]?.let { return it to false }
        val r = api.getList("anime/$animeId/episodes", itemSerializer = NanimeEpisodeDto.serializer())
        val list = r.data.mapNotNull { it.toEpisode() }.sortedBy { it.number ?: Double.MAX_VALUE }
        if (list.isNotEmpty()) episodesCache[animeId] = list
        return list to r.stale
    }

    suspend fun episodes(animeId: String, page: Int, search: String? = null): Page<Episode> {
        val (all, stale) = fullEpisodes(animeId)
        val q = search?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isEmpty()) all else all.filter { ep ->
            ep.numberLabel == q || ep.numberLabel.lowercase().contains(q) ||
                ep.title.lowercase().contains(q)
        }
        // Menurun (halaman 0 = terbaru), 30 per halaman — sama seperti kontrak lama.
        val desc = filtered.sortedByDescending { it.number ?: Double.MIN_VALUE }
        val from = (page * EPISODE_PAGE_SIZE).coerceAtMost(desc.size)
        val to = minOf(from + EPISODE_PAGE_SIZE, desc.size)
        val items = if (from < to) desc.subList(from, to) else emptyList()
        val hasNext = to < desc.size
        return Page(items, page, hasNext, if (hasNext) page + 1 else null, stale)
    }

    suspend fun stream(episodeId: String): StreamInfo {
        val r = api.getObject("episode/$episodeId", serializer = NanimeEpisodeDto.serializer())
        val dto = r.data ?: throw AppError.NotFound("Episode tidak ditemukan")
        val animeId = dto.anime_id ?: dto.anime?.id
        val cur = dto.nomor_episode.toDoubleOrNullSafe() ?: Double.MIN_VALUE
        // Episode sebelum/berikutnya dari daftar yang sudah di-cache (ada bila layar
        // detail sudah dibuka, atau setelah pencarian episode di player); bila belum
        // ada, keduanya null — sama seperti perilaku `next` sebelumnya.
        val cached = animeId?.let { episodesCache[it] }
        val next = cached
            ?.filter { (it.number ?: Double.MIN_VALUE) > cur }
            ?.minByOrNull { it.number ?: Double.MAX_VALUE }
        val prev = cached
            ?.filter { (it.number ?: Double.MAX_VALUE) < cur }
            ?.maxByOrNull { it.number ?: Double.MIN_VALUE }
        return dto.toStream(next, prev) ?: throw AppError.NotFound("Episode tidak ditemukan")
    }

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.replace(',', '.')?.toDoubleOrNull()

    /** Encode satu segmen path (genre bisa berisi spasi, "/", "&", …). */
    private fun encodePathSegment(raw: String): String =
        java.net.URLEncoder.encode(raw, Charsets.UTF_8).replace("+", "%20")

    companion object {
        private const val HOME_LIMIT = 20
        private const val SEARCH_LIMIT = 20
        private const val EPISODE_PAGE_SIZE = 30
        private const val SCHEDULE_TTL_MS = 30L * 60 * 1000
    }
}
