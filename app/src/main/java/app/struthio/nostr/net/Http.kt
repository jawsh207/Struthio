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

    /** NIP-05 forbids following redirects, so that is the caller's choice. */
    fun json(url: String, followRedirects: Boolean = true): JSONObject {
        val conn = open(url, followRedirects)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return JSONObject(String(read(conn, MAX_JSON)))
        } finally {
            conn.disconnect()
        }
    }

    fun bytes(url: String): ByteArray {
        val conn = open(url, true)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return read(conn, MAX_FILE)
        } finally {
            conn.disconnect()
        }
    }

    /** Blossom upload: the body is sent as is, and the server describes what it stored. */
    fun put(url: String, body: ByteArray, type: String, headers: Map<String, String>): JSONObject {
        val conn = open(url, true)
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.readTimeout = 60_000
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", type)
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode !in 200..299) {
                throw IOException(conn.getHeaderField("X-Reason") ?: "HTTP ${conn.responseCode}")
            }
            return JSONObject(String(read(conn, MAX_JSON)))
        } finally {
            conn.disconnect()
        }
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
