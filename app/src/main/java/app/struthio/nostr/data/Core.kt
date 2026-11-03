package app.struthio.nostr.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Hex
import app.struthio.nostr.core.Keys
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.LocalSigner
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.Nip44
import app.struthio.nostr.core.RelayList
import app.struthio.nostr.core.Signer
import app.struthio.nostr.core.Tags
import app.struthio.nostr.core.bolt11Msats
import app.struthio.nostr.core.chooseRelays
import app.struthio.nostr.core.normalizeRelay
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.parseRelayList
import app.struthio.nostr.core.prepareContent
import app.struthio.nostr.core.replyTags
import app.struthio.nostr.core.roomOf
import app.struthio.nostr.core.secureRandom
import app.struthio.nostr.core.threadRef
import app.struthio.nostr.core.values
import app.struthio.nostr.core.zapSplits
import app.struthio.nostr.net.Bech32Lnurl
import app.struthio.nostr.net.Http
import app.struthio.nostr.net.RelayPool
import app.struthio.nostr.net.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

data class Profile(
    val pubkey: String,
    val name: String,
    val picture: String?,
    val about: String,
    val nip05: String?,
    val lud16: String?,
    val lud06: String?,
    val verified: Boolean = false,
    val raw: String = "{}",
)

class UserError(message: String) : Exception(message)

/** Everything behind the screens: account, cache, relays and the actions on them. */
class Core(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = Store(context)
    val secure = SecureStore(context)
    val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    val imageDir = File(context.cacheDir, "images")
    val pool = RelayPool(scope) { signer.value }
    val wallet = Wallet(this)

    val signer = MutableStateFlow<Signer?>(null)
    val me: String? get() = signer.value?.pubkey

    val profiles = mutableStateMapOf<String, Profile>()
    val stats = mutableStateMapOf<String, Stats>()
    val follows = MutableStateFlow<Set<String>>(emptySet())
    val muted = MutableStateFlow<Set<String>>(emptySet())
    val myRelays = MutableStateFlow(RelayList(DEFAULT_RELAYS, DEFAULT_RELAYS))
    val dmRelays = MutableStateFlow<List<String>>(emptyList())

    /** Bumps when the cache changes, so that open screens can re-read it. */
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val main = Handler(Looper.getMainLooper())
    private val incoming = Channel<Pair<Event, String>>(Channel.UNLIMITED)
    private val relayLists = HashMap<String, RelayList>()
    private val wanted = LinkedHashSet<String>()
    private var profileJob: Job? = null
    private val wantedStats = LinkedHashSet<String>()
    private var statsJob: Job? = null
    private val statsFetched = HashMap<String, Long>()
    private val sessionSubs = ArrayList<Subscription>()
    private val followsLock = Mutex()
    private val nip05Checked = HashSet<String>()

    init {
        scope.launch { ingestLoop() }
        scope.launch {
            restoreAccount()
            store.prune(me, CACHE_EVENTS)
            Http.trimCache(imageDir, IMAGE_CACHE_BYTES)
        }
    }

    private suspend fun ingestLoop() {
        val batch = ArrayList<Pair<Event, String>>(256)
        while (true) {
            batch += incoming.receive()
            while (batch.size < 256) batch += incoming.tryReceive().getOrNull() ?: break
            val added = store.insert(batch.filterNot { (e, _) -> e.isExpired() || e.pubkey in muted.value })
            batch.clear()
            added.forEach(::onNewEvent)
            if (added.isNotEmpty()) changes.tryEmit(Unit)
            delay(60)
        }
    }

    fun ingest(event: Event, relay: String) {
        incoming.trySend(event to relay)
    }

    private fun onNewEvent(e: Event) {
        when (e.kind) {
            Kind.METADATA -> publishProfile(parseProfile(e))
            Kind.RELAYS -> {
                synchronized(relayLists) { relayLists[e.pubkey] = parseRelayList(e) }
                if (e.pubkey == me) myRelays.value = parseRelayList(e).orDefaults()
            }
            Kind.FOLLOWS -> if (e.pubkey == me) applyFollows(e)
            Kind.MUTES -> if (e.pubkey == me) scope.launch { applyMutes(e) }
            Kind.DM_RELAYS -> if (e.pubkey == me) {
                dmRelays.value = e.tags.values("relay").mapNotNull(::normalizeRelay)
            }
        }
    }

    private fun RelayList.orDefaults() = RelayList(read.ifEmpty { DEFAULT_RELAYS }, write.ifEmpty { DEFAULT_RELAYS })

    private fun applyFollows(e: Event) {
        val set = e.tags.values("p").filter(Hex::isKey).toSet()
        store.setFollows(set)
        follows.value = set
    }

    private suspend fun applyMutes(e: Event) {
        val set = HashSet(e.tags.values("p"))
        val me = signer.value
        if (me != null && e.content.isNotBlank() && !e.content.contains("?iv=")) {
            runCatching {
                val list = org.json.JSONArray(me.nip44Decrypt(e.content, me.pubkey))
                for (i in 0 until list.length()) {
                    val tag = list.optJSONArray(i) ?: continue
                    if (tag.optString(0) == "p") set += tag.optString(1)
                }
            }
        }
        muted.value = set
    }

    private fun publishProfile(profile: Profile) {
        main.post {
            val old = profiles[profile.pubkey]
            profiles[profile.pubkey] = profile.copy(verified = old?.verified == true && old.nip05 == profile.nip05)
        }
        verifyNip05(profile)
    }

    /** NIP-05: only a matching pubkey at the domain earns the check mark. Redirects are not followed. */
    private fun verifyNip05(profile: Profile) {
        val address = profile.nip05 ?: return
        val at = address.lastIndexOf('@').takeIf { it > 0 } ?: return
        synchronized(nip05Checked) { if (!nip05Checked.add(profile.pubkey + address)) return }
        scope.launch {
            val name = address.substring(0, at).lowercase()
            val domain = address.substring(at + 1)
            val ok = runCatching {
                val url = "https://$domain/.well-known/nostr.json?name=${URLEncoder.encode(name, "UTF-8")}"
                Http.json(url, followRedirects = false).getJSONObject("names").optString(name) == profile.pubkey
            }.getOrDefault(false)
            if (ok) {
                main.post { profiles[profile.pubkey]?.let { profiles[profile.pubkey] = it.copy(verified = true) } }
            }
        }
    }

    private fun parseProfile(e: Event): Profile {
        val o = runCatching { JSONObject(e.content) }.getOrDefault(JSONObject())
        fun field(name: String) = o.optString(name).takeIf { it.isNotBlank() && it != "null" }
        return Profile(
            e.pubkey,
            field("display_name") ?: field("name") ?: "",
            field("picture")?.takeIf { it.startsWith("https://") },
            field("about") ?: "",
            field("nip05"),
            field("lud16"),
            field("lud06"),
            raw = e.content,
        )
    }

    fun displayName(pubkey: String): String =
        profiles[pubkey]?.name?.takeIf { it.isNotEmpty() } ?: Nip19.npub(pubkey).let { it.take(10) + "…" + it.takeLast(4) }

    /** Asks for a profile; cards call this as they appear, and requests are grouped. */
    fun wantProfile(pubkey: String) {
        if (profiles.containsKey(pubkey)) return
        synchronized(wanted) {
            wanted += pubkey
            if (profileJob?.isActive == true) return
            profileJob = scope.launch {
                delay(200)
                val batch = synchronized(wanted) { wanted.toList().also { wanted.clear() } }
                val cached = store.latestOfKind(batch, Kind.METADATA)
                cached.forEach { publishProfile(parseProfile(it)) }
                val missing = batch - cached.map { it.pubkey }.toSet()
                missing.chunked(200).forEach { chunk ->
                    pool.query(
                        (INDEXER_RELAYS + DEFAULT_RELAYS).toSet(),
                        listOf(Filter(authors = chunk, kinds = listOf(Kind.METADATA, Kind.RELAYS))),
                        onEvent = ::ingest,
                    )
                }
            }
        }
    }

    fun relaysOf(pubkey: String): RelayList? = synchronized(relayLists) {
        relayLists[pubkey] ?: store.latest(pubkey, Kind.RELAYS)?.let(::parseRelayList)?.also { relayLists[pubkey] = it }
    }

    private suspend fun ensureRelayLists(pubkeys: Collection<String>) {
        val known = store.latestOfKind(pubkeys, Kind.RELAYS)
        synchronized(relayLists) { known.forEach { relayLists[it.pubkey] = parseRelayList(it) } }
        val missing = pubkeys.toSet() - known.map { it.pubkey }.toSet()
        missing.chunked(400).forEach { chunk ->
            pool.query(INDEXER_RELAYS.toSet(), listOf(Filter(authors = chunk, kinds = listOf(Kind.RELAYS)))) { e, relay ->
                synchronized(relayLists) { relayLists[e.pubkey] = parseRelayList(e) }
                ingest(e, relay)
            }
        }
    }

    /** NIP-65 outbox: the fewest relays that reach these authors, each with its authors. */
    suspend fun outbox(authors: Collection<String>, maxRelays: Int = MAX_FEED_RELAYS): Map<String, Set<String>> {
        ensureRelayLists(authors)
        val cover = chooseRelays(authors, { relaysOf(it)?.write.orEmpty() }, maxRelays)
        val covered = cover.values.flatten().toSet()
        val rest = authors.toSet() - covered
        if (rest.isEmpty()) return cover
        val out = cover.mapValues { it.value.toMutableSet() }.toMutableMap()
        DEFAULT_RELAYS.forEach { out.getOrPut(it) { HashSet() } += rest }
        return out
    }

    fun readRelays(): Set<String> = myRelays.value.read.toSet()
    fun writeRelays(): Set<String> = myRelays.value.write.toSet()

    /** Counters for a note; cards call this as they appear. */
    fun wantStats(id: String) {
        synchronized(wantedStats) {
            wantedStats += id
            if (statsJob?.isActive == true) return
            statsJob = scope.launch {
                delay(300)
                val ids = synchronized(wantedStats) { wantedStats.toList().also { wantedStats.clear() } }
                refreshStats(ids)
                val now = nowSeconds()
                val stale = synchronized(statsFetched) {
                    ids.filter { now - (statsFetched[it] ?: 0) > 300 }.also { list -> list.forEach { statsFetched[it] = now } }
                }
                stale.chunked(60).forEach { chunk ->
                    pool.query(
                        readRelays() + DEFAULT_RELAYS,
                        listOf(
                            Filter(kinds = STAT_KINDS, tags = mapOf("e" to chunk), limit = 600),
                            Filter(kinds = listOf(Kind.NOTE), tags = mapOf("q" to chunk), limit = 100),
                        ),
                        onEvent = ::ingest,
                    )
                    delay(120)
                    resolveProviders(chunk)
                    refreshStats(chunk)
                }
            }
        }
    }

    private fun refreshStats(ids: List<String>) {
        ids.chunked(200).forEach { chunk ->
            val fresh = store.stats(chunk, me)
            main.post { stats.putAll(fresh) }
        }
    }

    private suspend fun resolveProviders(ids: List<String>) {
        val now = nowSeconds()
        store.unresolvedRecipients(ids, now).take(12).forEach { pubkey ->
            val profile = withContext(Dispatchers.Main) { profiles[pubkey] }
                ?: store.latest(pubkey, Kind.METADATA)?.let(::parseProfile)
            if (profile == null) {
                wantProfile(pubkey)
            } else {
                val info = runCatching { lnurlInfo(profile) }.getOrNull()
                store.setProvider(pubkey, info?.nostrPubkey, now)
            }
        }
    }

    // Account

    private suspend fun restoreAccount() {
        val external = prefs.getString("signer_package", null)
        val externalKey = prefs.getString("signer_pubkey", null)
        val active: Signer? = when {
            external != null && externalKey != null -> ExternalSigner(context, externalKey, external)
            else -> runCatching { secure.get(KEY_SECRET) }.getOrNull()?.let { LocalSigner(it) }
        }
        wallet.restore()
        if (active != null) activate(active)
    }

    private suspend fun activate(active: Signer) {
        signer.value = active
        val pubkey = active.pubkey
        store.latest(pubkey, Kind.FOLLOWS)?.let(::applyFollows)
        store.latest(pubkey, Kind.RELAYS)?.let { myRelays.value = parseRelayList(it).orDefaults() }
        store.latest(pubkey, Kind.DM_RELAYS)?.let { dmRelays.value = it.tags.values("relay").mapNotNull(::normalizeRelay) }
        store.latest(pubkey, Kind.MUTES)?.let { applyMutes(it) }
        wantProfile(pubkey)
        startSession()
    }

    /** A new account gets a key, a profile and the relay lists others need to reach it. */
    suspend fun createAccount(name: String) {
        val secret = Keys.generate()
        secure.put(KEY_SECRET, secret)
        val local = LocalSigner(secret)
        activate(local)
        publish(Draft(local.pubkey, nowSeconds(), Kind.METADATA, emptyList(), JSONObject().put("name", name).toString()))
        publish(relayListDraft(local.pubkey, RelayList(DEFAULT_RELAYS, DEFAULT_RELAYS)), extraRelays = INDEXER_RELAYS.toSet())
        publish(dmRelayDraft(local.pubkey, DEFAULT_DM_RELAYS))
    }

    suspend fun signIn(input: String) {
        val text = input.trim()
        val entity = Nip19.decode(text)
        val secret = when {
            entity is Entity.Secret -> entity.key
            Hex.isKey(text.lowercase()) -> Hex.decode(text.lowercase())
            else -> throw UserError("That is not a private key (nsec).")
        }
        if (!Keys.isValidSecret(secret)) throw UserError("That key is not valid.")
        secure.put(KEY_SECRET, secret)
        activate(LocalSigner(secret))
    }

    suspend fun signInExternal(pubkey: String, packageName: String) {
        prefs.edit().putString("signer_package", packageName).putString("signer_pubkey", pubkey).apply()
        activate(ExternalSigner(context, pubkey, packageName))
    }

    suspend fun exportSecret(): String? = secure.get(KEY_SECRET)?.let(Nip19::nsec)

    suspend fun signOut() {
        sessionSubs.forEach(pool::close)
        sessionSubs.clear()
        secure.remove(KEY_SECRET)
        wallet.disconnect()
        prefs.edit().clear().apply()
        store.clearAll()
        signer.value = null
        follows.value = emptySet()
        muted.value = emptySet()
        dmRelays.value = emptyList()
        myRelays.value = RelayList(DEFAULT_RELAYS, DEFAULT_RELAYS)
        withContext(Dispatchers.Main) {
            profiles.clear()
            stats.clear()
        }
    }

    /** Long-lived subscriptions of the signed-in user; the pool closes the sockets in the background. */
    private fun startSession() {
        val pubkey = me ?: return
        scope.launch {
            val own = Filter(authors = listOf(pubkey), kinds = OWN_LISTS)
            pool.query((INDEXER_RELAYS + DEFAULT_RELAYS).toSet() + writeRelays(), listOf(own), onEvent = ::ingest)
            delay(200)
            // Our own zap provider, so that received zaps can be validated (NIP-57 Appendix F).
            store.latest(pubkey, Kind.METADATA)?.let(::parseProfile)?.let { profile ->
                runCatching { lnurlInfo(profile) }.getOrNull()?.let { store.setProvider(pubkey, it.nostrPubkey, nowSeconds()) }
            }
            sessionSubs.forEach(pool::close)
            sessionSubs.clear()
            val since = nowSeconds() - 14 * 86400
            sessionSubs += pool.subscribe(
                readRelays(),
                listOf(Filter(kinds = ALERT_KINDS, tags = mapOf("p" to listOf(pubkey)), since = since, limit = 200)),
                onEvent = ::ingest,
            )
            startDmSubscription()
        }
    }

    private fun startDmSubscription() {
        val pubkey = me ?: return
        val relays = dmRelays.value.toSet()
        if (relays.isEmpty()) return
        val since = prefs.getLong("dm_since", 0L).let { if (it == 0L) null else it - 3 * 86400 }
        sessionSubs += pool.subscribe(
            relays,
            listOf(Filter(kinds = listOf(Kind.GIFT_WRAP), tags = mapOf("p" to listOf(pubkey)), since = since)),
            onEvent = { wrap, _ -> scope.launch { receiveWrap(wrap) } },
        )
    }

    // Publishing

    /** Signs and publishes to our write relays and the read relays of everyone tagged (NIP-65). */
    suspend fun publish(draft: Draft, extraRelays: Set<String> = emptySet()): Event {
        val active = signer.value ?: throw UserError("Sign in first.")
        val event = active.sign(draft)
        val tagged = draft.tags.values("p").distinct().filter(Hex::isKey).take(20)
        ensureRelayLists(tagged)
        val targets = writeRelays() + extraRelays + tagged.flatMap { relaysOf(it)?.read.orEmpty().take(3) }
        ingest(event, targets.firstOrNull() ?: "")
        val results = pool.publish(event, targets.take(MAX_PUBLISH_RELAYS).toSet())
        if (results.values.none { it.ok }) {
            throw UserError(results.values.firstOrNull()?.message?.takeIf { it.isNotBlank() } ?: "No relay accepted this.")
        }
        // NIP-65: help whoever reads this find us.
        if (tagged.isNotEmpty() && draft.kind != Kind.RELAYS) {
            store.latest(event.pubkey, Kind.RELAYS)?.let { list -> scope.launch { pool.publish(list, results.keys) } }
        }
        return event
    }

    private fun author(): String = me ?: throw UserError("Sign in first.")

    fun hintFor(id: String): String = store.relayOf(id)?.takeIf { it.isNotEmpty() } ?: writeRelays().firstOrNull() ?: ""

    suspend fun postNote(text: String): Event {
        val (content, tags) = prepareContent(text)
        return publish(Draft(author(), nowSeconds(), Kind.NOTE, tags, content))
    }

    suspend fun reply(parent: Event, text: String): Event {
        val (content, extra) = prepareContent(text)
        val (kind, tags) = replyTags(parent, hintFor(parent.id))
        return publish(Draft(author(), nowSeconds(), kind, (tags + extra).distinct(), content))
    }

    suspend fun quote(target: Event, text: String): Event {
        val hint = hintFor(target.id)
        val reference = Nip19.nevent(target.id, listOf(hint).filter { it.isNotEmpty() }, target.pubkey)
        val (content, tags) = prepareContent("$text\n\nnostr:$reference")
        return publish(Draft(author(), nowSeconds(), Kind.NOTE, tags, content))
    }

    suspend fun boost(target: Event) {
        val hint = hintFor(target.id)
        val generic = target.kind != Kind.NOTE
        val tags = ArrayList<List<String>>()
        tags += listOf("e", target.id, hint)
        tags += listOf("p", target.pubkey)
        if (generic) tags += listOf("k", target.kind.toString())
        val protected = target.tags.any { it.firstOrNull() == "-" }
        publish(Draft(author(), nowSeconds(), if (generic) Kind.GENERIC_REPOST else Kind.REPOST, tags, if (protected) "" else target.toJson()))
        refreshStats(listOf(target.id))
    }

    suspend fun react(target: Event, content: String = "+") {
        val hint = hintFor(target.id)
        val tags = listOf(
            listOf("e", target.id, hint, target.pubkey),
            listOf("p", target.pubkey, hint),
            listOf("k", target.kind.toString()),
        )
        publish(Draft(author(), nowSeconds(), Kind.REACTION, tags, content))
        delay(100)
        refreshStats(listOf(target.id))
    }

    suspend fun saveProfile(fields: Map<String, String>) {
        val pubkey = author()
        latestFromRelays(pubkey, Kind.METADATA)
        val o = runCatching { JSONObject(store.latest(pubkey, Kind.METADATA)?.content ?: "{}") }.getOrDefault(JSONObject())
        fields.forEach { (key, value) -> if (value.isBlank()) o.remove(key) else o.put(key, value.trim()) }
        // Deprecated field names are dropped when a profile is rewritten (NIP-24).
        o.remove("displayName")
        o.remove("username")
        publish(Draft(pubkey, nowSeconds(), Kind.METADATA, emptyList(), o.toString()))
    }

    /** Replaceable lists are edited from the newest copy on the network, never from a stale one. */
    private suspend fun latestFromRelays(pubkey: String, kind: Int): Boolean {
        val answered = pool.query(
            writeRelays() + INDEXER_RELAYS + DEFAULT_RELAYS,
            listOf(Filter(authors = listOf(pubkey), kinds = listOf(kind), limit = 1)),
            onEvent = ::ingest,
        )
        delay(150)
        return answered > 0
    }

    suspend fun setFollowing(pubkey: String, follow: Boolean) = followsLock.withLock {
        val me = author()
        if (!latestFromRelays(me, Kind.FOLLOWS)) throw UserError("Could not reach your relays. Try again.")
        val current = store.latest(me, Kind.FOLLOWS)
        val tags = current?.tags.orEmpty().filterNot { it.size >= 2 && it[0] == "p" && it[1] == pubkey }
        val next: Tags = if (follow) tags + listOf(listOf("p", pubkey)) else tags
        publish(Draft(me, nowSeconds(), Kind.FOLLOWS, next, current?.content ?: ""))
        Unit
    }

    suspend fun setMuted(pubkey: String, mute: Boolean) {
        val me = author()
        if (!latestFromRelays(me, Kind.MUTES)) throw UserError("Could not reach your relays. Try again.")
        val current = store.latest(me, Kind.MUTES)
        val tags = current?.tags.orEmpty().filterNot { it.size >= 2 && it[0] == "p" && it[1] == pubkey }
        publish(Draft(me, nowSeconds(), Kind.MUTES, if (mute) tags + listOf(listOf("p", pubkey)) else tags, current?.content ?: ""))
        muted.value = if (mute) muted.value + pubkey else muted.value - pubkey
    }

    private fun relayListDraft(pubkey: String, list: RelayList): Draft {
        val tags = (list.read + list.write).distinct().map { url ->
            when {
                url in list.read && url in list.write -> listOf("r", url)
                url in list.read -> listOf("r", url, "read")
                else -> listOf("r", url, "write")
            }
        }
        return Draft(pubkey, nowSeconds(), Kind.RELAYS, tags, "")
    }

    private fun dmRelayDraft(pubkey: String, relays: List<String>) =
        Draft(pubkey, nowSeconds(), Kind.DM_RELAYS, relays.map { listOf("relay", it) }, "")

    suspend fun saveRelays(list: RelayList) {
        val pubkey = author()
        publish(relayListDraft(pubkey, list), extraRelays = (INDEXER_RELAYS + DEFAULT_RELAYS + myRelays.value.write).toSet())
        myRelays.value = list.orDefaults()
        startSession()
    }

    suspend fun saveDmRelays(relays: List<String>) {
        val pubkey = author()
        publish(dmRelayDraft(pubkey, relays), extraRelays = INDEXER_RELAYS.toSet() + relays)
        dmRelays.value = relays
        startSession()
    }

    // Lightning

    data class LnurlInfo(val callback: String, val min: Long, val max: Long, val nostrPubkey: String?, val url: String)

    private fun lnurlEndpoint(profile: Profile): String? {
        profile.lud16?.let {
            val at = it.lastIndexOf('@')
            if (at > 0) return "https://${it.substring(at + 1)}/.well-known/lnurlp/${it.substring(0, at)}"
        }
        return profile.lud06?.let(Bech32Lnurl::decode)
    }

    private fun lnurlInfo(profile: Profile): LnurlInfo? {
        val url = lnurlEndpoint(profile) ?: return null
        val o = Http.json(url)
        val nostrKey = o.optString("nostrPubkey").takeIf { o.optBoolean("allowsNostr") && Hex.isKey(it) }
        return LnurlInfo(o.getString("callback"), o.optLong("minSendable"), o.optLong("maxSendable"), nostrKey, url)
    }

    private suspend fun profileOf(pubkey: String): Profile {
        withContext(Dispatchers.Main) { profiles[pubkey] }?.let { return it }
        store.latest(pubkey, Kind.METADATA)?.let { return parseProfile(it) }
        pool.query((INDEXER_RELAYS + DEFAULT_RELAYS).toSet(), listOf(Filter(authors = listOf(pubkey), kinds = listOf(Kind.METADATA))), onEvent = ::ingest)
        delay(150)
        return store.latest(pubkey, Kind.METADATA)?.let(::parseProfile) ?: throw UserError("Could not load that profile.")
    }

    /** NIP-57 for one recipient: returns the invoice to pay. */
    private suspend fun zapInvoice(recipient: String, target: Event?, msats: Long, comment: String): String {
        val profile = profileOf(recipient)
        val info = withContext(Dispatchers.IO) { lnurlInfo(profile) }
            ?: throw UserError("${displayName(recipient)} has no Lightning address.")
        if (info.nostrPubkey == null) throw UserError("${displayName(recipient)}'s Lightning address does not support zaps.")
        if (msats < info.min || (info.max > 0 && msats > info.max)) throw UserError("That amount is outside what this address accepts.")
        val lnurl = Bech32Lnurl.encode(info.url)
        val receiptRelays = (readRelays() + relaysOf(recipient)?.read.orEmpty()).take(6)
        val tags = ArrayList<List<String>>()
        tags += listOf("relays") + receiptRelays
        tags += listOf("amount", msats.toString())
        tags += listOf("lnurl", lnurl)
        tags += listOf("p", recipient)
        target?.let {
            tags += listOf("e", it.id)
            tags += listOf("k", it.kind.toString())
        }
        val active = signer.value ?: throw UserError("Sign in first.")
        val request = active.sign(Draft(active.pubkey, nowSeconds(), Kind.ZAP_REQUEST, tags, comment))
        val separator = if (info.callback.contains('?')) '&' else '?'
        val url = info.callback + separator + "amount=$msats&nostr=" + URLEncoder.encode(request.toJson(), "UTF-8") + "&lnurl=$lnurl"
        val response = withContext(Dispatchers.IO) { Http.json(url) }
        val invoice = response.optString("pr").takeIf { it.isNotEmpty() }
            ?: throw UserError(response.optString("reason").ifEmpty { "No invoice came back." })
        if (bolt11Msats(invoice) != msats) throw UserError("The invoice amount does not match.")
        store.setProvider(recipient, info.nostrPubkey, nowSeconds())
        return invoice
    }

    /** Pays through the connected wallet, or returns the invoices for an external wallet app. */
    suspend fun zap(target: Event?, recipient: String?, sats: Long, comment: String): List<String> {
        val splits = when {
            target != null -> zapSplits(target)
            recipient != null -> listOf(recipient to 1.0)
            else -> emptyList()
        }
        val invoices = splits.map { (who, share) ->
            zapInvoice(who, target, ((sats * 1000 * share).toLong() / 1000).coerceAtLeast(1) * 1000, comment)
        }
        if (!wallet.connected.value) return invoices
        invoices.forEach { wallet.pay(it) }
        target?.let { statsFetchedReset(it.id) }
        return emptyList()
    }

    private fun statsFetchedReset(id: String) {
        synchronized(statsFetched) { statsFetched.remove(id) }
        scope.launch {
            delay(2500)
            wantStats(id)
        }
    }

    // Private messages (NIP-17, NIP-59)

    private suspend fun receiveWrap(wrap: Event) {
        if (store.hasWrap(wrap.id)) return
        val active = signer.value ?: return
        try {
            val seal = Event.parse(active.nip44Decrypt(wrap.content, wrap.pubkey)) ?: throw IllegalStateException()
            check(seal.kind == Kind.SEAL && seal.isValid() && seal.tags.isEmpty())
            val rumor = Event.parseRumor(active.nip44Decrypt(seal.content, seal.pubkey)) ?: throw IllegalStateException()
            check(rumor.pubkey == seal.pubkey)
            if (rumor.pubkey !in muted.value && (rumor.kind == Kind.DM || rumor.kind == Kind.FILE_DM)) {
                val text = if (rumor.kind == Kind.FILE_DM) "Sent a file" else rumor.content
                store.addDm(Dm(rumor.id, roomKey(roomOf(rumor)), rumor.pubkey, rumor.createdAt, text))
                changes.tryEmit(Unit)
            }
            store.addWrap(wrap.id)
            prefs.edit().putLong("dm_since", maxOf(prefs.getLong("dm_since", 0L), wrap.createdAt)).apply()
        } catch (_: IllegalStateException) {
            // A signer that needs approval leaves the wrap for later; anything else is unreadable.
            if (active is LocalSigner) store.addWrap(wrap.id)
        } catch (_: Exception) {
            if (active is LocalSigner) store.addWrap(wrap.id)
        }
    }

    private suspend fun wrapFor(rumorJson: String, recipient: String, active: Signer): Event {
        val sealContent = active.nip44Encrypt(rumorJson, recipient)
        val seal = active.sign(Draft(active.pubkey, pastTime(), Kind.SEAL, emptyList(), sealContent))
        val ephemeral = Keys.generate()
        val wrapper = LocalSigner(ephemeral)
        val content = Nip44.encrypt(seal.toJson(), Nip44.conversationKey(ephemeral, recipient))
        return wrapper.signNow(Draft(wrapper.pubkey, pastTime(), Kind.GIFT_WRAP, listOf(listOf("p", recipient)), content))
    }

    private fun pastTime() = nowSeconds() - secureRandom.nextInt(2 * 86400)

    private suspend fun dmRelaysOf(pubkey: String): List<String> {
        pool.query(
            (relaysOf(pubkey)?.write.orEmpty() + INDEXER_RELAYS + DEFAULT_RELAYS).toSet(),
            listOf(Filter(authors = listOf(pubkey), kinds = listOf(Kind.DM_RELAYS), limit = 1)),
            onEvent = ::ingest,
        )
        delay(150)
        return store.latest(pubkey, Kind.DM_RELAYS)?.tags?.values("relay")?.mapNotNull(::normalizeRelay).orEmpty()
    }

    suspend fun sendMessage(room: Set<String>, text: String) {
        val active = signer.value ?: throw UserError("Sign in first.")
        val others = room - active.pubkey
        val mine = dmRelays.value
        if (mine.isEmpty()) throw UserError("Set up your message relays first.")
        val targets = others.associateWith { dmRelaysOf(it) }
        targets.entries.firstOrNull { it.value.isEmpty() }?.let {
            throw UserError("${displayName(it.key)} has not set up private messages yet.")
        }
        val tags = others.map { listOf("p", it) }
        val rumor = Draft(active.pubkey, nowSeconds(), Kind.DM, tags, text)
        val json = rumor.toJson()
        var delivered = false
        for ((recipient, relays) in targets) {
            delivered = pool.publish(wrapFor(json, recipient, active), relays.toSet()).values.any { it.ok } || delivered
        }
        if (!delivered) throw UserError("The message could not be delivered.")
        pool.publish(wrapFor(json, active.pubkey, active), mine.toSet())
        store.addDm(Dm(rumor.id, roomKey(room), active.pubkey, rumor.createdAt, text))
        changes.tryEmit(Unit)
    }

    suspend fun setUpMessages() = saveDmRelays(DEFAULT_DM_RELAYS)

    // Lookups

    suspend fun fetchEvent(id: String, hints: List<String> = emptyList(), author: String? = null): Event? = withContext(Dispatchers.IO) {
        store.event(id)?.let { return@withContext it }
        val relays = hints.mapNotNull(::normalizeRelay).toMutableSet()
        author?.let { relaysOf(it)?.write?.let(relays::addAll) }
        relays += readRelays()
        relays += DEFAULT_RELAYS
        var found: Event? = null
        pool.query(relays.take(8).toSet(), listOf(Filter(ids = listOf(id)))) { e, relay ->
            found = e
            ingest(e, relay)
        }
        found
    }

    /** The thread a note belongs to: its root, then everything replying to it. */
    suspend fun loadThread(event: Event): String = withContext(Dispatchers.IO) {
        val root = threadRef(event).root ?: event.id
        val ref = threadRef(event)
        if (ref.root != null) fetchEvent(ref.root, author = ref.rootAuthor)
        val relays = readRelays() + DEFAULT_RELAYS + relaysOf(event.pubkey)?.write.orEmpty() + relaysOf(event.pubkey)?.read.orEmpty()
        pool.query(
            relays.take(8).toSet(),
            listOf(
                Filter(kinds = listOf(Kind.NOTE), tags = mapOf("e" to listOf(root)), limit = 300),
                Filter(kinds = listOf(Kind.COMMENT), tags = mapOf("E" to listOf(root)), limit = 300),
            ),
            onEvent = ::ingest,
        )
        root
    }

    fun searchRelays(): Set<String> {
        val own = me?.let { store.latest(it, Kind.SEARCH_RELAYS) }?.tags?.values("relay")?.mapNotNull(::normalizeRelay)
        return (own?.takeIf { it.isNotEmpty() } ?: DEFAULT_SEARCH_RELAYS).toSet()
    }

    companion object {
        const val KEY_SECRET = "nsec"
        const val KEY_NWC = "nwc"
        private const val CACHE_EVENTS = 20_000
        private const val IMAGE_CACHE_BYTES = 80L * 1024 * 1024
        private const val MAX_FEED_RELAYS = 8
        private const val MAX_PUBLISH_RELAYS = 12

        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
        val INDEXER_RELAYS = listOf("wss://purplepag.es", "wss://user.kindpag.es")
        val DEFAULT_SEARCH_RELAYS = listOf("wss://relay.nostr.band", "wss://search.nos.today")
        val DEFAULT_DM_RELAYS = listOf("wss://auth.nostr1.com", "wss://relay.damus.io")

        val OWN_LISTS = listOf(Kind.METADATA, Kind.FOLLOWS, Kind.MUTES, Kind.RELAYS, Kind.SEARCH_RELAYS, Kind.DM_RELAYS)
        val STAT_KINDS = listOf(Kind.NOTE, Kind.REPOST, Kind.REACTION, Kind.COMMENT, Kind.ZAP_RECEIPT)
        val ALERT_KINDS = listOf(Kind.NOTE, Kind.COMMENT, Kind.REPOST, Kind.REACTION, Kind.ZAP_RECEIPT)
    }
}
