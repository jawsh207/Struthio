package app.struthio.nostr.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.value
import app.struthio.nostr.core.values
import app.struthio.nostr.data.Alert

@Composable
fun AlertsScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val me = core.me ?: return
    val alerts by liveQuery(emptyList<Alert>()) { core.store.alerts(me, core.muted.value, 150) }
    LaunchedEffect(alerts) { core.prefs.edit().putLong("alerts_read", nowSeconds()).apply() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Notifications", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        if (alerts.isEmpty()) {
            Text(
                "Replies, boosts, reactions and zaps show up here.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(alerts, key = { it.event.id }) { alert -> AlertRow(alert) }
        }
    }
}

@Composable
private fun AlertRow(alert: Alert) {
    val core = LocalCore.current
    val nav = LocalNav.current
    LaunchedEffect(alert.actor) { core.wantProfile(alert.actor) }
    val e = alert.event
    val name = core.displayName(alert.actor)
    val (icon, headline, detail) = when (e.kind) {
        Kind.REACTION -> Triple(Icons.Filled.Favorite, "$name reacted to your note", e.content.takeIf { it != "+" }.orEmpty())
        Kind.REPOST, Kind.GENERIC_REPOST -> Triple(Icons.Filled.Repeat, "$name boosted your note", "")
        Kind.ZAP_RECEIPT -> Triple(Icons.Filled.Bolt, "$name zapped ${compact(alert.msats / 1000)} sats", alert.comment)
        else -> Triple(Icons.Outlined.ChatBubbleOutline, "$name replied or mentioned you", e.content)
    }
    val target = when (e.kind) {
        Kind.NOTE, Kind.COMMENT -> e.id
        else -> e.tags.values("e").lastOrNull()
    }
    ListItem(
        headlineContent = { Text(headline, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = detail.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Avatar(alert.actor, 40.dp) },
        trailingContent = {
            Column {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(relativeTime(e.createdAt), style = MaterialTheme.typography.labelSmall)
            }
        },
        modifier = Modifier.tap {
            if (target != null) nav.push(Screen.Thread(target)) else nav.push(Screen.Profile(alert.actor))
        },
    )
}
