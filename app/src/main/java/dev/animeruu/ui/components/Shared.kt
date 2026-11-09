package dev.animeruu.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect

val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Batas elemen bersama dipindah dengan pegas — kartu "terbang" ke posisinya di layar detail. */
val SpringBounds = BoundsTransform { _: Rect, _: Rect -> spring(dampingRatio = 0.86f, stiffness = 380f) }

/**
 * Elemen bersama antar layar (poster kartu → poster detail). No-op bila tidak berada di
 * dalam SharedTransitionLayout/NavHost atau [key] null, jadi komponen tetap bisa dipakai di mana saja.
 */
fun Modifier.sharedBounds(key: String?): Modifier = composed {
    val shared = LocalSharedScope.current
    val nav = LocalNavScope.current
    if (key == null || shared == null || nav == null) return@composed this
    with(shared) {
        this@composed.sharedElement(
            state = rememberSharedContentState(key),
            animatedVisibilityScope = nav,
            boundsTransform = SpringBounds,
        )
    }
}
