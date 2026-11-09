package dev.animeruu.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Klik dengan umpan balik fisik ala iOS: elemen mengecil dengan pegas saat ditekan dan
 * memantul kembali saat dilepas. Skala dibaca di fase draw (graphicsLayer) → tanpa
 * rekomposisi per frame.
 */
fun Modifier.springClick(
    pressedScale: Float = 0.955f,
    haptic: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    val hf = LocalHapticFeedback.current
    this
        .semantics { role = Role.Button; onClick { click(); true } }
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    scope.launch { scale.animateTo(pressedScale, Motion.press()) }
                    tryAwaitRelease()
                    scope.launch { scale.animateTo(1f, Motion.press()) }
                },
                onLongPress = long?.let { { _: Offset -> hf.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
                onTap = {
                    if (haptic) hf.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    click()
                },
            )
        }
}

/** Shimmer halus untuk skeleton; satu gradien bergerak, digambar di fase draw. */
fun Modifier.shimmer(base: Color = Palette.SurfaceHigh, highlight: Color = Palette.SurfaceHigher): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "x",
    )
    drawWithCache {
        val w = size.width.coerceAtLeast(1f)
        onDrawBehind {
            val start = w * x
            drawRect(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(start - w * 0.6f, 0f),
                    end = Offset(start, size.height),
                )
            )
        }
    }
}
