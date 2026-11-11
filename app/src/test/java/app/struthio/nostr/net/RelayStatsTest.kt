package app.struthio.nostr.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayStatsTest {
    @Test
    fun countsDataAndTriesAndSurvivesSaving() {
        var saved: String? = null
        val stats = RelayStats(load = { null }, save = { saved = it })
        assertNull(stats.summary("wss://a").uptime)
        stats.traffic("wss://a", 3_000, 200)
        stats.traffic("wss://a", 1_000, 0)
        stats.attempt("wss://a", true)
        stats.attempt("wss://a", true)
        stats.attempt("wss://a", true)
        stats.attempt("wss://a", false)
        stats.flush()
        val again = RelayStats(load = { saved }, save = {})
        val s = again.summary("wss://a")
        assertEquals(4_000L, s.down)
        assertEquals(200L, s.up)
        assertEquals(75, s.uptime)
        assertEquals(0, again.summary("wss://b").tries)
    }

    @Test
    fun sizesReadNaturally() {
        assertEquals("1 KB", formatBytes(10))
        assertEquals("98 KB", formatBytes(100_000))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("2.00 GB", formatBytes(2_147_483_648))
    }
}
