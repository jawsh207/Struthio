package app.struthio.nostr.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * What Zino has seen of each relay over the last [DAYS] days, kept per day: data down and up,
 * and how many connection attempts reached it. Only Zino's own use is counted, so a relay that
 * was down while the app was closed isn't known to have been.
 */
class RelayStats(load: () -> String?, private val save: (String) -> Unit) {
    data class Summary(val down: Long, val up: Long, val tries: Int, val reached: Int) {
        /** Percent of connection attempts that got through, or null before any. */
        val uptime: Int? get() = if (tries == 0) null else (reached * 100L / tries).toInt()
    }

    private class Day(var tries: Int = 0, var reached: Int = 0, var down: Long = 0, var up: Long = 0)

    private val lock = Any()
    private val relays = HashMap<String, java.util.TreeMap<Long, Day>>()
    private var dirty = false
    private var savedAt = System.currentTimeMillis()

    init {
        runCatching { load()?.let(::parse) }
    }

    private fun today() = System.currentTimeMillis() / 86_400_000L

    private inline fun update(url: String, change: Day.() -> Unit) {
        synchronized(lock) {
            val days = relays.getOrPut(url) { java.util.TreeMap() }
            val day = today()
            days.getOrPut(day) { Day() }.change()
            days.headMap(day - DAYS + 1).clear()
            dirty = true
        }
    }

    fun traffic(url: String, down: Int, up: Int) = update(url) {
        this.down += down
        this.up += up
    }

    fun attempt(url: String, reached: Boolean) {
        update(url) {
            tries++
            if (reached) this.reached++
        }
        // Kept now and then too, not only when the app is left, so a crash loses little.
        if (System.currentTimeMillis() - savedAt > SAVE_EVERY_MS) flush()
    }

    fun summary(url: String): Summary = synchronized(lock) {
        val days = relays[url]?.tailMap(today() - DAYS + 1)?.values.orEmpty()
        Summary(days.sumOf { it.down }, days.sumOf { it.up }, days.sumOf { it.tries }, days.sumOf { it.reached })
    }

    fun flush() {
        val text = synchronized(lock) {
            if (!dirty) return
            dirty = false
            savedAt = System.currentTimeMillis()
            val oldest = today() - DAYS + 1
            JSONObject().apply {
                relays.forEach { (url, days) ->
                    val list = JSONArray()
                    days.tailMap(oldest).forEach { (day, d) -> list.put(JSONArray(listOf(day, d.tries, d.reached, d.down, d.up))) }
                    if (list.length() > 0) put(url, list)
                }
            }.toString()
        }
        save(text)
    }

    private fun parse(text: String) {
        val o = JSONObject(text)
        o.keys().forEach { url ->
            val list = o.optJSONArray(url) ?: return@forEach
            val days = java.util.TreeMap<Long, Day>()
            for (i in 0 until list.length()) {
                val d = list.optJSONArray(i) ?: continue
                days[d.optLong(0)] = Day(d.optInt(1), d.optInt(2), d.optLong(3), d.optLong(4))
            }
            relays[url] = days
        }
    }

    companion object {
        const val DAYS = 30
        private const val SAVE_EVERY_MS = 5 * 60_000L
    }
}

/** Data sizes as people read them: KB below a tenth of a megabyte, then MB, then GB. */
fun formatBytes(bytes: Long): String = when {
    bytes < 102_400 -> "${(bytes + 1023) / 1024} KB"
    bytes < 1_073_741_824 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
}
