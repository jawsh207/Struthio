package app.struthio.nostr.data

import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.threadRef
import app.struthio.nostr.core.values
import app.struthio.nostr.net.Subscription
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface FeedSource {
    data object Following : FeedSource
    data object Trending : FeedSource
    data object Global : FeedSource
    data object MyRelays : FeedSource
    data class Author(val pubkey: String) : FeedSource
    data class Hashtag(val tag: String) : FeedSource
    data class Search(val query: String) : FeedSource
}

/** One list of notes: cache first, then live from the relays that matter for it. */
class Feed(private val core: Core, private val source: FeedSource) {
    val notes = MutableStateFlow<List<Event>>(emptyList())
    val loading = MutableStateFlow(true)

    private val byId = LinkedHashMap<String, Event>()
    private val subs = ArrayList<Subscription>()
    private var flushJob: Job? = null
    private var started = false
    private var coverage: Map<String, Set<String>> = emptyMap()
    private var moreRunning = false

    private val kinds = listOf(Kind.NOTE, Kind.REPOST)

    fun start() {
        if (started) return
        started = true
        core.scope.launch {
            preload()
            when (source) {
                FeedSource.Trending -> loadTrending()
                is FeedSource.Search -> loadSearch(source.query)
                else -> subscribe()
            }
            loading.value = false
        }
    }

    fun stop() {
        subs.forEach(core.pool::close)
        subs.clear()
        flushJob?.cancel()
        started = false
    }

    fun refresh() {
        stop()
        start()
    }

    private fun preload() {
        val now = nowSeconds() + 60
        val cached = when (source) {
            FeedSource.Following -> core.store.followingFeed(PAGE, now)
            is FeedSource.Author -> core.store.authorNotes(source.pubkey, PAGE, now)
            is FeedSource.Search -> core.store.searchNotes(source.query, PAGE)
            else -> emptyList()
        }
        cached.forEach { add(it) }
        flush()
    }

    private fun filterFor(authors: Collection<String>?, since: Long?, until: Long? = null) = Filter(
        authors = authors,
        kinds = kinds,
        tags = (source as? FeedSource.Hashtag)?.let { mapOf("t" to listOf(it.tag.lowercase())) } ?: emptyMap(),
        since = since,
        until = until,
        limit = PAGE,
    )

    private suspend fun subscribe() {
        val newest = byId.values.maxOfOrNull { it.createdAt }
        coverage = when (val s = source) {
            FeedSource.Following -> core.outbox(core.follows.value)
            is FeedSource.Author -> {
                val relays = core.outbox(listOf(s.pubkey), 4).keys
                relays.associateWith { setOf(s.pubkey) }
            }
            FeedSource.MyRelays -> (core.readRelays() + core.writeRelays()).associateWith { emptySet() }
            else -> Core.DEFAULT_RELAYS.associateWith { emptySet() }
        }
        for ((relay, authors) in coverage) {
            if (source == FeedSource.Following && authors.isEmpty()) continue
            val filter = filterFor(authors.takeIf { it.isNotEmpty() }, newest?.minus(3600))
            subs += core.pool.subscribe(setOf(relay), listOf(filter), onEvent = ::receive)
        }
        delay(2500)
    }

    private fun receive(event: Event, relay: String) {
        core.ingest(event, relay)
        if (source == FeedSource.Following && event.pubkey !in core.follows.value) return
        if (source is FeedSource.Author && event.pubkey != (source as FeedSource.Author).pubkey) return
        add(event)
        scheduleFlush()
    }

    private fun add(event: Event) {
        if (event.pubkey in core.muted.value) return
        if (event.isExpired()) return
        if (source !is FeedSource.Author && source !is FeedSource.Search && threadRef(event).root != null) return
        synchronized(byId) { byId.putIfAbsent(event.id, event) }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = core.scope.launch {
            delay(350)
            flush()
        }
    }

    private fun flush() {
        notes.value = synchronized(byId) { byId.values.sortedByDescending { it.createdAt } }
    }

    fun loadMore() {
        if (moreRunning || source == FeedSource.Trending || source is FeedSource.Search) return
        val oldest = notes.value.lastOrNull()?.createdAt ?: return
        moreRunning = true
        core.scope.launch {
            val cached = when (source) {
                FeedSource.Following -> core.store.followingFeed(PAGE, oldest)
                is FeedSource.Author -> core.store.authorNotes(source.pubkey, PAGE, oldest)
                else -> emptyList()
            }
            cached.forEach { add(it) }
            flush()
            if (cached.size < PAGE / 2) {
                for ((relay, authors) in coverage) {
                    if (source == FeedSource.Following && authors.isEmpty()) continue
                    core.pool.query(setOf(relay), listOf(filterFor(authors.takeIf { it.isNotEmpty() }, null, oldest)), 5000, ::receive)
                }
                flush()
            }
            moreRunning = false
        }
    }

    /** What the relays are reacting to right now, counted here rather than taken from a ranking service. */
    private suspend fun loadTrending() {
        val score = HashMap<String, Int>()
        val since = nowSeconds() - 86400
        core.pool.query(
            Core.DEFAULT_RELAYS.toSet(),
            listOf(Filter(kinds = listOf(Kind.REACTION, Kind.REPOST), since = since, limit = 800)),
            8000,
        ) { e, _ ->
            e.tags.values("e").lastOrNull()?.let { id ->
                synchronized(score) { score[id] = (score[id] ?: 0) + if (e.kind == Kind.REPOST) 3 else 1 }
            }
        }
        val top = synchronized(score) { score.entries.sortedByDescending { it.value }.take(50).map { it.key } }
        if (top.isEmpty()) return
        val found = ArrayList<Event>()
        core.pool.query(Core.DEFAULT_RELAYS.toSet(), listOf(Filter(ids = top, kinds = listOf(Kind.NOTE)))) { e, relay ->
            core.ingest(e, relay)
            synchronized(found) { found += e }
        }
        val rank = top.withIndex().associate { it.value to it.index }
        val muted = core.muted.value
        notes.value = synchronized(found) {
            found.distinctBy { it.id }.filter { it.pubkey !in muted }.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        }
    }

    /** NIP-50 on search relays, added to whatever the local cache already matched. */
    private suspend fun loadSearch(query: String) {
        core.pool.query(
            core.searchRelays(),
            listOf(Filter(kinds = listOf(Kind.NOTE), search = query, limit = PAGE)),
            8000,
            ::receive,
        )
        flush()
    }

    private companion object {
        const val PAGE = 50
    }
}
