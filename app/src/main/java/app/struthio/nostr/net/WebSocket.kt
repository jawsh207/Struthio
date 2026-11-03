package app.struthio.nostr.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal RFC 6455 client: text messages, fragmentation, ping and pong. One daemon thread per
 * connection reads; [send] writes from the caller's thread, so call it off the main thread.
 */
class WebSocket(
    private val url: String,
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
    private val idleTimeoutMs: Int = 45_000,
) {
    private val random = SecureRandom()
    private val writeLock = Any()

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closing = false

    private var closedNotified = false

    fun connect() {
        Thread({ run() }, "ws").apply { isDaemon = true }.start()
    }

    private fun run() {
        var failure: Throwable? = null
        try {
            val uri = URI(url)
            val secure = uri.scheme == "wss"
            val host = uri.host ?: throw IOException("no host in $url")
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val plain = Socket()
            socket = plain
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plain.soTimeout = CONNECT_TIMEOUT_MS
            val sock = if (secure) upgradeTls(plain, host, port) else plain
            socket = sock
            val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
            val out = BufferedOutputStream(sock.getOutputStream(), 16 * 1024)
            handshake(uri, host, port, secure, input, out)
            output = out
            sock.soTimeout = idleTimeoutMs
            onOpen()
            readLoop(input)
        } catch (e: Throwable) {
            if (!closing) failure = e
        } finally {
            shutdown()
            synchronized(writeLock) {
                if (closedNotified) return
                closedNotified = true
            }
            onClosed(failure)
        }
    }

    private fun upgradeTls(plain: Socket, host: String, port: Int): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(plain, host, port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(SNIHostName(host))
        }
        tls.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
            throw IOException("certificate does not match $host")
        }
        return tls
    }

    private fun handshake(uri: URI, host: String, port: Int, secure: Boolean, input: InputStream, out: OutputStream) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val hostHeader = if (port == (if (secure) 443 else 80)) host else "$host:$port"
        val request = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        out.write(request.toByteArray())
        out.flush()

        val status = readLine(input)
        if (status.split(' ').getOrNull(1) != "101") throw IOException("handshake refused: $status")
        var accept: String? = null
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).equals("sec-websocket-accept", ignoreCase = true)) {
                accept = line.substring(colon + 1).trim()
            }
        }
        if (accept != acceptFor(key)) throw IOException("bad accept header")
    }

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8192) throw IOException("header too long")
        }
        return out.toString("UTF-8")
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n < 0) throw EOFException()
            offset += n
        }
    }

    /** A read that survives idle timeouts between frames by pinging, and gives up after a silent ping. */
    private fun firstByte(input: InputStream): Int {
        var pinged = false
        while (true) {
            try {
                return input.read().also { if (it < 0) throw EOFException() }
            } catch (e: SocketTimeoutException) {
                if (pinged) throw e
                pinged = true
                writeFrame(OP_PING, ByteArray(0))
            }
        }
    }

    private fun readLoop(input: InputStream) {
        var message: ByteArrayOutputStream? = null
        val header = ByteArray(8)
        while (true) {
            val b0 = firstByte(input)
            readFully(input, header, 1)
            val b1 = header[0].toInt() and 0xff
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0f
            if (b0 and 0x70 != 0 || b1 and 0x80 != 0) throw IOException("invalid frame")
            var length = (b1 and 0x7f).toLong()
            if (length == 126L) {
                readFully(input, header, 2)
                length = ((header[0].toLong() and 0xff) shl 8) or (header[1].toLong() and 0xff)
            } else if (length == 127L) {
                readFully(input, header, 8)
                length = header.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xff) }
            }
            if (length < 0 || length > MAX_MESSAGE) throw IOException("message too large")
            val payload = ByteArray(length.toInt())
            readFully(input, payload, payload.size)
            when (opcode) {
                OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                    if (fin && message == null) {
                        if (opcode == OP_TEXT) onText(String(payload))
                    } else {
                        val buffer = message ?: ByteArrayOutputStream().also { message = it }
                        if (buffer.size() + payload.size > MAX_MESSAGE) throw IOException("message too large")
                        buffer.write(payload)
                        if (fin) {
                            onText(buffer.toString("UTF-8"))
                            message = null
                        }
                    }
                }
                OP_PING -> writeFrame(OP_PONG, payload)
                OP_PONG -> Unit
                OP_CLOSE -> {
                    closing = true
                    runCatching { writeFrame(OP_CLOSE, ByteArray(0)) }
                    return
                }
                else -> throw IOException("unknown opcode $opcode")
            }
        }
    }

    /** Sends a text message; false when the socket is not open. */
    fun send(text: String): Boolean = try {
        writeFrame(OP_TEXT, text.toByteArray())
        true
    } catch (_: IOException) {
        false
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val out = output ?: throw IOException("not connected")
            val head = ByteArray(14)
            var n = 0
            head[n++] = (0x80 or opcode).toByte()
            when {
                payload.size < 126 -> head[n++] = (0x80 or payload.size).toByte()
                payload.size < 65536 -> {
                    head[n++] = (0x80 or 126).toByte()
                    head[n++] = (payload.size ushr 8).toByte()
                    head[n++] = payload.size.toByte()
                }
                else -> {
                    head[n++] = (0x80 or 127).toByte()
                    repeat(4) { head[n++] = 0 }
                    for (shift in intArrayOf(24, 16, 8, 0)) head[n++] = (payload.size ushr shift).toByte()
                }
            }
            val mask = ByteArray(4).also(random::nextBytes)
            mask.copyInto(head, n)
            n += 4
            val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
            out.write(head, 0, n)
            out.write(masked)
            out.flush()
        }
    }

    /** Starts a clean close; [onClosed] follows from the reader thread. */
    fun close() {
        closing = true
        runCatching { writeFrame(OP_CLOSE, ByteArray(0)) }
        shutdown()
    }

    private fun shutdown() {
        output = null
        runCatching { socket?.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_MESSAGE = 4 * 1024 * 1024L
        private const val OP_CONTINUATION = 0
        private const val OP_TEXT = 1
        private const val OP_BINARY = 2
        private const val OP_CLOSE = 8
        private const val OP_PING = 9
        private const val OP_PONG = 10

        fun acceptFor(key: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
    }
}
