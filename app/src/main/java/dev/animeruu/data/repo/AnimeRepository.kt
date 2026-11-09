package dev.animeruu.data.repo

import dev.animeruu.core.AppError
import dev.animeruu.core.Day
import dev.animeruu.data.remote.ApiClient
import dev.animeruu.data.remote.DetailDto
import dev.animeruu.data.remote.EpisodeDto
import dev.animeruu.data.remote.HomeDto
import dev.animeruu.data.remote.MovieDto
import dev.animeruu.data.remote.StreamDto
import dev.animeruu.data.remote.toAnimeList
import dev.animeruu.data.remote.toDomain
import dev.animeruu.data.remote.toEpisode
import dev.animeruu.domain.Anime
import dev.animeruu.domain.AnimeDetail
import dev.animeruu.domain.Episode
import dev.animeruu.domain.HomeFeed
import dev.animeruu.domain.Page
import dev.animeruu.domain.StreamInfo
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.ConcurrentHashMap

/**
 * Satu pintu ke REST-ANIM. Semua JSON berhenti di sini — UI hanya melihat model domain.
 * Setiap Anime yang pernah lewat disimpan di [known] supaya layar detail bisa langsung
 * menampilkan judul/poster (dan transisi elemen bersama) sebelum detail selesai dimuat.
 */
class AnimeRepository(private val api: ApiClient) {

    private val known = ConcurrentHashMap<String, Anime>()
    private val detailCache = ConcurrentHashMap<String, AnimeDetail>()

    fun peek(id: String): Anime? = known[id]

    private fun remember(list: List<Anime>): List<Anime> {
        list.forEach { a -> known.merge(a.id, a) { old, new -> if ((new.synopsis?.length ?: 0) >= (old.synopsis?.length ?: 0)) new else old } }
        return list
    }

    suspend fun home(day: Day? = null): Pair<HomeFeed, Boolean> {
        val r = api.get("api/home", mapOf("day" to day?.api), HomeDto.serializer())
        val feed = (r.envelope.data ?: throw AppError.Parse(IllegalStateException("home kosong"))).toDomain()
        remember(feed.today + feed.hot + feed.new + feed.popular + feed.random + feed.waiting + feed.fyp)
        return feed to r.stale
    }

    suspend fun schedule(day: Day): Pair<List<Anime>, Boolean> {
        val r = api.get("api/schedule", mapOf("day" to day.api), ListSerializer(MovieDto.serializer()))
        return remember(r.envelope.data.orEmpty().toAnimeList()) to r.stale
    }

    suspend fun search(keyword: String, genre: String?, page: Int): Page<Anime> {
        val r = api.get(
            "api/search",
            mapOf("keyword" to keyword.trim(), "genre" to (genre ?: ""), "sort" to "views", "page" to page.toString()),
            ListSerializer(MovieDto.serializer()),
        )
        val items = remember(r.envelope.data.orEmpty().toAnimeList())
        val p = r.envelope.pagination
        return Page(items, p?.page ?: page, p?.has_next ?: false, p?.next_page, r.stale)
    }

    fun cachedDetail(id: String): AnimeDetail? = detailCache[id]

    suspend fun detail(id: String): AnimeDetail {
        val r = api.get("api/anime/$id", serializer = DetailDto.serializer())
        val d = r.envelope.data?.toDomain() ?: throw AppError.NotFound("Anime tidak ditemukan")
        remember(listOf(d.anime) + d.seasons)
        detailCache[id] = d
        return d
    }

    suspend fun episodes(animeId: String, page: Int, search: String? = null): Page<Episode> {
        val r = api.get(
            "api/anime/$animeId/episodes",
            mapOf("page" to page.toString(), "search" to search?.trim()?.takeIf { it.isNotEmpty() }),
            ListSerializer(EpisodeDto.serializer()),
        )
        val items = r.envelope.data.orEmpty().mapNotNull { it.toEpisode() }.distinctBy { it.id }
        val p = r.envelope.pagination
        return Page(items, p?.page ?: page, p?.has_next ?: false, p?.next_page, r.stale)
    }

    suspend fun stream(episodeId: String): StreamInfo {
        val r = api.get("api/episode/$episodeId/stream", serializer = StreamDto.serializer())
        return r.envelope.data?.toDomain() ?: throw AppError.NotFound("Episode tidak ditemukan")
    }
}
