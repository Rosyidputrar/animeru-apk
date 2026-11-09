package dev.animeruu.download

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.NotificationUtil
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import dev.animeruu.AnimeruuApp
import dev.animeruu.R
import dev.animeruu.data.local.DownloadDao
import dev.animeruu.data.local.DownloadEntity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.Executors

data class DownloadItem(
    val meta: DownloadEntity,
    val state: Int,
    val percent: Float,
    val bytes: Long,
) {
    val completed get() = state == Download.STATE_COMPLETED
    val failed get() = state == Download.STATE_FAILED
    val active get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
}

/**
 * Unduhan offline memakai Media3 DownloadManager: MP4 direct diunduh ke SimpleCache,
 * dan player membaca cache yang sama → episode terunduh otomatis diputar offline.
 */
class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
    upstream: DataSource.Factory,
) {
    private val dbProvider = StandaloneDatabaseProvider(context)
    val cache: Cache = SimpleCache(File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads"), NoOpCacheEvictor(), dbProvider)

    val manager: DownloadManager = DownloadManager(context, dbProvider, cache, upstream, Executors.newFixedThreadPool(2)).apply {
        maxParallelDownloads = 2
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        manager.requirements = Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
    }

    suspend fun find(episodeId: String): DownloadEntity? = dao.get(episodeId)

    fun isComplete(episodeId: String): Boolean =
        manager.downloadIndex.getDownload(episodeId)?.state == Download.STATE_COMPLETED

    suspend fun enqueue(meta: DownloadEntity) {
        dao.upsert(meta)
        val req = DownloadRequest.Builder(meta.episodeId, Uri.parse(meta.url))
            .setCustomCacheKey(meta.url)
            .setData("${meta.animeTitle} · Ep ${meta.episodeLabel}".toByteArray())
            .build()
        DownloadService.sendAddDownload(context, AnimeruuDownloadService::class.java, req, false)
    }

    suspend fun remove(episodeId: String) {
        DownloadService.sendRemoveDownload(context, AnimeruuDownloadService::class.java, episodeId, false)
        dao.delete(episodeId)
    }

    fun retry(meta: DownloadEntity) {
        val req = DownloadRequest.Builder(meta.episodeId, Uri.parse(meta.url)).setCustomCacheKey(meta.url).build()
        DownloadService.sendAddDownload(context, AnimeruuDownloadService::class.java, req, false)
    }

    /** Status unduhan berjalan diperbarui periodik (progress tidak dipancarkan listener). */
    private val ticker: Flow<Map<String, Download>> = flow {
        while (true) {
            val map = HashMap<String, Download>()
            runCatching {
                manager.downloadIndex.getDownloads().use { c ->
                    while (c.moveToNext()) map[c.download.request.id] = c.download
                }
            }
            manager.currentDownloads.forEach { map[it.request.id] = it }
            emit(map)
            delay(if (manager.currentDownloads.isEmpty()) 1500 else 700)
        }
    }.conflate()

    val items: Flow<List<DownloadItem>> = combine(dao.observeAll(), ticker) { metas, states ->
        metas.map { m ->
            val d = states[m.episodeId]
            DownloadItem(
                meta = m,
                state = d?.state ?: Download.STATE_QUEUED,
                percent = d?.percentDownloaded?.takeIf { it >= 0 } ?: 0f,
                bytes = d?.bytesDownloaded ?: 0L,
            )
        }
    }
}

class AnimeruuDownloadService : DownloadService(
    FOREGROUND_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, CHANNEL, R.string.download_channel, R.string.download_channel_desc,
) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL) }

    override fun getDownloadManager(): DownloadManager {
        val app = application as AnimeruuApp
        val mgr = app.container.downloads.manager
        mgr.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                val title = String(download.request.data).ifBlank { "Episode" }
                val n = when (download.state) {
                    Download.STATE_COMPLETED -> helper.buildDownloadCompletedNotification(this@AnimeruuDownloadService, R.drawable.ic_stat_download, null, "$title siap ditonton offline")
                    Download.STATE_FAILED -> helper.buildDownloadFailedNotification(this@AnimeruuDownloadService, R.drawable.ic_stat_download, null, "$title gagal diunduh")
                    else -> null
                } ?: return
                NotificationUtil.setNotification(this@AnimeruuDownloadService, download.request.id.hashCode(), n)
            }
        })
        return mgr
    }

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val msg = when {
            notMetRequirements and Requirements.NETWORK_UNMETERED != 0 -> "Menunggu Wi‑Fi"
            downloads.size == 1 -> String(downloads[0].request.data)
            else -> "${downloads.size} episode diunduh"
        }
        return helper.buildProgressNotification(this, R.drawable.ic_stat_download, null, msg, downloads, notMetRequirements)
    }

    companion object {
        const val CHANNEL = "downloads"
        const val FOREGROUND_ID = 7
    }
}
