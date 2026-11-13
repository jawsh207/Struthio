package app.struthio.nostr.ui

import app.struthio.nostr.core.parseContent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.FileMessage
import app.struthio.nostr.core.invoicesIn
import app.struthio.nostr.core.replaceInvoices
import app.struthio.nostr.core.summarizeInvoices
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.data.Dm
import app.struthio.nostr.data.Room
import app.struthio.nostr.data.roomKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessagesScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val me = core.me ?: return
    val relays by core.dmRelays.collectAsState()
    val rooms by liveQuery(emptyList<Room>()) { core.store.rooms(me) }
    val unread by liveQuery(emptyMap<String, Int>(), me) { core.unreadRooms(me) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            UnreadHeader("Messages", unread.isNotEmpty()) { core.readAllRooms(me) }
            if (relays.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter),
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
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(rooms, key = { _, room -> room.key }) { index, room ->
                    RoomRow(room, unread[room.key] ?: 0, segmentShape(index, rooms.size)) { nav.push(Screen.Chat(room.key)) }
                }
            }
        }
        FloatingActionButton(
            onClick = { nav.push(Screen.NewMessage) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) { Icon(Icons.Rounded.Add, "New message") }
    }
}

/** Finds someone to message the same way search finds people. */
@Composable
fun NewMessageScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val me = core.me ?: return
    var query by remember { mutableStateOf("") }
    val people = rememberPeople(query)
    val start = { pubkey: String ->
        nav.pop()
        nav.push(Screen.Chat(roomKey(setOf(pubkey, me))))
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("New message")
        OutlinedTextField(
            query,
            { query = it },
            placeholder = { Text("Name, NIP-05 address or npub") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                ScanButton { scanned ->
                    val entity = Nip19.decode(scanned.removePrefix("nostr:")) as? Entity.Profile
                    if (entity != null) start(entity.pubkey) else nav.say("That is not a profile code.")
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = PageGutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(people, key = { _, it -> it }) { index, pubkey ->
                PersonRow(core, pubkey, segmentShape(index, people.size)) { start(pubkey) }
            }
        }
    }
}

@Composable
private fun RoomRow(room: Room, unread: Int, shape: Shape, onClick: () -> Unit) {
    val core = LocalCore.current
    val first = room.members.firstOrNull() ?: core.me ?: return
    LaunchedEffect(first) { room.members.forEach(core::wantProfile) }
    CardRow(shape, onClick,
        headlineContent = {
            Text(
                room.members.joinToString(", ") { core.displayName(it) }.ifEmpty { "Note to self" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (unread > 0) FontWeight.Bold else null,
            )
        },
        supportingContent = {
            Text(summarizeInvoices(room.last.content), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (unread > 0) FontWeight.Bold else null)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(relativeTime(room.last.createdAt))
                if (unread > 0) Text(if (unread > 99) "99+" else unread.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        leadingContent = { UnreadAvatar(first, unread > 0) },
    )
}

@Composable
fun ChatScreen(room: String) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val me = core.me ?: return
    val members = remember(room) { room.split(',').toSet() }
    val others = members - me
    val messages by liveQuery(emptyList<Dm>(), room) { core.store.messages(room, 300) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<FileMessage?>(null) }
    LaunchedEffect(room) { others.forEach(core::wantProfile) }
    // Reading the conversation is what makes it read, including anything that arrives while it's open.
    LaunchedEffect(messages) { messages.firstOrNull()?.let { core.readRoom(room, it.createdAt) } }

    fun attach(uri: Uri?) {
        if (uri == null) return
        sending = true
        scope.act(nav) {
            try {
                val prepared = core.media.prepareFile(uri)
                core.sendFile(members, prepared.bytes, prepared.type, prepared.width, prepared.height)
            } finally {
                sending = false
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::attach)
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::attach)
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = saving
        saving = null
        if (uri != null && file != null) {
            scope.act(nav) {
                val data = core.openFile(file)
                context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                nav.say("File saved")
            }
        }
    }
    LaunchedEffect(saving) { if (saving != null) saveFile.launch("struthio-file") }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar(others.joinToString(", ") { core.displayName(it) })
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = PageGutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(messages, key = { _, it -> it.id }) { index, dm ->
                // The list runs newest first, so the next item is the message before this one.
                val before = messages.getOrNull(index + 1)
                if (before == null || dayOf(before.createdAt) != dayOf(dm.createdAt)) DateDivider(dm.createdAt)
                Bubble(dm, dm.sender == me, others.size > 1, onSave = { file -> saving = file })
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton({ menu = true }, enabled = !sending) { Icon(Icons.Rounded.Add, "Attach") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Photo") }, onClick = {
                        menu = false
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("File") }, onClick = {
                        menu = false
                        pickFile.launch(arrayOf("*/*"))
                    })
                }
                OutlinedTextField(
                    text,
                    { text = it },
                    placeholder = { Text(if (sending) "Sending…" else "Message") },
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
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
            }
        }
    }
}

@Composable
private fun Bubble(dm: Dm, mine: Boolean, group: Boolean, onSave: (FileMessage) -> Unit) {
    val core = LocalCore.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, if (mine) 6.dp else 20.dp, if (mine) 20.dp else 6.dp))
                .background(if (mine) colors.primaryContainer else colors.surfaceBright)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (group && !mine) Text(core.displayName(dm.sender), style = MaterialTheme.typography.labelMedium, color = colors.primary)
            val file = dm.file
            when {
                file == null -> {
                    val invoices = remember(dm.content) { invoicesIn(dm.content) }
                    val text = remember(dm.content) { replaceInvoices(dm.content) { "" }.trim() }
                    if (text.isNotEmpty()) {
                        // Links, hashtags and mentions work here as they do in notes.
                        Text(linkedText(remember(text) { parseContent(text) }, emptyMap()), color = if (mine) colors.onPrimaryContainer else colors.onSurface)
                    }
                    invoices.forEach { InvoiceCard(it) }
                }
                file.type.startsWith("image/") -> EncryptedImage(file)
                else -> TextButton({ onSave(file) }) {
                    Icon(Icons.Rounded.AttachFile, null)
                    Text("Save file")
                }
            }
        }
        Text(
            DateFormat.getTimeFormat(LocalContext.current).format(Date(dm.createdAt * 1000)),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 3.dp, bottom = 6.dp),
        )
    }
}

private fun dayOf(createdAt: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(createdAt * 1000))

@Composable
private fun DateDivider(createdAt: Long) {
    val thisYear = dayOf(System.currentTimeMillis() / 1000).take(4) == dayOf(createdAt).take(4)
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (thisYear) "EEEEMMMd" else "EEEEMMMdyyyy")
    Text(
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(createdAt * 1000)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

/** Decrypted only in memory, and only when the bubble is on screen. */
@Composable
private fun EncryptedImage(file: FileMessage) {
    val core = LocalCore.current
    val bitmap by produceState<ImageBitmap?>(null, file.url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val data = core.openFile(file)
                BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val aspect = (file.aspect ?: 1.3f).coerceIn(0.6f, 2f)
    Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(14.dp))) {
        val image = bitmap
        if (image == null) {
            Loading(Modifier.fillMaxSize())
        } else {
            Image(image, "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
