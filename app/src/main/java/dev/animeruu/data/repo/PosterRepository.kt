package dev.animeruu.data.repo

import dev.animeruu.data.local.PosterCacheEntity
import dev.animeruu.data.local.PosterDao
import dev.animeruu.domain.PosterArt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Poster Kitsu (cache lokal saja).
 *
 * Backend baru (anime-r) tidak punya endpoint poster, dan poster Nanime
 * (`gambar_anime`) sudah dipakai langsung sebagai gambar utama — jadi tidak ada lagi
 * pencarian Kitsu lewat jaringan. Repository ini dipertahankan supaya:
 * - entri Kitsu lama yang sudah tersimpan di Room tetap bisa dipakai sebagai fallback
 *   gambar (dan rating/sinopsis di layar detail),
 * - [dev.animeruu.ui.components.AnimeArt] dan notifikasi episode baru tidak perlu diubah
 *   bentuknya — `get()` kini hanya membaca cache, tidak pernah memanggil jaringan.
 */
class PosterRepository(private val dao: PosterDao) {

    private val memory = ConcurrentHashMap<String, Result>()
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<Result>>()
    private val gate = Semaphore(4)

    sealed interface Result {
        data class Found(val art: PosterArt) : Result
        data object Missing : Result
    }

    fun peek(title: String): PosterArt? = (memory[key(title)] as? Result.Found)?.art

    suspend fun get(title: String): PosterArt? {
        val k = key(title)
        if (k.isEmpty()) return null
        memory[k]?.let { return (it as? Result.Found)?.art }
        val mine = CompletableDeferred<Result>()
        val existing = inflight.putIfAbsent(k, mine)
        if (existing != null) return (existing.await() as? Result.Found)?.art
        return try {
            val r = resolve(k)
            memory[k] = r
            mine.complete(r)
            (r as? Result.Found)?.art
        } catch (t: Throwable) {
            mine.complete(Result.Missing)
            null
        } finally {
            inflight.remove(k)
        }
    }

    /** Hanya Room: tidak ada lagi panggilan jaringan (anime-r tidak punya /api/poster). */
    private suspend fun resolve(k: String): Result {
        val now = System.currentTimeMillis()
        return gate.withPermit {
            dao.get(k)?.let { c ->
                val ttl = if (c.notFound) DAY else 14 * DAY
                if (now - c.fetchedAt < ttl) {
                    return@withPermit if (c.notFound) Result.Missing
                    else Result.Found(PosterArt(c.poster, c.cover, c.rating, c.synopsis, c.exact, c.episodes))
                }
            }
            // Tidak ada entri cache → tidak ada yang bisa diberikan; jangan tulis
            // notFound baru, karena tidak ada sumber jaringan untuk memeriksanya.
            Result.Missing
        }
    }

    private fun key(title: String) = title.trim().lowercase(Locale.ROOT)

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000

        /** URL Kitsu gaya lama (".../large.jpg") punya varian small/tiny → dipakai sebagai thumbnail progresif. */
        fun sized(url: String?, small: Boolean): String? {
            if (url == null || !small) return url
            return if (url.contains("/large.")) url.replace("/large.", "/small.") else url
        }
    }
}
