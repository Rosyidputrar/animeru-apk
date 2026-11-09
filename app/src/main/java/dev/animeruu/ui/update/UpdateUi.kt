package dev.animeruu.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.animeruu.update.ReleaseInfo
import dev.animeruu.update.UpdateManager
import dev.animeruu.update.UpdateState
import dev.animeruu.ui.components.PrimaryButton
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun mb(bytes: Long) = if (bytes <= 0) "" else "%.1f MB".format(Locale("in", "ID"), bytes / 1_048_576.0)

private fun ago(ts: Long): String {
    if (ts <= 0) return "belum pernah"
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> "baru saja"
        m < 60 -> "$m menit lalu"
        m < 24 * 60 -> "${m / 60} jam lalu"
        else -> SimpleDateFormat("d MMM yyyy", Locale("in", "ID")).format(Date(ts))
    }
}

/** Tombol aksi utama sesuai status — dipakai kartu Pengaturan & lembar pembaruan. */
@Composable
private fun UpdateAction(s: UpdateState, m: UpdateManager, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    fun install() { m.install()?.let { runCatching { ctx.startActivity(it) } } }
    AnimatedContent(
        targetState = s::class,
        transitionSpec = { fadeIn(Motion.gentle()) togetherWith fadeOut(Motion.gentle()) },
        modifier = modifier,
        label = "upd",
    ) { _ ->
        when (s) {
            is UpdateState.Available -> PrimaryButton("Unduh ${s.release.version} · ${mb(s.release.sizeBytes)}", m::download, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download)
            is UpdateState.Downloading -> Column(Modifier.fillMaxWidth()) {
                val p by animateFloatAsState(s.progress, Motion.snappy(), label = "p")
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Palette.SurfaceHigher)) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Palette.AccentBrush))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (s.waitingNetwork) "Menunggu jaringan…" else "${(s.progress * 100).toInt()}% · ${mb(s.bytes)} / ${mb(s.total)}",
                        style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, modifier = Modifier.weight(1f),
                    )
                    Text("Batal", style = MaterialTheme.typography.labelLarge, color = Palette.Accent, modifier = Modifier.springClick(onClick = m::cancelDownload).padding(6.dp))
                }
                Text("Boleh keluar dari aplikasi — unduhan lanjut di latar.", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
            }
            is UpdateState.Verifying -> Busy("Memeriksa keaslian berkas…")
            is UpdateState.Ready -> PrimaryButton("Pasang ${s.release.version} sekarang", ::install, Modifier.fillMaxWidth(), icon = Icons.Rounded.InstallMobile)
            is UpdateState.NeedsPermission -> Column {
                Text(
                    "Izinkan Animeruu memasang aplikasi, lalu kembali — pemasangan berlanjut otomatis.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Warning,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Buka izin pemasangan", ::install, Modifier.fillMaxWidth(), icon = Icons.Rounded.Security)
            }
            is UpdateState.Installing -> Busy("Membuka installer Android…")
            is UpdateState.Checking -> Busy("Memeriksa pembaruan…")
            is UpdateState.Failed -> PrimaryButton(
                if (s.retryDownload) "Unduh ulang" else "Coba lagi",
                { if (s.retryDownload && s.release != null) m.download() else m.check(manual = true) },
                Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh,
            )
            else -> PrimaryButton(
                "Periksa pembaruan", { m.check(manual = true) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh,
                brush = SolidColor(Palette.SurfaceHigher),
            )
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
    }
}

@Composable
private fun StatusLine(s: UpdateState, m: UpdateManager) {
    val (icon, color, text) = when (s) {
        is UpdateState.UpToDate -> Triple(Icons.Rounded.CheckCircle, Palette.Success, s.note ?: "Kamu memakai versi terbaru · dicek ${ago(s.checkedAt)}")
        is UpdateState.Failed -> Triple(Icons.Rounded.ErrorOutline, Palette.Danger, s.message)
        is UpdateState.Idle -> Triple(Icons.Rounded.Refresh, Palette.TextFaint, "Terakhir dicek ${ago(m.lastCheck)}")
        is UpdateState.Checking -> return
        else -> {
            val r = release(s) ?: return
            Triple(Icons.Rounded.NewReleases, Palette.Accent, "Versi ${r.version} tersedia${r.sizeBytes.takeIf { it > 0 }?.let { " · ${mb(it)}" } ?: ""}")
        }
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private fun release(s: UpdateState): ReleaseInfo? = when (s) {
    is UpdateState.Available -> s.release
    is UpdateState.Downloading -> s.release
    is UpdateState.Verifying -> s.release
    is UpdateState.Ready -> s.release
    is UpdateState.NeedsPermission -> s.release
    is UpdateState.Installing -> s.release
    is UpdateState.Failed -> s.release
    else -> null
}

@Composable
private fun Notes(r: ReleaseInfo, maxHeight: Int) {
    if (r.notes.isBlank()) return
    Text("Apa yang baru", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier.fillMaxWidth().heightIn(max = maxHeight.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh)
            .verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text(r.notes, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
    }
}

/** Kartu "Pembaruan" di layar Pengaturan. */
@Composable
fun UpdateSettingsCard(m: UpdateManager, autoCheck: Boolean, onAutoCheck: (Boolean) -> Unit, toggle: @Composable (String, String, Boolean, (Boolean) -> Unit) -> Unit) {
    val s by m.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    Column(Modifier.animateContentSize(Motion.gentle())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Animeruu ${m.currentVersion}", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                Text("Build ${m.currentCode} · github.com/${m.repo}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
        Spacer(Modifier.height(10.dp))
        StatusLine(s, m)
        Spacer(Modifier.height(12.dp))
        UpdateAction(s, m)
        release(s)?.let { r ->
            if (s !is UpdateState.Failed) {
                Spacer(Modifier.height(14.dp))
                Notes(r, maxHeight = 180)
                r.pageUrl?.let { url ->
                    Text(
                        "Lihat rilis di GitHub", style = MaterialTheme.typography.labelMedium, color = Palette.Accent,
                        modifier = Modifier.padding(top = 8.dp).springClick { runCatching { uri.openUri(url) } },
                    )
                }
            }
        }
        Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(0.5.dp).background(Palette.Stroke))
        toggle("Cek pembaruan otomatis", "Saat aplikasi dibuka, paling sering 2× sehari", autoCheck, onAutoCheck)
    }
}

/**
 * Lembar pembaruan global. Muncul otomatis bila ada versi baru (sekali per versi, bisa
 * "Nanti"), saat diminta dari notifikasi, atau setelah aplikasi berhasil diperbarui.
 * Tidak pernah muncul di atas pemutar video ([allowAutoShow] = false di layar non-tab).
 */
@Composable
fun UpdateSheetHost(m: UpdateManager, allowAutoShow: Boolean) {
    val s by m.state.collectAsStateWithLifecycle()
    val requested by m.sheetRequest.collectAsStateWithLifecycle()
    val updated by m.justUpdated.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }

    LaunchedEffect(s, allowAutoShow) {
        val r = (s as? UpdateState.Available)?.release ?: (s as? UpdateState.Ready)?.release
        if (allowAutoShow && r != null && !m.isDismissed(r.version)) open = true
    }
    LaunchedEffect(requested) { if (requested) { open = true; m.consumeSheetRequest() } }

    if (updated != null && allowAutoShow) {
        JustUpdatedSheet(updated!!) { m.consumeJustUpdated() }
        return
    }
    if (!open) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = {
            open = false
            release(s)?.let { if (s is UpdateState.Available) m.dismiss(it.version) }
        },
        sheetState = sheet, containerColor = Palette.Surface,
    ) {
        val r = release(s)
        Column(Modifier.padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 18.dp)) {
            Header(Icons.Rounded.NewReleases, if (r != null) "Pembaruan tersedia" else "Pembaruan")
            Spacer(Modifier.height(4.dp))
            Text(
                if (r != null) "${m.currentVersion}  →  ${r.version}${r.sizeBytes.takeIf { it > 0 }?.let { "  ·  ${mb(it)}" } ?: ""}" else "Animeruu ${m.currentVersion}",
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
            )
            Spacer(Modifier.height(16.dp))
            if (r != null && s !is UpdateState.Failed) { Notes(r, maxHeight = 220); Spacer(Modifier.height(16.dp)) }
            if (s is UpdateState.Failed || s is UpdateState.UpToDate) { StatusLine(s, m); Spacer(Modifier.height(12.dp)) }
            UpdateAction(s, m)
            if (s is UpdateState.Available) {
                Text(
                    "Nanti saja", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).springClick {
                        m.dismiss(r!!.version); open = false
                    }.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Palette.Accent.copy(alpha = 0.25f), Palette.Accent2.copy(alpha = 0.25f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Palette.Text, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
    }
}

@Composable
private fun JustUpdatedSheet(r: ReleaseInfo, onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onClose, sheetState = sheet, containerColor = Palette.Surface) {
        Column(Modifier.padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 18.dp)) {
            Header(Icons.Rounded.CheckCircle, "Berhasil diperbarui")
            Spacer(Modifier.height(4.dp))
            Text("Animeruu sekarang versi ${r.version}", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            Spacer(Modifier.height(16.dp))
            Notes(r, maxHeight = 260)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(0.5.dp, Palette.StrokeStrong, RoundedCornerShape(14.dp))
                    .springClick(onClick = onClose).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Mantap", style = MaterialTheme.typography.labelLarge, color = Palette.Text) }
        }
    }
}

