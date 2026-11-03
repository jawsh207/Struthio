package app.struthio.nostr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.data.Dm
import app.struthio.nostr.data.Room
import app.struthio.nostr.data.roomKey

@Composable
fun MessagesScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val me = core.me ?: return
    val relays by core.dmRelays.collectAsState()
    val rooms by liveQuery(emptyList<Room>()) { core.store.rooms(me) }
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(rooms) { core.prefs.edit().putLong("dms_read", nowSeconds()).apply() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text("Messages", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
            if (relays.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Private messages need an inbox", style = MaterialTheme.typography.titleMedium)
                        Text("This publishes which relays hold your encrypted messages, so people can reach you.", style = MaterialTheme.typography.bodyMedium)
                        Button({ scope.act(nav) { core.setUpMessages() } }) { Text("Set up messages") }
                    }
                }
            }
            if (rooms.isEmpty() && relays.isNotEmpty()) {
                Text(
                    "No conversations yet.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                items(rooms, key = { it.key }) { room -> RoomRow(room) { nav.push(Screen.Chat(room.key)) } }
            }
        }
        FloatingActionButton(
            onClick = { starting = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) { Icon(Icons.Filled.Add, "New message") }
    }

    if (starting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { starting = false },
            title = { Text("New message") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("npub of the person") }, singleLine = true) },
            confirmButton = {
                TextButton({
                    val entity = Nip19.decode(text.trim()) as? Entity.Profile
                    if (entity == null) {
                        nav.say("That is not an npub.")
                    } else {
                        starting = false
                        nav.push(Screen.Chat(roomKey(setOf(entity.pubkey, me))))
                    }
                }) { Text("Start") }
            },
            dismissButton = { TextButton({ starting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RoomRow(room: Room, onClick: () -> Unit) {
    val core = LocalCore.current
    val first = room.members.firstOrNull() ?: core.me ?: return
    LaunchedEffect(first) { room.members.forEach(core::wantProfile) }
    ListItem(
        headlineContent = {
            Text(room.members.joinToString(", ") { core.displayName(it) }.ifEmpty { "Note to self" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(room.last.content, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text(relativeTime(room.last.createdAt)) },
        leadingContent = { Avatar(first) },
        modifier = Modifier.tap(onClick),
    )
}

@Composable
fun ChatScreen(room: String) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val me = core.me ?: return
    val members = remember(room) { room.split(',').toSet() }
    val others = members - me
    val messages by liveQuery(emptyList<Dm>(), room) { core.store.messages(room, 300) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(room) { others.forEach(core::wantProfile) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(others.joinToString(", ") { core.displayName(it) })
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.id }) { Bubble(it, it.sender == me, others.size > 1) }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    text,
                    { text = it },
                    placeholder = { Text("Message") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = {
                        sending = true
                        scope.act(nav) {
                            try {
                                core.sendMessage(members, text.trim())
                                text = ""
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = text.isNotBlank() && !sending,
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun Bubble(dm: Dm, mine: Boolean, group: Boolean) {
    val core = LocalCore.current
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, if (mine) 6.dp else 20.dp, if (mine) 20.dp else 6.dp))
                .background(if (mine) colors.primaryContainer else colors.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (group && !mine) Text(core.displayName(dm.sender), style = MaterialTheme.typography.labelMedium, color = colors.primary)
            Text(dm.content, color = if (mine) colors.onPrimaryContainer else colors.onSurface)
            Text(relativeTime(dm.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}
