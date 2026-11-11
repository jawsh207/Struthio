package app.struthio.nostr.data

import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Hex
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.LocalSigner
import app.struthio.nostr.core.Nip44
import app.struthio.nostr.core.Signer
import app.struthio.nostr.core.normalizeRelay
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.net.AppLog
import app.struthio.nostr.net.RelayPool
import app.struthio.nostr.net.Subscription
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * NIP-46: the key lives in a remote signer ("bunker"), reached over relays. Struthio keeps only a
 * throwaway client key for talking to it; every message is NIP-44 encrypted, kind 24133.
 */
class RemoteSigner(
    private val pool: RelayPool,
    private val clientSecret: ByteArray,
    val remote: String,
    relays: List<String>,
    /** The user's key, learned from `get_public_key`; not necessarily the signer's own key. */
    override val pubkey: String,
    private val onAuthUrl: (String) -> Unit,
) : Signer {
    @Volatile var relays: List<String> = relays
        private set

    private val client = LocalSigner(clientSecret)
    private val session = Session(pool, clientSecret, remote, relays, onAuthUrl)

    val clientPubkey: String get() = client.pubkey

    fun close() = session.close()

    /** Lets the signer move the connection to relays it prefers, as the NIP asks clients to. */
    suspend fun switchRelays(): List<String>? = session.switchRelays()?.also { relays = it }

    /** A courtesy so the signer can drop this session; the client key is deleted either way. */
    suspend fun logout() {
        runCatching { session.request("logout", emptyList(), 5_000) }
        session.close()
    }

    override suspend fun sign(draft: Draft, background: Boolean): Event {
        val unsigned = JSONObject()
            .put("kind", draft.kind)
            .put("content", draft.content)
            .put("tags", JSONArray(draft.tags.map { JSONArray(it) }))
            .put("created_at", draft.createdAt)
        val reply = session.request("sign_event", listOf(unsigned.toString()))
        val event = Event.parse(reply) ?: throw UserError("The remote signer sent back something that isn't an event.")
        // Never trust what comes back: it must be validly signed, by this account, and be what was asked.
        if (!event.isValid() || event.pubkey != pubkey || event.id != draft.id) {
            throw UserError("The remote signer returned an event that doesn't match what was asked.")
        }
        return event
    }

    override suspend fun nip44Encrypt(plaintext: String, peer: String): String =
        session.request("nip44_encrypt", listOf(peer, plaintext))

    override suspend fun nip44Decrypt(payload: String, peer: String, background: Boolean): String =
        session.request("nip44_decrypt", listOf(peer, payload))

    /** The conversation with one signer: a listener on its relays, and requests waiting for answers. */
    class Session(
        private val pool: RelayPool,
        clientSecret: ByteArray,
        private val remote: String,
        relays: List<String>,
        private val onAuthUrl: (String) -> Unit,
    ) {
        private val client = LocalSigner(clientSecret)
        private val conversation = Nip44.conversationKey(clientSecret, remote)
        private val waiting = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        @Volatile private var relays = relays
        private var listener: Subscription? = null

        init {
            listen()
        }

        private fun listen() {
            listener?.let(pool::close)
            listener = pool.subscribe(
                relays.toSet(),
                listOf(Filter(authors = listOf(remote), kinds = listOf(Kind.NOSTR_CONNECT), tags = mapOf("p" to listOf(client.pubkey)), since = nowSeconds() - 60)),
            ) { event, _ ->
                val reply = runCatching { JSONObject(Nip44.decrypt(event.content, conversation)) }.getOrNull() ?: return@subscribe
                val id = reply.optString("id")
                // An auth challenge: the signer wants the user to approve on a web page first. The answer
                // to the same request comes later, so the request keeps waiting.
                if (reply.optString("result") == "auth_url" && reply.has("error")) {
                    AppLog.add("remote signer asked for approval on a web page")
                    onAuthUrl(reply.optString("error"))
                    return@subscribe
                }
                waiting.remove(id)?.complete(reply)
            }
        }

        fun close() {
            listener?.let(pool::close)
            listener = null
            waiting.values.forEach { it.cancel() }
            waiting.clear()
        }

        suspend fun request(method: String, params: List<String>, timeoutMs: Long = REQUEST_MS): String {
            val id = Hex.encode(ByteArray(8).also { random.nextBytes(it) })
            val body = JSONObject().put("id", id).put("method", method).put("params", JSONArray(params))
            val draft = Draft(client.pubkey, nowSeconds(), Kind.NOSTR_CONNECT, listOf(listOf("p", remote)), Nip44.encrypt(body.toString(), conversation))
            val answer = CompletableDeferred<JSONObject>()
            waiting[id] = answer
            try {
                val sent = pool.publishFirst(client.signNow(draft), relays.toSet())
                if (!sent.ok) throw UserError("Couldn't reach your remote signer's relays.")
                // Approving on a web page can take a while, so an auth challenge earns a longer wait.
                val reply = withTimeoutOrNull(timeoutMs) { answer.await() }
                    ?: throw UserError("Your remote signer didn't answer. Check that it's online.")
                reply.optString("error").takeIf { it.isNotBlank() }?.let { throw UserError("Remote signer: $it") }
                return reply.optString("result")
            } finally {
                waiting.remove(id)
            }
        }

        /** `switch_relays`: the signer's own relays, or null to keep these. */
        suspend fun switchRelays(): List<String>? {
            val result = runCatching { request("switch_relays", emptyList(), 10_000) }.getOrNull() ?: return null
            if (result.isBlank() || result == "null") return null
            val list = runCatching { JSONArray(result) }.getOrNull() ?: return null
            val next = (0 until list.length()).mapNotNull { normalizeRelay(list.optString(it)) }.distinct()
            if (next.isEmpty() || next.toSet() == relays.toSet()) return null
            relays = next
            listen()
            return next
        }
    }

    companion object {
        const val REQUEST_MS = 120_000L
        private val random = SecureRandom()

        /** What Struthio asks the signer to allow, so it needn't ask about each kind separately. */
        const val PERMISSIONS = "sign_event,nip44_encrypt,nip44_decrypt,get_public_key,switch_relays"

        /** Shown by the signer when it lists connected apps. */
        val METADATA: String = JSONObject().put("name", "Struthio").put("url", "https://github.com/jawsh207/Struthio").toString()

        fun newClientKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

        fun randomSecret(): String = Hex.encode(ByteArray(8).also { random.nextBytes(it) })

        /** A `bunker://` link: the signer's key, its relays and a one-time secret. */
        data class Bunker(val remote: String, val relays: List<String>, val secret: String?)

        fun parseBunker(text: String): Bunker? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("bunker://")) return null
            val rest = trimmed.removePrefix("bunker://")
            val remote = rest.substringBefore('?').lowercase()
            if (!Hex.isKey(remote)) return null
            val query = rest.substringAfter('?', "")
            val params = query.split('&').filter { '=' in it }.map {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val relays = params.filter { it.first == "relay" }.mapNotNull { normalizeRelay(it.second) }.distinct()
            if (relays.isEmpty()) return null
            return Bunker(remote, relays, params.firstOrNull { it.first == "secret" }?.second?.takeIf { it.isNotBlank() })
        }

        /** The `nostrconnect://` link Struthio shows for a signer to scan or open. */
        fun nostrConnectUri(clientPubkey: String, relays: List<String>, secret: String): String {
            fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
            return buildString {
                append("nostrconnect://").append(clientPubkey).append('?')
                append(relays.joinToString("&") { "relay=" + enc(it) })
                append("&secret=").append(enc(secret))
                append("&perms=").append(enc(PERMISSIONS))
                append("&name=").append(enc("Struthio"))
                append("&url=").append(enc("https://github.com/jawsh207/Struthio"))
            }
        }

        /**
         * Waits for a signer to answer a `nostrconnect://` link: any kind 24133 to the client key whose
         * result is the secret. The answer's author is the signer. Returns its key, or null on timeout.
         */
        suspend fun awaitNostrConnect(pool: RelayPool, clientSecret: ByteArray, relays: List<String>, secret: String, timeoutMs: Long): String? {
            val client = LocalSigner(clientSecret)
            val found = CompletableDeferred<String>()
            val sub = pool.subscribe(
                relays.toSet(),
                listOf(Filter(kinds = listOf(Kind.NOSTR_CONNECT), tags = mapOf("p" to listOf(client.pubkey)), since = nowSeconds() - 60)),
            ) { event, _ ->
                val key = runCatching { Nip44.conversationKey(clientSecret, event.pubkey) }.getOrNull() ?: return@subscribe
                val reply = runCatching { JSONObject(Nip44.decrypt(event.content, key)) }.getOrNull() ?: return@subscribe
                // The secret has to come back, so nobody else can pose as the signer.
                if (reply.optString("result") == secret) found.complete(event.pubkey)
            }
            return try {
                withTimeoutOrNull(timeoutMs) { found.await() }
            } finally {
                pool.close(sub)
            }
        }

        /** Relays used for a `nostrconnect://` link; most signers listen on these. */
        val CONNECT_RELAYS = listOf("wss://relay.nsec.app", "wss://nos.lol", "wss://relay.primal.net")
    }
}
