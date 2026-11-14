package dev.animeruu.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Perilaku "Lewati intro" di pemutar. */
enum class IntroMode {
    /** Tidak ada tombol, tidak ada tawaran menyimpan posisi. */
    OFF,
    /** Tombol "Lewati intro" saat intro berjalan (bawaan). */
    BUTTON,
    /** Langsung dilewati bila posisi intro seri itu sudah disimpan. */
    AUTO,
}

/** 0 = Otomatis (Wi‑Fi 720p, seluler 480p). */
data class Settings(
    val preferredHeight: Int = 0,
    val downloadHeight: Int = 480,
    val wifiOnlyDownloads: Boolean = true,
    val autoplayNext: Boolean = true,
    /** Masuk Picture-in-Picture otomatis saat keluar aplikasi ketika video sedang diputar. */
    val autoPip: Boolean = true,
    /** Cek GitHub Releases otomatis (maks. 2× sehari) saat aplikasi dibuka. */
    val autoCheckUpdates: Boolean = true,
    /** Notifikasi episode baru untuk anime favorit (cek latar ±1 jam sekali). */
    val newEpisodeAlerts: Boolean = true,
    /** Tombol/otomatis/mati untuk "Lewati intro" (posisi per seri disimpan di IntroStore). */
    val introMode: IntroMode = IntroMode.BUTTON,
    /** Lanjutkan suara saat aplikasi ditinggal / layar dikunci (tanpa PiP). */
    val backgroundAudio: Boolean = false,
)

private val Context.store by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private val kQuality = intPreferencesKey("quality")
    private val kDlQuality = intPreferencesKey("dl_quality")
    private val kWifi = booleanPreferencesKey("wifi_only")
    private val kAutoplay = booleanPreferencesKey("autoplay")
    private val kPip = booleanPreferencesKey("auto_pip")
    private val kUpd = booleanPreferencesKey("auto_update_check")
    private val kNewEp = booleanPreferencesKey("new_episode_alerts")
    /** Kunci lama (v1.2–v1.3): true → AUTO, false → BUTTON. */
    private val kAutoIntroLegacy = booleanPreferencesKey("auto_skip_intro")
    private val kIntroMode = intPreferencesKey("intro_mode")
    private val kBgAudio = booleanPreferencesKey("background_audio")

    val settings: Flow<Settings> = context.store.data.map {
        Settings(
            preferredHeight = it[kQuality] ?: 0,
            downloadHeight = it[kDlQuality] ?: 480,
            wifiOnlyDownloads = it[kWifi] ?: true,
            autoplayNext = it[kAutoplay] ?: true,
            autoPip = it[kPip] ?: true,
            autoCheckUpdates = it[kUpd] ?: true,
            newEpisodeAlerts = it[kNewEp] ?: true,
            introMode = it[kIntroMode]?.let { o -> IntroMode.entries.getOrNull(o) }
                ?: if (it[kAutoIntroLegacy] == true) IntroMode.AUTO else IntroMode.BUTTON,
            backgroundAudio = it[kBgAudio] ?: false,
        )
    }

    suspend fun setQuality(h: Int) = context.store.edit { it[kQuality] = h }
    suspend fun setDownloadQuality(h: Int) = context.store.edit { it[kDlQuality] = h }
    suspend fun setWifiOnly(v: Boolean) = context.store.edit { it[kWifi] = v }
    suspend fun setAutoplay(v: Boolean) = context.store.edit { it[kAutoplay] = v }
    suspend fun setAutoPip(v: Boolean) = context.store.edit { it[kPip] = v }
    suspend fun setAutoCheckUpdates(v: Boolean) = context.store.edit { it[kUpd] = v }
    suspend fun setNewEpisodeAlerts(v: Boolean) = context.store.edit { it[kNewEp] = v }
    suspend fun setIntroMode(m: IntroMode) = context.store.edit { it[kIntroMode] = m.ordinal }
    suspend fun setBackgroundAudio(v: Boolean) = context.store.edit { it[kBgAudio] = v }
}
