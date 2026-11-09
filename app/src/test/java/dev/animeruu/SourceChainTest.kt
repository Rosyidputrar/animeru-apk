package dev.animeruu

import dev.animeruu.domain.SourceChain
import dev.animeruu.domain.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceChainTest {
    private fun s(q: String, h: Int?, direct: Boolean = true) = StreamSource(q, "S", q, h, "https://x/$q", null, direct)

    @Test fun autoOnWifiPrefers720ThenLowerThenHigher() {
        val out = SourceChain.order(listOf(s("360p", 360), s("1080p", 1080), s("480p", 480), s("720p", 720), s("embed", 480, false)), 0, true)
        assertEquals(listOf("720p", "480p", "360p", "1080p"), out.map { it.id })
    }

    @Test fun cellularDefaultsTo480AndDropsEmbeds() {
        val out = SourceChain.order(listOf(s("720p", 720), s("480p", 480), s("XX0p", null), s("e", 480, false)), 0, false)
        assertEquals(listOf("480p", "720p", "XX0p"), out.map { it.id })
    }
}
