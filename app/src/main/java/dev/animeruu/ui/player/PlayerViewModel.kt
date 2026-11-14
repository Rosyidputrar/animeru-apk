package dev.animeruu.ui.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import dev.animeruu.playback.PlaybackService
import dev.animeruu.playback.PlaybackSessions
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.animeruu.AppContainer
import dev.animeruu.core.AppError
import dev.animeruu.core.formatDuration
import dev.animeruu.core.userMessage
import dev.animeruu.data.repo.IntroMode
import dev.animeruu.feature.IntroRange
import dev.animeruu.feature.IntroRules
import dev.animeruu.domain.Anime
import dev.animeruu.domain.Episode
import dev.animeruu.domain.SourceChain
import dev.animeruu.domain.StreamInfo
import dev.animeruu.domain.StreamSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PlayerPhase {
    data object LoadingStream : PlayerPhase
    data object Playing : PlayerPhase
    /** Semua sumber sudah dicoba dan gagal. */
    data class AllFailed(val message: String) : PlayerPhase
    /** Episode selesai; [next] siap diputar (hitung mundur di UI). */
    data class Ended(val next: Episode?) : PlayerPhase
}

/** Chip kecil di atas seek bar terkait intro. Tidak ada yang tersimpan tanpa ketukan pengguna. */
sealed interface IntroPrompt {
    /** Lompatan manual terdeteksi → tawarkan simpan sebagai posisi intro seri ini. */
    data class Remember(val range: IntroRange, val update: Boolean) : IntroPrompt
    /** Intro baru saja dilewati otomatis; [backTo] = posisi sebelum lompat (untuk "Tonton intro"). */
    data class SkippedAuto(val backTo: Long) : IntroPrompt
}

/**
 * Memegang ExoPlayer di level ViewModel: rotasi/fullscreen tidak memutus pemutaran, dan
 * player dilepas tepat sekali di onCleared (tanpa audio hantu / kebocoran).
 */
class PlayerViewModel(
    private val app: Application,
    private val c: AppContainer,
    val animeId: String,
    startEpisodeId: String,
) : ViewModel() {

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                // Baca dari cache unduhan bila ada (offline), selain itu langsung dari jaringan.
                CacheDataSource.Factory()
                    .setCache(c.downloads.cache)
                    // DefaultDataSource: http(s) lewat OkHttp, plus file:// & content:// untuk berkas lokal.
                    .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(app, c.mediaDataSource))
                    .setCacheWriteDataSinkFactory(null)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            )
        )
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 1_500, 3_000).build())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    var anime by mutableStateOf(c.anime.peek(animeId) ?: c.anime.cachedDetail(animeId)?.anime)
        private set
    var episodeId by mutableStateOf(startEpisodeId)
        private set
    var stream by mutableStateOf<StreamInfo?>(null)
        private set
    var chain by mutableStateOf<List<StreamSource>>(emptyList())
        private set
    var current by mutableStateOf<StreamSource?>(null)
        private set
    var phase by mutableStateOf<PlayerPhase>(PlayerPhase.LoadingStream)
        private set
    /** Pesan sementara (mis. "Beralih ke 480p · RAPSODI"). */
    var toast by mutableStateOf<String?>(null)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var autoplay by mutableStateOf(true)
        private set
    var offline by mutableStateOf(false)
        private set

    // ---- Lewati intro (per seri) ----
    /** Rentang intro yang disimpan pengguna untuk seri ini (null = belum diatur). */
    var intro by mutableStateOf(c.introStore.get(animeId))
        private set
    /** true setelah intro episode ini dilewati (tombol disembunyikan). */
    var introSkipped by mutableStateOf(false)
        private set
    var introMode by mutableStateOf(IntroMode.BUTTON)
        private set
    var introPrompt by mutableStateOf<IntroPrompt?>(null)
        private set
    private var promptJob: Job? = null
    private val learner = dev.animeruu.feature.IntroLearner()
    private var programmaticSeek = false
    /** Nilai "putar berikutnya otomatis" dari Pengaturan; "Batal" hanya berlaku untuk episode itu. */
    private var autoplayPref = true

    // ---- Tahan untuk 2× ----
    var boosting by mutableStateOf(false)
        private set

    private val failed = HashSet<String>()
    private var watchdog: Job? = null
    private var saver: Job? = null
    private var toastJob: Job? = null
    private var pendingSeek: Long = 0

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (programmaticSeek) { programmaticSeek = false; return }
            learner.onSeek(oldPosition.positionMs, newPosition.positionMs, android.os.SystemClock.elapsedRealtime())
        }

        override fun onPlayerError(error: PlaybackException) {
            onSourceFailed("galat ${error.errorCodeName}")
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> watchdog?.cancel()
                Player.STATE_BUFFERING -> armWatchdog(stallMs = 25_000)
                Player.STATE_ENDED -> {
                    saveProgress(finished = true)
                    phase = PlayerPhase.Ended(stream?.next)
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
        }
    }

    // ---- MediaSession: notifikasi media, layar kunci, tombol earphone/Bluetooth ----
    /** Membungkus player: tombol "next" di notifikasi/earphone = episode berikutnya. */
    private val sessionPlayer = object : ForwardingPlayer(player) {
        // "Sebelumnya" (= ulang dari awal) disembunyikan; tempatnya dipakai ⏪10 / ⏩10.
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
            .removeAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        override fun hasNextMediaItem() = stream?.next != null
        override fun seekToNext() { if (stream?.next != null) playNext() }
        override fun seekToNextMediaItem() = seekToNext()
    }

    private val session: MediaSession = MediaSession.Builder(app, sessionPlayer)
        .setId("animeruu-${System.nanoTime()}")
        .setSessionActivity(
            PendingIntent.getActivity(
                app, 0,
                Intent(app, dev.animeruu.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .setCustomLayout(dev.animeruu.playback.PlaybackCommands.layout())
        .setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(dev.animeruu.playback.PlaybackCommands.back)
                            .add(dev.animeruu.playback.PlaybackCommands.forward)
                            .build()
                    )
                    .setCustomLayout(dev.animeruu.playback.PlaybackCommands.layout())
                    .build()

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: androidx.media3.session.SessionCommand,
                args: android.os.Bundle,
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                when (customCommand.customAction) {
                    dev.animeruu.playback.PlaybackCommands.SEEK_BACK -> player.seekBack()
                    dev.animeruu.playback.PlaybackCommands.SEEK_FORWARD -> player.seekForward()
                }
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                )
            }
        })
        .build().also { PlaybackSessions.current = it }

    /** Menyambung ke PlaybackService → service hidup & notifikasi media tampil selama layar player ada. */
    private val controllerFuture = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()

    /** true selama suara diputar di latar (track video dimatikan untuk hemat baterai). */
    private var backgroundAudioActive = false

    init {
        player.addListener(listener)
        // Diikuti terus: mode intro bisa diubah dari lembar pengaturan pemutar.
        viewModelScope.launch {
            var first = true
            c.settings.settings.collect { st ->
                if (first || st.autoplayNext != autoplayPref) autoplay = st.autoplayNext
                autoplayPref = st.autoplayNext
                first = false
                introMode = st.introMode
                if (st.introMode == IntroMode.OFF) dismissIntroPrompt()
            }
        }
        // Detak 0,5 dtk: deteksi lompatan intro manual & lewati otomatis bila diaktifkan.
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                learner.poll(android.os.SystemClock.elapsedRealtime())?.let { cand ->
                    // Hanya DITAWARKAN; baru disimpan bila pengguna mengetuk "Simpan".
                    if (introMode != IntroMode.OFF && IntroRules.shouldOffer(cand, intro)) {
                        introSkipped = true
                        prompt(IntroPrompt.Remember(cand, update = intro != null))
                    }
                }
                val r = intro
                if (introMode == IntroMode.AUTO && r != null && !introSkipped && phase == PlayerPhase.Playing && player.isPlaying) {
                    val pos = player.currentPosition
                    if (IntroRules.inAutoSkipWindow(r, pos)) {
                        seekProgrammatic(r.endMs)
                        introSkipped = true
                        prompt(IntroPrompt.SkippedAuto(backTo = pos))
                    }
                }
            }
        }
        load(startEpisodeId)
        saver = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) saveProgress()
            }
        }
    }

    fun load(epId: String) {
        saveProgress()
        learner.reset()
        introSkipped = false
        dismissIntroPrompt()
        autoplay = autoplayPref
        episodeId = epId
        failed.clear()
        watchdog?.cancel()
        player.stop()
        player.clearMediaItems()
        stream = null
        current = null
        chain = emptyList()
        phase = PlayerPhase.LoadingStream
        viewModelScope.launch {
            if (anime == null) runCatching { anime = c.anime.detail(animeId).anime }
            val resumeAt = c.library.progress(epId)?.let { p ->
                if (p.positionMs > 10_000 && (p.durationMs <= 0 || p.positionMs < p.durationMs * 0.95)) p.positionMs else 0L
            } ?: 0L
            val downloaded = c.downloads.find(epId)?.takeIf { c.downloads.isComplete(epId) }
            try {
                val info = c.anime.stream(epId)
                stream = info
                offline = false
                val s = c.settings.settings.first()
                var ordered = SourceChain.order(info.sources, s.preferredHeight, c.isUnmetered())
                // Episode yang sudah diunduh diputar dari file lokal lebih dulu.
                if (downloaded != null) {
                    val local = ordered.firstOrNull { it.url == downloaded.url }
                        ?: StreamSource("local", "Unduhan", downloaded.quality, null, downloaded.url, downloaded.sizeMb, true)
                    ordered = listOf(local) + ordered.filter { it.url != downloaded.url }
                }
                debugSource()?.let { ordered = listOf(it) + ordered }
                chain = ordered
                if (ordered.isEmpty()) {
                    phase = PlayerPhase.AllFailed("Episode ini belum punya sumber video yang bisa diputar langsung.")
                    return@launch
                }
                start(ordered.first(), resumeAt)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (downloaded != null) {
                    // Offline tapi episode terunduh → tetap bisa ditonton.
                    offline = true
                    val ep = Episode(epId, animeId, downloaded.episodeLabel.toDoubleOrNull(), downloaded.episodeLabel, downloaded.episodeTitle, null, null, null, false)
                    stream = StreamInfo(ep, null, emptyList())
                    val local = StreamSource("local", "Unduhan", downloaded.quality, null, downloaded.url, downloaded.sizeMb, true)
                    chain = listOf(local)
                    start(local, resumeAt)
                } else {
                    phase = PlayerPhase.AllFailed(
                        if (t is AppError.Offline) "Tidak ada koneksi. Sambungkan internet atau unduh episode untuk ditonton offline."
                        else t.userMessage()
                    )
                }
            }
        }
    }

    /**
     * Khusus build debug (dibuang R8 di release): bila ada files/debug_source.txt berisi URI,
     * sumber itu ditaruh paling depan rantai — untuk menguji pipeline pemutar di lingkungan
     * yang CDN videonya diblokir. Sumber API asli tetap ada di belakangnya sebagai fallback.
     */
    private fun debugSource(): StreamSource? {
        if (!dev.animeruu.BuildConfig.DEBUG) return null
        val f = java.io.File(app.getExternalFilesDir(null), "debug_source.txt")
        val uri = runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return StreamSource("debug", "DEBUG-LOKAL", "480p", 480, uri, null, true)
    }

    private fun start(src: StreamSource, positionMs: Long) {
        current = src
        phase = PlayerPhase.Playing
        pendingSeek = positionMs
        val ep = stream?.episode
        val item = MediaItem.Builder()
            .setUri(src.url)
            .setCustomCacheKey(src.url)
            .setMediaId(episodeId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(ep?.title)
                    .setDisplayTitle(ep?.title)
                    .setArtist(anime?.title)
                    .setSubtitle(anime?.title)
                    // Poster Kitsu lebih andal daripada CDN sumber untuk notifikasi & layar kunci.
                    .setArtworkUri((anime?.title?.let { c.posters.peek(it)?.posterUrl } ?: anime?.posterUrl)?.let(Uri::parse))
                    .build()
            )
            .build()
        player.setMediaItem(item, positionMs)
        player.playbackParameters = player.playbackParameters.withSpeed(speed)
        player.prepare()
        player.playWhenReady = true
        armWatchdog(stallMs = 20_000)
    }

    /** Bila tidak siap dalam batas waktu (link menggantung tanpa error), anggap gagal → sumber berikutnya. */
    private fun armWatchdog(stallMs: Long) {
        watchdog?.cancel()
        watchdog = viewModelScope.launch {
            delay(stallMs)
            if (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE) {
                onSourceFailed("tidak merespons")
            }
        }
    }

    private fun onSourceFailed(reason: String) {
        val cur = current ?: return
        failed += cur.url
        val pos = player.currentPosition.takeIf { it > 0 } ?: pendingSeek
        val next = chain.firstOrNull { it.url !in failed }
        if (next == null) {
            watchdog?.cancel()
            player.stop()
            phase = PlayerPhase.AllFailed("Semua ${chain.size} sumber gagal diputar ($reason). Server mungkin sedang sibuk atau menolak jaringanmu.")
            return
        }
        flash("${cur.qualityLabel} gagal · beralih ke ${next.displayName}")
        start(next, pos)
    }

    /** Pilihan manual dari lembar kualitas/server. */
    fun select(src: StreamSource) {
        if (src.url == current?.url && phase == PlayerPhase.Playing) return
        failed.remove(src.url)
        val pos = player.currentPosition
        flash("Memutar ${src.displayName}")
        start(src, pos)
    }

    /** Ulang seluruh rantai dari awal (tombol "Coba lagi"). */
    fun retry() {
        if (chain.isEmpty()) { load(episodeId); return }
        failed.clear()
        start(chain.first(), pendingSeek)
    }

    private fun seekProgrammatic(to: Long) {
        programmaticSeek = true
        player.seekTo(to)
    }

    /**
     * Tombol "Lewati intro". Bila posisi intro seri ini sudah disimpan → lompat ke akhirnya.
     * Bila belum → lompat 85 dtk (panjang opening umum) TANPA menyimpan apa pun; pengguna
     * ditawari menyimpannya lewat chip, yang bisa diabaikan.
     */
    fun skipIntro() {
        val pos = player.currentPosition
        val r = intro
        val target = (r?.endMs?.takeIf { it > pos } ?: (pos + IntroRules.DEFAULT_LENGTH_MS))
            .coerceAtMost((player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE) - 1_000)
        seekProgrammatic(target)
        introSkipped = true
        if (r == null && introMode != IntroMode.OFF) prompt(IntroPrompt.Remember(IntroRange(pos, target), update = false))
    }

    private fun prompt(p: IntroPrompt) {
        introPrompt = p
        promptJob?.cancel()
        promptJob = viewModelScope.launch { delay(8_000); introPrompt = null }
    }

    fun dismissIntroPrompt() {
        promptJob?.cancel()
        introPrompt = null
    }

    /** Ketukan "Simpan" pada chip. */
    fun rememberIntro(r: IntroRange) {
        intro = c.introStore.set(animeId, r)
        dismissIntroPrompt()
        flash("Intro ${formatDuration(r.startMs)}–${formatDuration(r.endMs)} disimpan untuk seri ini")
    }

    /** "Tonton intro" setelah dilewati otomatis: kembali ke posisi sebelum lompat. */
    fun watchIntro(backTo: Long) {
        dismissIntroPrompt()
        seekProgrammatic(backTo)
    }

    /** Lembar pengaturan pemutar: "Intro mulai di sini". */
    fun markIntroStart() {
        val r = IntroRules.markStart(intro, player.currentPosition.coerceAtLeast(0))
        intro = c.introStore.set(animeId, r)
        introSkipped = true // jangan langsung dilompati/ditawari selagi pengguna menandai
        flash("Awal intro: ${formatDuration(r.startMs)}")
    }

    /** Lembar pengaturan pemutar: "Intro selesai di sini". */
    fun markIntroEnd() {
        val r = IntroRules.markEnd(intro, player.currentPosition.coerceAtLeast(0))
        intro = c.introStore.set(animeId, r)
        introSkipped = true
        flash("Akhir intro: ${formatDuration(r.endMs)}")
    }

    fun forgetIntro() {
        c.introStore.forget(animeId)
        intro = null
        flash("Posisi intro seri ini dihapus")
    }

    fun chooseIntroMode(m: IntroMode) {
        introMode = m
        c.appScope.launch { c.settings.setIntroMode(m) }
    }

    /** "Tonton ulang" dari awal (bukan dari posisi lanjut-tonton saat episode dibuka). */
    fun replay() {
        val src = current ?: chain.firstOrNull() ?: run { load(episodeId); return }
        failed.clear()
        learner.reset()
        introSkipped = false
        start(src, 0)
    }

    /** Tahan layar = 2× sementara; lepas = kembali ke kecepatan sebelumnya. */
    fun boost(on: Boolean) {
        if (on == boosting) return
        boosting = on
        player.playbackParameters = player.playbackParameters.withSpeed(if (on) 2f else speed)
    }

    fun changeSpeed(v: Float) {
        speed = v
        player.playbackParameters = player.playbackParameters.withSpeed(v)
        flash("Kecepatan ${if (v == 1f) "normal" else "${v}×"}")
    }

    fun playNext() {
        val n = stream?.next ?: return
        load(n.id)
    }

    fun cancelAutoNext() {
        autoplay = false
    }

    fun flash(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(2600); toast = null }
    }

    fun saveProgress(finished: Boolean = false) {
        val a = anime ?: return
        val ep = stream?.episode ?: return
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val pos = if (finished) dur else player.currentPosition
        if (pos < 3_000 && !finished) return
        c.appScope.launch { c.library.record(a, ep, pos, dur) }
    }

    /**
     * Aplikasi ke latar / layar dikunci (bukan PiP).
     * @param keepAudio true bila "Putar audio di latar" aktif → suara lanjut, video tidak didekode.
     */
    fun onBackground(keepAudio: Boolean) {
        saveProgress()
        if (keepAudio && player.isPlaying) {
            backgroundAudioActive = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        } else {
            player.pause()
        }
    }

    /** Kembali ke depan: nyalakan lagi track video bila tadi diputar sebagai audio. */
    fun onForeground() {
        if (!backgroundAudioActive) return
        backgroundAudioActive = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
    }

    override fun onCleared() {
        saveProgress()
        watchdog?.cancel()
        saver?.cancel()
        player.removeListener(listener)
        if (PlaybackSessions.current === session) PlaybackSessions.current = null
        MediaController.releaseFuture(controllerFuture)
        session.release()
        player.release()
        // Tanpa session, service tidak punya tugas lagi → hentikan supaya tidak ada proses menggantung.
        runCatching { app.stopService(Intent(app, PlaybackService::class.java)) }
    }
}
