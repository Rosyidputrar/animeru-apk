package dev.animeruu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import dev.animeruu.AppContainer
import dev.animeruu.BuildConfig
import dev.animeruu.data.repo.IntroMode
import dev.animeruu.data.repo.Settings
import dev.animeruu.ui.components.GlassIconButton
import dev.animeruu.ui.components.Pill
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle(Settings())
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // Umpan balik per baris (id → pesan), supaya pesan tidak muncul di baris lain.
    var feedback by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun note(id: String) = feedback?.takeIf { it.first == id }?.second
    // Aksi yang menghapus data butuh ketukan kedua (id baris yang sedang menunggu konfirmasi).
    var armed by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", onBack, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Text("Pengaturan", style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
        }
        Group("Pembaruan") {
            dev.animeruu.ui.update.UpdateSettingsCard(
                c.updates, s.autoCheckUpdates, { v -> scope.launch { c.settings.setAutoCheckUpdates(v) } },
            ) { t, sub, v, on -> Toggle(t, sub, v, on) }
        }
        Group("Pemutaran") {
            Label("Kualitas bawaan", "Otomatis: 720p di Wi‑Fi, 480p di data seluler. Bila gagal, aplikasi turun/naik ke kualitas lain secara otomatis.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                listOf(0 to "Otomatis", 360 to "360p", 480 to "480p", 720 to "720p", 1080 to "1080p").forEach { (h, l) ->
                    Pill(l, s.preferredHeight == h, { scope.launch { c.settings.setQuality(h) } })
                }
            }
            Divider()
            Toggle("Putar episode berikutnya otomatis", "Hitung mundur 5 detik di akhir episode", s.autoplayNext) { scope.launch { c.settings.setAutoplay(it) } }
            Divider()
            Toggle("Picture-in-Picture otomatis", "Video lanjut di jendela kecil saat kamu keluar aplikasi", s.autoPip) { scope.launch { c.settings.setAutoPip(it) } }
            Divider()
            Toggle("Putar audio di latar", "Suara tetap jalan saat keluar aplikasi atau layar dikunci (bila PiP tidak dipakai). Lebih boros baterai & kuota.", s.backgroundAudio) { scope.launch { c.settings.setBackgroundAudio(it) } }
            Divider()
            Label("Lompatan ketuk-dua-kali", "Juga dipakai tombol mundur/maju di tengah layar. Notifikasi & earphone tetap 10 dtk.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                dev.animeruu.data.repo.SEEK_STEPS.forEach { sec -> Pill("$sec dtk", s.seekStepSec == sec, { scope.launch { c.settings.setSeekStep(sec) } }) }
            }
            Divider()
            Label(
                "Lewati intro",
                when (s.introMode) {
                    IntroMode.OFF -> "Mati: tidak ada tombol lewati intro dan tidak ada tawaran menyimpan posisi."
                    IntroMode.BUTTON -> "Tombol: \u201cLewati intro\u201d muncul saat intro berjalan. Sebelum posisinya disimpan, tombol melompat 85 dtk."
                    IntroMode.AUTO -> "Otomatis: intro langsung dilewati bila posisinya sudah disimpan untuk seri itu (ada tombol \u201cTonton intro\u201d untuk batal)."
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                listOf(IntroMode.OFF to "Mati", IntroMode.BUTTON to "Tombol", IntroMode.AUTO to "Otomatis").forEach { (m, l) ->
                    Pill(l, s.introMode == m, { scope.launch { c.settings.setIntroMode(m) } })
                }
            }
            Text(
                "Posisi intro diatur per seri dari \u2699 di pemutar (\u201cMulai di…\u201d / \u201cSelesai di…\u201d), atau disimpan saat kamu mengetuk \u201cSimpan\u201d setelah melewati opening sendiri. Tidak ada yang disimpan tanpa persetujuanmu.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Group("Notifikasi") {
            Toggle("Episode baru dari favorit", "Dicek di latar sekitar tiap jam saat ada internet", s.newEpisodeAlerts) { scope.launch { c.settings.setNewEpisodeAlerts(it) } }
            if (s.newEpisodeAlerts) {
                Divider()
                Action("Cek sekarang", note("check") ?: "Periksa episode baru semua favorit sekarang juga") {
                    dev.animeruu.feature.NewEpisodeWorker.runNow(ctx)
                    feedback = "check" to "Memeriksa episode baru… notifikasi muncul bila ada"
                }
            }
        }
        Group("Unduhan") {
            Label("Kualitas unduhan", "Kualitas terdekat yang tersedia akan dipakai")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                listOf(360, 480, 720, 1080).forEach { h -> Pill("${h}p", s.downloadHeight == h, { scope.launch { c.settings.setDownloadQuality(h) } }) }
            }
            Divider()
            Toggle("Hanya lewat Wi‑Fi", "Unduhan menunggu sampai tersambung ke jaringan tanpa kuota", s.wifiOnlyDownloads) { scope.launch { c.settings.setWifiOnly(it) } }
        }
        Group("Data") {
            Action(
                "Hapus riwayat tonton",
                if (armed == "history") "Ketuk sekali lagi untuk menghapus" else note("history") ?: "Termasuk posisi lanjutkan menonton",
                danger = armed == "history",
            ) {
                if (armed != "history") armed = "history"
                else { armed = null; scope.launch { c.library.clearHistory(); feedback = "history" to "Riwayat dihapus" } }
            }
            Divider()
            Action(
                "Hapus semua posisi intro",
                if (armed == "intro") "Ketuk sekali lagi untuk menghapus" else note("intro") ?: "Posisi intro yang disimpan untuk semua seri",
                danger = armed == "intro",
            ) {
                if (armed != "intro") armed = "intro"
                else { armed = null; c.introStore.clear(); feedback = "intro" to "Semua posisi intro dihapus" }
            }
            Divider()
            Action("Bersihkan cache gambar", note("cache") ?: "Poster dan thumbnail akan dimuat ulang") {
                armed = null
                ctx.imageLoader.memoryCache?.clear()
                ctx.imageLoader.diskCache?.clear()
                feedback = "cache" to "Cache gambar dibersihkan"
            }
        }
        Text(
            "Animeruu ${BuildConfig.VERSION_NAME}\nData: REST-ANIM API • Poster: Kitsu\nFavorit, riwayat & unduhan tersimpan di perangkat ini saja.",
            style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint, modifier = Modifier.padding(start = 32.dp, top = 18.dp, bottom = 8.dp))
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface)
            .border(0.5.dp, Palette.Stroke, RoundedCornerShape(18.dp)).padding(16.dp)
    ) { content() }
}

@Composable private fun Label(t: String, sub: String, subColor: androidx.compose.ui.graphics.Color = Palette.TextDim) {
    Text(t, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
    Text(sub, style = MaterialTheme.typography.bodySmall, color = subColor)
}

@Composable private fun Divider() = Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(0.5.dp).background(Palette.Stroke))

@Composable
private fun Toggle(t: String, sub: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().springClick(pressedScale = 0.99f) { onChange(!v) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Label(t, sub) }
        Switch(v, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White))
    }
}

@Composable
private fun Action(t: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().springClick(pressedScale = 0.98f, onClick = onClick)) { Label(t, sub, if (danger) Palette.Danger else Palette.TextDim) }
}
