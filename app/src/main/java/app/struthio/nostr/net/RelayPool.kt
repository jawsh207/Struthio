package app.struthio.nostr.net

import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.Signer
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.quoteJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class Subscription internal constructor(
    val id: String,
    val relays: Set<String>,
    val filters: List<Filter>,
    internal val onEvent: (Event, String) -> Unit,
    internal val onDone: (String) -> Unit,
) {
    internal val request: String by lazy {
        buildString {
            append("[\"REQ\",").append(quoteJson(id))
            filters.forEach { append(',').append(it.toJson()) }
            append(']')
        }
    }
}

data class PublishResult(val ok: Boolean, val message: String)

/** All relay connections. Sockets exist only while something needs them and the app is in front. */
class RelayPool(
    private val scope: CoroutineScope,
    private val signer: () -> Signer?,
) {
    private val relays = ConcurrentHashMap<String, Relay>()
    private val nextId = AtomicInteger()
    private val verified = object : LinkedHashMap<String, Boolean>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 8000
    }

    @Volatile private var foreground = true

    private fun relay(url: String) = relays.getOrPut(url) { Relay(url) }

    fun subscribe(
        urls: Set<String>,
        filters: List<Filter>,
        onDone: (String) -> Unit = {},
        onEvent: (Event, String) -> Unit,
    ): Subscription {
        val sub = Subscription("s${nextId.incrementAndGet()}", urls, filters, onEvent, onDone)
        urls.forEach { relay(it).add(sub) }
        return sub
    }

    fun close(sub: Subscription) {
        sub.relays.forEach { relays[it]?.remove(sub) }
    }

    /** Fetches stored events and returns how many relays answered. */
    suspend fun query(
        urls: Set<String>,
        filters: List<Filter>,
        timeoutMs: Long = 6_000,
        onEvent: (Event, String) -> Unit,
    ): Int {
        if (urls.isEmpty()) return 0
        val remaining = HashSet(urls)
        val answered = AtomicInteger()
        val done = CompletableDeferred<Unit>()
        val sub = subscribe(urls, filters, onDone = { url ->
            val finished = synchronized(remaining) {
                if (remaining.remove(url)) answered.incrementAndGet()
                remaining.isEmpty()
            }
            if (finished) done.complete(Unit)
        }, onEvent = onEvent)
        withTimeoutOrNull(timeoutMs) { done.await() }
        close(sub)
        return answered.get()
    }

    suspend fun publish(event: Event, urls: Set<String>, timeoutMs: Long = 8_000): Map<String, PublishResult> {
        if (urls.isEmpty()) return emptyMap()
        val pending = urls.associateWith { relay(it).publish(event) }
        withTimeoutOrNull(timeoutMs) { pending.values.awaitAll() }
        return pending.mapValues { (url, deferred) ->
            relays[url]?.forget(event.id)
            if (deferred.isCompleted) deferred.getCompleted() else PublishResult(false, "timed out")
        }
    }

    fun pause() {
        foreground = false
        relays.values.forEach { it.disconnect() }
    }

    fun resume() {
        foreground = true
        relays.values.forEach { it.wake() }
    }

    private fun accept(event: Event): Boolean {
        if (!event.hasValidId()) return false
        synchronized(verified) { if (verified.containsKey(event.id)) return true }
        if (!event.hasValidSignature()) return false
        synchronized(verified) { verified[event.id] = true }
        return true
    }

    private class Pending(val event: Event, val result: CompletableDeferred<PublishResult>) {
        var retriedAuth = false
    }

    private inner class Relay(val url: String) {
        private val lock = Any()
        private val subs = LinkedHashMap<String, Subscription>()
        private val pending = LinkedHashMap<String, Pending>()
        private var socket: WebSocket? = null
        private var open = false
        private var failures = 0
        private var challenge: String? = null
        private var authEventId: String? = null
        private val waitingForAuth = LinkedHashSet<String>()
        private var idleJob: Job? = null
        private var retryJob: Job? = null

        fun add(sub: Subscription) {
            val send = synchronized(lock) {
                subs[sub.id] = sub
                idleJob?.cancel()
                ensureConnected()
                open
            }
            if (send) write(sub.request)
        }

        fun remove(sub: Subscription) {
            val wasOpen = synchronized(lock) {
                val found = subs.remove(sub.id) != null
                scheduleIdleClose()
                found && open
            }
            if (wasOpen) write("[\"CLOSE\",${quoteJson(sub.id)}]")
        }

        fun publish(event: Event): CompletableDeferred<PublishResult> {
            val deferred = CompletableDeferred<PublishResult>()
            val send = synchronized(lock) {
                pending[event.id] = Pending(event, deferred)
                idleJob?.cancel()
                ensureConnected()
                open
            }
            if (send) write("[\"EVENT\",${event.toJson()}]")
            return deferred
        }

        fun forget(id: String) {
            synchronized(lock) {
                pending.remove(id)
                scheduleIdleClose()
            }
        }

        private fun write(text: String) {
            scope.launch { socket?.send(text) }
        }

        private fun ensureConnected() {
            if (socket != null || !foreground) return
            val ws = WebSocket(url, onOpen = ::opened, onText = ::message, onClosed = ::closed)
            socket = ws
            ws.connect()
        }

        private fun scheduleIdleClose() {
            if (subs.isNotEmpty() || pending.isNotEmpty()) return
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(IDLE_CLOSE_MS)
                val ws = synchronized(lock) {
                    if (subs.isEmpty() && pending.isEmpty()) socket else null
                }
                ws?.close()
            }
        }

        private fun opened() {
            val requests = synchronized(lock) {
                open = true
                failures = 0
                subs.values.map { it.request } + pending.values.map { "[\"EVENT\",${it.event.toJson()}]" }
            }
            requests.forEach { socket?.send(it) }
        }

        private fun closed(error: Throwable?) {
            val failed: List<Pending>
            val current: List<Subscription>
            val retry: Boolean
            synchronized(lock) {
                val wasOpen = open
                open = false
                socket = null
                challenge = null
                authEventId = null
                waitingForAuth.clear()
                current = subs.values.toList()
                failed = pending.values.toList()
                pending.clear()
                if (!wasOpen || error != null) failures++
                retry = current.isNotEmpty() && foreground
                if (retry) {
                    val wait = minOf(300L, 2L shl minOf(failures, 8)) * 1000
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(wait)
                        synchronized(lock) { if (subs.isNotEmpty()) ensureConnected() }
                    }
                }
            }
            failed.forEach { it.result.complete(PublishResult(false, error?.message ?: "connection closed")) }
            current.forEach { it.onDone(url) }
        }

        fun disconnect() {
            val ws = synchronized(lock) {
                retryJob?.cancel()
                socket
            }
            ws?.close()
        }

        fun wake() {
            synchronized(lock) { if (subs.isNotEmpty()) ensureConnected() }
        }

        private fun message(text: String) {
            val msg = try {
                JSONArray(text)
            } catch (_: Exception) {
                return
            }
            when (msg.optString(0)) {
                "EVENT" -> {
                    val sub = synchronized(lock) { subs[msg.optString(1)] } ?: return
                    val event = msg.optJSONObject(2)?.let(Event::parse) ?: return
                    if (accept(event)) sub.onEvent(event, url)
                }
                "EOSE" -> synchronized(lock) { subs[msg.optString(1)] }?.onDone?.invoke(url)
                "OK" -> ok(msg.optString(1), msg.optBoolean(2), msg.optString(3))
                "CLOSED" -> {
                    val id = msg.optString(1)
                    val reason = msg.optString(2)
                    val sub = synchronized(lock) { subs[id] } ?: return
                    if (reason.startsWith("auth-required") && signer() != null) {
                        synchronized(lock) { waitingForAuth += id }
                        authenticate()
                    } else {
                        sub.onDone(url)
                    }
                }
                "AUTH" -> {
                    synchronized(lock) { challenge = msg.optString(1) }
                }
            }
        }

        private fun ok(id: String, accepted: Boolean, message: String) {
            val isAuth = synchronized(lock) { id == authEventId }
            if (isAuth) {
                if (accepted) replayAfterAuth() else failAuth(message)
                return
            }
            val item = synchronized(lock) { pending[id] } ?: return
            if (!accepted && message.startsWith("auth-required") && !item.retriedAuth && signer() != null) {
                item.retriedAuth = true
                authenticate()
                return
            }
            synchronized(lock) { pending.remove(id) }
            item.result.complete(PublishResult(accepted, message))
        }

        private fun replayAfterAuth() {
            val requests = synchronized(lock) {
                authEventId = null
                val subRequests = waitingForAuth.mapNotNull { subs[it]?.request }
                waitingForAuth.clear()
                subRequests + pending.values.filter { it.retriedAuth }.map { "[\"EVENT\",${it.event.toJson()}]" }
            }
            requests.forEach { socket?.send(it) }
        }

        private fun failAuth(message: String) {
            val affected: List<Subscription>
            val failed: List<Pending>
            synchronized(lock) {
                authEventId = null
                affected = waitingForAuth.mapNotNull { subs[it] }
                waitingForAuth.clear()
                failed = pending.values.filter { it.retriedAuth }
                failed.forEach { pending.remove(it.event.id) }
            }
            affected.forEach { it.onDone(url) }
            failed.forEach { it.result.complete(PublishResult(false, message)) }
        }

        /** NIP-42, only when a relay demands it, so the user's key is not shown to every relay. */
        private fun authenticate() {
            val token = synchronized(lock) {
                if (authEventId != null) return
                authEventId = "pending"
                challenge
            }
            val who = signer()
            if (token == null || who == null) {
                failAuth("auth-required: no challenge")
                return
            }
            scope.launch {
                try {
                    val auth = who.sign(
                        Draft(who.pubkey, nowSeconds(), Kind.AUTH, listOf(listOf("relay", url), listOf("challenge", token)), ""),
                    )
                    synchronized(lock) { authEventId = auth.id }
                    socket?.send("[\"AUTH\",${auth.toJson()}]")
                } catch (e: Exception) {
                    failAuth("auth-required: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val IDLE_CLOSE_MS = 45_000L
    }
}
