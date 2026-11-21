package dev.animeruu

import dev.animeruu.core.AppError
import dev.animeruu.data.remote.ApiClient
import dev.animeruu.data.remote.NanimeGenresDataDto
import dev.animeruu.data.repo.AnimeRepository
import dev.animeruu.domain.AnimeKind
import dev.animeruu.domain.EpisodeLocator
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Uji terhadap API production yang hidup (anime-r) — bukan mock. Jalankan dengan:
 *   LIVE_API=1 ./gradlew :app:testReleaseUnitTest --tests '*LiveApiTest*'
 */
class LiveApiTest {
    private val live = System.getenv("LIVE_API") == "1"
    private val api = ApiClient(OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build())
    private val repo = AnimeRepository(api)
    private fun log(s: String) = println("[LIVE] $s")

    @Test fun health_home_schedule() = runBlocking {
        assumeTrue(live)
        val (feed, _) = repo.home()
        log("home: today=${feed.today.size} hot=${feed.hot.size} new=${feed.new.size} popular=${feed.popular.size} waiting=${feed.waiting.size}")
        assertTrue(feed.new.isNotEmpty() && feed.hot.isNotEmpty())
        for (d in dev.animeruu.core.Day.entries) {
            val (list, _) = repo.schedule(d)
            log("schedule ${d.api}: ${list.size} judul")
        }
    }

    @Test fun search_paging_and_empty() = runBlocking {
        assumeTrue(live)
        val p0 = repo.search("", null, 0)
        val p1 = repo.search("", null, 1)
        log("browse p0=${p0.items.size} hasNext=${p0.hasNext} p1=${p1.items.size} overlap=${p0.items.map { it.id }.intersect(p1.items.map { it.id }.toSet()).size}")
        assertEquals(20, p0.items.size); assertTrue(p0.hasNext)
        // Urutan upstream bisa bergeser antar permintaan (rilis baru masuk di
        // antara page 1 dan 2), jadi tumpang tindih 1–2 judul sesekali adalah
        // perilaku upstream, bukan bug paging aplikasi. Yang dijaga: halaman
        // maju (bukan halaman yang sama) dan hasNext bekerja.
        val overlap = p0.items.map { it.id }.intersect(p1.items.map { it.id }.toSet()).size
        assertTrue(overlap <= 2)
        val g = repo.search("", "Horror", 0)
        log("genre Horror: ${g.items.size}, semua mengandung Horror=${g.items.all { "Horror" in it.genres }}")
        assertTrue(g.items.isNotEmpty())
        assertTrue(g.items.all { "Horror" in it.genres })
        val none = repo.search("zzqxqzz tidak ada", null, 0)
        log("search tanpa hasil: ${none.items.size} hasNext=${none.hasNext}")
        assertTrue(none.items.isEmpty() && !none.hasNext)
        val genres = api.getObject("anime/genres", serializer = NanimeGenresDataDto.serializer()).data
        log("genres: ${genres.genres.size} total, ada Action=${"Action" in genres.genres}")
        assertTrue("Action" in genres.genres && "Horror" in genres.genres)
    }

    @Test fun longSeries_onePiece_episodeNavigation() = runBlocking {
        assumeTrue(live)
        val hit = repo.search("one piece", null, 0).items.first { it.title == "One Piece" }
        val d = repo.detail(hit.id)
        log("detail ${d.anime.title} kind=${d.anime.kind} studio=${d.anime.studio} lead=${d.leadEpisode?.title}")
        val first = repo.episodes(hit.id, 0)
        val latest = first.items.first().number!!
        log("episode terbaru=$latest, halaman0=${first.items.size} item")
        assertTrue(latest >= 1180.0)
        var calls = 0
        val loc = EpisodeLocator(load = { calls++; repo.episodes(hit.id, it) })
        val last = loc.lastPage(latest)
        val lastPage = repo.episodes(hit.id, last)
        log("halaman terakhir=$last (${calls} permintaan), berisi ep ${lastPage.items.last().numberLabel}..${lastPage.items.first().numberLabel}")
        assertEquals(1.0, lastPage.items.minOf { it.number!! }, 0.0)
        for (target in listOf(1.0, 500.0, 1000.0, latest)) {
            calls = 0
            val p = loc.pageFor(target, latest)
            val nums = repo.episodes(hit.id, p).items.mapNotNull { it.number }
            log("lompat ke ep $target → halaman $p ($calls permintaan), rentang ${nums.min()}..${nums.max()}")
            assertTrue(target in nums.min()..nums.max())
        }
        val s = repo.episodes(hit.id, 0, "1000")
        log("search episode '1000': ${s.items.map { it.numberLabel }}")
        assertTrue(s.items.any { it.numberLabel == "1000" })
        // first.items.first() adalah episode TERBARU (1180) — next-nya memang null.
        // Uji "berikutnya" memakai episode tengah halaman 0 (1151 → next 1152, prev 1150).
        val st = repo.stream(first.items.last().id)
        log("stream ${st.episode.title}: prev=${st.prev?.title} next=${st.next?.title} sumber=${st.sources.map { "${it.server}/${it.qualityLabel}" }}")
        assertTrue(st.playable.isNotEmpty())
        assertNotNull(st.next) // daftar episode sudah di-cache dari detail()
        assertEquals(1150.0, st.prev?.number ?: -1.0, 0.0)
        val stLatest = repo.stream(first.items.first().id)
        log("stream episode terbaru ${stLatest.episode.title}: prev=${stLatest.prev?.title} next=${stLatest.next}")
        assertNull(stLatest.next)
        assertNotNull(stLatest.prev) // 1179
        val st1 = repo.stream(d.leadEpisode!!.id)
        log("stream ep1: prev=${st1.prev} next=${st1.next?.title} playable=${st1.playable.size}/${st1.sources.size}")
        assertNotNull(st1.next)
        assertNull(st1.prev) // episode pertama tidak punya sebelumnya
    }

    @Test fun movie_stream_qualities() = runBlocking {
        assumeTrue(live)
        val m = repo.search("Film Red", null, 0).items.first { it.kind == AnimeKind.MOVIE }
        val d = repo.detail(m.id)
        log("MOVIE ${d.anime.title}: lead=${d.leadEpisode?.title} seasons=${d.seasons.size}")
        val st = repo.stream(d.leadEpisode!!.id)
        log("MOVIE stream playable=${st.playable.map { it.qualityLabel }} next=${st.next}")
        assertTrue(st.playable.isNotEmpty())
        assertTrue(st.playable.any { it.qualityLabel == "1080p" })
    }

    @Test fun unknown_anime_is_404() = runBlocking {
        assumeTrue(live)
        try {
            repo.detail("999999999"); error("seharusnya 404")
        } catch (e: AppError.NotFound) {
            log("detail tak dikenal → 404 NotFound (ditangani)")
        }
    }
}
