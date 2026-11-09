package dev.animeruu.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import dev.animeruu.R

/**
 * Rumah MediaSession milik layar pemutar. Media3 otomatis menampilkan notifikasi media
 * (poster, judul, kontrol), kontrol layar kunci/quick settings, menerima tombol
 * earphone/Bluetooth, dan menjadikan service foreground selama diputar di latar.
 *
 * Player tetap dimiliki PlayerViewModel — service hanya "meminjam" session lewat
 * [PlaybackSessions], sehingga siklus hidup player tetap satu sumber kebenaran.
 */
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        // Urutan tombol notifikasi: ⏪10 · ⏯ · ⏩10 · ⏭ (episode berikutnya).
        setMediaNotificationProvider(object : DefaultMediaNotificationProvider(this) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showPauseButton: Boolean,
            ): ImmutableList<CommandButton> {
                val base = super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                val play = base.firstOrNull { it.playerCommand == Player.COMMAND_PLAY_PAUSE }
                val next = base.firstOrNull { it.playerCommand == Player.COMMAND_SEEK_TO_NEXT || it.playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM }
                val back = customLayout.firstOrNull { it.sessionCommand?.customAction == PlaybackCommands.SEEK_BACK }
                val fwd = customLayout.firstOrNull { it.sessionCommand?.customAction == PlaybackCommands.SEEK_FORWARD }
                return ImmutableList.copyOf(listOfNotNull(back, play, fwd, next))
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlaybackSessions.current

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Aplikasi digeser dari Recent: jangan tinggalkan suara/notifikasi hantu.
        PlaybackSessions.current?.player?.let { it.pause(); it.stop() }
        stopSelf()
    }
}

object PlaybackSessions {
    @Volatile var current: MediaSession? = null
}

/** Perintah kustom notifikasi/layar kunci: lompat ±10 detik. */
object PlaybackCommands {
    const val SEEK_BACK = "dev.animeruu.SEEK_BACK_10"
    const val SEEK_FORWARD = "dev.animeruu.SEEK_FORWARD_10"

    val back = SessionCommand(SEEK_BACK, Bundle.EMPTY)
    val forward = SessionCommand(SEEK_FORWARD, Bundle.EMPTY)

    fun layout(): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder().setDisplayName("Mundur 10 detik").setIconResId(R.drawable.ic_pip_rewind).setSessionCommand(back).build(),
        CommandButton.Builder().setDisplayName("Maju 10 detik").setIconResId(R.drawable.ic_pip_forward).setSessionCommand(forward).build(),
    )
}
