package dev.animeruu.feature

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import dev.animeruu.MainActivity
import dev.animeruu.R
import dev.animeruu.container
import dev.animeruu.domain.AnimeKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

/** Nomor episode terakhir yang "sudah diketahui" per anime — baseline notifikasi. */
class EpisodeWatch(context: Context) {
    private val prefs = context.getSharedPreferences("episode_watch", Context.MODE_PRIVATE)
    fun known(animeId: String): Double? = prefs.getString(animeId, null)?.toDoubleOrNull()
    /** Hanya maju: membuka halaman detail / menerima notifikasi menandai episode terbaru sudah diketahui. */
    fun markSeen(animeId: String, latest: Double) {
        val k = known(animeId)
        if (k == null || latest > k) prefs.edit { putString(animeId, latest.toString()) }
    }
}

/**
 * Cek berkala episode baru untuk anime favorit (seri saja), hanya saat ada jaringan.
 * Run pertama untuk tiap judul hanya menyimpan baseline → tidak ada notifikasi palsu.
 */
class NewEpisodeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.settings.settings.first().newEpisodeAlerts) return Result.success()
        val favs = c.db.favorites().all().filter { it.kind != AnimeKind.MOVIE.name }
        if (favs.isEmpty()) return Result.success()
        val gate = Semaphore(3)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            favs.map { f ->
                async {
                    gate.withPermit {
                        val latest = runCatching { c.anime.episodes(f.animeId, 0).items.firstOrNull() }
                            .onFailure { failures.incrementAndGet() }.getOrNull() ?: return@withPermit
                        val n = latest.number ?: return@withPermit
                        val count = newEpisodeCount(c.episodeWatch.known(f.animeId), n)
                        c.episodeWatch.markSeen(f.animeId, n)
                        if (count > 0) notify(f.animeId, f.title, f.posterUrl, latest.numberLabel, count)
                    }
                }
            }.awaitAll()
        }
        // Semua gagal (mis. API down) → coba lagi dengan backoff; sebagian gagal → tunggu jadwal berikutnya.
        return if (failures.get() == favs.size) Result.retry() else Result.success()
    }

    private suspend fun notify(animeId: String, title: String, poster: String?, epLabel: String, count: Int) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Episode baru", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Episode baru dari anime favoritmu"
            })
        }
        val open = PendingIntent.getActivity(
            ctx, animeId.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_ANIME, animeId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Poster sebagai ikon besar: CDN dulu, lalu Kitsu; gagal = tanpa gambar (notifikasi tetap terkirim).
        val art = c().posters.get(title)
        val bitmap = listOfNotNull(poster, art?.posterUrl).firstNotNullOfOrNull { url ->
            val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(256).allowHardware(false).build())
            ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        }
        val text = if (count > 1) "$count episode baru · terbaru Episode $epLabel" else "Episode $epLabel sudah tayang"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_episode)
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(bitmap as Bitmap?)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(animeId.hashCode(), n) }
    }

    private fun c() = applicationContext.container

    companion object {
        const val CHANNEL = "new_episodes"
        const val GROUP = "dev.animeruu.NEW_EPISODES"
        const val EXTRA_OPEN_ANIME = "open_anime"
        private const val WORK = "new-episodes"

        /** Cek sekali sekarang (tombol "Cek sekarang" di Pengaturan). */
        fun runNow(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<NewEpisodeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("new-episodes-now", androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(WORK); return }
            val req = PeriodicWorkRequestBuilder<NewEpisodeWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
