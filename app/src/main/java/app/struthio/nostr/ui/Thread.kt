package app.struthio.nostr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import app.struthio.nostr.data.Upload
import kotlinx.coroutines.delay
import app.struthio.nostr.data.Feed
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch

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

/** Who a comment answers. Tapping it goes to that comment. */
@Composable
private fun ReplyLine(author: String?, onClick: () -> Unit) {
    val core = LocalCore.current
    LaunchedEffect(author) { author?.let(core::wantProfile) }
    Text(
        "Replying to " + (author?.let(core::displayName) ?: "a note"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp).clickable(onClick = onClick),
    )
}

/** Above the comment box: where this comment is going. */
@Composable
private fun ReplyTarget(note: Event, onClear: (() -> Unit)?) {
    val core = LocalCore.current
    LaunchedEffect(note.pubkey) { core.wantProfile(note.pubkey) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Replying to " + core.displayName(note.pubkey),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                note.content.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClear != null) IconButton(onClear) { Icon(Icons.Rounded.Close, "Reply to the opened note instead") }
    }
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
    val draft = remember { NoteDraft() }
    var sending by remember { mutableStateOf(false) }
    val attached = remember { mutableStateListOf<Upload>() }
    var replyTo by remember(screen.id) { mutableStateOf<Event?>(null) }
    val target = replyTo ?: focus
    val list = remember(notes, focus) { notes.ifEmpty { listOfNotNull(focus?.let { it to 0 }) } }
    val rows = remember(list) { list.withIndex().associate { (index, row) -> row.first.id to index } }
    val listState = rememberLazyListState()
    val field = remember { FocusRequester() }
    var placed by remember(screen.id) { mutableStateOf(false) }
    // Opens at the note that was tapped. Anything loading above it afterwards doesn't move the view.
    LaunchedEffect(rows) {
        val row = rows[screen.id]
        if (!placed && row != null && notes.isNotEmpty()) {
            placed = true
            if (row > 0) listState.scrollToItem(row)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Thread")
        if (focus == null) {
            Loading(Modifier.fillMaxWidth().weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(list, key = { _, it -> it.first.id }) { index, (event, depth) ->
                    val parent = threadRef(event).parent
                    NoteCard(
                        event,
                        Modifier.padding(start = (minOf(depth, 3) * 12).dp),
                        detail = event.id == screen.id,
                        shape = segmentShape(index, list.size),
                        highlighted = event.id == target?.id,
                        onReply = {
                            replyTo = it
                            field.requestFocus()
                        },
                        top = if (depth > 0 && parent != null) {
                            {
                                ReplyLine(list.getOrNull(rows[parent] ?: -1)?.first?.pubkey ?: threadRef(event).parentAuthor) {
                                    rows[parent]?.let { row -> scope.launch { listState.animateScrollToItem(row) } }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
              Column {
                target?.let { note ->
                    ReplyTarget(note, onClear = if (replyTo != null && note.id != focus?.id) ({ replyTo = null }) else null)
                }
                AttachedImages(attached) { attached.remove(it) }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AttachImageButton { attached += it }
                    NoteField(draft, "Add a comment", Modifier.weight(1f).focusRequester(field), maxLines = 4)
                    FilledIconButton(
                        onClick = {
                            val parent = target ?: return@FilledIconButton
                            sending = true
                            scope.act(nav) {
                                try {
                                    core.reply(parent, draft.finalText(core::mentionRelays), attached.toList())
                                    draft.clear()
                                    attached.clear()
                                    replyTo = null
                                } finally {
                                    sending = false
                                }
                            }
                        },
                        enabled = (draft.value.text.isNotBlank() || attached.isNotEmpty()) && !sending,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                    }
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
    val draft = remember { NoteDraft(if (quoteId == null) core.prefs.getString("note_draft", "").orEmpty() else "") }
    var sending by remember { mutableStateOf(false) }
    var sensitive by remember { mutableStateOf(false) }
    val attached = remember { mutableStateListOf<Upload>() }
    val text = draft.value.text
    if (quoteId == null) {
        // Saved as it is typed, so leaving the screen or the app loses nothing.
        LaunchedEffect(text) {
            delay(400)
            core.prefs.edit().putString("note_draft", text).apply()
        }
    }
    val quoted by produceState<Event?>(null, quoteId) {
        if (quoteId != null) value = core.fetchEvent(quoteId)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(if (quoteId == null) "New note" else "Quote note") {
            AttachImageButton { attached += it }
            if (quoteId == null) {
                IconButton(
                    {
                        sensitive = !sensitive
                        nav.say(if (sensitive) "Marked as sensitive content" else "No longer marked as sensitive")
                    },
                    colors = if (sensitive) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
                ) {
                    Icon(Icons.Rounded.VisibilityOff, if (sensitive) "Sensitive content, on" else "Sensitive content, off")
                }
            }
            androidx.compose.material3.Button(
                onClick = {
                    sending = true
                    scope.act(nav) {
                        try {
                            val target = quoted
                            val body = draft.finalText(core::mentionRelays)
                            if (target != null) core.quote(target, body, attached.toList()) else core.postNote(body, attached.toList(), sensitive)
                            if (quoteId == null) {
                                core.prefs.edit().remove("note_draft").apply()
                                draft.clear()
                            }
                            nav.pop()
                        } finally {
                            sending = false
                        }
                    }
                },
                enabled = (text.isNotBlank() || attached.isNotEmpty()) && !sending && (quoteId == null || quoted != null),
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(if (sending) "Posting…" else "Post") }
        }
        NoteField(draft, "What's on your mind?", Modifier.fillMaxWidth().weight(1f).padding(PageGutter))
        AttachedImages(attached) { attached.remove(it) }
        quoted?.let {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceBright,
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(core.displayName(it.pubkey), style = MaterialTheme.typography.labelLarge)
                    NoteBody(it, inQuote = true)
                }
            }
        }
    }
}
