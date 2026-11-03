package app.struthio.nostr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.threadRef
import app.struthio.nostr.data.FeedSource
import app.struthio.nostr.data.Feed
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect

/** Replies in reading order, each under its parent, with depth capped so text stays wide. */
private fun ordered(all: List<Event>, rootId: String): List<Pair<Event, Int>> {
    val byParent = all.filter { it.id != rootId }.groupBy { threadRef(it).parent ?: rootId }
    val known = all.map { it.id }.toSet()
    val out = ArrayList<Pair<Event, Int>>()
    fun walk(id: String, depth: Int) {
        byParent[id].orEmpty().forEach {
            out += it to depth
            walk(it.id, depth + 1)
        }
    }
    all.firstOrNull { it.id == rootId }?.let { out += it to 0 }
    walk(rootId, 1)
    // Replies whose parent has not arrived still deserve a place.
    byParent.filterKeys { it != rootId && it !in known }.values.flatten().forEach { out += it to 1 }
    return out
}

@Composable
fun ThreadScreen(screen: Screen.Thread) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val focus by produceState<Event?>(core.store.event(screen.id), screen.id) {
        value = core.fetchEvent(screen.id, screen.relays, screen.author)
    }
    val root by produceState<String?>(null, focus) {
        focus?.let { value = core.loadThread(it) }
    }
    val rootId = root ?: focus?.let { threadRef(it).root ?: it.id }
    val events by liveQuery(emptyList<Event>(), rootId) { rootId?.let(core.store::thread).orEmpty() }
    val notes = remember(events, rootId) { rootId?.let { ordered(events, it) }.orEmpty() }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Thread")
        if (focus == null) {
            Loading(Modifier.fillMaxWidth().weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val list = notes.ifEmpty { listOf(focus!! to 0) }
                items(list, key = { it.first.id }) { (event, depth) ->
                    NoteCard(event, Modifier.padding(start = (minOf(depth, 3) * 12).dp), detail = event.id == screen.id)
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        draft,
                        { draft = it },
                        placeholder = { Text("Add a comment") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(
                        onClick = {
                            val target = focus ?: return@FilledIconButton
                            sending = true
                            scope.act(nav) {
                                try {
                                    core.reply(target, draft.trim())
                                    draft = ""
                                } finally {
                                    sending = false
                                }
                            }
                        },
                        enabled = draft.isNotBlank() && !sending,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                    }
                }
            }
        }
    }
}

@Composable
fun HashtagScreen(tag: String) {
    val core = LocalCore.current
    val feed = remember(tag) { Feed(core, FeedSource.Hashtag(tag)) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.stop() }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("#$tag")
        NoteList(feed, Modifier.weight(1f))
    }
}

@Composable
fun ComposeScreen(quoteId: String?) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val quoted by produceState<Event?>(null, quoteId) {
        if (quoteId != null) value = core.fetchEvent(quoteId)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(if (quoteId == null) "New note" else "Quote note") {
            androidx.compose.material3.Button(
                onClick = {
                    sending = true
                    scope.act(nav) {
                        try {
                            val target = quoted
                            if (target != null) core.quote(target, text.trim()) else core.postNote(text.trim())
                            nav.pop()
                        } finally {
                            sending = false
                        }
                    }
                },
                enabled = text.isNotBlank() && !sending && (quoteId == null || quoted != null),
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(if (sending) "Posting…" else "Post") }
        }
        OutlinedTextField(
            text,
            { text = it },
            placeholder = { Text("What's on your mind?") },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
        )
        quoted?.let {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceBright,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(core.displayName(it.pubkey), style = MaterialTheme.typography.labelLarge)
                    NoteBody(it, inQuote = true)
                }
            }
        }
    }
}
