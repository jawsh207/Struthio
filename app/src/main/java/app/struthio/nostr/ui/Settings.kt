package app.struthio.nostr.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.RelayList
import app.struthio.nostr.core.normalizeRelay
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.ExternalSigner
import app.struthio.nostr.data.SignerBridge
import org.json.JSONObject

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun SettingsScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
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
    var revealed by remember { mutableStateOf<String?>(null) }
    var confirmReveal by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Settings")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Profile") {
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
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                ) { Text(if (saving) "Saving…" else "Save profile") }
            }
            Section("Relays") {
                Text("Choose where your notes go and where people can reach you.", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton({ nav.push(Screen.Relays) }) { Text("Manage relays") }
            }
            Section("Account") {
                val npub = Nip19.npub(me)
                Text(npub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        context.copy(npub)
                        nav.say("Public key copied")
                    }) { Text("Copy npub") }
                    OutlinedButton({ nav.push(Screen.Profile(me)) }) { Text("View profile") }
                }
                if (core.secure.get(Core.KEY_SECRET) != null) {
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

private class EditRelay(val url: String, read: Boolean, write: Boolean) {
    var read by mutableStateOf(read)
    var write by mutableStateOf(write)
}

@Composable
fun RelaysScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val list by core.myRelays.collectAsState()
    val dm by core.dmRelays.collectAsState()
    val relays = remember {
        mutableStateListOf<EditRelay>().apply {
            (list.read + list.write).distinct().forEach { add(EditRelay(it, it in list.read, it in list.write)) }
        }
    }
    val dmRelays = remember { mutableStateListOf<String>().apply { addAll(dm) } }
    var input by remember { mutableStateOf("") }
    var dmInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Relays")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Your relays") {
                Text("Two to four of each works best.", style = MaterialTheme.typography.bodyMedium)
                relays.forEach { relay ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(relay.url.removePrefix("wss://"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton({ relays.remove(relay) }) { Icon(Icons.Filled.Close, "Remove") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(relay.read, { relay.read = !relay.read }, { Text("Read") })
                            FilterChip(relay.write, { relay.write = !relay.write }, { Text("Write") })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it }, label = { Text("wss://…") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton({
                        val url = normalizeRelay(input)
                        if (url == null) {
                            nav.say("Relays start with wss://")
                        } else {
                            if (relays.none { it.url == url }) relays += EditRelay(url, true, true)
                            input = ""
                        }
                    }) { Text("Add") }
                }
                Button(
                    onClick = {
                        busy = true
                        scope.act(nav) {
                            try {
                                core.saveRelays(RelayList(relays.filter { it.read }.map { it.url }, relays.filter { it.write }.map { it.url }))
                                nav.say("Relays saved")
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && relays.any { it.write } && relays.any { it.read },
                ) { Text("Save") }
            }
            Section("Message inbox") {
                Text("Encrypted messages to you go here. One to three is plenty.", style = MaterialTheme.typography.bodyMedium)
                dmRelays.toList().forEach { url ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(url.removePrefix("wss://"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton({ dmRelays.remove(url) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(dmInput, { dmInput = it }, label = { Text("wss://…") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton({
                        val url = normalizeRelay(dmInput)
                        if (url == null) {
                            nav.say("Relays start with wss://")
                        } else {
                            if (url !in dmRelays) dmRelays += url
                            dmInput = ""
                        }
                    }) { Text("Add") }
                }
                Button(
                    onClick = {
                        busy = true
                        scope.act(nav) {
                            try {
                                core.saveDmRelays(dmRelays.toList())
                                nav.say("Inbox saved")
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && dmRelays.isNotEmpty(),
                ) { Text("Save inbox") }
            }
        }
    }
}

@Composable
fun Onboarding(modifier: Modifier = Modifier) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
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
            OutlinedTextField(key, { key = it }, label = { Text("nsec…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FilledTonalButton({ run { core.signIn(key) } }, enabled = key.isNotBlank() && !busy) { Text("Sign in") }
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
