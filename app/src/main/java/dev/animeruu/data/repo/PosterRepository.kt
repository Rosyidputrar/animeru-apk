package dev.animeruu.data.repo

import dev.animeruu.core.AppError
import dev.animeruu.data.local.PosterCacheEntity
import dev.animeruu.data.local.PosterDao
import dev.animeruu.data.remote.ApiClient
import dev.animeruu.data.remote.PosterDto
import dev.animeruu.data.remote.toDomain
import dev.animeruu.domain.PosterArt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Jalur gambar andal: /api/poster (Kitsu) per judul.
 * Tiga lapis: memori → Room (bertahan antar sesi) → jaringan. Permintaan paralel untuk
 * judul yang sama digabung (dedupe) dan konkurensi dibatasi supaya beranda yang memuat
 * ±80 judul tidak membanjiri API.
 */
class PosterRepository(private val api: ApiClient, private val dao: PosterDao) {

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
            val r = resolve(k, title)
            memory[k] = r
            mine.complete(r)
            (r as? Result.Found)?.art
        } catch (t: Throwable) {
            mine.complete(Result.Missing) // penunggu lain tidak menggantung; kegagalan jaringan tidak dicache permanen
            null
        } finally {
            inflight.remove(k)
        }
    }

    private suspend fun resolve(k: String, title: String): Result {
        val now = System.currentTimeMillis()
        dao.get(k)?.let { c ->
            val ttl = if (c.notFound) DAY else 14 * DAY
            if (now - c.fetchedAt < ttl) {
                return if (c.notFound) Result.Missing
                else Result.Found(PosterArt(c.poster, c.cover, c.rating, c.synopsis, c.exact, c.episodes))
            }
        }
        return gate.withPermit {
            try {
                val art = api.get("api/poster", mapOf("title" to title.trim()), PosterDto.serializer())
                    .envelope.data?.toDomain()
                if (art == null || (art.posterUrl == null && art.coverUrl == null)) {
                    dao.put(PosterCacheEntity(k, null, null, null, null, false, null, true, now))
                    Result.Missing
                } else {
                    dao.put(PosterCacheEntity(k, art.posterUrl, art.coverUrl, art.rating, art.synopsisEn, art.exact, art.episodes, false, now))
                    Result.Found(art)
                }
            } catch (e: AppError.NotFound) {
                dao.put(PosterCacheEntity(k, null, null, null, null, false, null, true, now))
                Result.Missing
            }
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
