package dev.animeruu.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
)

private val Context.store by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private val kQuality = intPreferencesKey("quality")
    private val kDlQuality = intPreferencesKey("dl_quality")
    private val kWifi = booleanPreferencesKey("wifi_only")
    private val kAutoplay = booleanPreferencesKey("autoplay")
    private val kPip = booleanPreferencesKey("auto_pip")
    private val kUpd = booleanPreferencesKey("auto_update_check")

    val settings: Flow<Settings> = context.store.data.map {
        Settings(
            preferredHeight = it[kQuality] ?: 0,
            downloadHeight = it[kDlQuality] ?: 480,
            wifiOnlyDownloads = it[kWifi] ?: true,
            autoplayNext = it[kAutoplay] ?: true,
            autoPip = it[kPip] ?: true,
            autoCheckUpdates = it[kUpd] ?: true,
        )
    }

    suspend fun setQuality(h: Int) = context.store.edit { it[kQuality] = h }
    suspend fun setDownloadQuality(h: Int) = context.store.edit { it[kDlQuality] = h }
    suspend fun setWifiOnly(v: Boolean) = context.store.edit { it[kWifi] = v }
    suspend fun setAutoplay(v: Boolean) = context.store.edit { it[kAutoplay] = v }
    suspend fun setAutoPip(v: Boolean) = context.store.edit { it[kPip] = v }
    suspend fun setAutoCheckUpdates(v: Boolean) = context.store.edit { it[kUpd] = v }
}
