package app.struthio.nostr.data

import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.LocalSigner
import app.struthio.nostr.core.Nip44
import app.struthio.nostr.core.NwcUri
import app.struthio.nostr.core.Tags
import app.struthio.nostr.core.first
import app.struthio.nostr.core.normalizeRelay
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.nwcRequestJson
import app.struthio.nostr.core.bolt11Msats
import app.struthio.nostr.net.AppLog
import app.struthio.nostr.net.Http
import app.struthio.nostr.net.Subscription
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class Transaction(
    val incoming: Boolean,
    val msats: Long,
    val description: String,
    val createdAt: Long,
    val state: String,
)

/** NIP-47 with NIP-44 encryption only. Keys and funds stay with the user's wallet service. */
class Wallet(private val core: Core) {
    private companion object {
        const val METHODS = "nwc_methods"
        const val LISTEN_MS = 3_000L
        const val RESEND_MS = 10_000L

        /** Asking these twice does no harm, so an unanswered request is sent again. */
        val REPEATABLE = setOf("get_balance", "list_transactions", "get_info", "get_budget", "lookup_invoice")

        /** For connections saved before methods were kept; the next answer from the wallet corrects it. */
        val ASSUMED_METHODS = setOf("pay_invoice", "get_balance", "make_invoice", "list_transactions")
    }

    private class Connection(val uri: NwcUri, val relays: Set<String>, val methods: Set<String>) {
        val signer = LocalSigner(uri.secret)
        val conversation = Nip44.conversationKey(uri.secret, uri.wallet)
    }

    private var connection: Connection? = null

    val connected = MutableStateFlow(false)
    val balance = MutableStateFlow<Long?>(null)
    val transactions = MutableStateFlow<List<Transaction>>(emptyList())

    /** Why the wallet couldn't be reached last time, or null when it answered. */
    val problem = MutableStateFlow<String?>(null)

    /**
     * A saved wallet stays connected without asking the network. It used to depend on the wallet
     * answering at launch, so any unreachable moment looked like the wallet had been removed.
     */
    suspend fun restore() {
        if (connection != null) return
        val text = runCatching { core.secure.get(Core.KEY_NWC) }.getOrNull()?.let(::String) ?: return
        val uri = NwcUri.parse(text) ?: return
        val relays = uri.relays.mapNotNull(::normalizeRelay).toSet()
        if (relays.isEmpty()) return
        val methods = core.prefs.getString(METHODS, null)?.split(' ')?.filter { it.isNotEmpty() }?.toSet() ?: ASSUMED_METHODS
        connection = Connection(uri, relays, methods).also(::listen)
        connected.value = true
        AppLog.add("wallet restored")
    }

    suspend fun connect(text: String, save: Boolean = true) {
        val uri = NwcUri.parse(text) ?: throw UserError("That is not a wallet connection link.")
        val relays = uri.relays.mapNotNull(::normalizeRelay).toSet()
        if (relays.isEmpty()) throw UserError("That connection has no usable relay.")
        var info: Event? = null
        core.pool.query(relays, listOf(Filter(authors = listOf(uri.wallet), kinds = listOf(Kind.NWC_INFO), limit = 1))) { e, _ ->
            if (info == null || e.createdAt > info!!.createdAt) info = e
        }
        val found = info ?: throw UserError("The wallet service did not answer.")
        val encryption = found.tags.first("encryption")?.getOrNull(1).orEmpty().split(' ')
        if ("nip44_v2" !in encryption) {
            throw UserError("This wallet only offers outdated encryption. Update it, or use another wallet.")
        }
        val methods = found.content.split(' ').filter { it.isNotEmpty() }.toSet()
        stopListening()
        connection = Connection(uri, relays, methods).also(::listen)
        if (save) core.secure.put(Core.KEY_NWC, text.trim().toByteArray())
        core.prefs.edit().putString(METHODS, methods.joinToString(" ")).apply()
        problem.value = null
        connected.value = true
    }

    fun disconnect() {
        stopListening()
        connection = null
        core.secure.remove(Core.KEY_NWC)
        core.prefs.edit().remove(METHODS).apply()
        problem.value = null
        connected.value = false
        balance.value = null
        transactions.value = emptyList()
    }

    fun supports(method: String) = connection?.methods?.contains(method) == true

    /** Requests still waiting for the wallet, by request id. */
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var listener: Subscription? = null
    private var listeningSince = 0L
    private val listening = MutableStateFlow(false)

    /**
     * One subscription stays open for every answer addressed to this app, from the moment the
     * wallet is connected. Wallet answers are never stored, so a request sent before anything is
     * listening loses its answer; some wallet relays never say when they're listening either.
     */
    private fun listen(c: Connection) {
        if (listener != null) return
        listeningSince = System.currentTimeMillis()
        listening.value = false
        listener = core.pool.subscribe(
            c.relays,
            listOf(Filter(authors = listOf(c.uri.wallet), kinds = listOf(Kind.NWC_RESPONSE), tags = mapOf("p" to listOf(c.signer.pubkey)))),
            onDone = { url, ok ->
                if (ok) {
                    AppLog.add("wallet listening on $url")
                    listening.value = true
                } else {
                    AppLog.add("wallet not listening on $url")
                }
            },
        ) { e, url ->
            val id = e.tags.first("e")?.getOrNull(1)
            val pending = id?.let(waiting::get)
            if (pending == null) {
                AppLog.add("wallet answer from $url for a request no longer waiting")
                return@subscribe
            }
            val opened = runCatching { JSONObject(Nip44.decrypt(e.content, c.conversation)) }
            opened.onSuccess { pending.complete(it) }.onFailure {
                // Old wallets answer with NIP-04, whose content carries "?iv=". Struthio doesn't read it.
                val format = if ("?iv=" in e.content) "the old NIP-04 encryption" else "an unreadable format"
                AppLog.add("wallet answered from $url in $format")
            }
        }
    }

    private fun stopListening() {
        listener?.let(core.pool::close)
        listener = null
        listening.value = false
    }

    private suspend fun call(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): JSONObject {
        val c = connection ?: throw UserError("Connect a wallet first.")
        listen(c)
        // A just-opened listener gets a moment to reach the relay, unless a relay already confirmed it.
        val settle = LISTEN_MS - (System.currentTimeMillis() - listeningSince)
        if (settle > 0) withTimeoutOrNull(settle) { listening.first { it } }
        val answer = CompletableDeferred<JSONObject>()
        val ids = ArrayList<String>()
        val started = System.currentTimeMillis()
        try {
            var attempt = 0
            var response: JSONObject? = null
            while (response == null) {
                attempt++
                val content = Nip44.encrypt(nwcRequestJson(method, params), c.conversation)
                val tags: Tags = listOf(
                    listOf("p", c.uri.wallet),
                    listOf("encryption", "nip44_v2"),
                    listOf("expiration", (nowSeconds() + 60).toString()),
                )
                val request = c.signer.signNow(Draft(c.signer.pubkey, nowSeconds(), Kind.NWC_REQUEST, tags, content))
                waiting[request.id] = answer
                ids += request.id
                // Returns at the first relay that takes it, so waiting on a silent relay doesn't eat into the answer time.
                val sent = core.pool.publishFirst(request, c.relays)
                AppLog.add("wallet $method #$attempt: " + if (sent.ok) "taken by a relay" else "refused: ${sent.message}")
                if (!sent.ok && attempt == 1) throw UserError("The wallet relay did not take the request.")
                val left = timeoutMs - (System.currentTimeMillis() - started)
                if (left <= 0) break
                val wait = if (method in REPEATABLE) minOf(RESEND_MS, left) else left
                response = withTimeoutOrNull(wait) { answer.await() }
                if (response == null && (method !in REPEATABLE || System.currentTimeMillis() - started >= timeoutMs)) break
            }
            val elapsed = (System.currentTimeMillis() - started) / 1000
            AppLog.add("wallet $method: " + if (response == null) "no answer in ${elapsed}s" else "answered after ${elapsed}s")
            if (response == null) throw UserError("The wallet did not respond.")
            response.optJSONObject("error")?.let { throw UserError(it.optString("message").ifEmpty { it.optString("code") }) }
            return response.optJSONObject("result") ?: JSONObject()
        } finally {
            ids.forEach(waiting::remove)
        }
    }

    suspend fun refresh() {
        if (connection == null) return
        try {
            fetch()
            problem.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.add("wallet refresh failed: ${e.message ?: e.javaClass.simpleName}")
            problem.value = (e as? UserError)?.message ?: "Something went wrong"
        }
    }

    /** What the wallet service says about itself, written to the logs. */
    private suspend fun checkService(c: Connection) {
        var info: Event? = null
        core.pool.query(c.relays, listOf(Filter(authors = listOf(c.uri.wallet), kinds = listOf(Kind.NWC_INFO), limit = 1)), 5_000) { e, _ ->
            if (info == null || e.createdAt > info!!.createdAt) info = e
        }
        val found = info
        AppLog.add(
            if (found == null) {
                "wallet service: no info found on its relays"
            } else {
                "wallet service: methods ${found.content.trim()}; encryption ${found.tags.first("encryption")?.getOrNull(1) ?: "not stated (NIP-04 only)"}"
            },
        )
    }

    private suspend fun fetch() {
        connection?.let { checkService(it) }
        if (supports("get_balance")) balance.value = call("get_balance").optLong("balance")
        if (supports("list_transactions")) {
            val list = call("list_transactions", JSONObject().put("limit", 30)).optJSONArray("transactions")
            if (list != null) {
                transactions.value = List(list.length()) { i ->
                    val t = list.getJSONObject(i)
                    Transaction(
                        t.optString("type") == "incoming",
                        t.optLong("amount"),
                        t.optString("description").takeIf { it != "null" }.orEmpty(),
                        t.optLong("settled_at").takeIf { it > 0 } ?: t.optLong("created_at"),
                        t.optString("state"),
                    )
                }
            }
        }
    }

    suspend fun pay(invoice: String) {
        call("pay_invoice", JSONObject().put("invoice", invoice.removePrefix("lightning:")), 60_000)
        runCatching { refresh() }
    }

    suspend fun makeInvoice(sats: Long, description: String): String {
        val params = JSONObject().put("amount", sats * 1000)
        if (description.isNotBlank()) params.put("description", description)
        return call("make_invoice", params).optString("invoice").ifEmpty { throw UserError("The wallet returned no invoice.") }
    }

    /** Pays a BOLT-11 invoice or a lightning address. */
    suspend fun send(target: String, sats: Long?) {
        val text = target.trim().removePrefix("lightning:")
        if (text.contains('@')) {
            val at = text.lastIndexOf('@')
            val amount = sats ?: throw UserError("Enter an amount.")
            val o = Http.json("https://${text.substring(at + 1)}/.well-known/lnurlp/${text.substring(0, at)}")
            val msats = amount * 1000
            val callback = o.getString("callback")
            val separator = if (callback.contains('?')) '&' else '?'
            val invoice = Http.json("$callback${separator}amount=$msats").optString("pr")
            if (bolt11Msats(invoice) != msats) throw UserError("The invoice amount does not match.")
            pay(invoice)
        } else {
            pay(text)
        }
    }
}
