package app.struthio.nostr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
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

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = core.me ?: return
    var copying by remember { mutableStateOf(false) }
    var sealing by remember { mutableStateOf(false) }
    if (sealing) KeyBackupDialog { sealing = false }
    var interval by remember { mutableIntStateOf(core.notifyMinutes()) }
    var trustFilter by remember { mutableStateOf(core.trustFilterEnabled()) }
    var linkPreviews by remember { mutableStateOf(core.linkPreviewsEnabled()) }
    var videoEmbeds by remember { mutableStateOf(core.videoEmbedsEnabled()) }
    var servers by remember { mutableStateOf(core.mediaServers().joinToString("\n")) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var confirmReveal by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Settings")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Account") {
                val npub = Nip19.npub(me)
                Text(npub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        context.copy(npub)
                        nav.say("Public key copied")
                    }) { Text("Copy npub") }
                    OutlinedButton({ nav.push(Screen.Profile(me)) }) { Text("View profile") }
                }
                OutlinedButton({ nav.push(Screen.Bookmarks) }) { Text("Bookmarks") }
                if (core.secure.get(Core.KEY_SECRET) != null) {
                    OutlinedButton({ sealing = true }) { Text("Encrypted key backup") }
                    if (revealed == null) {
                        OutlinedButton({ confirmReveal = true }) { Text("Back up private key") }
                    } else {
                        SelectionContainer { Text(revealed!!, style = MaterialTheme.typography.bodySmall) }
                        FilledTonalButton({
                            context.copy(revealed!!)
                            nav.say("Private key copied. Clear your clipboard after saving it.")
                        }) { Text("Copy private key") }
                    }
                }
                TextButton({ confirmSignOut = true }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            }
            Section("Relays") {
                Text("Choose where your notes go and where people can reach you.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ nav.push(Screen.Relays) }) { Text("Manage relays") }
                    OutlinedButton({ nav.push(Screen.AccountCheck) }) { Text("Account check") }
                }
                Text("Help other apps and people find you by copying your lists to popular relays.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = {
                        copying = true
                        scope.act(nav) {
                            try {
                                nav.say(core.copyListsToPopularRelays())
                            } finally {
                                copying = false
                            }
                        }
                    },
                    enabled = !copying,
                ) { Text(if (copying) "Copying…" else "Copy my lists to popular relays") }
            }
            Section("Notifications") {
                Text("How often Struthio checks for new activity. Android may delay checks when the phone is idle.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Notifier.INTERVALS.forEach { minutes ->
                        val label = when {
                            minutes == 0 -> "Off"
                            minutes < 60 -> "$minutes min"
                            minutes == 60 -> "1 hour"
                            else -> "${minutes / 60} hours"
                        }
                        FilterChip(interval == minutes, {
                            interval = minutes
                            core.setNotifyMinutes(minutes)
                        }, { Text(label) })
                    }
                }
            }
            Section("Feeds") {
                ToggleRow("Trusted network", "Global and Trending show only people you follow and the people they follow.", trustFilter) {
                    trustFilter = it
                    core.setTrustFilter(it)
                }
                ToggleRow("Link previews", "Shows a card for links. Each linked site can see your IP address.", linkPreviews) {
                    linkPreviews = it
                    core.setLinkPreviews(it)
                }
                ToggleRow(
                    "Embedded videos",
                    "Plays videos from YouTube, PeerTube, Invidious, Piped and similar sites inside notes. Nothing loads until you tap play, then that site can see your IP address and what you watch.",
                    videoEmbeds,
                ) {
                    videoEmbeds = it
                    core.setVideoEmbeds(it)
                }
            }
            Section("Advanced") {
                Text(
                    "Logs show relay connections, sign-ins and errors, and stay on this phone until you copy them. They leave out your keys, your phone's network address and the text of your notes and messages, but they do list your relays and the phone model.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        scope.act(nav) {
                            val report = core.logReport()
                            withContext(Dispatchers.Main) { context.copy(report) }
                            nav.say("Logs copied")
                        }
                    }) { Text("Copy logs") }
                    OutlinedButton({
                        AppLog.clear()
                        nav.say("Logs cleared")
                    }) { Text("Clear logs") }
                }
            }
            Section("Media servers") {
                Text("Images and files you send are stored here. One server per line.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(servers, { servers = it }, modifier = Modifier.fillMaxWidth())
                FilledTonalButton({
                    val list = servers.lines().map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") }.distinct()
                    if (list.isEmpty()) {
                        nav.say("Servers start with https://")
                    } else {
                        scope.act(nav) {
                            core.saveMediaServers(list)
                            nav.say("Media servers saved")
                        }
                    }
                }) { Text("Save servers") }
            }
            Text(
                "Struthio",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    var nip05 by remember { mutableStateOf(current.optString("nip05")) }
    var lud16 by remember { mutableStateOf(current.optString("lud16")) }
    var saving by remember { mutableStateOf(false) }

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
                OutlinedTextField(nip05, { nip05 = it }, label = { Text("Nostr address (NIP-05)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lud16, { lud16 = it }, label = { Text("Lightning address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        saving = true
                        scope.act(nav) {
                            try {
                                core.saveProfile(
                                    mapOf(
                                        "name" to name, "display_name" to displayName, "about" to about,
                                        "picture" to picture, "nip05" to nip05, "lud16" to lud16,
                                    ),
                                )
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
        }
    }
}
