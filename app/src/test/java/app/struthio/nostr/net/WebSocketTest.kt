package app.struthio.nostr.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises the client against a tiny server that speaks the wire format directly. */
class WebSocketTest {
    private fun OutputStream.frame(first: Int, payload: ByteArray) {
        write(first)
        when {
            payload.size < 126 -> write(payload.size)
            payload.size < 65536 -> {
                write(126); write(payload.size ushr 8); write(payload.size)
            }
            else -> {
                write(127)
                repeat(4) { write(0) }
                for (s in intArrayOf(24, 16, 8, 0)) write(payload.size ushr s)
            }
        }
        write(payload)
        flush()
    }

    private fun DataInputStream.clientFrame(): Pair<Int, ByteArray> {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        assertTrue("client frames must be masked", b1 and 0x80 != 0)
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = readUnsignedShort().toLong() else if (len == 127L) len = readLong()
        val mask = ByteArray(4).also(::readFully)
        val data = ByteArray(len.toInt()).also(::readFully)
        return (b0 and 0x0f) to ByteArray(data.size) { (data[it].toInt() xor mask[it and 3].toInt()).toByte() }
    }

    @Test
    fun handshakeMessagesFragmentsPingsAndClose() {
        val server = ServerSocket(0)
        val big = "x".repeat(70_000)
        val received = LinkedBlockingQueue<String>()
        val serverSaw = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        Thread {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val lines = ArrayList<String>()
                val sb = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c == '\n'.code) {
                        val line = sb.toString().trim()
                        sb.clear()
                        if (line.isEmpty()) break
                        lines += line
                    } else {
                        sb.append(c.toChar())
                    }
                }
                val key = lines.first { it.startsWith("Sec-WebSocket-Key") }.substringAfter(": ")
                val out = s.getOutputStream()
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${WebSocket.acceptFor(key)}\r\n\r\n".toByteArray())
                out.flush()
                serverSaw.put(input.clientFrame())
                out.frame(0x81, "héllo".toByteArray())
                out.frame(0x01, "frag".toByteArray())
                out.frame(0x89, "p".toByteArray())
                out.frame(0x00, "men".toByteArray())
                out.frame(0x80, "ted".toByteArray())
                out.frame(0x81, big.toByteArray())
                serverSaw.put(input.clientFrame())
                out.frame(0x88, ByteArray(0))
                serverSaw.put(input.clientFrame())
            }
        }.apply { isDaemon = true }.start()

        val closed = CountDownLatch(1)
        var failure: Throwable? = null
        lateinit var ws: WebSocket
        ws = WebSocket(
            "ws://127.0.0.1:${server.localPort}/",
            onOpen = { ws.send("client says hi") },
            onText = { received.put(it) },
            onClosed = {
                failure = it
                closed.countDown()
            },
        )
        ws.connect()

        val first = serverSaw.poll(5, TimeUnit.SECONDS)!!
        assertEquals(1, first.first)
        assertEquals("client says hi", String(first.second))
        assertEquals("héllo", received.poll(5, TimeUnit.SECONDS))
        assertEquals("fragmented", received.poll(5, TimeUnit.SECONDS))
        assertEquals(big.length, received.poll(5, TimeUnit.SECONDS)!!.length)
        val pong = serverSaw.poll(5, TimeUnit.SECONDS)!!
        assertEquals(10, pong.first)
        assertEquals("p", String(pong.second))
        assertEquals(8, serverSaw.poll(5, TimeUnit.SECONDS)!!.first)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertNull(failure)
        server.close()
    }

    @Test
    fun refusedHandshakeReportsFailure() {
        val server = ServerSocket(0)
        Thread {
            server.accept().use { s ->
                s.getInputStream().read()
                s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
            }
        }.apply { isDaemon = true }.start()
        val closed = CountDownLatch(1)
        var failure: Throwable? = null
        WebSocket("ws://127.0.0.1:${server.localPort}/", {}, {}, {
            failure = it
            closed.countDown()
        }).connect()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(failure != null)
        server.close()
    }
}
