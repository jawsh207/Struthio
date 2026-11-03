package app.struthio.nostr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.data.Feed
import app.struthio.nostr.data.FeedSource

private val REPORT_TYPES = listOf(
    "spam" to "Spam",
    "nudity" to "Nudity",
    "profanity" to "Hateful or abusive",
    "illegal" to "Illegal",
    "impersonation" to "Impersonation",
    "malware" to "Malware",
    "other" to "Something else",
)

@Composable
fun NoteMenu(event: Event) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bookmarks by core.bookmarks.collectAsState()
    var open by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val mine = event.pubkey == core.me
    val saved = event.id in bookmarks

    Box {
        IconButton({ open = true }, Modifier.size(32.dp)) {
            Icon(Icons.Rounded.MoreVert, "More", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(if (saved) "Remove bookmark" else "Bookmark") }, onClick = {
                open = false
                scope.act(nav) {
                    core.setBookmarked(event, !saved)
                    nav.say(if (saved) "Bookmark removed" else "Bookmarked")
                }
            })
            DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                open = false
                context.copy("nostr:" + Nip19.nevent(event.id, listOf(core.hintFor(event.id)).filter { it.isNotEmpty() }, event.pubkey))
                nav.say("Link copied")
            })
            if (mine) {
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = {
                    open = false
                    deleting = true
                })
            } else {
                DropdownMenuItem(text = { Text("Report") }, onClick = {
                    open = false
                    reporting = true
                })
            }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this note?") },
            text = { Text("Relays are asked to remove it, but each one decides for itself, and copies may remain.") },
            confirmButton = {
                TextButton({
                    deleting = false
                    scope.act(nav) {
                        core.deleteNote(event)
                        nav.say("Note deleted")
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
        )
    }
    if (reporting) {
        var type by remember { mutableStateOf("spam") }
        var mute by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text("Report this note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reports are public events on Nostr.", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        REPORT_TYPES.forEach { (id, label) ->
                            FilterChip(type == id, { type = id }, { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(mute, { mute = it })
                        Text("Also mute ${core.displayName(event.pubkey)}")
                    }
                }
            },
            confirmButton = {
                TextButton({
                    reporting = false
                    scope.act(nav) {
                        core.report(event, type)
                        if (mute) core.setMuted(event.pubkey, true)
                        nav.say("Report sent")
                    }
                }) { Text("Report") }
            },
            dismissButton = { TextButton({ reporting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun BookmarksScreen() {
    val core = LocalCore.current
    val feed = remember { Feed(core, FeedSource.Bookmarks) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.stop() }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("Bookmarks")
        NoteList(feed, Modifier.weight(1f).padding(top = 8.dp), empty = "Notes you bookmark show up here.")
    }
}
