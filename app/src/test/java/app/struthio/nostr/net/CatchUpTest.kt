package app.struthio.nostr.net

import app.struthio.nostr.core.Filter
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CatchUpTest {
    private fun sub(vararg filters: Filter) = Subscription("s1", setOf("wss://a.example"), filters.toList(), { _, _ -> }, { _, _ -> })

    @Test
    fun catchUpAsksOnlyForNewerEvents() {
        val req = JSONArray(sub(Filter(kinds = listOf(1), limit = 50), Filter(kinds = listOf(7), since = 2_000)).requestSince(1_000))
        assertEquals("REQ", req.getString(0))
        assertEquals(1_000L, req.getJSONObject(2).getLong("since"))
        assertEquals(50, req.getJSONObject(2).getInt("limit"))
        // A filter already asking for later events keeps its own start.
        assertEquals(2_000L, req.getJSONObject(3).getLong("since"))
    }

    @Test
    fun lookupsByIdAreLeftAlone() {
        val req = JSONArray(sub(Filter(ids = listOf("ab"))).requestSince(1_000))
        assertFalse(req.getJSONObject(2).has("since"))
    }
}
