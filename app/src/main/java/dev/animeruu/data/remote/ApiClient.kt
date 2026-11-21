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

/** Hasil satu panggilan API: data yang sudah dinormalisasi + apakah ini dari cache basi (offline). */
data class ApiResponse<T>(val data: T, val stale: Boolean, val page: NanimePage? = null)

/** Info pagination yang dinormalisasi dari berbagai bentuk respons anime-r. */
data class NanimePage(
    val page: Int,
    val hasNext: Boolean,
    val nextPage: Int?,
    val total: Int? = null,
)

/**
 * Klien anime-r (https://anime-r.rosdiyttaa.workers.dev). Tanggung jawab:
 * - timeout yang masuk akal,
 * - retry dengan exponential backoff + jitter untuk IOException, 5xx, dan 429
 *   (rate limit anime-r: 200/menit per IP),
 * - bila semua percobaan gagal: jatuh ke cache HTTP di disk (stale-if-error) supaya data
 *   yang pernah dimuat tetap tampil saat offline.
 *
 * Bentuk respons anime-r (raw passthrough Nanime) tidak seragam: daftar bisa ada di
 * `data` atau `items`, pagination bisa ada di `pagination`, `meta`, atau root. Kedua
 * metode `getObject`/`getList` menormalisasi itu ke [ApiResponse] supaya repository
 * tidak perlu tahu bentuk mentahnya.
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

    /** Objek tunggal: `{status, message, data: {...}}`. */
    suspend fun <T> getObject(
        path: String,
        query: Map<String, String?> = emptyMap(),
        serializer: KSerializer<T>,
    ): ApiResponse<T> {
        val envSer = NanimeObjectResponse.serializer(serializer)
        return request(path, query) { body ->
            runCatching { json.decodeFromString(envSer, body) }.getOrNull()?.data
        }
    }

    /** Daftar: `{status, message, data|items: [...]}` + info pagination bila ada. */
    suspend fun <T> getList(
        path: String,
        query: Map<String, String?> = emptyMap(),
        itemSerializer: KSerializer<T>,
    ): ApiResponse<List<T>> {
        val envSer = NanimeListResponse.serializer(itemSerializer)
        var lastPage: NanimePage? = null
        return request(path, query) { body ->
            val env = runCatching { json.decodeFromString(envSer, body) }.getOrNull() ?: return@request null
            lastPage = normalizePage(env)
            env.data ?: env.items
        }.let { r -> r.copy(page = lastPage ?: r.page) }
    }

    private fun <T> normalizePage(env: NanimeListResponse<T>): NanimePage? {
        val p = env.pagination
        if (p != null) {
            val hasNext = p.hasNext ?: ((p.totalPages ?: 1) > p.page)
            return NanimePage(p.page, hasNext, if (hasNext) p.page + 1 else null, p.total)
        }
        val m = env.meta
        if (m?.total != null) {
            val page = m.page ?: 1
            val totalPages = m.totalPages ?: 1
            val hasNext = page < totalPages
            return NanimePage(page, hasNext, if (hasNext) page + 1 else null, m.total)
        }
        if (env.total != null) {
            val page = env.page ?: 1
            val totalPages = env.totalPages ?: 1
            val hasNext = page < totalPages
            return NanimePage(page, hasNext, if (hasNext) page + 1 else null, env.total)
        }
        return null
    }

    private suspend fun <T> request(
        path: String,
        query: Map<String, String?>,
        decode: (String) -> T?,
    ): ApiResponse<T> = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder().apply {
            // Segmen path sudah di-encode oleh pemanggil bila dinamis (mis. nama genre);
            // di sini ditambahkan apa adanya supaya "%2F" tidak jadi "%252F".
            addEncodedPathSegments(path.trim('/'))
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        var lastError: Throwable = AppError.Offline()
        for (attempt in 0 until maxAttempts) {
            try {
                val resp = http.newCall(Request.Builder().url(url).build()).await()
                resp.use { r ->
                    val body = r.body?.string().orEmpty()
                    when {
                        r.isSuccessful -> {
                            val data = decode(body)
                            if (data != null) return@withContext ApiResponse(data, stale = false)
                            lastError = AppError.Parse(IllegalStateException("bad json"))
                        }
                        r.code == 404 -> throw AppError.NotFound(errorMessage(body) ?: "Tidak ditemukan")
                        r.code == 400 -> throw AppError.Server(400, errorMessage(body) ?: "Permintaan tidak valid")
                        else -> lastError = AppError.Server(r.code, errorMessage(body) ?: "HTTP ${r.code}")
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
                else decode(r.body?.string().orEmpty())
            }
        }.getOrNull()
        if (cached != null) return@withContext ApiResponse(cached, stale = true)
        throw lastError
    }

    private fun errorMessage(body: String): String? =
        runCatching { json.decodeFromString(NanimeErrorDto.serializer(), body) }.getOrNull()
            ?.let { it.message ?: it.error }

    companion object {
        const val BASE_URL = "https://anime-r.rosdiyttaa.workers.dev/"
        /** CDN gambar Nanime (dipakai bila URL gambar relatif — di praktiknya sudah absolut). */
        const val CDN_HOST = "https://cdn-stable.nanimeid.xyz"
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
