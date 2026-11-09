package dev.animeruu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.animeruu.ui.player.SeekGestureLayer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Gestur video: tap = kontrol, ketuk-dua-kali kiri/kanan = ±10 dtk, tahan = 2× lalu kembali. */
@RunWith(AndroidJUnit4::class)
class GestureLayerTest {
    @get:Rule val rule = createComposeRule()

    private var toggles = 0
    private val seeks = mutableListOf<Long>()
    private val boosts = mutableListOf<Boolean>()

    @Before fun setUp() {
        rule.setContent {
            Box(Modifier.size(400.dp, 225.dp).testTag("video")) {
                SeekGestureLayer(
                    onToggle = { toggles++ },
                    onSeek = { seeks += it },
                    onBoost = { boosts += it },
                    swipeEnabled = false,
                )
            }
        }
    }

    @Test fun singleTapTogglesControls() {
        rule.onNodeWithTag("video").performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(1, toggles)
        assertEquals(emptyList<Long>(), seeks)
        assertEquals(emptyList<Boolean>(), boosts)
    }

    @Test fun doubleTapRightSeeksForward_leftSeeksBack() {
        rule.onNodeWithTag("video").performTouchInput { doubleClick(centerRight.copy(x = width * 0.8f)) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("video").performTouchInput { doubleClick(centerLeft.copy(x = width * 0.2f)) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(listOf(10_000L, -10_000L), seeks)
        assertEquals(0, toggles)
    }

    @Test fun holdBoostsThenRestores_withoutToggling() {
        rule.onNodeWithTag("video").performTouchInput { longClick(center, durationMillis = 1_500) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(listOf(true, false), boosts)
        assertEquals(0, toggles)
    }
}
