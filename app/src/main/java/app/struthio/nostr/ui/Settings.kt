package app.struthio.nostr.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import app.struthio.nostr.core.LocalSigner
import app.struthio.nostr.data.RemoteSigner
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.mutableStateListOf
import app.struthio.nostr.core.Identity
import app.struthio.nostr.core.gistId
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.ExternalSigner
import app.struthio.nostr.net.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.struthio.nostr.data.Notifier
import app.struthio.nostr.data.SignerBridge
import org.json.JSONObject

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A titled group of rows, joined into one card the way the other screens group lists. */
@Composable
private fun Group(title: String, vararg rows: (@Composable (Shape) -> Unit)?) {
    val shown = rows.filterNotNull()
    if (shown.isEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEachIndexed { index, row -> row(segmentShape(index, shown.size)) }
    }
}

@Composable
private fun SettingRow(
    shape: Shape,
    icon: ImageVector,
    title: String,
    summary: String? = null,
    color: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    CardRow(
        shape,
        onClick,
        headlineContent = { Text(title, color = color) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color) },
        trailingContent = trailing,
    )
}

@Composable
private fun SwitchRow(shape: Shape, icon: ImageVector, title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(shape, icon, title, summary, trailing = { Switch(checked, onChange) }) { onChange(!checked) }
}

private fun intervalLabel(minutes: Int) = when {
    minutes == 0 -> "Off"
    minutes < 60 -> "Every $minutes minutes"
    minutes == 60 -> "Every hour"
    else -> "Every ${minutes / 60} hours"
}

@Composable
fun SettingsScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = core.me ?: return
    val hasKey = remember { core.secure.get(Core.KEY_SECRET) != null }
    var copying by remember { mutableStateOf(false) }
    var sealing by remember { mutableStateOf(false) }
    var interval by remember { mutableIntStateOf(core.notifyMinutes()) }
    var pickingInterval by remember { mutableStateOf(false) }
    var trustFilter by remember { mutableStateOf(core.trustFilterEnabled()) }
    var linkPreviews by remember { mutableStateOf(core.linkPreviewsEnabled()) }
    var videoEmbeds by remember { mutableStateOf(core.videoEmbedsEnabled()) }
    var editingServers by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var confirmReveal by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var vanishing by remember { mutableStateOf(false) }
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }

    Column(Modifier.fillMaxSize()) {
        BackBar("Settings")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter).navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(8.dp))
            CardRow(
                RoundedCornerShape(24.dp),
                { nav.push(Screen.Profile(me)) },
                headlineContent = { Text(core.displayName(me), style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(Nip19.npub(me), maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                leadingContent = { Avatar(me, 48.dp) },
                trailingContent = {
                    IconButton({
                        context.copy(Nip19.npub(me))
                        nav.say("Public key copied")
                    }) { Icon(Icons.Rounded.ContentCopy, "Copy npub") }
                },
            )
            Group(
                "Account",
                { SettingRow(it, Icons.Rounded.Bookmarks, "Bookmarks") { nav.push(Screen.Bookmarks) } },
                if (hasKey) {
                    { SettingRow(it, Icons.Rounded.Lock, "Encrypted key backup", "Export your key protected by a password") { sealing = true } }
                } else {
                    null
                },
                if (hasKey) {
                    { SettingRow(it, Icons.Rounded.Key, "Show private key", "For backing up by hand") { confirmReveal = true } }
                } else {
                    null
                },
            )
            Group(
                "Relays",
                { SettingRow(it, Icons.Rounded.Hub, "Manage relays", "General, messaging, search and profile lookup") { nav.push(Screen.Relays) } },
                { SettingRow(it, Icons.Rounded.FactCheck, "Account check", "See which relays have your profile and lists") { nav.push(Screen.AccountCheck) } },
                {
                    SettingRow(
                        it,
                        Icons.Rounded.CloudUpload,
                        if (copying) "Copying…" else "Copy lists to popular relays",
                        "Helps other apps and people find you",
                    ) {
                        if (!copying) {
                            copying = true
                            scope.act(nav) {
                                try {
                                    nav.say(core.copyListsToPopularRelays())
                                } finally {
                                    copying = false
                                }
                            }
                        }
                    }
                },
                { SettingRow(it, Icons.Rounded.Storage, "Media servers", "Where images and files you send are stored") { editingServers = true } },
            )
            Group(
                "Notifications",
                { SettingRow(it, Icons.Rounded.Notifications, "Check for activity", intervalLabel(interval)) { pickingInterval = true } },
            )
            Group(
                "Feeds",
                {
                    SwitchRow(it, Icons.Rounded.Groups, "Trusted network", "Global and Trending show only people you follow and the people they follow", trustFilter) { on ->
                        trustFilter = on
                        core.setTrustFilter(on)
                    }
                },
                {
                    SwitchRow(it, Icons.Rounded.Link, "Link previews", "Shows a card for links. Each linked site can see your IP address", linkPreviews) { on ->
                        linkPreviews = on
                        core.setLinkPreviews(on)
                    }
                },
                {
                    SwitchRow(
                        it,
                        Icons.Rounded.SmartDisplay,
                        "Embedded videos",
                        "Plays YouTube, PeerTube, Invidious, Piped and similar videos in notes. Nothing loads until you tap play, then that site can see your IP address and what you watch",
                        videoEmbeds,
                    ) { on ->
                        videoEmbeds = on
                        core.setVideoEmbeds(on)
                    }
                },
            )
            Group(
                "Troubleshooting",
                {
                    SettingRow(it, Icons.Rounded.ContentCopy, "Copy logs", "Connections, sign-ins and errors. Leaves out your keys, network address and the text of notes and messages") {
                        scope.act(nav) {
                            val report = core.logReport()
                            withContext(Dispatchers.Main) { context.copy(report) }
                            nav.say("Logs copied")
                        }
                    }
                },
                {
                    SettingRow(it, Icons.Rounded.DeleteSweep, "Clear logs") {
                        AppLog.clear()
                        nav.say("Logs cleared")
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            SettingRow(RoundedCornerShape(24.dp), Icons.AutoMirrored.Rounded.Logout, "Sign out", color = MaterialTheme.colorScheme.error) { confirmSignOut = true }
            Spacer(Modifier.height(8.dp))
            SettingRow(
                RoundedCornerShape(24.dp),
                Icons.Rounded.DeleteForever,
                "Delete account everywhere",
                "Ask every relay to erase everything you've posted",
                color = MaterialTheme.colorScheme.error,
            ) { vanishing = true }
            Text(
                listOfNotNull("Struthio", version).joinToString(" "),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (sealing) KeyBackupDialog { sealing = false }
    if (editingServers) MediaServersDialog { editingServers = false }
    if (pickingInterval) {
        AlertDialog(
            onDismissRequest = { pickingInterval = false },
            title = { Text("Check for activity") },
            text = {
                Column {
                    Text("Android may delay checks while the phone is idle.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Notifier.INTERVALS.forEach { minutes ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                interval = minutes
                                core.setNotifyMinutes(minutes)
                                pickingInterval = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(interval == minutes, null)
                            Spacer(Modifier.width(8.dp))
                            Text(intervalLabel(minutes))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ pickingInterval = false }) { Text("Close") } },
        )
    }
    if (confirmReveal) {
        AlertDialog(
            onDismissRequest = { confirmReveal = false },
            title = { Text("Show your private key?") },
            text = { Text("Anyone who sees this key controls your account, and it cannot be changed or reset. Keep it somewhere private.") },
            confirmButton = {
                TextButton({
                    confirmReveal = false
                    scope.act(nav) { revealed = core.exportSecret() }
                }) { Text("Show key") }
            },
            dismissButton = { TextButton({ confirmReveal = false }) { Text("Cancel") } },
        )
    }
    revealed?.let { key ->
        AlertDialog(
            onDismissRequest = { revealed = null },
            title = { Text("Your private key") },
            text = { SelectionContainer { Text(key, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = {
                TextButton({
                    context.copy(key)
                    nav.say("Private key copied. Clear your clipboard after saving it.")
                }) { Text("Copy") }
            },
            dismissButton = { TextButton({ revealed = null }) { Text("Close") } },
        )
    }
    if (vanishing) VanishDialog { vanishing = false }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This removes your key and messages from this device. Without a backup of your private key you cannot get this account back.") },
            confirmButton = {
                TextButton({
                    confirmSignOut = false
                    nav.stack.clear()
                    scope.act(nav) { core.signOut() }
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MediaServersDialog(onClose: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(core.mediaServers().joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Media servers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Images and files you send are stored here. One server per line.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(servers, { servers = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton({
                val list = servers.lines().map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") }.distinct()
                if (list.isEmpty()) {
                    nav.say("Servers start with https://")
                } else {
                    scope.act(nav) {
                        core.saveMediaServers(list)
                        nav.say("Media servers saved")
                        onClose()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
fun EditProfileScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val me = core.me ?: return
    val current = remember { runCatching { JSONObject(core.profiles[me]?.raw ?: "{}") }.getOrDefault(JSONObject()) }
    var name by remember { mutableStateOf(current.optString("name")) }
    var displayName by remember { mutableStateOf(current.optString("display_name")) }
    var about by remember { mutableStateOf(current.optString("about")) }
    var picture by remember { mutableStateOf(current.optString("picture")) }
    var banner by remember { mutableStateOf(current.optString("banner")) }
    var website by remember { mutableStateOf(current.optString("website")) }
    var nip05 by remember { mutableStateOf(current.optString("nip05")) }
    var lud16 by remember { mutableStateOf(current.optString("lud16")) }
    var saving by remember { mutableStateOf(false) }
    // NIP-38 status and NIP-39 linked accounts live in their own events, loaded from the network.
    val loadedStatus by produceState<String?>(null) { value = core.statusesOf(me).firstOrNull { !it.music }?.text.orEmpty() }
    val loadedIdentities by produceState<List<Identity>?>(null) { value = core.identitiesOf(me) }
    var status by remember(loadedStatus) { mutableStateOf(loadedStatus.orEmpty()) }
    val identities = remember(loadedIdentities) { mutableStateListOf<Identity>().apply { addAll(loadedIdentities.orEmpty()) } }
    var addingAccount by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Edit profile")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(about, { about = it }, label = { Text("About") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(picture, { picture = it }, label = { Text("Picture URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    banner,
                    { banner = it },
                    label = { Text("Banner URL") },
                    supportingText = { Text("A wide picture across the top of your profile.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(website, { website = it }, label = { Text("Website") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(nip05, { nip05 = it }, label = { Text("Nostr address (NIP-05)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lud16, { lud16 = it }, label = { Text("Lightning address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    status,
                    { status = it },
                    label = { Text("Status") },
                    placeholder = { Text("What are you up to?") },
                    supportingText = { Text("Shown under your name. Leave empty to clear it.") },
                    singleLine = true,
                    enabled = loadedStatus != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Linked accounts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                if (loadedIdentities == null) {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    identities.forEach { identity ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${identity.label} · ${identity.name}", style = MaterialTheme.typography.bodyMedium)
                                Text(identity.proof, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton({ identities.remove(identity) }) { Icon(Icons.Rounded.Close, "Remove ${identity.label} account") }
                        }
                    }
                    OutlinedButton({ addingAccount = true }) { Text("Link an account") }
                }
                Button(
                    onClick = {
                        saving = true
                        scope.act(nav) {
                            try {
                                core.saveProfile(
                                    mapOf(
                                        "name" to name, "display_name" to displayName, "about" to about,
                                        "picture" to picture, "banner" to banner, "website" to website, "nip05" to nip05, "lud16" to lud16,
                                    ),
                                )
                                if (loadedStatus != null && status.trim() != loadedStatus) core.setStatus(status)
                                if (loadedIdentities != null && identities.toList() != loadedIdentities) core.setIdentities(identities.toList())
                                nav.say("Profile saved")
                                nav.pop()
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                ) { Text(if (saving) "Saving…" else "Save profile") }
        }
    }
    if (addingAccount) LinkAccountDialog(Nip19.npub(me), { identities += it }) { addingAccount = false }
}

/**
 * Adds a NIP-39 linked account. The proof is a post or gist on that account holding the text below,
 * so others can check the account is really yours.
 */
@Composable
private fun LinkAccountDialog(npub: String, onAdd: (Identity) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var platform by remember { mutableStateOf("github") }
    var name by remember { mutableStateOf("") }
    var proof by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val proofHint = when (platform) {
        "github" -> "Gist ID"
        "twitter" -> "Post ID"
        "mastodon" -> "Post ID"
        "bluesky" -> "Post record key"
        "telegram" -> "channel/message"
        "discord" -> "server/channel/message IDs"
        else -> "Proof"
    }
    val nameHint = when (platform) {
        "mastodon" -> "instance/@username"
        "bluesky" -> "handle, like alice.bsky.social"
        "telegram" -> "user ID"
        else -> "Username"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link an account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton({ choosing = true }, Modifier.fillMaxWidth()) { Text(Identity.PLATFORMS[platform] ?: platform) }
                    DropdownMenu(choosing, { choosing = false }) {
                        Identity.PLATFORMS.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                platform = key
                                choosing = false
                            })
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(nameHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(proof, { proof = it }, label = { Text(proofHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "Post this from that account, then put the post's ID above:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Surface(
                    onClick = { context.copy(Identity.proofText(npub)) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(Identity.proofText(npub), modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
                Text("Tap the text to copy it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                {
                    val normalized = name.trim().removePrefix("@").let { if (platform == "mastodon") it else it.lowercase() }
                    onAdd(Identity(platform, normalized, if (platform == "github") gistId(proof) else proof.trim()))
                    onDismiss()
                },
                enabled = name.isNotBlank() && proof.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Shows what each relay really holds for this account. Everything in the report is public. */
@Composable
fun AccountCheckScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val report by produceState<String?>(null) { value = core.accountCheck() }
    Column(Modifier.fillMaxSize()) {
        BackBar("Account check")
        val text = report
        if (text == null) {
            Text("Asking your relays…", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Loading(Modifier.fillMaxWidth().padding(24.dp))
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton({
                    context.copy(text)
                    nav.say("Report copied")
                }) { Text("Copy report") }
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Exports the key sealed with a password. Nothing here is saved or sent anywhere. */
@Composable
private fun KeyBackupDialog(onClose: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var backup by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val sealed = backup
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Encrypted key backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sealed == null) {
                    Text("Choose a strong password. Nobody can recover it for you, and without it this backup can't be opened.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(repeat, { repeat = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (password.isNotEmpty() && password.length < 12) Text("Use at least 12 characters.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Keep this somewhere safe. Don't post it publicly, even though it's encrypted.", style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer { Text(sealed, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (sealed == null) {
                TextButton(
                    {
                        busy = true
                        scope.act(nav) {
                            try {
                                backup = core.exportEncrypted(password)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && password.length >= 12 && password == repeat,
                ) { Text(if (busy) "Encrypting…" else "Encrypt") }
            } else {
                TextButton({
                    context.copy(sealed)
                    nav.say("Encrypted backup copied")
                }) { Text("Copy") }
            }
        },
        dismissButton = { TextButton(onClose) { Text("Close") } },
    )
}

@Composable
fun Onboarding(modifier: Modifier = Modifier) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var remoteSigning by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        busy = true
        scope.act(nav) {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Struthio", style = MaterialTheme.typography.displayMedium)
        Text("Fast, light Nostr.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Section("New to Nostr") {
            OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button({ run { core.createAccount(name.trim()) } }, enabled = name.isNotBlank() && !busy) { Text("Create account") }
        }
        Section("Already have a key") {
            OutlinedTextField(key, { key = it }, label = { Text("nsec, ncryptsec, or npub to browse read-only") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val encrypted = key.trim().startsWith("ncryptsec1")
            if (encrypted) {
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Backup password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FilledTonalButton(
                { run { if (encrypted) core.signInEncrypted(key, password) else core.signIn(key) } },
                enabled = key.isNotBlank() && !busy && (!encrypted || password.isNotEmpty()),
            ) { Text("Sign in") }
            if (ExternalSigner.isInstalled(context)) {
                OutlinedButton(
                    onClick = {
                        run {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
                                putExtra("type", "get_public_key")
                                putExtra("permissions", ExternalSigner.PERMISSIONS)
                            }
                            val reply = SignerBridge.request(intent)
                            val pubkey = reply.result
                            val pkg = reply.packageName
                            if (reply.ok && !reply.rejected && pubkey != null && pkg != null) {
                                val hex = (Nip19.decode(pubkey) as? app.struthio.nostr.core.Entity.Profile)?.pubkey ?: pubkey
                                core.signInExternal(hex, pkg)
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text("Use a signer app") }
            }
            OutlinedButton({ remoteSigning = true }, enabled = !busy) { Text("Use a remote signer (bunker)") }
        }
    }
    if (remoteSigning) RemoteSignInSheet { remoteSigning = false }
}

/**
 * NIP-46 sign-in. Either paste a bunker:// link from the signer, or let the signer connect to the
 * nostrconnect:// link shown here, by scanning it or opening it on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteSignInSheet(onDismiss: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    // One throwaway client key and secret for this sheet; a new sheet makes new ones.
    val clientKey = remember { RemoteSigner.newClientKey() }
    val secret = remember { RemoteSigner.randomSecret() }
    val connectUri = remember {
        RemoteSigner.nostrConnectUri(LocalSigner(clientKey).pubkey, RemoteSigner.CONNECT_RELAYS, secret)
    }
    // Listens for a signer answering the link for as long as the sheet is open.
    LaunchedEffect(clientKey) {
        val ok = runCatching { core.signInNostrConnect(clientKey, secret, RemoteSigner.CONNECT_RELAYS, 10 * 60_000L) }
        ok.exceptionOrNull()?.let { nav.say(it.message ?: "The remote signer couldn't connect.") }
        if (ok.getOrNull() == true) onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Use a remote signer", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your key stays in a signer like nsec.app or a bunker of your own. Struthio asks it to sign over Nostr relays, encrypted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(link, { link = it }, label = { Text("bunker:// link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                {
                    connecting = true
                    scope.act(nav) {
                        try {
                            core.signInBunker(link)
                            onDismiss()
                        } finally {
                            connecting = false
                        }
                    }
                },
                enabled = link.trim().startsWith("bunker://") && !connecting,
            ) { Text(if (connecting) "Connecting… approve it in your signer" else "Connect") }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Or connect from your signer", style = MaterialTheme.typography.titleSmall)
            Text(
                "Scan this with your signer, or open it in a signer on this phone. Struthio is waiting for it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(connectUri) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({
                    context.copy(connectUri)
                    nav.say("Link copied")
                }) { Text("Copy link") }
                OutlinedButton({
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(connectUri))) }
                        .onFailure { nav.say("No signer on this phone opens these links") }
                }) { Text("Open in signer") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * NIP-62 request to vanish. Relays that support it must erase every event from this key, and
 * messages sent to it, and refuse them if they're sent again. There is no undo, so it asks twice.
 */
@Composable
private fun VanishDialog(onDismiss: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete account everywhere?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This asks every relay to permanently erase your posts, profile, lists, reactions and the messages sent to you. " +
                        "Relays that support it must delete everything and refuse it if it's sent again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "It can't be undone. Not every relay supports it, and anyone who saved a copy keeps it. " +
                        "Struthio signs out afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(typed, { typed = it }, label = { Text("Type DELETE to confirm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                {
                    sending = true
                    scope.act(nav) {
                        try {
                            val taken = core.requestVanish(reason)
                            nav.say(if (taken > 0) "Sent to $taken relays. Signing out." else "No relay took the request. Try again.")
                            if (taken > 0) {
                                nav.stack.clear()
                                core.signOut()
                            }
                        } finally {
                            sending = false
                            onDismiss()
                        }
                    }
                },
                enabled = typed.trim() == "DELETE" && !sending,
            ) { Text(if (sending) "Sending…" else "Delete everywhere", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onDismiss, enabled = !sending) { Text("Cancel") } },
    )
}
