package dev.animeruu

import dev.animeruu.feature.IntroLearner
import dev.animeruu.feature.IntroRange
import dev.animeruu.feature.IntroRules
import dev.animeruu.feature.newEpisodeCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureLogicTest {
    @Test fun doubleTapChainIsLearnedAsIntro() {
        val l = IntroLearner()
        var t = 0L
        var pos = 62_000L
        repeat(9) { l.onSeek(pos, pos + 10_000, t); pos += 10_000 + 400; t += 900 } // 9× ketuk-dua-kali
        assertNull(l.poll(t)) // masih dalam jeda
        val r = l.poll(t + 5_000)!!
        assertEquals(62_000L, r.startMs)
        assertEquals(true, r.lengthMs in 85_000..100_000)
    }

    @Test fun singleScrubIsLearned() {
        val l = IntroLearner()
        l.onSeek(30_000, 120_000, 1_000)
        assertEquals(IntroRange(30_000, 120_000), l.poll(10_000))
    }

    @Test fun ignoresSmallBackwardOrLateSeeks() {
        val l = IntroLearner()
        l.onSeek(30_000, 40_000, 0); assertNull(l.poll(10_000))          // terlalu pendek
        l.onSeek(120_000, 30_000, 20_000); assertNull(l.poll(30_000))    // mundur
        l.onSeek(900_000, 990_000, 40_000); assertNull(l.poll(50_000))   // di tengah episode
        l.onSeek(10_000, 600_000, 60_000); assertNull(l.poll(70_000))    // lompat jauh, bukan intro
    }

    @Test fun offerOnlyWhenNewOrDifferent() {
        val known = IntroRange(60_000, 150_000)
        assertEquals(true, IntroRules.shouldOffer(IntroRange(30_000, 120_000), null))
        assertEquals(false, IntroRules.shouldOffer(IntroRange(62_000, 152_000), known))   // hampir sama: jangan tanya
        assertEquals(true, IntroRules.shouldOffer(IntroRange(80_000, 175_000), known))    // bergeser: tawarkan perbarui
        assertEquals(false, IntroRules.shouldOffer(IntroRange(0, 65_000), known))         // lompat MENUJU intro
    }

    @Test fun markStartAndEnd() {
        // Tanpa data: ujung lain diisi ±85 dtk.
        assertEquals(IntroRange(40_000, 125_000), IntroRules.markStart(null, 40_000))
        assertEquals(IntroRange(45_000, 130_000), IntroRules.markEnd(null, 130_000))
        assertEquals(IntroRange(0, 60_000), IntroRules.markEnd(null, 60_000))
        // Dengan data: ujung lain dipertahankan.
        val r = IntroRules.markStart(null, 40_000)
        assertEquals(IntroRange(40_000, 131_000), IntroRules.markEnd(r, 131_000))
        assertEquals(IntroRange(38_000, 125_000), IntroRules.markStart(r, 38_000))
        // Menandai mulai SETELAH akhir lama → akhir lama dibuang.
        assertEquals(IntroRange(200_000, 285_000), IntroRules.markStart(r, 200_000))
    }

    @Test fun autoSkipOnlyAtIntroStart() {
        val r = IntroRange(60_000, 150_000)
        assertEquals(true, IntroRules.inAutoSkipWindow(r, 60_200))
        assertEquals(false, IntroRules.inAutoSkipWindow(r, 100_000)) // sengaja mundur ke tengah intro
        assertEquals(false, IntroRules.inAutoSkipWindow(r, 20_000))
    }

    @Test fun newEpisodeDiff() {
        assertEquals(0, newEpisodeCount(null, 1180.0)) // baseline pertama: diam
        assertEquals(0, newEpisodeCount(1180.0, 1180.0))
        assertEquals(1, newEpisodeCount(1180.0, 1181.0))
        assertEquals(3, newEpisodeCount(1180.0, 1183.0))
        assertEquals(1, newEpisodeCount(1071.0, 1071.5))
    }
}
