package app.struthio.nostr.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.Media
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.Part
import app.struthio.nostr.core.Invoice
import app.struthio.nostr.core.Poll
import app.struthio.nostr.core.emojiMap
import app.struthio.nostr.core.noteImages
import app.struthio.nostr.core.noteVideos
import app.struthio.nostr.core.parseContent
import app.struthio.nostr.core.parsePoll
import app.struthio.nostr.core.repostedEvent
import app.struthio.nostr.core.values
import app.struthio.nostr.data.Stats

/** A list entry: a note, or a boost wrapped around one. */
@Composable
fun FeedItem(event: Event, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(24.dp)) {
    val core = LocalCore.current
    if (core.deleted.containsKey(event.id)) return
    if (event.kind == Kind.REPOST) {
        val inner by produceState<Event?>(repostedEvent(event), event.id) {
            if (value == null) {
                event.tags.values("e").lastOrNull()?.let { value = core.fetchEvent(it, author = null) }
            }
        }
        val note = inner ?: return
        NoteCard(note, modifier, boostedBy = event.pubkey, shape = shape)
    } else {
        NoteCard(event, modifier, shape = shape)
    }
}

@Composable
fun NoteCard(
    event: Event,
    modifier: Modifier = Modifier,
    boostedBy: String? = null,
    detail: Boolean = false,
    shape: Shape = RoundedCornerShape(24.dp),
    highlighted: Boolean = false,
    onReply: ((Event) -> Unit)? = null,
    top: (@Composable () -> Unit)? = null,
) {
    val nav = LocalNav.current
    Surface(
        onClick = { nav.push(Screen.Thread(event.id)) },
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = if (highlighted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceBright,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = if (boostedBy != null) 12.dp else 16.dp, bottom = 8.dp)) {
            boostedBy?.let { BoostLine(it) }
            top?.invoke()
            NoteHeader(event)
            NoteBody(event, detail)
            Actions(event, onReply)
        }
    }
}

@Composable
private fun BoostLine(pubkey: String) {
    val core = LocalCore.current
    LaunchedEffect(pubkey) { core.wantProfile(pubkey) }
    Row(
        Modifier.padding(start = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Repeat, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${core.displayName(pubkey)} boosted",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NoteHeader(event: Event) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val profile = core.profiles[event.pubkey]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(event.pubkey) { nav.push(Screen.Profile(event.pubkey)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiText(
                    core.richName(event.pubkey),
                    profile?.emoji.orEmpty(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (profile?.verified == true) {
                    Icon(Icons.Rounded.Verified, "Verified", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    " · " + relativeTime(event.createdAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                profile?.nip05?.removePrefix("_@") ?: Nip19.npub(event.pubkey).let { it.take(12) + "…" + it.takeLast(4) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NoteMenu(event)
    }
}

private class Body(
    val parts: List<Part>,
    val images: List<Media>,
    val videos: List<String>,
    val quotes: List<Entity.Note>,
    val preview: String?,
    val emoji: Map<String, String>,
    val poll: Poll?,
    val invoices: List<Invoice>,
)

@Composable
fun NoteBody(event: Event, detail: Boolean = false, inQuote: Boolean = false) {
    val warning = event.tags.firstOrNull { it.firstOrNull() == "content-warning" }
    var revealed by remember(event.id) { mutableStateOf(false) }
    if (warning != null && !revealed) {
        Surface(
            onClick = { revealed = true },
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Sensitive content", style = MaterialTheme.typography.titleSmall)
                warning.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Tap to show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        return
    }
    NoteContent(event, detail, inQuote)
}

@Composable
private fun NoteContent(event: Event, detail: Boolean, inQuote: Boolean) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val body = remember(event.id) {
        val images = noteImages(event)
        val videos = noteVideos(event)
        val mediaUrls = images.map { it.url }.toSet() + videos
        val parts = parseContent(event.content)
        val quotes = parts.filterIsInstance<Part.Mention>().mapNotNull { it.entity as? Entity.Note }
        Body(
            parts.filterNot {
                (it is Part.Link && it.url in mediaUrls) || (it is Part.Mention && it.entity is Entity.Note)
            },
            images,
            videos,
            quotes,
            parts.filterIsInstance<Part.Link>().firstOrNull { it.url !in mediaUrls }?.url,
            emojiMap(event.tags),
            parsePoll(event),
            parts.filterIsInstance<Part.Lightning>().map { it.invoice },
        )
    }
    val primary = MaterialTheme.colorScheme.primary
    val linkStyle = TextLinkStyles(SpanStyle(color = primary, fontWeight = FontWeight.Medium))
    val text = buildAnnotatedString {
        for (part in body.parts) {
            when (part) {
                is Part.Text -> appendEmoji(part.text, body.emoji)
                is Part.Lightning -> Unit
                is Part.Tag -> withLink(
                    LinkAnnotation.Clickable("tag", linkStyle) { nav.push(Screen.Hashtag(part.tag)) },
                ) { append("#" + part.tag) }
                is Part.Link -> withLink(
                    LinkAnnotation.Clickable("url", linkStyle) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(part.url))) }
                    },
                ) { append(part.url.removePrefix("https://").removePrefix("http://").let { if (it.length > 40) it.take(38) + "…" else it }) }
                is Part.Mention -> {
                    val entity = part.entity
                    if (entity is Entity.Profile) {
                        withLink(LinkAnnotation.Clickable("user", linkStyle) { nav.push(Screen.Profile(entity.pubkey)) }) {
                            append("@" + core.displayName(entity.pubkey))
                        }
                    } else {
                        append(part.raw.take(24) + "…")
                    }
                }
            }
        }
    }
    val mentions = remember(event.id) {
        body.parts.filterIsInstance<Part.Mention>().mapNotNull { (it.entity as? Entity.Profile)?.pubkey }
    }
    LaunchedEffect(mentions) { mentions.forEach(core::wantProfile) }
    val trimmed = text.length > 0 && text.text.isNotBlank()
    if (trimmed) {
        Text(
            if (text.text.endsWith("\n\n")) text.subSequence(0, text.length - 2) else text,
            modifier = Modifier.padding(top = if (inQuote) 4.dp else 12.dp),
            style = if (inQuote) TextStyle(fontSize = 14.sp, lineHeight = 20.sp) else TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (detail) Int.MAX_VALUE else if (inQuote) 5 else 16,
            overflow = TextOverflow.Ellipsis,
            inlineContent = emojiImages(body.emoji),
        )
    } else {
        Spacer(Modifier.height(4.dp))
    }
    body.poll?.let { PollCard(event, it) }
    body.invoices.forEach { InvoiceCard(it) }
    var viewing by remember(event.id) { mutableStateOf<Int?>(null) }
    body.images.take(if (detail) 12 else 4).forEachIndexed { index, media ->
        NetImage(
            media.url,
            380.dp,
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .aspectRatio((media.aspect ?: 1.5f).coerceIn(0.7f, 2.4f))
                .clip(RoundedCornerShape(20.dp))
                .clickable { viewing = index },
        )
    }
    viewing?.let { start -> ImageViewer(body.images.map { it.url }, start) { viewing = null } }
    if (!inQuote) {
        body.videos.take(2).forEach { VideoCard(it) }
        if (body.images.isEmpty() && body.videos.isEmpty() && core.linkPreviewsEnabled()) body.preview?.let { LinkPreview(it) }
        body.quotes.take(2).forEach { QuoteCard(it) }
    }
}

@Composable
private fun QuoteCard(ref: Entity.Note) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val quoted by produceState<Event?>(core.store.event(ref.id), ref.id) {
        if (value == null) value = core.fetchEvent(ref.id, ref.relays, ref.author)
    }
    val event = quoted
    Surface(
        onClick = { nav.push(Screen.Thread(ref.id)) },
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (event == null) {
                Text("Loading note…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LaunchedEffect(event.pubkey) { core.wantProfile(event.pubkey) }
                Row {
                    Text(
                        core.displayName(event.pubkey),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(" · " + relativeTime(event.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NoteBody(event, inQuote = true)
            }
        }
    }
}

/** A button whose corners tighten when selected, the Expressive shape change from the mockup. */
@Composable
private fun RowScope.ActionButton(
    icon: ImageVector?,
    label: String,
    selected: Boolean,
    selectedColor: Color,
    onSelectedColor: Color,
    description: String,
    weight: Float = 1f,
    glyphs: String = "",
    emoji: Map<String, String> = emptyMap(),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val radius by animateDpAsState(if (selected) 14.dp else 22.dp, label = "corner")
    Surface(
        modifier = Modifier
            .weight(weight)
            .height(44.dp)
            .clip(RoundedCornerShape(radius))
            .combinedClickable(onLongClick = onLongClick, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(radius),
        color = if (selected) selectedColor else Color.Transparent,
        contentColor = if (selected) onSelectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp))
            if (glyphs.isNotEmpty()) EmojiText(glyphs, emoji, style = TextStyle(fontSize = 16.sp), maxLines = 1)
            if (label.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Actions(event: Event, onReply: ((Event) -> Unit)? = null) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(event.id) { core.wantStats(event.id) }
    val stats = core.stats[event.id] ?: Stats()
    val colors = MaterialTheme.colorScheme
    var boostMenu by remember { mutableStateOf(false) }
    var zapping by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    Row(
        Modifier.padding(top = 8.dp).padding(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ActionButton(
            Icons.Outlined.ChatBubbleOutline, count(stats.comments.toLong()), false, colors.secondaryContainer,
            colors.onSecondaryContainer, "Comments",
        ) { if (onReply != null) onReply(event) else nav.push(Screen.Thread(event.id)) }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            Row {
                ActionButton(
                    Icons.Rounded.Repeat, count(stats.boosts.toLong()), stats.mineBoosted, colors.primaryContainer,
                    colors.onPrimaryContainer, "Boost or quote",
                ) { boostMenu = true }
            }
            DropdownMenu(boostMenu, { boostMenu = false }) {
                DropdownMenuItem(text = { Text("Boost") }, onClick = {
                    boostMenu = false
                    scope.act(nav) { core.boost(event) }
                })
                DropdownMenuItem(text = { Text("Quote") }, onClick = {
                    boostMenu = false
                    nav.push(Screen.Compose(quote = event.id))
                })
            }
        }
        val shown = (listOfNotNull(stats.mine) + stats.top).distinct().take(2).joinToString("")
        ActionButton(
            Icons.Outlined.FavoriteBorder.takeIf { shown.isEmpty() },
            count(stats.reactions.toLong()), stats.mine != null, colors.secondaryContainer,
            colors.onSecondaryContainer, "Reactions",
            glyphs = shown,
            emoji = stats.emoji,
            onLongClick = { picking = true },
        ) {
            // A tap gives a purple heart, and a second tap takes the reaction back.
            scope.act(nav) { if (stats.mine != null) core.unreact(event) else core.react(event) }
        }
        ActionButton(
            if (stats.mineZapped) Icons.Rounded.Bolt else Icons.Outlined.Bolt,
            count(stats.zapMsats / 1000), stats.mineZapped, colors.tertiaryContainer,
            colors.onTertiaryContainer, "Zaps", 1.15f,
        ) { zapping = true }
    }
    if (zapping) ZapSheet(event, event.pubkey) { zapping = false }
    if (picking) {
        EmojiSheet({ emoji ->
            picking = false
            scope.act(nav) { core.react(event, emoji) }
        }) { picking = false }
    }
}

private fun count(n: Long) = if (n > 0) compact(n) else ""

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZapSheet(event: Event?, recipient: String, onDismiss: () -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf(core.prefs.getLong("zap_default", 100L).toString()) }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Zap ${core.displayName(recipient)}", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(21L, 100L, 500L, 1000L, 5000L).forEach { value ->
                    FilterChip(amount == value.toString(), { amount = value.toString() }, { Text(compact(value)) })
                }
            }
            OutlinedTextField(
                amount,
                { amount = it.filter(Char::isDigit).take(9) },
                label = { Text("Amount in sats") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(comment, { comment = it }, label = { Text("Comment (optional)") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val sats = amount.toLongOrNull() ?: 0
                    if (sats <= 0) return@Button
                    busy = true
                    scope.act(nav) {
                        try {
                            core.prefs.edit().putLong("zap_default", sats).apply()
                            val invoices = core.zap(event, recipient.takeIf { event == null }, sats, comment)
                            invoices.firstOrNull()?.let { invoice ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$invoice")))
                            }
                            nav.say("Zap sent")
                            onDismiss()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.Rounded.Bolt, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Zapping…" else "Zap")
            }
        }
    }
}

fun Modifier.tap(onClick: () -> Unit) = clickable(onClick = onClick)
