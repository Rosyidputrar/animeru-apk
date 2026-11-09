package dev.animeruu.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.animeruu.BuildConfig
import dev.animeruu.MainActivity
import dev.animeruu.R
import dev.animeruu.data.remote.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long, val note: String? = null) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float, val bytes: Long, val total: Long, val waitingNetwork: Boolean) : UpdateState
    data class Verifying(val release: ReleaseInfo) : UpdateState
    data class Ready(val release: ReleaseInfo) : UpdateState
    /** Izin "Pasang aplikasi tak dikenal" belum diberikan → pengguna dibawa ke Pengaturan sistem. */
    data class NeedsPermission(val release: ReleaseInfo) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?, val retryDownload: Boolean) : UpdateState
}

/**
 * Pembaruan dalam aplikasi dari GitHub Releases.
 *
 * Skenario yang ditangani:
 * - Keluar/menutup aplikasi saat mengunduh → unduhan dipegang DownloadManager sistem (lanjut
 *   di latar, tahan putus jaringan). Saat selesai di latar → notifikasi "siap dipasang".
 * - Membuka lagi aplikasi → status unduhan dipulihkan dari id yang disimpan.
 * - APK rusak/terpotong → SHA-256 dicocokkan dengan digest rilis; paket, versi, dan tanda
 *   tangan dicek SEBELUM installer dibuka (tanda tangan beda = pesan jelas, bukan error installer).
 * - Izin pasang aplikasi tak dikenal → diarahkan ke Pengaturan, dilanjutkan otomatis saat kembali.
 * - Installer dibatalkan → kembali ke "Siap dipasang".
 * - Setelah berhasil → "Berhasil diperbarui" + catatan rilis, APK lama dihapus.
 */
class UpdateManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Diisi setelah aplikasi berhasil diperbarui (sekali tampil). */
    private val _justUpdated = MutableStateFlow<ReleaseInfo?>(null)
    val justUpdated: StateFlow<ReleaseInfo?> = _justUpdated.asStateFlow()

    /** Permintaan membuka lembar pembaruan (mis. dari notifikasi). */
    private val _sheetRequest = MutableStateFlow(false)
    val sheetRequest: StateFlow<Boolean> = _sheetRequest.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME
    val currentCode: Long = BuildConfig.VERSION_CODE.toLong()
    val repo: String = BuildConfig.UPDATE_REPO

    private var pollJob: Job? = null

    // ---------- Persistensi ----------
    private var savedRelease: ReleaseInfo?
        get() = prefs.getString("release", null)?.let { runCatching { json.decodeFromString(ReleaseInfo.serializer(), it) }.getOrNull() }
        set(v) = prefs.edit { if (v == null) remove("release") else putString("release", json.encodeToString(ReleaseInfo.serializer(), v)) }
    private var downloadId: Long
        get() = prefs.getLong("download_id", -1)
        set(v) = prefs.edit { putLong("download_id", v) }
    var lastCheck: Long
        get() = prefs.getLong("last_check", 0)
        private set(v) = prefs.edit { putLong("last_check", v) }
    fun isDismissed(version: String) = prefs.getString("dismissed", null) == version
    fun dismiss(version: String) = prefs.edit { putString("dismissed", version) }

    private fun apkFile(r: ReleaseInfo) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "animeruu-${r.version}.apk")

    // ---------- Siklus hidup ----------
    /** Dipanggil saat aplikasi mulai: deteksi "baru diperbarui", pulihkan unduhan, bersihkan sisa. */
    fun restore() {
        val pending = prefs.getString("installing_version", null)
        val cur = Version.parse(currentVersion)
        val saved = savedRelease
        if (pending != null && cur != null && Version.parse(pending)?.let { cur >= it } == true) {
            _justUpdated.value = saved?.takeIf { it.version == pending }
            prefs.edit { remove("installing_version") }
        }
        // Rilis tersimpan yang tidak lebih baru dari versi terpasang → bersihkan semuanya.
        if (saved != null && cur != null && Version.parse(saved.version)?.let { it <= cur } == true) {
            clearDownload(deleteFile = true)
            savedRelease = null
            _state.value = UpdateState.UpToDate(lastCheck)
            return
        }
        if (saved == null) return
        val file = apkFile(saved)
        when {
            downloadId >= 0 -> resumeTracking(saved)
            file.exists() -> scope.launch { verifyAndReady(saved) }
            else -> _state.value = UpdateState.Available(saved)
        }
    }

    fun requestSheet() { _sheetRequest.value = true }
    fun consumeSheetRequest() { _sheetRequest.value = false }
    fun consumeJustUpdated() { _justUpdated.value = null }

    // ---------- Cek ----------
    fun shouldAutoCheck() = System.currentTimeMillis() - lastCheck > 12 * 60 * 60 * 1000L

    fun check(manual: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing || s is UpdateState.Verifying) return
        if (!manual && (s is UpdateState.Ready || s is UpdateState.NeedsPermission)) return
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = try {
                val r = fetchLatest()
                lastCheck = System.currentTimeMillis()
                val cur = Version.parse(currentVersion)
                val remote = r?.let { Version.parse(it.version) }
                when {
                    r == null -> UpdateState.UpToDate(lastCheck, "Belum ada rilis yang berisi APK")
                    cur != null && remote != null && remote <= cur -> UpdateState.UpToDate(lastCheck)
                    else -> {
                        val old = savedRelease
                        if (old != null && old.version != r.version) clearDownload(deleteFile = true)
                        savedRelease = r
                        if (apkFile(r).exists() && downloadId < 0) { verifyAndReady(r); return@launch } else UpdateState.Available(r)
                    }
                }
            } catch (e: UpdateError) {
                UpdateState.Failed(e.message ?: "Gagal memeriksa pembaruan", null, retryDownload = false)
            } catch (e: IOException) {
                UpdateState.Failed("Tidak bisa terhubung ke GitHub. Periksa koneksi.", null, retryDownload = false)
            }
        }
    }

    private class UpdateError(msg: String) : Exception(msg)

    private suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${BuildConfig.UPDATE_API}/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(req).await().use { r ->
            when {
                r.code == 404 -> null
                r.code == 403 || r.code == 429 -> throw UpdateError("GitHub membatasi pengecekan sementara. Coba lagi sebentar lagi.")
                !r.isSuccessful -> throw UpdateError("GitHub merespons ${r.code}. Coba lagi nanti.")
                else -> runCatching { json.decodeFromString(GhRelease.serializer(), r.body!!.string()) }
                    .getOrElse { throw UpdateError("Data rilis tidak terbaca") }
                    .toInfo()
            }
        }
    }

    // ---------- Unduh ----------
    fun download() {
        val r = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        clearDownload(deleteFile = true)
        val file = apkFile(r)
        val req = DownloadManager.Request(Uri.parse(r.apkUrl))
            .setTitle("Animeruu ${r.version}")
            .setDescription("Mengunduh pembaruan")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file.name)
        downloadId = runCatching { dm.enqueue(req) }.getOrElse {
            _state.value = UpdateState.Failed("Tidak bisa memulai unduhan (${it.message})", r, retryDownload = true)
            return
        }
        _state.value = UpdateState.Downloading(r, 0f, 0, r.sizeBytes, false)
        resumeTracking(r)
    }

    fun cancelDownload() {
        val r = savedRelease
        clearDownload(deleteFile = true)
        _state.value = r?.let { UpdateState.Available(it) } ?: UpdateState.Idle
    }

    private fun clearDownload(deleteFile: Boolean) {
        pollJob?.cancel()
        if (downloadId >= 0) runCatching { dm.remove(downloadId) }
        downloadId = -1
        if (deleteFile) context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles { f -> f.name.startsWith("animeruu-") && f.name.endsWith(".apk") }?.forEach { it.delete() }
    }

    /** Pantau progres DownloadManager; juga berjalan saat aplikasi dibuka ulang di tengah unduhan. */
    private fun resumeTracking(r: ReleaseInfo) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val id = downloadId
                if (id < 0) return@launch
                val row = query(id)
                when (row?.status) {
                    null -> { // dihapus pengguna dari aplikasi Download sistem
                        downloadId = -1
                        _state.value = UpdateState.Available(r); return@launch
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> { onDownloaded(id); return@launch }
                    DownloadManager.STATUS_FAILED -> {
                        downloadId = -1
                        _state.value = UpdateState.Failed(reasonText(row.reason), r, retryDownload = true); return@launch
                    }
                    else -> {
                        val total = if (row.total > 0) row.total else r.sizeBytes
                        _state.value = UpdateState.Downloading(
                            r, if (total > 0) (row.bytes.toFloat() / total).coerceIn(0f, 1f) else 0f, row.bytes, total,
                            waitingNetwork = row.status == DownloadManager.STATUS_PAUSED,
                        )
                    }
                }
                delay(400)
            }
        }
    }

    private data class Row(val status: Int, val reason: Int, val bytes: Long, val total: Long)

    private fun query(id: Long): Row? = runCatching {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            Row(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }.getOrNull()

    private fun reasonText(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Penyimpanan penuh. Kosongkan ruang lalu coba lagi."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "Unduhan terputus. Coba lagi."
        DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Server unduhan menolak. Coba lagi nanti."
        else -> "Unduhan gagal. Coba lagi."
    }

    /** Dari receiver DOWNLOAD_COMPLETE (aplikasi bisa saja sedang di latar) atau dari polling. */
    fun onDownloaded(id: Long) {
        if (id != downloadId) return
        val r = savedRelease ?: return
        val row = query(id)
        if (row?.status != DownloadManager.STATUS_SUCCESSFUL) return
        downloadId = -1
        pollJob?.cancel()
        scope.launch {
            verifyAndReady(r)
            if (_state.value is UpdateState.Ready && !appInForeground()) notifyReady(r)
        }
    }

    // ---------- Verifikasi ----------
    private suspend fun verifyAndReady(r: ReleaseInfo) {
        _state.value = UpdateState.Verifying(r)
        val file = apkFile(r)
        val err = withContext(Dispatchers.IO) { verify(file, r) }
        _state.value = if (err == null) UpdateState.Ready(r) else {
            file.delete()
            UpdateState.Failed(err, r, retryDownload = true)
        }
    }

    private fun verify(file: File, r: ReleaseInfo): String? {
        if (!file.exists() || file.length() == 0L) return "Berkas unduhan hilang. Unduh ulang."
        if (r.sizeBytes > 0 && file.length() != r.sizeBytes) return "Unduhan tidak lengkap (${file.length()} dari ${r.sizeBytes} byte). Unduh ulang."
        if (r.sha256 != null) {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != r.sha256) return "Berkas rusak (checksum tidak cocok). Unduh ulang."
        }
        val pm = context.packageManager
        // Minta keduanya: di Android 9–12 signingInfo untuk berkas APK sering kosong,
        // sedangkan `signatures` (lama) tetap terisi.
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return "Berkas bukan APK yang valid. Unduh ulang."
        if (archive.packageName != context.packageName) {
            return "APK ini untuk paket ${archive.packageName}, bukan ${context.packageName}. " +
                if (context.packageName.endsWith(".debug")) "Build debug tidak bisa diperbarui dari rilis." else ""
        }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (code <= currentCode) return "APK di rilis (build $code) tidak lebih baru dari yang terpasang (build $currentCode)."
        val installed = pm.getPackageInfo(context.packageName, flags)
        val a = signatures(archive)
        val b = signatures(installed)
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) {
            return "APK ditandatangani dengan kunci berbeda dari aplikasi yang terpasang, jadi Android akan menolak pembaruan. " +
                "Hapus Animeruu dulu lalu pasang APK baru (riwayat & favorit akan hilang), atau minta rilis yang ditandatangani kunci yang sama."
        }
        return null
    }

    private fun signatures(p: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val sigs = (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else null)?.takeIf { it.isNotEmpty() } ?: p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    // ---------- Pasang ----------
    fun canInstall() = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Panggil dari UI (aplikasi di depan). */
    fun install(): Intent? {
        val r = when (val s = _state.value) {
            is UpdateState.Ready -> s.release
            is UpdateState.NeedsPermission -> s.release
            else -> return null
        }
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(r)
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        }
        val file = apkFile(r)
        if (!file.exists()) { _state.value = UpdateState.Failed("Berkas unduhan hilang. Unduh ulang.", r, true); return null }
        _state.value = UpdateState.Installing(r)
        prefs.edit { putString("installing_version", r.version) }
        scope.launch(Dispatchers.IO) {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val sid = installer.createSession(params)
                installer.openSession(sid).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val pi = PendingIntent.getBroadcast(
                        context, sid,
                        Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                    )
                    session.commit(pi.intentSender)
                }
            } catch (t: Throwable) {
                prefs.edit { remove("installing_version") }
                _state.value = UpdateState.Failed("Gagal membuka installer: ${t.message}", r, retryDownload = false)
            }
        }
        return null
    }

    /** Dipanggil saat kembali dari layar izin. */
    fun onResume() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && canInstall()) install()
        // Installer ditutup tanpa hasil (mis. dibunuh sistem) → jangan terjebak di "Memasang".
        if (s is UpdateState.Installing) scope.launch {
            delay(1500)
            if (_state.value is UpdateState.Installing && !installerActive) _state.value = UpdateState.Ready(s.release)
        }
    }

    @Volatile var installerActive = false

    internal fun onInstallStatus(status: Int, message: String?) {
        val r = savedRelease
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> installerActive = true
            PackageInstaller.STATUS_SUCCESS -> { installerActive = false } // proses akan dimulai ulang oleh sistem
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                installerActive = false
                prefs.edit { remove("installing_version") }
                _state.value = r?.let { UpdateState.Ready(it) } ?: UpdateState.Idle
            }
            else -> {
                installerActive = false
                prefs.edit { remove("installing_version") }
                val msg = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Android menolak pembaruan (tanda tangan atau versi tidak cocok)."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Penyimpanan tidak cukup untuk memasang."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Pemasangan diblokir oleh perangkat."
                    else -> "Pemasangan gagal${message?.let { ": $it" } ?: ""}."
                }
                _state.value = UpdateState.Failed(msg, r, retryDownload = false)
            }
        }
    }

    // ---------- Notifikasi ----------
    private fun appInForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun notifyReady(r: ReleaseInfo) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pembaruan aplikasi", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 41,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Animeruu ${r.version} siap dipasang")
            .setContentText("Ketuk untuk memperbarui")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) }
    }

    fun cancelReadyNotification() = runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }

    companion object {
        const val CHANNEL = "updates"
        const val NOTIF_ID = 4101
        const val EXTRA_OPEN_UPDATE = "open_update"
    }
}
