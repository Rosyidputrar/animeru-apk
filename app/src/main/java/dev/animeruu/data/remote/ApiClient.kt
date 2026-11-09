package dev.animeruu.data.remote

import dev.animeruu.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** Hasil mentah satu panggilan API: amplop + apakah ini dari cache basi (offline). */
data class ApiResponse<T>(val envelope: Envelope<T>, val stale: Boolean)

/**
 * Klien REST-ANIM. Tanggung jawab:
 * - timeout yang masuk akal,
 * - retry dengan exponential backoff + jitter untuk IOException, 5xx, 403/429 (upstream
 *   menolak secara probabilistik),
 * - bila semua percobaan gagal: jatuh ke cache HTTP di disk (stale-if-error) supaya data
 *   yang pernah dimuat tetap tampil saat offline.
 */
class ApiClient(
    private val http: OkHttpClient,
    val baseUrl: HttpUrl = BASE_URL.toHttpUrl(),
    private val maxAttempts: Int = 3,
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    suspend fun <T> get(
        path: String,
        query: Map<String, String?> = emptyMap(),
        serializer: KSerializer<T>,
    ): ApiResponse<T> = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().apply {
            path.trim('/').split('/').forEach { addPathSegment(it) }
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        val envSer = Envelope.serializer(serializer)
        var lastError: Throwable = AppError.Offline()
        for (attempt in 0 until maxAttempts) {
            try {
                val resp = http.newCall(Request.Builder().url(url).build()).await()
                resp.use { r ->
                    val body = r.body?.string().orEmpty()
                    val env = runCatching { json.decodeFromString(envSer, body) }.getOrNull()
                    when {
                        r.isSuccessful && env != null && env.ok -> return@withContext ApiResponse(env, stale = false)
                        r.code == 404 -> throw AppError.NotFound(env?.error?.message ?: "Tidak ditemukan")
                        r.code == 400 -> throw AppError.Server(400, env?.error?.message ?: "Permintaan tidak valid")
                        r.isSuccessful && env == null -> lastError = AppError.Parse(IllegalStateException("bad json"))
                        else -> lastError = AppError.Server(r.code, env?.error?.message ?: "HTTP ${r.code}")
                    }
                }
            } catch (e: AppError.NotFound) {
                throw e
            } catch (e: AppError.Server) {
                if (e.code == 400) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = AppError.Offline(e)
            }
            if (attempt < maxAttempts - 1) {
                val base = 500L shl attempt // 500, 1000, 2000…
                delay(base + Random.nextLong(0, 250))
            }
        }
        // Semua percobaan gagal → coba cache disk walau sudah kedaluwarsa.
        val cached = runCatching {
            http.newCall(
                Request.Builder().url(url)
                    .cacheControl(CacheControl.Builder().onlyIfCached().maxStale(30, TimeUnit.DAYS).build())
                    .build()
            ).await().use { r ->
                if (r.code == 504 || !r.isSuccessful) null
                else json.decodeFromString(envSer, r.body?.string().orEmpty()).takeIf { it.ok }
            }
        }.getOrNull()
        if (cached != null) return@withContext ApiResponse(cached, stale = true)
        throw lastError
    }

    companion object {
        const val BASE_URL = "https://rest-anim.vercel.app/"
        const val CDN_HOST = "https://xyz-api.animein.net"
    }
}

/** Menjembatani Call OkHttp ke coroutine dengan dukungan pembatalan. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
