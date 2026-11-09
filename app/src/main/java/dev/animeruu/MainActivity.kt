package dev.animeruu

import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.animeruu.ui.components.LocalPosters
import dev.animeruu.ui.nav.AnimeruuApp
import dev.animeruu.ui.theme.AnimeruuTheme

class MainActivity : ComponentActivity() {
    /**
     * Diisi layar player: mengembalikan parameter PiP bila saat ini layak masuk PiP
     * (video sedang diputar & pengaturan aktif), atau null. Android 12+ memakai
     * auto-enter bawaan sistem; hook ini hanya untuk Android 8–11.
     */
    var pipRequest: (() -> android.app.PictureInPictureParams?)? = null

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31) {
            pipRequest?.invoke()?.let { runCatching { enterPictureInPictureMode(it) } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        preferHighestRefreshRate()
        handleUpdateIntent(intent)
        setContent {
            AnimeruuTheme {
                CompositionLocalProvider(LocalPosters provides container.posters) {
                    AnimeruuApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleUpdateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        container.updates.onResume()
    }

    private fun handleUpdateIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(dev.animeruu.update.UpdateManager.EXTRA_OPEN_UPDATE, false) == true) {
            container.updates.cancelReadyNotification()
            container.updates.requestSheet()
        }
    }

    /** Minta mode tampilan dengan refresh rate tertinggi (90/120 Hz) pada resolusi yang sama. */
    @Suppress("DEPRECATION")
    private fun preferHighestRefreshRate() {
        val display: Display = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val cur = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }
}
