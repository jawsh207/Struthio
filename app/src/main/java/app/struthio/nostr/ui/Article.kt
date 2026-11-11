package app.struthio.nostr.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Markdown
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.article
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** About how long an article takes to read, at an ordinary 230 words a minute. */
private fun minutesToRead(text: String): Int = maxOf(1, text.split(Regex("\\s+")).count { it.isNotEmpty() } / 230)

/** NIP-23 in a feed or a quote: picture, title, summary and reading time. Tapping opens it. */
@Composable
fun ArticleCard(event: Event, inQuote: Boolean = false) {
    val info = remember(event.id) { article(event) } ?: return
    Column(Modifier.padding(top = if (inQuote) 6.dp else 12.dp)) {
        if (!inQuote) info.image?.let {
            NetImage(it, 380.dp, Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(20.dp)))
        }
        Text(
            info.title,
            modifier = Modifier.padding(top = if (inQuote || info.image == null) 0.dp else 10.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        info.summary?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (inQuote) 2 else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Article · ${minutesToRead(event.content)} min read",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** NIP-23 opened: the whole article, its Markdown laid out. */
@Composable
fun ArticleBody(event: Event) {
    val info = remember(event.id) { article(event) }
    val blocks = remember(event.id) { Markdown.parse(event.content) }
    Column(Modifier.padding(top = 12.dp)) {
        info?.image?.let {
            NetImage(it, 420.dp, Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(20.dp)))
        }
        info?.title?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 14.dp),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        val published = info?.publishedAt ?: event.createdAt
        Text(
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(published * 1000)) +
                " · ${minutesToRead(event.content)} min read",
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        info?.summary?.let {
            Text(
                it,
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        blocks.forEach { MarkdownBlock(it) }
    }
}

@Composable
private fun MarkdownBlock(block: Markdown.Block) {
    val body = TextStyle(fontSize = 16.sp, lineHeight = 25.sp)
    when (block) {
        is Markdown.Block.Heading -> Text(
            spans(block.text),
            modifier = Modifier.padding(top = 18.dp),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp)
                else -> MaterialTheme.typography.titleMedium
            }.copy(fontWeight = FontWeight.SemiBold),
        )
        is Markdown.Block.Paragraph -> Text(spans(block.text), modifier = Modifier.padding(top = 12.dp), style = body)
        is Markdown.Block.Quote -> Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Text(
                spans(block.text),
                modifier = Modifier.padding(start = 12.dp),
                style = body.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is Markdown.Block.Item -> Row(Modifier.padding(top = 6.dp, start = (8 + block.depth * 16).dp)) {
            Text(block.marker, style = body, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
            Text(spans(block.text), style = body)
        }
        is Markdown.Block.Code -> Surface(
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(block.text, modifier = Modifier.padding(12.dp), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp))
        }
        is Markdown.Block.Image -> NetImage(
            block.url,
            420.dp,
            Modifier.padding(top = 12.dp).fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(16.dp)),
        )
        Markdown.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 16.dp))
    }
}

/** Styled text with working links: web pages open in the browser, nostr: links inside Struthio. */
@Composable
private fun spans(list: List<Markdown.Span>): AnnotatedString {
    val core = LocalCore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val linkStyle = TextLinkStyles(SpanStyle(color = primary, fontWeight = FontWeight.Medium))
    return buildAnnotatedString {
        for (span in list) {
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (span.strike) TextDecoration.LineThrough else null,
            )
            val link = span.link
            if (link == null) {
                withStyle(style) { append(span.text) }
                continue
            }
            val entity = if (link.startsWith("nostr:")) Nip19.decode(link.removePrefix("nostr:")) else null
            // A bare nostr: link reads as the name it points to, not a long code.
            val label = when {
                span.text != link -> span.text
                entity is Entity.Profile -> "@" + core.displayName(entity.pubkey)
                entity != null -> "note"
                else -> link.removePrefix("https://").removePrefix("http://").let { if (it.length > 48) it.take(46) + "…" else it }
            }
            withLink(
                LinkAnnotation.Clickable("link", linkStyle) {
                    when (entity) {
                        is Entity.Profile -> nav.push(Screen.Profile(entity.pubkey))
                        is Entity.Note -> nav.push(Screen.Thread(entity.id, entity.relays, entity.author))
                        is Entity.Address -> core.scope.launch {
                            val found = core.fetchAddress(entity.kind, entity.author, entity.identifier, entity.relays)
                            withContext(Dispatchers.Main) { found?.let { nav.push(Screen.Thread(it.id)) } }
                        }
                        else -> if (entity == null) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                        }
                    }
                },
            ) { withStyle(style) { append(label) } }
        }
    }
}
