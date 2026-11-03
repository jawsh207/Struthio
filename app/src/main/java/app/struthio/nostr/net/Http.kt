package app.struthio.nostr.net

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTPS through the platform stack. */
object Http {
    private const val MAX_JSON = 256 * 1024
    private const val MAX_FILE = 32 * 1024 * 1024
    private const val PAGE_START = 96 * 1024

    private fun open(url: String, followRedirects: Boolean): HttpURLConnection {
        require(url.startsWith("https://")) { "only https is allowed" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", "Struthio")
        }
    }

    private fun read(conn: HttpURLConnection, limit: Int): ByteArray = conn.inputStream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw IOException("response too large")
        }
        out.toByteArray()
    }

    /**
     * Reads a whole response. The connection is only dropped on failure: fully read, it goes back
     * to the pool, and the next request to that server skips the connection and TLS setup.
     */
    private fun fetch(conn: HttpURLConnection, limit: Int): ByteArray = try {
        if (conn.responseCode !in 200..299) throw IOException(conn.getHeaderField("X-Reason") ?: "HTTP ${conn.responseCode}")
        read(conn, limit)
    } catch (e: IOException) {
        conn.disconnect()
        throw e
    }

    /** NIP-05 forbids following redirects, so that is the caller's choice. */
    fun json(url: String, followRedirects: Boolean = true): JSONObject =
        JSONObject(String(fetch(open(url, followRedirects), MAX_JSON)))

    fun bytes(url: String): ByteArray = fetch(open(url, true), MAX_FILE)

    class Page(val type: String, val html: String?)

    /**
     * What a link points at, from its headers. Only HTML has its body read, and only the start of
     * it, so asking about a large video costs no more than the headers.
     */
    fun inspect(url: String): Page? {
        val conn = open(url, true)
        try {
            if (conn.responseCode !in 200..299) return null
            val type = conn.contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
            if (type != "text/html") return Page(type, null)
            conn.inputStream.use { input ->
                val buffer = ByteArray(PAGE_START)
                var filled = 0
                while (filled < buffer.size) {
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                return Page(type, String(buffer, 0, filled))
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Blossom upload: the body is sent as is, and the server describes what it stored. */
    fun put(url: String, body: ByteArray, type: String, headers: Map<String, String>): JSONObject {
        val conn = open(url, true)
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.readTimeout = 60_000
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Content-Type", type)
        headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        conn.outputStream.use { it.write(body) }
        return JSONObject(String(fetch(conn, MAX_JSON)))
    }

    /** Fetches [url] through a small on-disk cache. */
    fun cached(url: String, dir: File, name: String): File {
        val file = File(dir, name)
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        dir.mkdirs()
        val temp = File(dir, "$name.tmp")
        temp.writeBytes(bytes(url))
        temp.renameTo(file)
        return file
    }

    fun trimCache(dir: File, maxBytes: Long) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > maxBytes) file.delete()
        }
    }
}
