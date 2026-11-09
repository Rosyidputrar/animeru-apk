package dev.animeruu

import dev.animeruu.update.GhAsset
import dev.animeruu.update.GhRelease
import dev.animeruu.update.Version
import dev.animeruu.update.toInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private fun v(s: String) = Version.parse(s)!!

    @Test fun versionOrdering() {
        assertTrue(v("v1.1.0") > v("1.0.0"))
        assertTrue(v("1.0.10") > v("1.0.9"))
        assertTrue(v("1.0.0") > v("1.0.0-ci.14"))   // build CI < rilis bernomor sama
        assertTrue(v("1.1.0-ci.3") > v("1.0.0"))
        assertEquals(0, v("1.0").compareTo(v("1.0.0")))
        assertNull(Version.parse("latest"))
    }

    @Test fun releaseNeedsApkAndSkipsPrerelease() {
        val apk = GhAsset("Animeruu-1.1.0.apk", "https://x/a.apk", 2_500_000, digest = "sha256:ABCD")
        val r = GhRelease("v1.1.0", "", "## What's Changed\n* PiP by @me in https://github.com/o/r/pull/1\n\n**Full Changelog**: https://github.com/o/r/compare/a...b", assets = listOf(apk)).toInfo()!!
        assertEquals("1.1.0", r.version)
        assertEquals("abcd", r.sha256)
        assertEquals("What's Changed\n• PiP", r.notes)
        assertNull(GhRelease("v1.2.0", assets = emptyList()).toInfo())
        assertNull(GhRelease("v1.2.0", prerelease = true, assets = listOf(apk)).toInfo())
    }
}
