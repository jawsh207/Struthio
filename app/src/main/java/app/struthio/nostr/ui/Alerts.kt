package app.struthio.nostr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Kind
import app.struthio.nostr.data.Alert

@Composable
fun AlertsScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val me = core.me ?: return
    val alerts by liveQuery(emptyList<Alert>()) { core.store.alerts(me, core.muted.value, 150) }
    val unread by liveQuery(emptySet<String>(), me) { core.unreadAlerts(me) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        UnreadHeader("Notifications", unread.isNotEmpty()) { core.readAllAlerts(me) }
        if (alerts.isEmpty()) {
            Text(
                "Replies, boosts, reactions and zaps show up here.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(alerts, key = { _, it -> it.event.id }) { index, alert -> AlertRow(alert, alert.event.id in unread, segmentShape(index, alerts.size)) }
        }
    }
}

@Composable
private fun AlertRow(alert: Alert, unread: Boolean, shape: Shape) {
    val core = LocalCore.current
    val nav = LocalNav.current
    LaunchedEffect(alert.actor) { core.wantProfile(alert.actor) }
    val e = alert.event
    val name = core.displayName(alert.actor)
    val icon = when (e.kind) {
        Kind.REACTION -> Icons.Rounded.Favorite
        Kind.REPOST, Kind.GENERIC_REPOST -> Icons.Rounded.Repeat
        Kind.ZAP_RECEIPT -> Icons.Rounded.Bolt
        else -> Icons.Outlined.ChatBubbleOutline
    }
    val headline = alert.headline(name)
    val detail = alert.detail
    val target = alert.target
    CardRow(shape, {
            core.readAlert(e.id)
            if (target != null) nav.push(Screen.Thread(target)) else nav.push(Screen.Profile(alert.actor))
        },
        headlineContent = {
            Text(headline, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (unread) FontWeight.Bold else null)
        },
        supportingContent = detail.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { UnreadAvatar(alert.actor, unread, 40.dp) },
        trailingContent = {
            Column {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(relativeTime(e.createdAt), style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}
