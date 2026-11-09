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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
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
        const val LISTEN_MS = 15_000L

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
        connection = Connection(uri, relays, methods)
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
        connection = Connection(uri, relays, methods)
        if (save) core.secure.put(Core.KEY_NWC, text.trim().toByteArray())
        core.prefs.edit().putString(METHODS, methods.joinToString(" ")).apply()
        problem.value = null
        connected.value = true
    }

    fun disconnect() {
        connection = null
        core.secure.remove(Core.KEY_NWC)
        core.prefs.edit().remove(METHODS).apply()
        problem.value = null
        connected.value = false
        balance.value = null
        transactions.value = emptyList()
    }

    fun supports(method: String) = connection?.methods?.contains(method) == true

    private suspend fun call(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): JSONObject {
        val c = connection ?: throw UserError("Connect a wallet first.")
        val content = Nip44.encrypt(nwcRequestJson(method, params), c.conversation)
        val tags: Tags = listOf(
            listOf("p", c.uri.wallet),
            listOf("encryption", "nip44_v2"),
            listOf("expiration", (nowSeconds() + 60).toString()),
        )
        val request = c.signer.signNow(Draft(c.signer.pubkey, nowSeconds(), Kind.NWC_REQUEST, tags, content))
        val answer = CompletableDeferred<JSONObject>()
        val listening = CompletableDeferred<Unit>()
        val sub = core.pool.subscribe(
            c.relays,
            listOf(
                Filter(
                    authors = listOf(c.uri.wallet),
                    kinds = listOf(Kind.NWC_RESPONSE),
                    tags = mapOf("e" to listOf(request.id), "p" to listOf(c.signer.pubkey)),
                ),
            ),
            onDone = { _, ok -> if (ok) listening.complete(Unit) },
        ) { e, _ ->
            runCatching { answer.complete(JSONObject(Nip44.decrypt(e.content, c.conversation))) }
        }
        try {
            // Wallet answers are never stored by relays, so the request only goes out once the
            // relay is listening for the answer. Before, a quick wallet could reply too early.
            val ready = withTimeoutOrNull(LISTEN_MS) { listening.await() } != null
            val sent = core.pool.publish(request, c.relays)
            val accepted = sent.filterValues { it.ok }.keys
            AppLog.add(
                "wallet $method to ${c.relays.joinToString(" ")}: " +
                    (if (accepted.isEmpty()) "refused ${sent.values.joinToString { it.message }}" else "sent") +
                    if (ready) "" else " (relay wasn't ready to listen)",
            )
            if (accepted.isEmpty()) throw UserError("The wallet relay did not take the request.")
            val response = withTimeoutOrNull(timeoutMs) { answer.await() }
            AppLog.add("wallet $method: " + if (response == null) "no answer in ${timeoutMs / 1000}s" else "answered")
            if (response == null) throw UserError("The wallet did not respond.")
            response.optJSONObject("error")?.let { throw UserError(it.optString("message").ifEmpty { it.optString("code") }) }
            return response.optJSONObject("result") ?: JSONObject()
        } finally {
            core.pool.close(sub)
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

    private suspend fun fetch() {
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
