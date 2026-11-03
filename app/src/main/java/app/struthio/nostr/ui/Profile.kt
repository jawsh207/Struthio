package app.struthio.nostr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.Feed
import app.struthio.nostr.data.FeedSource
import app.struthio.nostr.data.roomKey
import kotlinx.coroutines.delay

fun Context.copy(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Struthio", text))
}

@Composable
fun ProfileScreen(pubkey: String) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feed = remember(pubkey) { Feed(core, FeedSource.Author(pubkey)) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.stop() }
    }
    LaunchedEffect(pubkey) { core.wantProfile(pubkey) }
    val profile = core.profiles[pubkey]
    val follows by core.follows.collectAsState()
    val muted by core.muted.collectAsState()
    val isMe = pubkey == core.me
    var menu by remember { mutableStateOf(false) }
    var zapping by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(core.displayName(pubkey)) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Copy npub") }, onClick = {
                    menu = false
                    context.copy(Nip19.npub(pubkey))
                })
                if (!isMe) {
                    val isMuted = pubkey in muted
                    DropdownMenuItem(text = { Text(if (isMuted) "Unmute" else "Mute") }, onClick = {
                        menu = false
                        scope.act(nav) { core.setMuted(pubkey, !isMuted) }
                    })
                }
            }
        }
        NoteList(feed, Modifier.weight(1f), header = {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(pubkey, 84.dp)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(core.displayName(pubkey), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                            if (profile?.verified == true) {
                                Icon(Icons.Filled.Verified, "Verified", Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        profile?.nip05?.let { Text(it.removePrefix("_@"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                profile?.about?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (!isMe) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val following = pubkey in follows
                        val click = {
                            busy = true
                            scope.act(nav) {
                                try {
                                    core.setFollowing(pubkey, !following)
                                } finally {
                                    busy = false
                                }
                            }
                            Unit
                        }
                        if (following) {
                            FilledTonalButton(click, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Following") }
                        } else {
                            Button(click, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Follow") }
                        }
                        FilledTonalIconButton({
                            nav.push(Screen.Chat(roomKey(setOf(pubkey, core.me ?: ""))))
                        }) { Icon(Icons.Outlined.ChatBubbleOutline, "Message") }
                        FilledTonalIconButton({ zapping = true }) { Icon(Icons.Filled.Bolt, "Zap") }
                    }
                }
            }
        })
    }
    if (zapping) ZapSheet(null, pubkey) { zapping = false }
}

@Composable
fun SearchScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val feed = remember(submitted) { submitted.takeIf { it.isNotBlank() }?.let { Feed(core, FeedSource.Search(it)) } }
    DisposableEffect(feed) {
        feed?.start()
        onDispose { feed?.stop() }
    }
    val people by liveQuery(emptyList(), query) {
        if (query.length >= 2) core.store.searchProfiles(query, 12).map { it.pubkey } else emptyList()
    }
    LaunchedEffect(query) {
        if (query.length < 3) return@LaunchedEffect
        delay(500)
        core.pool.query(
            core.searchRelays(),
            listOf(Filter(kinds = listOf(Kind.METADATA), search = query, limit = 12)),
            4000,
            onEvent = core::ingest,
        )
    }

    fun submit() {
        val text = query.trim()
        when {
            text.startsWith("#") && text.length > 1 -> nav.push(Screen.Hashtag(text.drop(1)))
            else -> when (val entity = Nip19.decode(text)) {
                is Entity.Profile -> nav.push(Screen.Profile(entity.pubkey))
                is Entity.Note -> nav.push(Screen.Thread(entity.id, entity.relays, entity.author))
                else -> submitted = text
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Search")
        OutlinedTextField(
            query,
            { query = it },
            placeholder = { Text("Notes, people, #tags, npub") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { IconButton(onClick = ::submit) { Icon(Icons.Filled.Search, "Search") } },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        if (feed != null) {
            NoteList(feed, Modifier.weight(1f).padding(top = 8.dp), empty = "No notes found.")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(people, key = { it }) { pubkey ->
                    PersonRow(core, pubkey) { nav.push(Screen.Profile(pubkey)) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
fun PersonRow(core: Core, pubkey: String, onClick: () -> Unit) {
    LaunchedEffect(pubkey) { core.wantProfile(pubkey) }
    ListItem(
        headlineContent = { Text(core.displayName(pubkey)) },
        supportingContent = core.profiles[pubkey]?.nip05?.let { { Text(it) } },
        leadingContent = { Avatar(pubkey, 40.dp) },
        modifier = Modifier.tap(onClick),
    )
}
