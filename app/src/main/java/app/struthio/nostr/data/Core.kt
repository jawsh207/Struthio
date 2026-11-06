package app.struthio.nostr.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.DEFAULT_REACTION
import app.struthio.nostr.core.LinkInfo
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.FileCipher
import app.struthio.nostr.core.FileMessage
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Hex
import app.struthio.nostr.core.KeyBackup
import app.struthio.nostr.core.Keys
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.LocalSigner
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.Nip44
import app.struthio.nostr.core.Poll
import app.struthio.nostr.core.ReadOnlySigner
import app.struthio.nostr.core.RelayInfo
import app.struthio.nostr.core.RelayList
import app.struthio.nostr.core.Signer
import app.struthio.nostr.core.Tags
import app.struthio.nostr.core.bolt11Msats
import app.struthio.nostr.core.chooseRelays
import app.struthio.nostr.core.emojiMap
import app.struthio.nostr.core.imetaTag
import app.struthio.nostr.core.isNip05Address
import app.struthio.nostr.core.isPreviewable
import app.struthio.nostr.core.mediaKind
import app.struthio.nostr.core.normalizeRelay
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.parseFileMessage
import app.struthio.nostr.core.parseLinkInfo
import app.struthio.nostr.core.parseRelayList
import app.struthio.nostr.core.prepareContent
import app.struthio.nostr.core.randomBytes
import app.struthio.nostr.core.parseRelayInfo
import app.struthio.nostr.core.pollTags
import app.struthio.nostr.core.reactionGlyph
import app.struthio.nostr.core.replyTags
import app.struthio.nostr.core.reportTags
import app.struthio.nostr.core.roomOf
import app.struthio.nostr.core.secureRandom
import app.struthio.nostr.core.sha256
import app.struthio.nostr.core.stripEmoji
import app.struthio.nostr.core.tagsToJson
import app.struthio.nostr.core.threadRef
import app.struthio.nostr.core.trustedNetwork
import app.struthio.nostr.core.values
import app.struthio.nostr.core.zapSplits
import app.struthio.nostr.net.Bech32Lnurl
import app.struthio.nostr.net.AppLog
import app.struthio.nostr.net.Http
import app.struthio.nostr.net.RelayPool
import app.struthio.nostr.net.Subscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
    val emoji: Map<String, String> = emptyMap(),
)

class UserError(message: String) : Exception(message)

/** Everything behind the screens: account, cache, relays and the actions on them. */
class Core(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = Store(context)
    val secure = SecureStore(context)
    val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    val imageDir = File(context.cacheDir, "images")
    private val relayInfoDir = File(context.cacheDir, "relays")
    val pool = RelayPool(scope) { signer.value }
    val wallet = Wallet(this)
    val media = MediaUploader(this, context)
    private val previewDir = File(context.cacheDir, "previews")
    private val previewSlots = Semaphore(3)

    val signer = MutableStateFlow<Signer?>(null)

    /** True once the account's own lists have been asked for, so an empty list really is empty. */
    val sessionLoaded = MutableStateFlow(false)

    /** Completes once a saved account has been restored, for work that starts without the UI. */
    private val ready = CompletableDeferred<Unit>()
    val bookmarks = MutableStateFlow<Set<String>>(emptySet())
    val deleted = mutableStateMapOf<String, Boolean>()
    val paidInvoices = mutableStateMapOf<String, Boolean>()
    private val trustedSet = MutableStateFlow<Set<String>>(emptySet())
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
            ready.complete(Unit)
            store.prune(me, CACHE_EVENTS)
            Http.trimCache(imageDir, IMAGE_CACHE_BYTES)
        }
    }

    private suspend fun ingestLoop() {
        val batch = ArrayList<Pair<Event, String>>(256)
        while (true) {
            batch += incoming.receive()
            while (batch.size < 256) batch += incoming.tryReceive().getOrNull() ?: break
            try {
                val added = store.insert(batch.filterNot { (e, _) -> e.isExpired() || e.pubkey in muted.value })
                added.forEach(::onNewEvent)
                if (added.isNotEmpty()) changes.tryEmit(Unit)
            } catch (e: android.database.SQLException) {
                // One unstorable batch must not stop everything after it.
            }
            batch.clear()
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
            Kind.BOOKMARKS -> if (e.pubkey == me) scope.launch { applyBookmarks(e) }
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

    private suspend fun privateItems(e: Event, name: String): List<String> {
        val active = signer.value ?: return emptyList()
        if (e.content.isBlank() || e.content.contains("?iv=")) return emptyList()
        return runCatching {
            val list = org.json.JSONArray(active.nip44Decrypt(e.content, active.pubkey))
            (0 until list.length()).mapNotNull { i ->
                list.optJSONArray(i)?.takeIf { it.optString(0) == name }?.optString(1)
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun applyBookmarks(e: Event) {
        bookmarks.value = (e.tags.values("e") + privateItems(e, "e")).filter(Hex::isKey).toSet()
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
        synchronized(nip05Checked) { if (!nip05Checked.add(profile.pubkey + address)) return }
        scope.launch {
            if (nip05Matches(profile)) {
                main.post { profiles[profile.pubkey]?.let { profiles[profile.pubkey] = it.copy(verified = true) } }
            }
        }
    }

    private fun nip05Matches(profile: Profile) = profile.nip05?.let(::nip05Owner) == profile.pubkey

    /** Who a typed NIP-05 address belongs to, according to its domain. */
    suspend fun resolveAddress(text: String): String? =
        if (isNip05Address(text)) withContext(Dispatchers.IO) { nip05Owner(text) } else null

    private fun nip05Owner(address: String): String? {
        val at = address.lastIndexOf('@').takeIf { it > 0 } ?: return null
        val name = address.substring(0, at).lowercase()
        val domain = address.substring(at + 1)
        return runCatching {
            val url = "https://$domain/.well-known/nostr.json?name=${URLEncoder.encode(name, "UTF-8")}"
            Http.json(url, followRedirects = false).getJSONObject("names").optString(name).takeIf(Hex::isKey)
        }.getOrNull()
    }

    /** "_@example.com" is the NIP-05 way of saying "example.com". */
    private fun handleOf(profile: Profile) = profile.nip05?.removePrefix("_@")

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
            emoji = emojiMap(e.tags),
        )
    }

    /** Name, then a verified NIP-05 address, then a short npub. Unverified addresses prove nothing. */
    fun displayName(pubkey: String): String {
        val profile = profiles[pubkey]
        return profile?.name?.let { stripEmoji(it, profile.emoji) }?.takeIf { it.isNotEmpty() }
            ?: profile?.takeIf { it.verified }?.let(::handleOf)
            ?: shortNpub(pubkey)
    }

    /** The name with its :shortcodes: left in, for places that draw custom emoji. */
    fun richName(pubkey: String): String {
        val profile = profiles[pubkey]
        return if (profile != null && stripEmoji(profile.name, profile.emoji).isNotEmpty()) profile.name else displayName(pubkey)
    }

    private fun shortNpub(pubkey: String) = Nip19.npub(pubkey).let { it.take(10) + "…" + it.takeLast(4) }

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
                        (lookupRelays() + DEFAULT_RELAYS).toSet(),
                        listOf(Filter(authors = chunk, kinds = listOf(Kind.METADATA, Kind.RELAYS))),
                        onEvent = ::ingest,
                    )
                }
            }
        }
    }

    fun relaysOf(pubkey: String): RelayList? = synchronized(relayLists) { relayLists[pubkey] }
        ?: store.latest(pubkey, Kind.RELAYS)?.let(::parseRelayList)?.also { list ->
            synchronized(relayLists) { relayLists[pubkey] = list }
        }

    private suspend fun ensureRelayLists(pubkeys: Collection<String>) {
        val known = store.latestOfKind(pubkeys, Kind.RELAYS)
        synchronized(relayLists) { known.forEach { relayLists[it.pubkey] = parseRelayList(it) } }
        val missing = pubkeys.toSet() - known.map { it.pubkey }.toSet()
        coroutineScope {
            missing.chunked(MAX_AUTHORS).map { chunk ->
                async {
                    pool.query(lookupRelays().toSet(), listOf(Filter(authors = chunk, kinds = listOf(Kind.RELAYS))), 4_000) { e, relay ->
                        synchronized(relayLists) { relayLists[e.pubkey] = parseRelayList(e) }
                        ingest(e, relay)
                    }
                }
            }.awaitAll()
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
                delay(600)
                val ids = synchronized(wantedStats) { wantedStats.toList().also { wantedStats.clear() } }
                refreshStats(ids)
                val now = nowSeconds()
                val stale = synchronized(statsFetched) {
                    ids.filter { now - (statsFetched[it] ?: 0) > 300 }.also { list -> list.forEach { statsFetched[it] = now } }
                }
                stale.chunked(60).forEach { chunk ->
                    pool.query(
                        readRelays().take(2).toSet() + DEFAULT_RELAYS,
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
        val readOnly = prefs.getString("read_only_pubkey", null)
        val active: Signer? = when {
            external != null && externalKey != null -> ExternalSigner(context, externalKey, external)
            readOnly != null -> ReadOnlySigner(readOnly)
            else -> runCatching { secure.get(KEY_SECRET) }.getOrNull()?.let { LocalSigner(it) }
        }
        if (active != null) activate(active)
        wallet.restore()
    }

    private suspend fun activate(active: Signer) {
        val pubkey = active.pubkey
        sessionLoaded.value = false
        // Cached lists first: screens look at these as soon as a signer exists.
        loadCachedLists(pubkey)
        signer.value = active
        applyCachedMutes(pubkey)
        wantProfile(pubkey)
        startSession()
        Notifier.schedule(context, notifyMinutes())
    }

    private fun loadCachedLists(pubkey: String) {
        store.latest(pubkey, Kind.FOLLOWS)?.let(::applyFollows)
        store.latest(pubkey, Kind.RELAYS)?.let { myRelays.value = parseRelayList(it).orDefaults() }
        store.latest(pubkey, Kind.DM_RELAYS)?.let { dmRelays.value = it.tags.values("relay").mapNotNull(::normalizeRelay) }
        trustedSet.value = store.trusted()
    }

    private suspend fun applyCachedMutes(pubkey: String) {
        store.latest(pubkey, Kind.MUTES)?.let { applyMutes(it) }
        store.latest(pubkey, Kind.BOOKMARKS)?.let { applyBookmarks(it) }
    }

    /** A new account gets a key, a profile and the relay lists others need to reach it. */
    suspend fun createAccount(name: String) {
        val secret = Keys.generate()
        secure.put(KEY_SECRET, secret)
        val local = LocalSigner(secret)
        prefs.edit().putBoolean("account_created_here", true).apply()
        activate(local)
        publish(Draft(local.pubkey, nowSeconds(), Kind.METADATA, emptyList(), JSONObject().put("name", name).toString()))
        publish(relayListDraft(local.pubkey, RelayList(DEFAULT_RELAYS, DEFAULT_RELAYS)), extraRelays = lookupRelays().toSet())
        publish(dmRelayDraft(local.pubkey, DEFAULT_DM_RELAYS))
        publish(searchRelayDraft(local.pubkey, DEFAULT_SEARCH_RELAYS))
    }

    /** Null means the password was wrong, or the backup is damaged. */
    suspend fun signInEncrypted(backup: String, password: String) {
        val secret = withContext(Dispatchers.Default) {
            try {
                KeyBackup.decrypt(backup, password)
            } catch (_: OutOfMemoryError) {
                throw UserError("This backup needs more memory than this phone can give it.")
            }
        } ?: throw UserError("Wrong password, or the backup is damaged.")
        adoptSecret(secret)
    }

    suspend fun exportEncrypted(password: String): String? {
        val secret = secure.get(KEY_SECRET) ?: return null
        return withContext(Dispatchers.Default) {
            try {
                KeyBackup.encrypt(secret, password)
            } catch (_: OutOfMemoryError) {
                throw UserError("There wasn't enough memory for that. Close some apps and try again.")
            }
        }
    }

    suspend fun signIn(input: String) {
        val text = input.trim()
        val entity = Nip19.decode(text)
        if (entity is Entity.Profile) {
            prefs.edit().putString("read_only_pubkey", entity.pubkey).apply()
            activate(ReadOnlySigner(entity.pubkey))
            return
        }
        val secret = when {
            entity is Entity.Secret -> entity.key
            Hex.isKey(text.lowercase()) -> Hex.decode(text.lowercase())
            else -> throw UserError("That is not an nsec or npub.")
        }
        adoptSecret(secret)
    }

    private suspend fun adoptSecret(secret: ByteArray) {
        if (!Keys.isValidSecret(secret)) throw UserError("That key is not valid.")
        secure.put(KEY_SECRET, secret)
        prefs.edit().remove("read_only_pubkey").apply()
        activate(LocalSigner(secret))
    }

    suspend fun signInExternal(pubkey: String, packageName: String) {
        prefs.edit().putString("signer_package", packageName).putString("signer_pubkey", pubkey).remove("read_only_pubkey").apply()
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
        Notifier.schedule(context, 0)
        Notifier.clear(context)
        signer.value = null
        sessionLoaded.value = false
        bookmarks.value = emptySet()
        trustedSet.value = emptySet()
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
            val asked = (lookupRelays() + DEFAULT_RELAYS).toSet() + writeRelays()
            pool.query(asked, listOf(own), onEvent = ::ingest)
            delay(300)
            // The relay list usually arrives in that first round. Lists kept only on the
            // account's own relays can only be found by asking those relays too (NIP-65).
            val mine = writeRelays() + readRelays() - asked
            if (mine.isNotEmpty()) {
                pool.query(mine, listOf(own), onEvent = ::ingest)
                delay(300)
            }
            sessionLoaded.value = true
            refreshTrustedNetwork()
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
        val found = ConcurrentHashMap<String, AtomicInteger>()
        sessionSubs += pool.subscribe(
            relays,
            listOf(Filter(kinds = listOf(Kind.GIFT_WRAP), tags = mapOf("p" to listOf(pubkey)), since = since)),
            onDone = { url, ok -> if (ok) AppLog.add("$url inbox checked: ${found[url]?.get() ?: 0} messages") },
            onEvent = { wrap, url ->
                found.getOrPut(url) { AtomicInteger() }.incrementAndGet()
                scope.launch { receiveWrap(wrap) }
            },
        )
    }

    // Publishing

    /**
     * Signs and publishes to our write relays, finishing when the first one accepts. Reaching the
     * read relays of everyone tagged (NIP-65) happens afterwards, so it never holds anything up.
     * Leaving a screen can't stop a note that is already signed.
     */
    suspend fun publish(draft: Draft, extraRelays: Set<String> = emptySet()): Event {
        val active = signer.value ?: throw UserError("Sign in first.")
        val event = active.sign(draft)
        val own = (writeRelays() + extraRelays).take(MAX_PUBLISH_RELAYS).toSet()
        withContext(NonCancellable) {
            val result = pool.publishFirst(event, own)
            AppLog.add("sent kind ${event.kind} to ${own.size} relays: " + if (result.ok) "accepted" else "failed ${result.message}")
            if (!result.ok) throw UserError(result.message.takeIf { it.isNotBlank() } ?: "No relay accepted this.")
            ingest(event, own.first())
        }
        val tagged = draft.tags.values("p").distinct().filter(Hex::isKey).take(20)
        if (tagged.isNotEmpty()) scope.launch { runCatching { reachTagged(event, tagged, own) } }
        return event
    }

    private suspend fun reachTagged(event: Event, tagged: List<String>, sent: Set<String>) {
        ensureRelayLists(tagged)
        val inboxes = (tagged.flatMap { relaysOf(it)?.read.orEmpty().take(3) }.toSet() - sent).take(MAX_PUBLISH_RELAYS).toSet()
        pool.publish(event, inboxes)
        // NIP-65: help whoever reads this find us.
        if (event.kind != Kind.RELAYS) store.latest(event.pubkey, Kind.RELAYS)?.let { pool.publish(it, inboxes) }
    }

    /** Shows [change] on a note at once. The real counts follow, which undoes it if sending failed. */
    private suspend fun tentatively(id: String, change: (Stats) -> Stats, send: suspend () -> Unit) {
        withContext(Dispatchers.Main) { stats[id] = change(stats[id] ?: Stats()) }
        try {
            send()
        } finally {
            withContext(NonCancellable) {
                delay(150)
                refreshStats(listOf(id))
            }
        }
    }

    private fun author(): String = me ?: throw UserError("Sign in first.")

    fun mentionRelays(pubkey: String): List<String> = relaysOf(pubkey)?.write.orEmpty().take(2)

    fun hintFor(id: String): String = store.relayOf(id)?.takeIf { it.isNotEmpty() } ?: writeRelays().firstOrNull() ?: ""

    /** NIP-09. Relays decide whether to honour this, which the confirmation says. */
    suspend fun deleteNote(event: Event) {
        publish(Draft(author(), nowSeconds(), Kind.DELETION, listOf(listOf("e", event.id), listOf("k", event.kind.toString())), ""))
        withContext(Dispatchers.Main) { deleted[event.id] = true }
    }

    /** NIP-56. */
    suspend fun report(event: Event, type: String) {
        publish(Draft(author(), nowSeconds(), Kind.REPORT, reportTags(event, type), ""))
    }

    /** Bookmarks stay private: the list is encrypted to ourselves, as NIP-51 provides. */
    suspend fun setBookmarked(event: Event, saved: Boolean) = followsLock.withLock {
        val active = signer.value ?: throw UserError("Sign in first.")
        val me = active.pubkey
        if (!latestFromRelays(me, Kind.BOOKMARKS)) throw UserError("Could not reach your relays. Try again.")
        val current = store.latest(me, Kind.BOOKMARKS)
        if (current != null && current.content.contains("?iv=")) {
            throw UserError("Your bookmarks use an old format that Struthio will not overwrite.")
        }
        val items = ArrayList<List<String>>()
        if (current != null && current.content.isNotBlank()) {
            val list = org.json.JSONArray(active.nip44Decrypt(current.content, me, background = false))
            for (i in 0 until list.length()) {
                val tag = list.optJSONArray(i) ?: continue
                items += List(tag.length()) { tag.optString(it) }
            }
        }
        items.removeAll { it.size >= 2 && it[0] == "e" && it[1] == event.id }
        if (saved) items += listOf("e", event.id, hintFor(event.id))
        val content = active.nip44Encrypt(tagsToJson(items), me)
        val publicTags = current?.tags.orEmpty().filterNot { it.size >= 2 && it[0] == "e" && it[1] == event.id }
        val published = publish(Draft(me, nowSeconds(), Kind.BOOKMARKS, publicTags, content))
        delay(100)
        applyBookmarks(published)
    }

    private fun withMedia(text: String, media: List<Upload>): Pair<String, List<List<String>>> {
        val body = (listOf(text.trim()) + media.map { it.url }).filter { it.isNotEmpty() }.joinToString("\n")
        return body to media.map { imetaTag(it.url, it.type, it.sha256, it.width, it.height, it.size) }
    }

    suspend fun postNote(text: String, media: List<Upload> = emptyList(), sensitive: Boolean = false): Event {
        val (body, imeta) = withMedia(text, media)
        val (content, tags) = prepareContent(body)
        val warning = if (sensitive) listOf(listOf("content-warning")) else emptyList()
        return publish(Draft(author(), nowSeconds(), Kind.NOTE, tags + imeta + warning, content))
    }

    suspend fun reply(parent: Event, text: String, media: List<Upload> = emptyList()): Event {
        val (body, imeta) = withMedia(text, media)
        val (content, extra) = prepareContent(body)
        val (kind, tags) = replyTags(parent, hintFor(parent.id))
        return publish(Draft(author(), nowSeconds(), kind, (tags + extra).distinct() + imeta, content))
    }

    suspend fun quote(target: Event, text: String, media: List<Upload> = emptyList()): Event {
        val hint = hintFor(target.id)
        val reference = Nip19.nevent(target.id, listOf(hint).filter { it.isNotEmpty() }, target.pubkey)
        val (body, imeta) = withMedia(text, media)
        val (content, tags) = prepareContent("$body\n\nnostr:$reference")
        return publish(Draft(author(), nowSeconds(), Kind.NOTE, tags + imeta, content))
    }

    suspend fun boost(target: Event) {
        val hint = hintFor(target.id)
        val generic = target.kind != Kind.NOTE
        val tags = ArrayList<List<String>>()
        tags += listOf("e", target.id, hint)
        tags += listOf("p", target.pubkey)
        if (generic) tags += listOf("k", target.kind.toString())
        val protected = target.tags.any { it.firstOrNull() == "-" }
        tentatively(target.id, { if (it.mineBoosted) it else it.copy(boosts = it.boosts + 1, mineBoosted = true) }) {
            publish(Draft(author(), nowSeconds(), if (generic) Kind.GENERIC_REPOST else Kind.REPOST, tags, if (protected) "" else target.toJson()))
        }
    }

    suspend fun react(target: Event, content: String = DEFAULT_REACTION) {
        val hint = hintFor(target.id)
        val tags = listOf(
            listOf("e", target.id, hint, target.pubkey),
            listOf("p", target.pubkey, hint),
            listOf("k", target.kind.toString()),
        )
        val shown = { s: Stats -> s.copy(mine = reactionGlyph(content), reactions = s.reactions + if (s.mine == null) 1 else 0, mineReacted = true) }
        tentatively(target.id, shown) { publish(Draft(author(), nowSeconds(), Kind.REACTION, tags, content)) }
    }

    /** NIP-09: reactions are taken back by asking relays to delete them. */
    suspend fun unreact(target: Event) {
        val me = author()
        val mine = store.myReactions(target.id, me)
        if (mine.isEmpty()) return
        val tags = mine.map { listOf("e", it.id) } + listOf(listOf("k", Kind.REACTION.toString()))
        val hidden = { s: Stats ->
            s.copy(mine = null, reactions = (s.reactions - 1).coerceAtLeast(0), mineReacted = false, top = if (s.reactions <= 1) emptyList() else s.top)
        }
        tentatively(target.id, hidden) { publish(Draft(me, nowSeconds(), Kind.DELETION, tags, "")) }
    }

    suspend fun saveProfile(fields: Map<String, String>) {
        val pubkey = author()
        latestFromRelays(pubkey, Kind.METADATA)
        if (store.latest(pubkey, Kind.METADATA) == null && !safeToStartList()) {
            throw UserError("Your profile has not been found yet, so saving now could replace it. Run the account check in Settings.")
        }
        val o = runCatching { JSONObject(store.latest(pubkey, Kind.METADATA)?.content ?: "{}") }.getOrDefault(JSONObject())
        fields.forEach { (key, value) -> if (value.isBlank()) o.remove(key) else o.put(key, value.trim()) }
        // Deprecated field names are dropped when a profile is rewritten (NIP-24).
        o.remove("displayName")
        o.remove("username")
        publish(Draft(pubkey, nowSeconds(), Kind.METADATA, emptyList(), o.toString()))
    }

    /** Replaceable lists are edited from the newest copy on the network, never from a stale one. */
    private suspend fun latestFromRelays(pubkey: String, kind: Int): Boolean {
        val own = writeRelays()
        val answered = pool.query(
            own + readRelays() + lookupRelays() + DEFAULT_RELAYS,
            listOf(Filter(authors = listOf(pubkey), kinds = listOf(kind), limit = 1)),
            onEvent = ::ingest,
        )
        delay(150)
        // The newest copy lives on the account's write relays, so one of those has to have answered.
        return answered.any { it in own }
    }

    /** True when a missing list really means the account has none, so writing one loses nothing. */
    private fun safeToStartList(): Boolean =
        prefs.getBoolean("account_created_here", false) || prefs.getBoolean("confirmed_no_follow_list", false)

    suspend fun setFollowing(pubkey: String, follow: Boolean) = followsLock.withLock {
        val me = author()
        if (!latestFromRelays(me, Kind.FOLLOWS)) throw UserError("Could not reach your relays. Try again.")
        val current = store.latest(me, Kind.FOLLOWS)
        if (current == null && !safeToStartList()) {
            // Publishing now would replace a list we simply failed to find.
            throw UserError("Your follow list has not been found yet, so following now could replace it. Run the account check in Settings.")
        }
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
        publish(relayListDraft(pubkey, list), extraRelays = (lookupRelays() + DEFAULT_RELAYS + myRelays.value.write).toSet())
        myRelays.value = list.orDefaults()
        startSession()
    }

    suspend fun saveDmRelays(relays: List<String>) {
        val pubkey = author()
        publish(dmRelayDraft(pubkey, relays), extraRelays = lookupRelays().toSet() + relays)
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
        pool.query((lookupRelays() + DEFAULT_RELAYS).toSet(), listOf(Filter(authors = listOf(pubkey), kinds = listOf(Kind.METADATA))), onEvent = ::ingest)
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

    /** Stores the message inside a gift wrap and returns its sender, when it can be opened. */
    private suspend fun receiveWrap(wrap: Event): String? {
        if (store.hasWrap(wrap.id)) return null
        val active = signer.value ?: return null
        try {
            val seal = Event.parse(active.nip44Decrypt(wrap.content, wrap.pubkey)) ?: throw IllegalStateException()
            check(seal.kind == Kind.SEAL && seal.isValid() && seal.tags.isEmpty())
            val rumor = Event.parseRumor(active.nip44Decrypt(seal.content, seal.pubkey)) ?: throw IllegalStateException()
            check(rumor.pubkey == seal.pubkey)
            if (rumor.pubkey !in muted.value && (rumor.kind == Kind.DM || rumor.kind == Kind.FILE_DM)) {
                val file = parseFileMessage(rumor)
                val text = when {
                    rumor.kind == Kind.DM -> rumor.content
                    file?.type?.startsWith("image/") == true -> "Photo"
                    else -> "File"
                }
                store.addDm(Dm(rumor.id, roomKey(roomOf(rumor)), rumor.pubkey, rumor.createdAt, text, file))
                changes.tryEmit(Unit)
            }
            AppLog.add("opened a message")
            store.addWrap(wrap.id)
            prefs.edit().putLong("dm_since", maxOf(prefs.getLong("dm_since", 0L), wrap.createdAt)).apply()
            return rumor.pubkey
        } catch (e: IllegalStateException) {
            AppLog.add("could not open a message: ${e.message}")
            // A signer that needs approval leaves the wrap for later; anything else is unreadable.
            if (active is LocalSigner) store.addWrap(wrap.id)
        } catch (e: Exception) {
            AppLog.add("could not open a message: ${e.javaClass.simpleName}")
            if (active is LocalSigner) store.addWrap(wrap.id)
        }
        return null
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
            (relaysOf(pubkey)?.write.orEmpty() + lookupRelays() + DEFAULT_RELAYS).toSet(),
            listOf(Filter(authors = listOf(pubkey), kinds = listOf(Kind.DM_RELAYS), limit = 1)),
            onEvent = ::ingest,
        )
        delay(150)
        return store.latest(pubkey, Kind.DM_RELAYS)?.tags?.values("relay")?.mapNotNull(::normalizeRelay).orEmpty()
    }

    private suspend fun deliver(room: Set<String>, kind: Int, extraTags: Tags, content: String, file: FileMessage?, preview: String) {
        val active = signer.value ?: throw UserError("Sign in first.")
        val others = room - active.pubkey
        val mine = dmRelays.value
        if (mine.isEmpty()) throw UserError("Set up your message relays first.")
        val targets = others.associateWith { dmRelaysOf(it) }
        targets.entries.firstOrNull { it.value.isEmpty() }?.let {
            throw UserError("${displayName(it.key)} has not set up private messages yet.")
        }
        val rumor = Draft(active.pubkey, nowSeconds(), kind, others.map { listOf("p", it) } + extraTags, content)
        val json = rumor.toJson()
        var delivered = false
        for ((recipient, relays) in targets) {
            val result = pool.publishFirst(wrapFor(json, recipient, active), relays.toSet())
            AppLog.add("message to ${relays.joinToString(" ")}: " + if (result.ok) "accepted" else "failed ${result.message}")
            delivered = result.ok || delivered
        }
        if (others.isNotEmpty() && !delivered) throw UserError("The message could not be delivered.")
        val ownCopy = wrapFor(json, active.pubkey, active)
        if (others.isEmpty()) {
            // A note to yourself has no one else to reach, so your own inbox has to take it.
            val result = pool.publishFirst(ownCopy, mine.toSet())
            AppLog.add("note to self to ${mine.joinToString(" ")}: " + if (result.ok) "accepted" else "failed ${result.message}")
            if (!result.ok) throw UserError(result.message.takeIf { it.isNotBlank() } ?: "The message could not be delivered.")
        } else {
            scope.launch { pool.publish(ownCopy, mine.toSet()) }
        }
        store.addDm(Dm(rumor.id, roomKey(room), active.pubkey, rumor.createdAt, preview, file))
        changes.tryEmit(Unit)
    }

    suspend fun sendMessage(room: Set<String>, text: String) = deliver(room, Kind.DM, emptyList(), text, null, text)

    /** NIP-17 kind 15: the file is encrypted here, and the server only ever holds ciphertext. */
    suspend fun sendFile(room: Set<String>, data: ByteArray, type: String, width: Int, height: Int) {
        val key = randomBytes(32)
        val nonce = randomBytes(16)
        val encrypted = FileCipher.encrypt(data, key, nonce)
        val upload = media.upload(encrypted, "application/octet-stream")
        val tags = ArrayList<List<String>>()
        tags += listOf("file-type", type)
        tags += listOf("encryption-algorithm", "aes-gcm")
        tags += listOf("decryption-key", Hex.encode(key))
        tags += listOf("decryption-nonce", Hex.encode(nonce))
        tags += listOf("x", upload.sha256)
        tags += listOf("ox", Hex.encode(sha256(data)))
        tags += listOf("size", encrypted.size.toString())
        if (width > 0 && height > 0) tags += listOf("dim", "${width}x$height")
        val file = FileMessage(
            upload.url, type, Hex.encode(key), Hex.encode(nonce), upload.sha256,
            if (height > 0) width.toFloat() / height else null,
        )
        deliver(room, Kind.FILE_DM, tags, upload.url, file, if (type.startsWith("image/")) "Photo" else "File")
    }

    /** Downloads, checks and decrypts an attachment. The plaintext is never written to disk here. */
    suspend fun openFile(file: FileMessage): ByteArray = withContext(Dispatchers.IO) {
        val data = Http.bytes(file.url)
        if (file.hash != null && Hex.encode(sha256(data)) != file.hash) throw UserError("The file failed its integrity check.")
        FileCipher.decrypt(data, Hex.decode(file.key), Hex.decode(file.nonce))
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

    /** Who an account follows, from its kind 3 list, without keeping other people's lists in the cache. */
    suspend fun followingOf(pubkey: String): List<String> = withContext(Dispatchers.IO) {
        if (pubkey == me) return@withContext follows.value.toList()
        var newest: Event? = null
        pool.query(
            (lookupRelays() + DEFAULT_RELAYS).toSet() + relaysOf(pubkey)?.write.orEmpty(),
            listOf(Filter(authors = listOf(pubkey), kinds = listOf(Kind.FOLLOWS), limit = 1)),
        ) { e, _ ->
            synchronized(this@Core) { if ((newest?.createdAt ?: 0) < e.createdAt) newest = e }
        }
        newest?.tags?.values("p")?.filter(Hex::isKey)?.distinct().orEmpty()
    }

    /** NIP-45 where relays support it. No relay sees everyone, so this is a floor, not a census. */
    suspend fun followerCount(pubkey: String): Long? = withContext(Dispatchers.IO) {
        pool.count(
            (lookupRelays() + DEFAULT_RELAYS).toSet() + readRelays(),
            Filter(kinds = listOf(Kind.FOLLOWS), tags = mapOf("p" to listOf(pubkey))),
        )
    }

    /** A sample of followers: kind 3 lists are heavy, so this runs only when someone asks to see them. */
    suspend fun followersOf(pubkey: String): List<String> = withContext(Dispatchers.IO) {
        val found = LinkedHashSet<String>()
        pool.query(
            (lookupRelays() + DEFAULT_RELAYS).toSet(),
            listOf(Filter(kinds = listOf(Kind.FOLLOWS), tags = mapOf("p" to listOf(pubkey)), limit = 300)),
            8_000,
        ) { e, _ ->
            synchronized(found) { found += e.pubkey }
        }
        synchronized(found) { found.toList() }
    }

    /**
     * Republishes the account's newest lists exactly as they were signed, so no signer is involved.
     * The mute list stays where it is: nobody needs it to find this account.
     */
    suspend fun copyListsToPopularRelays(): String = withContext(Dispatchers.IO) {
        val pubkey = author()
        if (signer.value is ReadOnlySigner) throw UserError("This account is read-only.")
        val own = writeRelays() + readRelays()
        val answered = pool.query(
            own + lookupRelays() + DEFAULT_RELAYS,
            listOf(Filter(authors = listOf(pubkey), kinds = PUBLIC_LISTS)),
            8_000,
            onEvent = ::ingest,
        )
        // Copying is only useful with the newest versions, and those are on the account's relays.
        if (answered.none { it in own }) throw UserError("Could not reach your relays. Try again.")
        delay(400)
        val lists = PUBLIC_LISTS.mapNotNull { store.latest(pubkey, it) }
        if (lists.isEmpty()) throw UserError("No lists were found to copy.")
        val targets = (lookupRelays() + DEFAULT_RELAYS).toSet()
        val reached = HashSet<String>()
        for (list in lists) {
            pool.publish(list, targets).filterValues { it.ok }.keys.let(reached::addAll)
        }
        if (reached.isEmpty()) throw UserError("No relay accepted your lists.")
        "Copied ${lists.size} lists to ${reached.size} of ${targets.size} relays"
    }

    // Link previews

    fun linkPreviewsEnabled() = prefs.getBoolean("link_previews", true)

    fun setLinkPreviews(on: Boolean) = prefs.edit().putBoolean("link_previews", on).apply()

    /** Open Graph data for a link, cached on disk so that each site is asked once. */
    suspend fun linkInfo(url: String): LinkInfo? = withContext(Dispatchers.IO) {
        if (!isPreviewable(url)) return@withContext null
        val file = File(previewDir, Hex.encode(sha256(url.toByteArray())))
        if (file.exists()) {
            return@withContext runCatching {
                val o = JSONObject(file.readText())
                LinkInfo(
                    url, o.getString("t"), o.getString("d"), o.optString("i").takeIf { it.isNotEmpty() },
                    o.optString("m").takeIf { it.isNotEmpty() },
                )
            }.getOrNull()
        }
        val page = previewSlots.withPermit { runCatching { Http.inspect(url) }.getOrNull() } ?: return@withContext null
        val info = mediaKind(page.type)?.let { LinkInfo(url, "", "", null, it) }
            ?: page.html?.let { parseLinkInfo(url, it) }?.let { it.copy(title = decodeHtml(it.title), description = decodeHtml(it.description)) }
        if (info != null) {
            previewDir.mkdirs()
            file.writeText(
                JSONObject().put("t", info.title).put("d", info.description).put("i", info.image ?: "").put("m", info.media ?: "").toString(),
            )
        }
        info
    }

    private fun decodeHtml(text: String) =
        android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

    // Trusted network

    fun trustFilterEnabled() = prefs.getBoolean("trust_filter", true)

    fun setTrustFilter(on: Boolean) = prefs.edit().putBoolean("trust_filter", on).apply()

    /** With no follows yet there is no network to go by, so nothing is hidden. */
    fun trustFilterActive() = trustFilterEnabled() && trustedSet.value.size > follows.value.size

    fun trustedNow(): Set<String> = trustedSet.value

    /** Reads what the people you follow follow, keeps only the result, and does so once a day. */
    private suspend fun refreshTrustedNetwork() {
        val pubkey = me ?: return
        val mine = follows.value
        if (mine.isEmpty() || !trustFilterEnabled()) return
        val now = nowSeconds()
        if (now - prefs.getLong("trusted_at", 0L) < 86400 && trustedSet.value.isNotEmpty()) return
        val lists = HashMap<String, Event>()
        for (chunk in mine.toList().chunked(MAX_AUTHORS)) {
            pool.query(
                (lookupRelays() + DEFAULT_RELAYS).toSet() + readRelays().take(2),
                listOf(Filter(authors = chunk, kinds = listOf(Kind.FOLLOWS))),
                10_000,
            ) { e, _ ->
                synchronized(lists) { if ((lists[e.pubkey]?.createdAt ?: 0) < e.createdAt) lists[e.pubkey] = e }
            }
        }
        val theirs = synchronized(lists) { lists.values.map { it.tags.values("p").filter(Hex::isKey) } }
        if (theirs.isEmpty()) return
        val network = trustedNetwork(pubkey, mine, theirs, TRUST_THRESHOLD)
        store.setTrusted(network)
        trustedSet.value = network
        prefs.edit().putLong("trusted_at", now).apply()
    }

    // Background notifications

    fun notifyMinutes() = prefs.getInt("notify_minutes", Notifier.DEFAULT_MINUTES)

    fun setNotifyMinutes(minutes: Int) {
        prefs.edit().putInt("notify_minutes", minutes).apply()
        Notifier.schedule(context, minutes)
    }

    /** One scheduled check: connect briefly, fetch what is new, notify, disconnect. */
    suspend fun backgroundCheck() {
        ready.await()
        val active = signer.value ?: return
        val pubkey = active.pubkey
        val now = nowSeconds()
        val since = prefs.getLong("notify_since", now - FIRST_CHECK_WINDOW_S)
        if (pool.isVisible) {
            // On screen, activity shows up by itself. The first background check looks back a few
            // hours, so this only moves a starting point that already exists.
            if (prefs.contains("notify_since")) prefs.edit().putLong("notify_since", now).apply()
            return
        }
        val checker = RelayPool(scope) { signer.value }
        checker.activityStarted()
        try {
            val answered = checker.query(
                readRelays().take(4).toSet(),
                listOf(Filter(kinds = ALERT_KINDS, tags = mapOf("p" to listOf(pubkey)), since = since, limit = 50)),
                onEvent = ::ingest,
            )
            val wraps = ArrayList<Event>()
            val inbox = dmRelays.value.toSet()
            if (inbox.isNotEmpty() && active !is ReadOnlySigner) {
                checker.query(
                    inbox,
                    listOf(Filter(kinds = listOf(Kind.GIFT_WRAP), tags = mapOf("p" to listOf(pubkey)), since = since - 2 * 86400, limit = 50)),
                ) { e, _ -> synchronized(wraps) { wraps += e } }
            }
            val seen = prefs.getStringSet("notified_wraps", emptySet()).orEmpty()
            val fresh = synchronized(wraps) { wraps.distinctBy { it.id } }.filter { it.id !in seen && !store.hasWrap(it.id) }
            val messages = fresh.count { receiveWrap(it) != pubkey }
            if (fresh.isNotEmpty()) {
                prefs.edit().putStringSet("notified_wraps", (seen + fresh.map { it.id }).toList().takeLast(300).toSet()).apply()
            }
            delay(500)
            val alerts = store.alerts(pubkey, muted.value, 30).filter { it.event.createdAt > since }
            val names = notificationNames(alerts.map { it.actor }.distinct(), checker)
            AppLog.add("background check: ${alerts.size} alerts, $messages messages, ${answered.size} relays answered")
            Notifier.post(context, alerts, messages) { names[it] ?: shortNpub(it) }
            if (answered.isNotEmpty()) prefs.edit().putLong("notify_since", now).apply()
        } finally {
            checker.shutdown()
        }
    }

    /** Names for a notification, fetched here because nothing on screen has asked for them. */
    private suspend fun notificationNames(actors: List<String>, checker: RelayPool): Map<String, String> {
        val wanted = actors.take(MAX_NOTIFIED_NAMES)
        val known = store.latestOfKind(wanted, Kind.METADATA).map { it.pubkey }.toSet()
        val missing = wanted - known
        if (missing.isNotEmpty()) {
            checker.query(
                (lookupRelays() + DEFAULT_RELAYS).toSet(),
                listOf(Filter(authors = missing, kinds = listOf(Kind.METADATA))),
                onEvent = ::ingest,
            )
            delay(300)
        }
        val names = HashMap<String, String>()
        for (event in store.latestOfKind(wanted, Kind.METADATA)) {
            val profile = parseProfile(event)
            val name = profile.name.ifEmpty { if (nip05Matches(profile)) handleOf(profile).orEmpty() else "" }
            if (name.isNotEmpty()) names[event.pubkey] = name
        }
        return names
    }

    /** Asks every relevant relay what it holds for this account. The report has public data only. */
    suspend fun accountCheck(): String = withContext(Dispatchers.IO) {
        val pubkey = me ?: return@withContext "Not signed in."
        val active = signer.value
        val relays = (lookupRelays() + DEFAULT_RELAYS).toSet() + writeRelays() + readRelays()
        pool.clearProblems()
        val report = StringBuilder()
        report.append(Nip19.npub(pubkey)).append('\n')
        report.append("Sign-in: ").append(
            when (active) {
                is ExternalSigner -> "signer app"
                is ReadOnlySigner -> "read-only"
                is LocalSigner -> "private key"
                else -> "none"
            },
        ).append('\n')
        val filter = Filter(authors = listOf(pubkey), kinds = listOf(Kind.METADATA, Kind.FOLLOWS, Kind.RELAYS, Kind.DM_RELAYS))
        val found = ArrayList<Pair<String, Event>>()
        val answered = coroutineScope {
            relays.map { url ->
                async {
                    val responded = pool.query(setOf(url), listOf(filter), 8_000) { e, relay ->
                        ingest(e, relay)
                        synchronized(found) { found += relay to e }
                    }
                    url to responded.isNotEmpty()
                }
            }.awaitAll()
        }
        delay(400)
        val events = synchronized(found) { found.toList() }
        for ((url, ok) in answered) {
            report.append('\n').append(url.removePrefix("wss://")).append(if (ok) ": answered" else ": no answer").append('\n')
            val here = events.filter { it.first == url }.map { it.second }
            here.firstOrNull { it.kind == Kind.FOLLOWS }?.let {
                report.append("  follows: ").append(it.tags.values("p").size).append(" at ").append(it.createdAt).append(' ').append(it.id.take(8)).append('\n')
            }
            if (ok && here.none { it.kind == Kind.FOLLOWS }) report.append("  follows: none\n")
            here.firstOrNull { it.kind == Kind.RELAYS }?.let {
                val list = parseRelayList(it)
                report.append("  relays: ").append(list.read.size).append(" read, ").append(list.write.size).append(" write\n")
            }
            if (here.any { it.kind == Kind.METADATA }) report.append("  profile: yes\n")
            pool.problemsOf(url).forEach { report.append("  ").append(it).append('\n') }
        }
        report.append("\nFollows loaded: ").append(follows.value.size)
        report.append("\nOwn relays: ").append(writeRelays().size).append(" write, ").append(readRelays().size).append(" read")
        // A clean answer from several relays is what makes starting a first list safe.
        if (events.none { it.second.kind == Kind.FOLLOWS } && answered.count { it.second } >= 3 && pool.noDroppedLists(relays)) {
            prefs.edit().putBoolean("confirmed_no_follow_list", true).apply()
        }
        report.toString()
    }

    fun mediaServers(): List<String> {
        val own = me?.let { store.latest(it, Kind.MEDIA_SERVERS) }?.tags?.values("server")?.filter { it.startsWith("https://") }
        return own?.takeIf { it.isNotEmpty() } ?: DEFAULT_MEDIA_SERVERS
    }

    suspend fun saveMediaServers(servers: List<String>) {
        publish(Draft(author(), nowSeconds(), Kind.MEDIA_SERVERS, servers.map { listOf("server", it) }, ""), extraRelays = lookupRelays().toSet())
    }

    /** Where to find people's profiles and relay lists. Kept on this phone, as no list covers it. */
    fun lookupRelays(): List<String> =
        prefs.getString("lookup_relays", null)?.split('\n')?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_LOOKUP_RELAYS

    fun saveLookupRelays(relays: List<String>) {
        prefs.edit().putString("lookup_relays", relays.joinToString("\n")).apply()
    }

    private fun searchRelayDraft(pubkey: String, relays: List<String>) =
        Draft(pubkey, nowSeconds(), Kind.SEARCH_RELAYS, relays.map { listOf("relay", it) }, "")

    suspend fun saveSearchRelays(relays: List<String>) {
        publish(searchRelayDraft(author(), relays))
    }

    /** NIP-11, kept for a day. An old copy is better than nothing while a relay is away. */
    suspend fun relayInfo(url: String): RelayInfo? = withContext(Dispatchers.IO) {
        val file = File(relayInfoDir, Hex.encode(sha256(url.toByteArray())))
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < RELAY_INFO_MS
        val text = if (fresh) file.readText() else runCatching { Http.relayDocument(url) }.getOrNull()?.also {
            relayInfoDir.mkdirs()
            file.writeText(it)
        } ?: file.takeIf { it.exists() }?.readText()
        text?.let(::parseRelayInfo)
    }

    /**
     * How long a relay takes to answer a small request, or null if it didn't. Search relays may
     * only take searches, and some never say they've finished, so a first result counts as well.
     */
    suspend fun pingRelay(url: String): Long? {
        val searches = relayInfo(url)?.nips?.contains(50) == true
        val filter = if (searches) Filter(kinds = listOf(Kind.NOTE), search = "nostr", limit = 1) else Filter(kinds = listOf(Kind.METADATA), limit = 1)
        val start = System.currentTimeMillis()
        val first = AtomicLong()
        val answered = pool.query(setOf(url), listOf(filter), 5_000) { _, _ -> first.compareAndSet(0, System.currentTimeMillis()) }
        return when {
            first.get() > 0 -> first.get() - start
            url in answered -> System.currentTimeMillis() - start
            else -> null
        }
    }

    /** Unread means not opened here. Marking all read moves a line that older items fall behind. */
    fun unreadAlerts(me: String): Set<String> {
        val since = prefs.getLong("alerts_read", 0L)
        val read = store.readAlerts()
        return store.alerts(me, muted.value, 150).filter { it.event.createdAt > since && it.event.id !in read }.map { it.event.id }.toSet()
    }

    fun unreadRooms(me: String): Map<String, Int> = store.unreadRooms(me, prefs.getLong("dms_read", 0L))

    suspend fun loadVotes(event: Event, poll: Poll) {
        val relays = poll.relays.ifEmpty { readRelays().toList() }.take(MAX_FEED_RELAYS).toSet()
        pool.query(relays, listOf(Filter(kinds = listOf(Kind.POLL_VOTE), tags = mapOf("e" to listOf(event.id)))), onEvent = ::ingest)
    }

    /** NIP-88: votes go to the relays the poll names, because that's where results are counted. */
    suspend fun vote(event: Event, poll: Poll, choices: List<String>) {
        val active = signer.value ?: throw UserError("Sign in first.")
        val tags = listOf(listOf("e", event.id)) + choices.map { listOf("response", it) }
        val signed = active.sign(Draft(active.pubkey, nowSeconds(), Kind.POLL_VOTE, tags, ""))
        val relays = poll.relays.ifEmpty { writeRelays().toList() }.take(MAX_PUBLISH_RELAYS).toSet()
        withContext(NonCancellable) {
            val result = pool.publishFirst(signed, relays)
            AppLog.add("vote to ${relays.size} relays: " + if (result.ok) "accepted" else "failed ${result.message}")
            if (!result.ok) throw UserError(result.message.takeIf { it.isNotBlank() } ?: "No relay accepted this vote.")
            ingest(signed, relays.first())
        }
    }

    suspend fun postPoll(question: String, options: List<String>, multiple: Boolean, endsAt: Long?): Event {
        val (content, tags) = prepareContent(question)
        return publish(Draft(author(), nowSeconds(), Kind.POLL, tags + pollTags(options, multiple, endsAt, writeRelays().take(4)), content))
    }

    fun readAlert(id: String) {
        scope.launch {
            store.markRead(id, nowSeconds())
            changes.tryEmit(Unit)
        }
    }

    fun readRoom(room: String, upTo: Long) {
        scope.launch {
            store.markRead("room:$room", upTo)
            changes.tryEmit(Unit)
        }
    }

    fun readAllAlerts(me: String) {
        scope.launch {
            val newest = store.alerts(me, muted.value, 1).firstOrNull()?.event?.createdAt ?: 0L
            prefs.edit().putLong("alerts_read", maxOf(nowSeconds(), newest)).apply()
            store.clearReads(rooms = false)
            changes.tryEmit(Unit)
        }
    }

    fun readAllRooms(me: String) {
        scope.launch {
            val newest = store.rooms(me).maxOfOrNull { it.last.createdAt } ?: 0L
            prefs.edit().putLong("dms_read", maxOf(nowSeconds(), newest)).apply()
            store.clearReads(rooms = true)
            changes.tryEmit(Unit)
        }
    }

    /** Logs to copy, headed by what they need to be understood. Nothing here identifies the account. */
    fun logReport(): String = buildString {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val key = when (signer.value) {
            is ExternalSigner -> "signer app"
            is ReadOnlySigner -> "read-only"
            null -> "signed out"
            else -> "key on this phone"
        }
        appendLine("Struthio $version, Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL}, $key")
        appendLine("General read: ${readRelays().joinToString(" ")}")
        appendLine("General write: ${writeRelays().joinToString(" ")}")
        appendLine("Messaging: ${dmRelays.value.joinToString(" ")}")
        appendLine("Search: ${searchRelays().joinToString(" ")}")
        appendLine("Lookup: ${lookupRelays().joinToString(" ")}")
        appendLine()
        append(AppLog.text())
    }

    fun relayUses(url: String): List<String> = buildList {
        if (url in readRelays()) add("Reading your feed")
        if (url in writeRelays()) add("Posting your notes")
        if (url in dmRelays.value) add("Private messages")
        if (url in searchRelays()) add("Search")
        if (url in lookupRelays()) add("Profile lookup")
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
        private const val RELAY_INFO_MS = 86_400_000L
        private const val MAX_FEED_RELAYS = 8
        private const val TRUST_THRESHOLD = 2
        private const val MAX_NOTIFIED_NAMES = 8
        private const val FIRST_CHECK_WINDOW_S = 6 * 3600L

        /** One request stays well under the frame limits relays apply. */
        const val MAX_AUTHORS = 400
        private const val MAX_PUBLISH_RELAYS = 12

        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
        val DEFAULT_LOOKUP_RELAYS = listOf("wss://purplepag.es", "wss://indexer.coracle.social")
        val DEFAULT_SEARCH_RELAYS = listOf("wss://relay.ditto.pub")
        val DEFAULT_DM_RELAYS = listOf("wss://auth.nostr1.com", "wss://nos.lol")
        val DEFAULT_MEDIA_SERVERS = listOf("https://blossom.primal.net", "https://blossom.band")

        val OWN_LISTS = listOf(Kind.METADATA, Kind.FOLLOWS, Kind.MUTES, Kind.BOOKMARKS, Kind.RELAYS, Kind.SEARCH_RELAYS, Kind.DM_RELAYS, Kind.MEDIA_SERVERS)
        val PUBLIC_LISTS = listOf(Kind.METADATA, Kind.FOLLOWS, Kind.RELAYS, Kind.DM_RELAYS, Kind.MEDIA_SERVERS)
        val STAT_KINDS = listOf(Kind.NOTE, Kind.REPOST, Kind.REACTION, Kind.COMMENT, Kind.ZAP_RECEIPT)
        val ALERT_KINDS = listOf(Kind.NOTE, Kind.COMMENT, Kind.REPOST, Kind.REACTION, Kind.ZAP_RECEIPT)
    }
}
