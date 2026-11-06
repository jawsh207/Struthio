package app.struthio.nostr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.invoicesIn
import app.struthio.nostr.core.isNip05Address
import app.struthio.nostr.core.replaceInvoices
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
    var showQr by remember { mutableStateOf(false) }
    val followingList by produceState<List<String>?>(null, pubkey, follows) { value = core.followingOf(pubkey) }
    val followerCount by produceState<Long?>(null, pubkey) { value = core.followerCount(pubkey) }

    Column(Modifier.fillMaxSize()) {
        BackBar(core.displayName(pubkey)) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Copy npub") }, onClick = {
                    menu = false
                    context.copy(Nip19.npub(pubkey))
                })
                DropdownMenuItem(text = { Text("Show QR code") }, onClick = {
                    menu = false
                    showQr = true
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
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(pubkey, 84.dp)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EmojiText(core.richName(pubkey), profile?.emoji.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                            if (profile?.verified == true) {
                                Icon(Icons.Rounded.Verified, "Verified", Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        profile?.nip05?.let { Text(it.removePrefix("_@"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                profile?.about?.takeIf { it.isNotBlank() }?.let { about ->
                    val text = replaceInvoices(about) { "" }.trim()
                    if (text.isNotEmpty()) EmojiText(text, profile.emoji, style = MaterialTheme.typography.bodyMedium)
                    invoicesIn(about).forEach { InvoiceCard(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ nav.push(Screen.People(pubkey, followers = false)) }) {
                        Text("${followingList?.size?.toLong()?.let(::compact) ?: "–"} following")
                    }
                    TextButton({ nav.push(Screen.People(pubkey, followers = true)) }) {
                        Text(followerCount?.let { "${compact(it)} followers" } ?: "Followers")
                    }
                }
                if (isMe) {
                    FilledTonalButton({ nav.push(Screen.EditProfile) }, Modifier.fillMaxWidth()) { Text("Edit profile") }
                } else {
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
                        FilledTonalIconButton({ zapping = true }) { Icon(Icons.Rounded.Bolt, "Zap") }
                    }
                }
            }
        })
    }
    if (zapping) ZapSheet(null, pubkey) { zapping = false }
    if (showQr) {
        ModalBottomSheet(onDismissRequest = { showQr = false }) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                QrImage("nostr:" + Nip19.npub(pubkey))
            }
        }
    }
}

@Composable
fun PeopleScreen(screen: Screen.People) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val people by produceState<List<String>?>(null, screen) {
        value = if (screen.followers) core.followersOf(screen.pubkey) else core.followingOf(screen.pubkey)
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(if (screen.followers) "Followers" else "Following")
        val list = people
        when {
            list == null -> Loading(Modifier.fillMaxWidth().padding(48.dp))
            list.isEmpty() -> Text("Nobody found on these relays.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, it -> it }) { index, pubkey ->
                    PersonRow(core, pubkey, segmentShape(index, list.size)) { nav.push(Screen.Profile(pubkey)) }
                }
            }
        }
    }
}

/** People matching what was typed: an npub or nprofile, a NIP-05 address, or a name. Follows come first. */
@Composable
fun rememberPeople(query: String): List<String> {
    val core = LocalCore.current
    val text = query.trim()
    val exact = remember(text) { (Nip19.decode(text.removePrefix("nostr:")) as? Entity.Profile)?.pubkey }
    var owner by remember(text) { mutableStateOf<String?>(null) }
    val named by liveQuery(emptyList(), text) {
        if (text.length >= 2) core.store.searchProfiles(text, 12).map { it.pubkey } else emptyList()
    }
    LaunchedEffect(text) {
        if (exact != null || text.length < 3) return@LaunchedEffect
        delay(500)
        owner = core.resolveAddress(text)
        core.pool.query(
            core.searchRelays(),
            listOf(Filter(kinds = listOf(Kind.METADATA), search = text, limit = 12)),
            4000,
            onEvent = core::ingest,
        )
    }
    return remember(exact, owner, named) {
        val follows = core.follows.value
        (listOfNotNull(exact, owner) + named.sortedByDescending { it in follows }).distinct()
    }
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
    val people = rememberPeople(query)

    fun submit() {
        val text = query.trim()
        when {
            text.startsWith("#") && text.length > 1 -> nav.push(Screen.Hashtag(text.drop(1)))
            isNip05Address(text) && people.isNotEmpty() -> nav.push(Screen.Profile(people.first()))
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
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                Row {
                    ScanButton {
                        query = it.removePrefix("nostr:")
                        submit()
                    }
                    IconButton(onClick = ::submit) { Icon(Icons.Rounded.Search, "Search") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter),
        )
        if (feed != null) {
            NoteList(feed, Modifier.weight(1f).padding(top = 8.dp), empty = "No notes found.")
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(people, key = { _, it -> it }) { index, pubkey ->
                    PersonRow(core, pubkey, segmentShape(index, people.size)) { nav.push(Screen.Profile(pubkey)) }
                }
            }
        }
    }
}

@Composable
fun PersonRow(core: Core, pubkey: String, shape: Shape, onClick: () -> Unit) {
    LaunchedEffect(pubkey) { core.wantProfile(pubkey) }
    CardRow(
        shape,
        onClick,
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiText(core.richName(pubkey), core.profiles[pubkey]?.emoji.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (core.profiles[pubkey]?.verified == true) {
                    Icon(Icons.Rounded.Verified, "Verified", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        supportingContent = core.profiles[pubkey]?.nip05?.let { { Text(it) } },
        leadingContent = { Avatar(pubkey, 40.dp) },
    )
}
