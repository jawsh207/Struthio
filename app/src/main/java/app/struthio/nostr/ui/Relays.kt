package app.struthio.nostr.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.RelayInfo
import app.struthio.nostr.core.RelayList
import app.struthio.nostr.core.nipName
import app.struthio.nostr.core.normalizeRelay
import app.struthio.nostr.data.Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CHECKING = -1L

private class EditRelay(val url: String, read: Boolean, write: Boolean) {
    var read by mutableStateOf(read)
    var write by mutableStateOf(write)
}

@Composable
private fun rememberRelayInfo(url: String): RelayInfo? {
    val core = LocalCore.current
    return produceState<RelayInfo?>(null, url) { value = core.relayInfo(url) }.value
}

/** Milliseconds to answer, [CHECKING] until known, or null if the relay didn't answer. */
@Composable
private fun rememberPing(url: String): Long? {
    val core = LocalCore.current
    return produceState<Long?>(CHECKING, url) { value = core.pingRelay(url) }.value
}

private fun statusText(ping: Long?, info: RelayInfo?): String = when {
    ping == CHECKING -> "Checking…"
    ping != null -> "$ping ms"
    info?.authRequired == true -> "Answers after sign-in"
    else -> "Not answering"
}

@Composable
private fun statusColor(ping: Long?, info: RelayInfo?): Color = when {
    ping == CHECKING -> MaterialTheme.colorScheme.outlineVariant
    ping != null -> MaterialTheme.colorScheme.primary
    info?.authRequired == true -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun RelayRow(url: String, onRemove: () -> Unit) {
    val nav = LocalNav.current
    val info = rememberRelayInfo(url)
    val ping = rememberPing(url)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { nav.push(Screen.RelayInfo(url)) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(ping, info)))
        Column(Modifier.weight(1f)) {
            Text(url.removePrefix("wss://"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(statusText(ping, info), info?.tag).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onRemove) { Icon(Icons.Rounded.Close, "Remove") }
    }
}

@Composable
private fun AddRelay(onAdd: (String) -> Unit) {
    val nav = LocalNav.current
    var input by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(input, { input = it }, label = { Text("wss://…") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton({
            val url = normalizeRelay(input)
            if (url == null) {
                nav.say("Relays start with wss://")
            } else {
                onAdd(url)
                input = ""
            }
        }) { Text("Add") }
    }
}

@Composable
private fun SaveButton(enabled: Boolean, saved: String, save: suspend () -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Button(
        onClick = {
            busy = true
            scope.act(nav) {
                try {
                    save()
                    nav.say(saved)
                } finally {
                    busy = false
                }
            }
        },
        enabled = enabled && !busy,
    ) { Text("Save") }
}

@Composable
private fun RelayGroup(title: String, hint: String, initial: List<String>, saved: String, save: suspend (List<String>) -> Unit) {
    val relays = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    Section(title) {
        Text(hint, style = MaterialTheme.typography.bodyMedium)
        relays.toList().forEach { url -> RelayRow(url) { relays.remove(url) } }
        AddRelay { if (it !in relays) relays += it }
        SaveButton(relays.isNotEmpty(), saved) { save(relays.toList()) }
    }
}

@Composable
fun RelaysScreen() {
    val core = LocalCore.current
    val list by core.myRelays.collectAsState()
    val dm by core.dmRelays.collectAsState()
    val general = remember {
        mutableStateListOf<EditRelay>().apply {
            (list.read + list.write).distinct().forEach { add(EditRelay(it, it in list.read, it in list.write)) }
        }
    }
    val search by produceState<List<String>?>(null) { value = withContext(Dispatchers.IO) { core.searchRelays().toList() } }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Relays")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("General") {
                Text("Your feed and the notes you post. Two to four works best.", style = MaterialTheme.typography.bodyMedium)
                general.forEach { relay ->
                    Column {
                        RelayRow(relay.url) { general.remove(relay) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(relay.read, { relay.read = !relay.read }, { Text("Read") })
                            FilterChip(relay.write, { relay.write = !relay.write }, { Text("Write") })
                        }
                    }
                }
                AddRelay { url -> if (general.none { it.url == url }) general += EditRelay(url, true, true) }
                SaveButton(general.any { it.write } && general.any { it.read }, "Relays saved") {
                    core.saveRelays(RelayList(general.filter { it.read }.map { it.url }, general.filter { it.write }.map { it.url }))
                }
            }
            RelayGroup(
                "Messaging",
                "Where encrypted messages to you arrive. One to three is plenty.",
                dm.ifEmpty { Core.DEFAULT_DM_RELAYS },
                "Messaging relays saved",
                core::saveDmRelays,
            )
            search?.let { RelayGroup("Search", "Finds notes and people.", it, "Search relays saved", core::saveSearchRelays) }
            RelayGroup(
                "Profile lookup",
                "Finds people's profiles and relay lists. Saved on this phone only.",
                core.lookupRelays(),
                "Lookup relays saved",
            ) { core.saveLookupRelays(it) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun stated(value: Boolean?, yes: String, no: String) = when (value) {
    true -> yes
    false -> no
    null -> "Not stated"
}

@Composable
fun RelayInfoScreen(url: String) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val info = rememberRelayInfo(url)
    val ping = rememberPing(url)
    val signedIn = remember(ping) { core.pool.signedIn(url) }
    val refused = remember(ping) { core.pool.signInRefused(url) }
    val uses by produceState(emptyList<String>(), url) { value = withContext(Dispatchers.IO) { core.relayUses(url) } }
    val open = { link: String ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        Unit
    }
    val host = url.removePrefix("wss://")
    Column(Modifier.fillMaxSize()) {
        BackBar("Relay details")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            info?.banner?.let {
                NetImage(it, 380.dp, Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(24.dp)))
            }
            Section(info?.name ?: host) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    info?.icon?.let { NetImage(it, 56.dp, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))) }
                    Column {
                        Text(host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(ping, info)))
                            Text(statusText(ping, info), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                info?.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (uses.isNotEmpty()) {
                Section("You use it for") { uses.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            }
            val problems = core.pool.problemsOf(url)
            if (problems.isNotEmpty()) {
                Section("Recent problems") { problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
            if (info == null) {
                Section("Details") { Text("This relay doesn't share any details.", style = MaterialTheme.typography.bodyMedium) }
                return@Column
            }
            Section("Cost and access") {
                val cost = when {
                    info.paymentRequired == true -> "Payment required"
                    info.fees.isNotEmpty() -> "Fees listed"
                    info.paymentRequired == false -> "Free"
                    else -> "No fee listed"
                }
                Fact("Cost", cost)
                info.fees.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Fact(
                    "Sign-in",
                    when {
                        signedIn -> "Signed in"
                        refused -> "Not working on this relay"
                        else -> stated(info.authRequired, "Required", "Not required")
                    },
                )
                Fact("Posting", stated(info.restrictedWrites, "Restricted", "Open"))
                info.paymentsUrl?.let { link -> OutlinedButton({ open(link) }) { Text("Payment page") } }
            }
            if (info.nips.isNotEmpty()) {
                Section("Features") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        info.nips.map(::nipName).distinct().forEach { name ->
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
            if (info.limits.isNotEmpty()) {
                Section("Limits") { info.limits.forEach { (label, value) -> Fact(label, value) } }
            }
            Section("Details") {
                info.software?.let { Fact("Software", listOfNotNull(it, info.version).joinToString(" ")) }
                info.contact?.let { Fact("Contact", it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.operator?.let { pubkey -> OutlinedButton({ nav.push(Screen.Profile(pubkey)) }) { Text("Operator") } }
                    info.terms?.let { link -> OutlinedButton({ open(link) }) { Text("Terms") } }
                }
            }
        }
    }
}
