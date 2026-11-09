package dev.animeruu

import dev.animeruu.core.AppError
import dev.animeruu.data.remote.ApiClient
import dev.animeruu.data.remote.PosterDto
import dev.animeruu.data.remote.toDomain
import dev.animeruu.data.repo.AnimeRepository
import dev.animeruu.domain.AnimeKind
import dev.animeruu.domain.EpisodeLocator
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Uji terhadap API production yang hidup (bukan mock). Jalankan dengan:
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
        log("home: today=${feed.today.size} hot=${feed.hot.size} new=${feed.new.size} popular=${feed.popular.size} random=${feed.random.size} trailer=${feed.trailer.size} waiting=${feed.waiting.size} promos=${feed.promos.size}")
        assertTrue(feed.hot.isNotEmpty() && feed.popular.isNotEmpty())
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
        assertEquals(60, p0.items.size); assertTrue(p0.hasNext)
        val g = repo.search("", "Horror", 0)
        log("genre Horror: ${g.items.size}, semua mengandung Horror=${g.items.all { "Horror" in it.genres }}")
        val none = repo.search("zzqxqzz tidak ada", null, 0)
        log("search tanpa hasil: ${none.items.size} hasNext=${none.hasNext}")
        assertTrue(none.items.isEmpty() && !none.hasNext)
    }

    @Test fun longSeries_onePiece_episodeNavigation() = runBlocking {
        assumeTrue(live)
        val hit = repo.search("one piece", null, 0).items.first { it.title == "One Piece" }
        val d = repo.detail(hit.id)
        log("detail ${d.anime.title} kind=${d.anime.kind} studio=${d.anime.studio} lead=${d.leadEpisode?.title}")
        val first = repo.episodes(hit.id, 0)
        val latest = first.items.first().number!!
        log("episode terbaru=$latest, halaman0=${first.items.size} item")
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
        val st = repo.stream(first.items.first().id)
        log("stream ${st.episode.title}: next=${st.next?.title} sumber=${st.sources.map { "${it.server}/${it.qualityLabel}/${if (it.direct) "direct" else "semi"}" }}")
        assertTrue(st.playable.isNotEmpty())
        val st1 = repo.stream(d.leadEpisode!!.id)
        log("stream ep1: next=${st1.next?.title} playable=${st1.playable.size}/${st1.sources.size}")
        assertNotNull(st1.next)
    }

    @Test fun movie_and_posterMatches() = runBlocking {
        assumeTrue(live)
        val m = repo.search("Mugen Ressha", null, 0).items.first { it.kind == AnimeKind.MOVIE }
        val d = repo.detail(m.id)
        log("MOVIE ${d.anime.title}: lead=${d.leadEpisode?.title} seasons=${d.seasons.map { it.title }}")
        val st = repo.stream(d.leadEpisode!!.id)
        log("MOVIE stream playable=${st.playable.map { it.qualityLabel }} next=${st.next}")
        assertTrue(st.playable.isNotEmpty())
        for (t in listOf("One Piece", "Kimetsu no Yaiba Movie: Mugen Ressha-hen", "Tensei Shitara Slime Datta Ken Movie 2: Soukai no Namida-hen")) {
            val dto = api.get("api/poster", mapOf("title" to t), PosterDto.serializer()).envelope.data!!
            log("poster '$t' → match=${dto.match} kitsu='${dto.title}' rating=${dto.toDomain().rating} poster=${dto.poster != null}")
        }
        try {
            api.get("api/poster", mapOf("title" to "zzqxqzz"), PosterDto.serializer()); error("seharusnya 404")
        } catch (e: AppError.NotFound) { log("poster tak dikenal → 404 NotFound (ditangani)") }
    }
}
