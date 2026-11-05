package app.struthio.nostr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Poll
import app.struthio.nostr.core.countVotes
import app.struthio.nostr.core.nowSeconds

private const val MAX_OPTIONS = 8

@Composable
fun PollCard(event: Event, poll: Poll) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val me = core.me
    LaunchedEffect(event.id) { core.loadVotes(event, poll) }
    val votes by liveQuery(emptyList<Event>(), event.id) { core.store.votes(event.id) }
    val result = remember(votes, poll, me) { countVotes(poll, votes, me) }
    val ended = poll.endsAt != null && nowSeconds() > poll.endsAt
    var busy by remember(event.id) { mutableStateOf(false) }
    val picked = remember(event.id) { mutableStateListOf<String>() }
    val showResults = ended || result.mine.isNotEmpty() || event.pubkey == me
    fun send(choices: List<String>) {
        busy = true
        scope.act(nav) {
            try {
                core.vote(event, poll, choices)
                picked.clear()
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        poll.options.forEach { option ->
            val count = result.counts[option.id] ?: 0
            val share = if (result.voters > 0) count.toFloat() / result.voters else 0f
            val chosen = option.id in result.mine || option.id in picked
            Surface(
                onClick = {
                    when {
                        !poll.multiple -> send(listOf(option.id))
                        option.id in picked -> picked.remove(option.id)
                        else -> picked += option.id
                    }
                },
                enabled = !ended && !busy,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    if (showResults && share > 0f) {
                        Box(Modifier.matchParentSize()) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(share)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                            )
                        }
                    }
                    Row(
                        Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (chosen) Icon(Icons.Rounded.Check, "Your choice", tint = MaterialTheme.colorScheme.primary)
                        Text(option.label, modifier = Modifier.weight(1f), fontWeight = if (chosen) FontWeight.SemiBold else null)
                        if (showResults) Text("${(share * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (poll.multiple && !ended && picked.isNotEmpty()) {
            FilledTonalButton({ send(picked.toList()) }, enabled = !busy) { Text("Vote") }
        }
        val parts = listOfNotNull(
            if (result.voters == 1) "1 vote" else "${result.voters} votes",
            "Choose several".takeIf { poll.multiple },
            when {
                poll.endsAt == null -> null
                ended -> "Ended"
                else -> "Ends in " + timeLeft(poll.endsAt - nowSeconds())
            },
        )
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun timeLeft(seconds: Long): String = when {
    seconds >= 86400 -> "${seconds / 86400}d"
    seconds >= 3600 -> "${seconds / 3600}h"
    else -> "${maxOf(1, seconds / 60)}m"
}

/** The choices a new poll is being built from. */
class PollDraft {
    val options = mutableStateListOf("", "")
    var multiple by mutableStateOf(false)
    var days by mutableStateOf<Int?>(1)

    val filled: List<String> get() = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

@Composable
fun PollEditor(draft: PollDraft) {
    Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        draft.options.forEachIndexed { index, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text,
                    { draft.options[index] = it.take(100) },
                    placeholder = { Text("Option ${index + 1}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (draft.options.size > 2) {
                    IconButton({ draft.options.removeAt(index) }) { Icon(Icons.Rounded.Close, "Remove option") }
                }
            }
        }
        if (draft.options.size < MAX_OPTIONS) TextButton({ draft.options += "" }) { Text("Add option") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(draft.multiple, { draft.multiple = !draft.multiple }, { Text("Choose several") })
            listOf(1 to "1 day", 3 to "3 days", 7 to "7 days", null to "No end").forEach { (days, label) ->
                FilterChip(draft.days == days, { draft.days = days }, { Text(label) })
            }
        }
    }
}
