package dev.animeruu.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import dev.animeruu.MainActivity
import dev.animeruu.R

private const val ACTION_PIP = "dev.animeruu.action.PIP"
private const val EXTRA_CMD = "cmd"
private const val CMD_TOGGLE = 1
private const val CMD_REWIND = 2
private const val CMD_FORWARD = 3

/**
 * Picture-in-Picture otomatis untuk layar player.
 *
 * - Android 12+: `setAutoEnterEnabled(true)` selama video diputar → sistem masuk PiP sendiri
 *   saat pengguna menekan Home/menggeser keluar, dengan animasi mulus dari [sourceRect].
 * - Android 8–11: [MainActivity.onUserLeaveHint] memanggil [MainActivity.pipRequest].
 * - Jendela PiP punya tombol ⏪10 / ▶⏸ / ⏩10 (RemoteAction → broadcast lokal, tidak diekspor).
 *
 * @return true selama aktivitas sedang dalam mode PiP (UI player menyembunyikan kontrol).
 */
@Composable
fun rememberPipState(vm: PlayerViewModel, enabled: Boolean, sourceRect: android.graphics.Rect?): Boolean {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    var playing by remember { mutableStateOf(vm.player.isPlaying) }
    var aspect by remember { mutableStateOf(Rational(16, 9)) }
    val currentRect by rememberUpdatedState(sourceRect)

    if (activity == null || Build.VERSION.SDK_INT < 26 ||
        !ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    ) return false

    fun action(cmd: Int, icon: Int, title: String): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            ctx, cmd,
            Intent(ACTION_PIP).setPackage(ctx.packageName).putExtra(EXTRA_CMD, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(ctx, icon), title, title, pi)
    }

    fun params(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setActions(
                listOf(
                    action(CMD_REWIND, R.drawable.ic_pip_rewind, "Mundur 10 detik"),
                    if (playing) action(CMD_TOGGLE, R.drawable.ic_pip_pause, "Jeda")
                    else action(CMD_TOGGLE, R.drawable.ic_pip_play, "Putar"),
                    action(CMD_FORWARD, R.drawable.ic_pip_forward, "Maju 10 detik"),
                )
            )
        currentRect?.takeIf { !it.isEmpty }?.let { b.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(enabled && playing)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    fun push() = runCatching { activity.setPictureInPictureParams(params()) }

    // Pantau status putar & ukuran video → perbarui parameter PiP.
    DisposableEffect(vm.player, enabled) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; push() }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    // Android membatasi rasio PiP ke 1:2.39 … 2.39:1.
                    val r = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(0.42f, 2.38f)
                    aspect = Rational((r * 1000).toInt(), 1000)
                    push()
                }
            }
        }
        vm.player.addListener(l)
        push()
        (activity as? MainActivity)?.pipRequest = {
            if (enabled && vm.player.isPlaying) params() else null
        }
        onDispose {
            vm.player.removeListener(l)
            (activity as? MainActivity)?.pipRequest = null
            // Keluar dari player → matikan auto-enter supaya layar lain tidak masuk PiP.
            if (Build.VERSION.SDK_INT >= 31) runCatching {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }
    DisposableEffect(sourceRect) { push(); onDispose { } }

    // Mode PiP masuk/keluar.
    DisposableEffect(activity) {
        val l = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(l)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(l) }
    }

    // Tombol di jendela PiP.
    DisposableEffect(vm) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val p = vm.player
                when (i.getIntExtra(EXTRA_CMD, 0)) {
                    CMD_TOGGLE -> if (p.isPlaying) p.pause() else p.play()
                    CMD_REWIND -> p.seekBack()
                    CMD_FORWARD -> p.seekForward()
                }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return inPip
}
