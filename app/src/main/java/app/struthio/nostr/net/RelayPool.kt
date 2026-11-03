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

    private val problems = HashMap<String, MutableList<String>>()
    private var pauseJob: Job? = null
    private val visible = AtomicInteger()

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

    /** NIP-45 counts where relays offer them; the highest answer wins because relays see partial data. */
    suspend fun count(urls: Set<String>, filter: Filter, timeoutMs: Long = 5_000): Long? {
        var best: Long? = null
        val remaining = HashSet(urls)
        val done = CompletableDeferred<Unit>()
        val id = "c${nextId.incrementAndGet()}"
        val request = "[\"COUNT\",${quoteJson(id)},${filter.toJson()}]"
        urls.forEach { url ->
            relay(url).count(id, request) { value ->
                val finished = synchronized(remaining) {
                    if (value != null) best = maxOf(best ?: 0, value)
                    remaining.remove(url)
                    remaining.isEmpty()
                }
                if (finished) done.complete(Unit)
            }
        }
        withTimeoutOrNull(timeoutMs) { done.await() }
        urls.forEach { relays[it]?.endCount(id) }
        return synchronized(remaining) { best }
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

    /**
     * Leaving the screen closes the sockets after a short grace period, because a trip to a signer
     * app or the photo picker also stops the activity and must not drop what is loading.
     */
    fun activityStopped() {
        if (visible.decrementAndGet() > 0) return
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(PAUSE_GRACE_MS)
            if (visible.get() > 0) return@launch
            foreground = false
            relays.values.forEach { it.disconnect() }
        }
    }

    /** Counted, because an old activity's stop can arrive after the next one has started. */
    fun activityStarted() {
        visible.incrementAndGet()
        pauseJob?.cancel()
        foreground = true
        relays.values.forEach { it.wake() }
    }

    /** What a relay refused or what we refused from it, for the account check. */
    fun problemsOf(url: String): List<String> = synchronized(problems) { problems[url]?.toList().orEmpty() }

    fun noDroppedLists(urls: Set<String>): Boolean = urls.none { url -> problemsOf(url).any { it.startsWith("dropped") } }

    private fun note(url: String, problem: String) {
        synchronized(problems) {
            val list = problems.getOrPut(url) { ArrayList() }
            if (problem !in list && list.size < 6) list += problem
        }
    }

    private fun accept(event: Event, url: String): Boolean {
        if (!event.hasValidId()) {
            note(url, "dropped kind ${event.kind}: id does not match its content")
            return false
        }
        synchronized(verified) { if (verified.containsKey(event.id)) return true }
        if (!event.hasValidSignature()) {
            note(url, "dropped kind ${event.kind}: bad signature")
            return false
        }
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
        private val counts = LinkedHashMap<String, Pair<String, (Long?) -> Unit>>()
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

        fun count(id: String, request: String, onResult: (Long?) -> Unit) {
            val send = synchronized(lock) {
                counts[id] = request to onResult
                idleJob?.cancel()
                ensureConnected()
                open
            }
            if (send) write(request)
        }

        fun endCount(id: String) {
            synchronized(lock) {
                counts.remove(id)
                scheduleIdleClose()
            }
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
            if (subs.isNotEmpty() || pending.isNotEmpty() || counts.isNotEmpty()) return
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(IDLE_CLOSE_MS)
                val ws = synchronized(lock) {
                    if (subs.isEmpty() && pending.isEmpty() && counts.isEmpty()) socket else null
                }
                ws?.close()
            }
        }

        private fun opened() {
            val requests = synchronized(lock) {
                open = true
                failures = 0
                subs.values.map { it.request } + pending.values.map { "[\"EVENT\",${it.event.toJson()}]" } +
                    counts.values.map { it.first }
            }
            requests.forEach { socket?.send(it) }
        }

        private fun closed(error: Throwable?) {
            error?.let { note(url, "connection: ${it.message ?: it.javaClass.simpleName}") }
            val failed: List<Pending>
            val current: List<Subscription>
            val retry: Boolean
            val waiting: List<(Long?) -> Unit>
            synchronized(lock) {
                waiting = counts.values.map { it.second }
                counts.clear()
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
            waiting.forEach { it(null) }
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
                    val raw = msg.optJSONObject(2) ?: return
                    val event = Event.parse(raw)
                    if (event == null) {
                        note(url, "dropped kind ${raw.optInt("kind", -1)}: could not be parsed")
                        return
                    }
                    if (accept(event, url)) sub.onEvent(event, url)
                }
                "COUNT" -> {
                    val item = synchronized(lock) { counts.remove(msg.optString(1)) } ?: return
                    item.second(msg.optJSONObject(2)?.optLong("count"))
                }
                "EOSE" -> synchronized(lock) { subs[msg.optString(1)] }?.onDone?.invoke(url)
                "OK" -> ok(msg.optString(1), msg.optBoolean(2), msg.optString(3))
                "CLOSED" -> {
                    val id = msg.optString(1)
                    val reason = msg.optString(2)
                    synchronized(lock) { counts.remove(id) }?.let {
                        it.second(null)
                        return
                    }
                    val sub = synchronized(lock) { subs[id] } ?: return
                    note(url, "closed: $reason")
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
                        background = true,
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
        const val PAUSE_GRACE_MS = 20_000L
    }
}
