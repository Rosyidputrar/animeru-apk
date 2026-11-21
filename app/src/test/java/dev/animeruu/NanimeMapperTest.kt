package dev.animeruu

import dev.animeruu.core.AppError
import dev.animeruu.data.remote.ApiClient
import dev.animeruu.data.remote.NanimeAnimeDto
import dev.animeruu.data.remote.NanimeEpisodeDto
import dev.animeruu.data.remote.parseViews
import dev.animeruu.data.remote.toAnime
import dev.animeruu.data.remote.toEpisode
import dev.animeruu.data.remote.toStream
import dev.animeruu.data.repo.AnimeRepository
import dev.animeruu.domain.AnimeKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Unit test pemetaan anime-r → model domain + normalisasi bentuk respons di ApiClient. */
class NanimeMapperTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var repo: AnimeRepository

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        api = ApiClient(OkHttpClient.Builder().build(), server.url("/"))
        repo = AnimeRepository(api)
    }

    @After fun teardown() { server.shutdown() }

    private fun animeJson(id: Int = 2779) = """{"id":$id,"nama_anime":"Tokyo Revengers: Santen Sensou-hen","gambar_anime":"https://example.com/a.jpg","rating_anime":"8,18","view_anime":"26.2k","tanggal_rilis_anime":"2026-10-03T00:00:00.000Z","status_anime":"ONGOING","genre_anime":["Action","Drama"],"sinopsis_anime":"Sekuel.","label_anime":"TV","studio_anime":["LIDENFILMS"],"content_type":"ANIME"}"""

    @Test fun parseViews_handlesSuffixes() {
        assertEquals(15200L, parseViews("15.2k"))
        assertEquals(26200L, parseViews("26.2k"))
        assertEquals(26201L, parseViews("26201"))
        assertEquals(26201L, parseViews(26201.toString()))
        assertEquals(7000L, parseViews("7.0k"))
        assertNull(parseViews(null))
        assertNull(parseViews(""))
    }

    @Test fun animeDto_mapsToDomain() {
        val dto = json.decodeFromString(NanimeAnimeDto.serializer(), animeJson())
        val a = dto.toAnime()!!
        assertEquals("2779", a.id)
        assertEquals("Tokyo Revengers: Santen Sensou-hen", a.title)
        assertEquals(26200L, a.views)
        assertEquals("2026", a.year)
        assertEquals(AnimeKind.SERIES, a.kind)
        assertEquals("LIDENFILMS", a.studio)
        assertEquals(listOf("Action", "Drama"), a.genres)
        assertEquals("https://example.com/a.jpg", a.posterUrl)
        assertEquals("https://example.com/a.jpg", a.coverUrl) // Nanime tak punya cover terpisah
    }

    @Test fun filmDto_mapsToMovieKind() {
        val dto = json.decodeFromString(
            NanimeAnimeDto.serializer(),
            animeJson().replace("\"label_anime\":\"TV\"", "\"label_anime\":\"Movie\"").replace("\"content_type\":\"ANIME\"", "\"content_type\":\"FILM\""),
        )
        assertEquals(AnimeKind.MOVIE, dto.toAnime()!!.kind)
    }

    @Test fun episodeDto_mapsToStreamSources() {
        val dto = json.decodeFromString(
            NanimeEpisodeDto.serializer(),
            """{"id":39742,"anime_id":2779,"nomor_episode":8,"judul_episode":"Episode 8","thumbnail_episode":"https://example.com/t.jpg","view_episode":303,"tanggal_rilis_episode":"2026-11-21T00:42:03.424Z","qualities":[{"id":107733,"episode_id":39742,"nama_quality":"1080p","source_quality":"https://example.com/1080.mp4","hls_url":null,"hls_size":null},{"id":107730,"episode_id":39742,"nama_quality":"360p","source_quality":"https://example.com/360.mp4","hls_url":null,"hls_size":null}]}""",
        )
        val ep = dto.toEpisode()!!
        assertEquals("39742", ep.id)
        assertEquals(8.0, ep.number!!, 0.0)
        assertEquals("8", ep.numberLabel)
        val stream = dto.toStream()!!
        assertEquals(2, stream.sources.size)
        assertTrue(stream.sources.all { it.direct })
        assertEquals(1080, stream.sources.first { it.qualityLabel == "1080p" }.height)
        assertEquals("Nanime", stream.sources.first().server)
    }

    @Test fun getList_readsItemsShapeWithRootPagination() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":200,"message":"ok","items":[${animeJson(1)},${animeJson(2)}],"page":1,"limit":2,"total":4,"totalPages":2}"""))
        val r = api.getList("anime/latest", mapOf("page" to "1", "limit" to "2"), NanimeAnimeDto.serializer())
        assertEquals(2, r.data.size)
        assertEquals(1, r.page!!.page)
        assertTrue(r.page!!.hasNext)
        assertEquals(2, r.page!!.nextPage)
    }

    @Test fun getList_readsDataShapeWithPaginationObject() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":200,"message":"ok","data":[${animeJson(1)}],"pagination":{"page":1,"limit":2,"total":3,"totalPages":2,"hasNext":true,"hasPrev":false}}"""))
        val r = api.getList("anime/search", mapOf("q" to "naruto", "page" to "1", "limit" to "2"), NanimeAnimeDto.serializer())
        assertEquals(1, r.data.size)
        assertTrue(r.page!!.hasNext)
        assertEquals(3, r.page!!.total)
    }

    @Test fun getList_readsDataShapeWithMeta() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":200,"message":"ok","data":[${animeJson(1)}],"meta":{"page":2,"limit":20,"total":40,"totalPages":2}}"""))
        val r = api.getList("anime", mapOf("page" to "2", "limit" to "20"), NanimeAnimeDto.serializer())
        assertEquals(1, r.data.size)
        assertEquals(2, r.page!!.page)
        assertEquals(false, r.page!!.hasNext)
        assertNull(r.page!!.nextPage)
    }

    @Test fun getObject_404BecomesNotFound() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"creator":"rosyid","error":"not_found","message":"Rute tidak ditemukan."}"""))
        try {
            api.getObject("anime/999999999", serializer = NanimeAnimeDto.serializer())
            error("seharusnya NotFound")
        } catch (e: AppError.NotFound) {
            assertTrue(e.message!!.contains("tidak ditemukan", ignoreCase = true))
        }
    }

    @Test fun episodes_areSlicedDescendingFromFullList() = runBlocking {
        val eps = (1..5).joinToString(",") { n ->
            """{"id":${100 + n},"anime_id":62,"nomor_episode":$n,"judul_episode":"Episode $n","thumbnail_episode":"https://example.com/t$n.jpg","view_episode":10,"tanggal_rilis_episode":"2026-01-0${n}T00:00:00.000Z","qualities":[]}"""
        }
        server.enqueue(MockResponse().setBody("""{"status":200,"message":"ok","data":[$eps],"count":5}"""))
        val page = repo.episodes("62", 0)
        assertEquals(listOf("5", "4", "3", "2", "1"), page.items.map { it.numberLabel })
        assertEquals(false, page.hasNext)
        // Pencarian episode difilter dari daftar yang sudah di-cache (tanpa request baru).
        val found = repo.episodes("62", 0, "3")
        assertEquals(listOf("3"), found.items.map { it.numberLabel })
        assertEquals(1, server.requestCount) // hanya 1 panggilan jaringan untuk keduanya
    }

    @Test fun stream_prevAndNextComeFromCachedEpisodeList() = runBlocking {
        val eps = (1..3).joinToString(",") { n ->
            """{"id":${100 + n},"anime_id":62,"nomor_episode":$n,"judul_episode":"Episode $n","thumbnail_episode":"https://example.com/t$n.jpg","view_episode":10,"tanggal_rilis_episode":"2026-01-0${n}T00:00:00.000Z","qualities":[]}"""
        }
        server.enqueue(MockResponse().setBody("""{"status":200,"message":"ok","data":[$eps],"count":3}"""))
        repo.episodes("62", 0) // isi cache episode
        server.enqueue(
            MockResponse().setBody(
                """{"status":200,"message":"ok","data":{"id":102,"anime_id":62,"nomor_episode":2,"judul_episode":"Episode 2","thumbnail_episode":"https://example.com/t2.jpg","view_episode":10,"tanggal_rilis_episode":"2026-01-02T00:00:00.000Z","qualities":[{"id":1,"episode_id":102,"nama_quality":"720p","source_quality":"https://example.com/720.mp4","hls_url":null,"hls_size":null}]}}""",
            ),
        )
        val stream = repo.stream("102")
        assertEquals("2", stream.episode.numberLabel)
        assertEquals(1, stream.sources.size)
        assertEquals("3", stream.next?.numberLabel)
        assertEquals("1", stream.prev?.numberLabel)
        // Episode pertama tidak punya prev.
        server.enqueue(
            MockResponse().setBody(
                """{"status":200,"message":"ok","data":{"id":101,"anime_id":62,"nomor_episode":1,"judul_episode":"Episode 1","thumbnail_episode":"https://example.com/t1.jpg","view_episode":10,"tanggal_rilis_episode":"2026-01-01T00:00:00.000Z","qualities":[]}}""",
            ),
        )
        val first = repo.stream("101")
        assertNull(first.prev)
        assertEquals("2", first.next?.numberLabel)
    }
}
