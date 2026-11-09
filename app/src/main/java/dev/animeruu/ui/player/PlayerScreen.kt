package dev.animeruu.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.animeruu.core.formatDuration
import dev.animeruu.domain.StreamSource
import dev.animeruu.ui.components.AnimeArt
import dev.animeruu.ui.components.ArtKind
import dev.animeruu.ui.components.MetaChip
import dev.animeruu.ui.components.Pill
import dev.animeruu.ui.components.PrimaryButton
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun PlayerScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current

    // Layar penuh = lanskap + system bar disembunyikan. Dipulihkan saat keluar dari player.
    DisposableEffect(landscape) {
        val window = activity?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, view)
            if (landscape) {
                ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl.hide(WindowInsetsCompat.Type.systemBars())
            } else ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    // Pause + simpan posisi saat aplikasi ke latar belakang.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onBackground() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    fun toggleFullscreen() {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    BackHandler(landscape) { toggleFullscreen() }

    var sheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (landscape) {
            VideoBox(vm, true, onBack = { toggleFullscreen() }, onFullscreen = ::toggleFullscreen, onSettings = { sheet = true }, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().background(Palette.Bg)) {
                Box(Modifier.background(Color.Black).statusBarsPadding()) {
                    VideoBox(vm, false, onBack = onBack, onFullscreen = ::toggleFullscreen, onSettings = { sheet = true }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
                InfoPanel(vm, onSettings = { sheet = true })
            }
        }
    }
    if (sheet) SourceSheet(vm) { sheet = false }
}

@Composable
private fun VideoBox(
    vm: PlayerViewModel,
    fullscreen: Boolean,
    onBack: () -> Unit,
    onFullscreen: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier,
) {
    val p = vm.player
    var visible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(p.isPlaying) }
    var buffering by remember { mutableStateOf(p.playbackState == Player.STATE_BUFFERING) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var interact by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableStateOf(false) }

    DisposableEffect(p) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(v: Boolean) { isPlaying = v }
            override fun onPlaybackStateChanged(s: Int) { buffering = s == Player.STATE_BUFFERING }
        }
        p.addListener(l)
        onDispose { p.removeListener(l) }
    }
    LaunchedEffect(p) {
        while (true) {
            if (!scrubbing) position = p.currentPosition.coerceAtLeast(0)
            duration = p.duration.coerceAtLeast(0)
            buffered = p.bufferedPosition
            delay(if (visible) 200 else 1000)
        }
    }
    // Sembunyikan kontrol otomatis saat sedang memutar dan tidak disentuh.
    LaunchedEffect(visible, isPlaying, interact, scrubbing) {
        if (visible && isPlaying && !scrubbing) { delay(3500); visible = false }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    player = p
                }
            },
            update = { it.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        // Zona gestur: ketuk = tampil/sembunyi, ketuk dua kali kiri/kanan = ±10 dtk.
        SeekGestureLayer(
            onToggle = { visible = !visible; interact++ },
            onSeek = { delta -> p.seekTo((p.currentPosition + delta).coerceIn(0, p.duration.coerceAtLeast(0))); interact++ },
        )

        // Poster selagi memuat stream supaya tidak ada layar hitam kosong.
        val anime = vm.anime
        AnimatedVisibility(vm.phase == PlayerPhase.LoadingStream && anime != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                if (anime != null) AnimeArt(anime.title, anime.coverUrl ?: anime.posterUrl, ArtKind.COVER, Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f }, small = false, showMonogram = false)
            }
        }

        if ((buffering || vm.phase == PlayerPhase.LoadingStream) && vm.phase !is PlayerPhase.AllFailed) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(46.dp), color = Color.White, strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
        }

        // Kontrol
        AnimatedVisibility(visible && vm.phase == PlayerPhase.Playing, enter = fadeIn(Motion.gentle()), exit = fadeOut(Motion.gentle())) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = if (fullscreen) 28.dp else 8.dp, vertical = if (fullscreen) 16.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CtrlIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", onBack)
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        if (fullscreen) {
                            Text(vm.anime?.title ?: "", style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(vm.stream?.episode?.title ?: "", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                        }
                    }
                    if (fullscreen) CtrlIcon(Icons.Rounded.Fullscreen, "Zoom", { zoom = !zoom; vm.flash(if (!zoom) "Pas layar" else "Isi layar") })
                    CtrlIcon(Icons.Rounded.Settings, "Kualitas & server", onSettings)
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    CtrlIcon(Icons.Rounded.Replay10, "Mundur 10 detik", { p.seekBack(); interact++ }, size = 46.dp)
                    Box(
                        Modifier.size(66.dp).springClick(pressedScale = 0.86f, haptic = true) {
                            if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
                            interact++
                        }.clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(isPlaying, transitionSpec = { (scaleIn(Motion.press()) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "pp") { playing ->
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Jeda" else "Putar", tint = Color.White, modifier = Modifier.size(38.dp))
                        }
                    }
                    CtrlIcon(Icons.Rounded.Forward10, "Maju 10 detik", { p.seekForward(); interact++ }, size = 46.dp)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = if (fullscreen) 28.dp else 12.dp, vertical = if (fullscreen) 18.dp else 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${formatDuration(position)} / ${formatDuration(duration)}", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        if (vm.stream?.next != null) {
                            Row(
                                Modifier.springClick { vm.playNext() }.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Berikutnya", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        CtrlIcon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Layar penuh", onFullscreen, size = 36.dp)
                    }
                    SeekBar(
                        position = position, duration = duration, buffered = buffered,
                        onScrub = { scrubbing = true; position = it; interact++ },
                        onCommit = { p.seekTo(it); scrubbing = false },
                    )
                }
            }
        }

        // Pesan sementara (beralih server, kecepatan…)
        AnimatedVisibility(
            vm.toast != null, Modifier.align(Alignment.TopCenter).padding(top = if (fullscreen) 72.dp else 48.dp),
            enter = fadeIn() + slideInVertically(Motion.snappy()) { -it }, exit = fadeOut() + slideOutVertically { -it },
        ) {
            Text(
                vm.toast ?: "", style = MaterialTheme.typography.labelLarge, color = Color.White,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)).border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        when (val ph = vm.phase) {
            is PlayerPhase.AllFailed -> FailedOverlay(ph.message, vm, onBack, onSettings)
            is PlayerPhase.Ended -> EndedOverlay(vm, ph)
            else -> Unit
        }
    }
}

@Composable
private fun SeekGestureLayer(onToggle: () -> Unit, onSeek: (Long) -> Unit) {
    var bubble by remember { mutableStateOf<Pair<Boolean, Int>?>(null) } // (kanan?, detik)
    LaunchedEffect(bubble) { if (bubble != null) { delay(700); bubble = null } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggle() },
                    onDoubleTap = { o ->
                        val right = o.x > size.width / 2
                        onSeek(if (right) 10_000 else -10_000)
                        val prev = bubble
                        bubble = right to (if (prev != null && prev.first == right) prev.second + 10 else 10)
                    },
                )
            }
        )
        val b = bubble
        AnimatedVisibility(b != null, Modifier.align(if (b?.first == true) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 40.dp), enter = scaleIn(Motion.press()) + fadeIn(), exit = fadeOut()) {
            Text(
                if (b?.first == true) "+${b.second} dtk" else "−${b?.second ?: 10} dtk",
                style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SeekBar(position: Long, duration: Long, buffered: Long, onScrub: (Long) -> Unit, onCommit: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val h by animateDpAsState(if (dragging) 8.dp else 4.dp, Motion.press(), label = "h")
    val thumb by animateDpAsState(if (dragging) 18.dp else 12.dp, Motion.press(), label = "t")
    val frac = if (dragging) dragFrac else if (duration > 0) position.toFloat() / duration else 0f
    val bufFrac = if (duration > 0) buffered.toFloat() / duration else 0f
    Box(
        Modifier.fillMaxWidth().height(30.dp)
            .pointerInput(duration) {
                detectTapGestures { o -> if (duration > 0) onCommit(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; dragFrac = (o.x / size.width).coerceIn(0f, 1f); onScrub((dragFrac * duration).toLong()) },
                    onDragEnd = { dragging = false; onCommit((dragFrac * duration).toLong()) },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { ch, d -> ch.consume(); dragFrac = (dragFrac + d / size.width).coerceIn(0f, 1f); onScrub((dragFrac * duration).toLong()) },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(thumb)) {
            val y = size.height / 2
            val sw = h.toPx()
            drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), sw, StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width * bufFrac.coerceIn(0f, 1f), y), sw, StrokeCap.Round)
            drawLine(Brush.horizontalGradient(listOf(Palette.Accent, Palette.Accent2)), Offset(0f, y), Offset(size.width * frac.coerceIn(0f, 1f), y), sw, StrokeCap.Round)
            drawCircle(Color.White, thumb.toPx() / 2, Offset(size.width * frac.coerceIn(0f, 1f), y))
        }
    }
}

@Composable
private fun CtrlIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(Modifier.size(size).springClick(pressedScale = 0.82f, haptic = true, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
private fun FailedOverlay(message: String, vm: PlayerViewModel, onBack: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.CloudOff, null, tint = Color.White, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp))
            Text("Video tidak dapat diputar", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Coba lagi", vm::retry, icon = Icons.Rounded.Refresh)
                if (vm.chain.isNotEmpty()) PrimaryButton("Pilih server", onSettings, brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.14f)))
                else PrimaryButton("Kembali", onBack, brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.14f)))
            }
        }
    }
}

@Composable
private fun EndedOverlay(vm: PlayerViewModel, ph: PlayerPhase.Ended) {
    val next = ph.next
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        if (next == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Selesai ditonton", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Tonton ulang", { vm.player.seekTo(0); vm.player.play(); vm.retry() }, icon = Icons.Rounded.Refresh)
            }
        } else {
            val ring = remember(next.id) { Animatable(0f) }
            LaunchedEffect(next.id, vm.autoplay) {
                if (vm.autoplay) {
                    ring.animateTo(1f, tween(5000, easing = LinearEasing))
                    vm.playNext()
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Berikutnya", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                Text(next.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.size(72.dp).springClick(haptic = true) { vm.playNext() }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, style = Stroke(4.dp.toPx()))
                        drawArc(Palette.Accent, -90f, 360f * ring.value, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Icon(Icons.Rounded.PlayArrow, "Putar berikutnya", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                if (vm.autoplay) {
                    Spacer(Modifier.height(10.dp))
                    Text("Batal", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.springClick { vm.cancelAutoNext() }.padding(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoPanel(vm: PlayerViewModel, onSettings: () -> Unit) {
    val anime = vm.anime
    val st = vm.stream
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding()) {
        Text(anime?.title ?: "", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(st?.episode?.title ?: "Memuat…", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            st?.episode?.releasedAt?.let { Text("  •  $it", style = MaterialTheme.typography.bodyMedium, color = Palette.TextFaint) }
        }
        if (vm.offline) {
            Spacer(Modifier.height(8.dp))
            MetaChip("Diputar dari unduhan (offline)", tint = Palette.Success)
        }
        Spacer(Modifier.height(20.dp))
        Text("Kualitas", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.chain.isEmpty()) repeat(3) { Box(Modifier.width(70.dp).height(36.dp).clip(CircleShape).background(Palette.SurfaceHigh)) }
            vm.chain.sortedByDescending { it.height ?: 0 }.forEach { s ->
                Pill(s.qualityLabel + if (vm.chain.count { it.qualityLabel == s.qualityLabel } > 1) " · ${s.server}" else "", s.url == vm.current?.url, { vm.select(s) })
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Kecepatan", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SPEEDS.forEach { s -> Pill(if (s == 1f) "Normal" else "${s}×", vm.speed == s, { vm.changeSpeed(s) }) }
        }
        val next = st?.next
        if (next != null) {
            Spacer(Modifier.height(24.dp))
            Text("Episode berikutnya", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().springClick(pressedScale = 0.97f) { vm.playNext() }
                    .clip(RoundedCornerShape(16.dp)).background(Palette.Surface).border(0.5.dp, Palette.Stroke, RoundedCornerShape(16.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                    AnimeArt(anime?.title ?: next.title, next.thumbUrl, ArtKind.COVER, Modifier.fillMaxSize(), showMonogram = false)
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(next.title, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                    next.releasedAt?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint) }
                }
                Icon(Icons.Rounded.SkipNext, null, tint = Palette.TextDim)
            }
        } else if (st != null) {
            Spacer(Modifier.height(24.dp))
            Text("Ini episode terbaru yang tersedia.", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.springClick(onClick = onSettings).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Speed, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(vm.current?.let { "Sumber: ${it.displayName}" } ?: "Sumber", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
        }
    }
}

@Composable
private fun SourceSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismiss, sheetState = state, containerColor = Palette.Surface) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Kualitas & server", style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Text("Urutan di bawah adalah rantai cadangan otomatis — bila satu gagal, berikutnya dicoba.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Spacer(Modifier.height(12.dp))
            vm.chain.forEachIndexed { i, s ->
                SourceRow(i, s, s.url == vm.current?.url) {
                    vm.select(s)
                    scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Kecepatan putar", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SPEEDS.forEach { sp -> Pill(if (sp == 1f) "1×" else "${sp}×", vm.speed == sp, { vm.changeSpeed(sp) }) }
            }
        }
    }
}

@Composable
private fun SourceRow(i: Int, s: StreamSource, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).springClick(pressedScale = 0.98f, onClick = onClick)
            .clip(RoundedCornerShape(14.dp)).background(if (selected) Palette.Accent.copy(alpha = 0.14f) else Palette.SurfaceHigh)
            .border(0.5.dp, if (selected) Palette.Accent.copy(alpha = 0.5f) else Palette.Stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Palette.TextFaint, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(s.qualityLabel, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Palette.Text)
            Text(listOfNotNull(s.server, s.sizeMb?.let { "%.0f MB".format(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        if (selected) MetaChip("Diputar", tint = Palette.Accent)
    }
}
