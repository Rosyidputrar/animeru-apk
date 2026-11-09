package dev.animeruu

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dev.animeruu.data.local.AppDatabase
import dev.animeruu.data.remote.ApiClient
import dev.animeruu.data.repo.AnimeRepository
import dev.animeruu.data.repo.LibraryRepository
import dev.animeruu.data.repo.PosterRepository
import dev.animeruu.data.repo.SettingsRepository
import dev.animeruu.download.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AnimeruuApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}

/** DI manual: satu tempat merakit dependensi; tidak perlu framework untuk graf sekecil ini. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val userAgent = "Animeruu/${BuildConfig.VERSION_NAME} (Android)"

    /** Klien dasar: dibagi antara API, gambar, dan video (pool koneksi & HTTP/2 bersama). */
    private val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    /**
     * Klien API: cache HTTP di disk. API tidak mengirim Cache-Control yang berguna, jadi
     * respons sukses ditandai segar 60 dtk (poster 24 jam) — cukup untuk menahan
     * permintaan ganda, dan entri tetap ada untuk mode offline (stale-if-error di ApiClient).
     */
    val apiHttp: OkHttpClient = baseHttp.newBuilder()
        .cache(Cache(File(app.cacheDir, "http-api"), 30L * 1024 * 1024))
        .callTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val resp = chain.proceed(req)
            if (!resp.isSuccessful) return@addNetworkInterceptor resp
            val maxAge = when {
                req.url.encodedPath.startsWith("/api/poster") -> 86_400
                req.url.encodedPath.endsWith("/stream") -> 300
                else -> 60
            }
            resp.newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=$maxAge").build()
        }
        .build()

    val api = ApiClient(apiHttp)
    val db = AppDatabase.create(app)
    val anime = AnimeRepository(api)
    val posters = PosterRepository(api, db.posters())
    val library = LibraryRepository(db)
    val settings = SettingsRepository(app)

    val mediaHttp: OkHttpClient = baseHttp.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val mediaDataSource = OkHttpDataSource.Factory(mediaHttp)
    val downloads by lazy { DownloadRepository(app, db.downloads(), mediaDataSource) }

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .okHttpClient(baseHttp.newBuilder().callTimeout(20, TimeUnit.SECONDS).build())
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "images")).maxSizeBytes(300L * 1024 * 1024).build() }
            // Header cache CDN tidak bisa diandalkan → simpan agresif tanpa peduli header.
            .respectCacheHeaders(false)
            .crossfade(220)
            .build()
    }

    fun start() {
        appScope.launch {
            settings.settings.map { it.wifiOnlyDownloads }.distinctUntilChanged().collect { downloads.setWifiOnly(it) }
        }
    }

    fun isUnmetered(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

val Context.container: AppContainer get() = (applicationContext as AnimeruuApp).container
