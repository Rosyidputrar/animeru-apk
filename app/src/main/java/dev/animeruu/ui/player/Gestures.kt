package dev.animeruu.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.animeruu.core.formatDuration
import dev.animeruu.feature.IntroRange
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private enum class SwipeKind { BRIGHTNESS, VOLUME }

/**
 * Lapisan gestur video:
 * - ketuk: tampil/sembunyi kontrol; ketuk dua kali kiri/kanan: ±10 dtk (gelembung akumulatif)
 * - tahan: 2× selama ditahan (haptic saat mulai)
 * - geser vertikal (bila [swipeEnabled]): kiri = kecerahan, kanan = volume, dengan indikator
 */
@Composable
fun SeekGestureLayer(
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onBoost: (Boolean) -> Unit,
    swipeEnabled: Boolean,
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val boost by rememberUpdatedState(onBoost)

    var bubble by remember { mutableStateOf<Pair<Boolean, Int>?>(null) } // (kanan?, detik)
    LaunchedEffect(bubble) { if (bubble != null) { delay(700); bubble = null } }

    var swipe by remember { mutableStateOf<SwipeKind?>(null) }
    var level by remember { mutableStateOf(0f) }

    fun currentBrightness(): Float {
        val w = activity?.window?.attributes?.screenBrightness ?: -1f
        if (w >= 0f) return w
        return runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Detektor sendiri: tap vs tahan diputuskan dari STEMPEL WAKTU event (turun→angkat),
        // bukan timer jam dinding — tetap benar walau main thread sedang tersendat.
        var gestureMod = Modifier.fillMaxSize().pointerInput(Unit) {
            val longPress = viewConfiguration.longPressTimeoutMillis
            val doubleTap = viewConfiguration.doubleTapTimeoutMillis
            awaitEachGesture {
                val down = awaitFirstDown()
                var boosted = false
                val job: Job = scope.launch {
                    delay(longPress)
                    boosted = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    boost(true)
                }
                val up = waitForUpOrCancellation()
                job.cancel()
                if (boosted) boost(false)
                if (up == null) return@awaitEachGesture // digeser/dibatalkan
                if (up.uptimeMillis - down.uptimeMillis >= longPress) return@awaitEachGesture // memang ditahan
                up.consume()
                // Tap: tunggu kemungkinan ketukan kedua.
                val second = withTimeoutOrNull(doubleTap) { awaitFirstDown(requireUnconsumed = false) }
                if (second != null && second.uptimeMillis - up.uptimeMillis <= doubleTap * 2) {
                    waitForUpOrCancellation()
                    val right = second.position.x > size.width / 2
                    onSeek(if (right) 10_000 else -10_000)
                    val prev = bubble
                    bubble = right to (if (prev != null && prev.first == right) prev.second + 10 else 10)
                } else {
                    onToggle()
                }
            }
        }
        if (swipeEnabled) {
            gestureMod = gestureMod.pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        swipe = if (o.x < size.width / 2) SwipeKind.BRIGHTNESS else SwipeKind.VOLUME
                        level = when (swipe) {
                            SwipeKind.BRIGHTNESS -> currentBrightness()
                            else -> audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        }
                    },
                    onDragEnd = { swipe = null },
                    onDragCancel = { swipe = null },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        level = (level - dy / (size.height * 0.85f)).coerceIn(0f, 1f)
                        when (swipe) {
                            SwipeKind.BRIGHTNESS -> activity?.window?.let { w ->
                                w.attributes = w.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                            }
                            SwipeKind.VOLUME -> {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                            }
                            null -> Unit
                        }
                    },
                )
            }
        }
        Box(gestureMod)

        val b = bubble
        AnimatedVisibility(b != null, Modifier.align(if (b?.first == true) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 40.dp), enter = scaleIn(Motion.press()) + fadeIn(), exit = fadeOut()) {
            Text(
                if (b?.first == true) "+${b.second} dtk" else "−${b?.second ?: 10} dtk",
                style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // Indikator kecerahan/volume: batang vertikal di sisi yang digeser.
        val k = swipe
        AnimatedVisibility(
            k != null,
            Modifier.align(if (k == SwipeKind.VOLUME) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 36.dp),
            enter = fadeIn() + scaleIn(Motion.press(), initialScale = 0.9f), exit = fadeOut(),
        ) {
            Column(
                Modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(6.dp).height(120.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(level).background(Color.White))
                }
                Spacer(Modifier.height(10.dp))
                Icon(
                    when {
                        k == SwipeKind.BRIGHTNESS || k == null -> Icons.Rounded.BrightnessMedium
                        level <= 0.001f -> Icons.Rounded.VolumeOff
                        else -> Icons.Rounded.VolumeUp
                    },
                    null, tint = Color.White, modifier = Modifier.size(20.dp),
                )
                Text("${(level * 100).roundToInt()}", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
    }
}

@Composable
fun BoostPill(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn() + scaleIn(Motion.press(), initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
        Row(
            Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("2×", style = MaterialTheme.typography.labelLarge, color = Color.White)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun IntroButton(visible: Boolean, learned: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible, modifier,
        enter = fadeIn(Motion.gentle()) + slideInHorizontally(Motion.snappy()) { it / 2 },
        exit = fadeOut(Motion.gentle()) + slideOutHorizontally(Motion.gentle()) { it / 2 },
    ) {
        Row(
            Modifier.springClick(pressedScale = 0.92f, haptic = true, onClick = onClick)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, if (learned) Palette.Accent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (learned) "Lewati intro" else "Lewati 85 dtk", style = MaterialTheme.typography.labelLarge, color = Color.White)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Chip intro: "Simpan posisi intro?" setelah lompatan manual, atau "Intro dilewati · Tonton intro"
 * setelah lompat otomatis. Mengabaikannya = tidak terjadi apa-apa (hilang sendiri).
 */
@Composable
fun IntroPromptChip(
    prompt: IntroPrompt?,
    onRemember: (IntroRange) -> Unit,
    onWatch: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Isi terakhir dipertahankan selama animasi keluar.
    val last = remember { arrayOfNulls<IntroPrompt>(1) }
    if (prompt != null) last[0] = prompt
    AnimatedVisibility(
        prompt != null, modifier,
        enter = fadeIn(Motion.gentle()) + slideInHorizontally(Motion.snappy()) { it / 2 },
        exit = fadeOut(Motion.gentle()),
    ) {
        val p = last[0] ?: return@AnimatedVisibility
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.72f))
                .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (p) {
                is IntroPrompt.Remember -> {
                    Column {
                        Text(if (p.update) "Perbarui posisi intro?" else "Simpan posisi intro?", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Text(
                            "${formatDuration(p.range.startMs)}–${formatDuration(p.range.endMs)} · seri ini",
                            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    ChipAction(if (p.update) "Perbarui" else "Simpan") { onRemember(p.range) }
                }
                is IntroPrompt.SkippedAuto -> {
                    Text("Intro dilewati", style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    ChipAction("Tonton intro") { onWatch(p.backTo) }
                }
            }
            Box(Modifier.size(36.dp).springClick(pressedScale = 0.85f, onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Tutup", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ChipAction(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = Color.White,
        modifier = Modifier.springClick(pressedScale = 0.92f, haptic = true, onClick = onClick)
            .clip(RoundedCornerShape(8.dp)).background(Palette.Accent).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
