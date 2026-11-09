package app.struthio.nostr.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.LinkInfo
import app.struthio.nostr.core.VideoEmbed

/**
 * A video from a hosting site. Nothing is fetched from that site until play is tapped, apart
 * from the link card, which follows the Link previews setting as before.
 */
@Composable
fun EmbedCard(url: String, embed: VideoEmbed) {
    val core = LocalCore.current
    val context = LocalContext.current
    val previews = remember { core.linkPreviewsEnabled() }
    val info by produceState<LinkInfo?>(null, url) { if (previews) value = core.linkInfo(url) }
    val open = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        Unit
    }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            // The site's own still when it's known from the address, otherwise the link preview's image.
            (if (previews) embed.thumbnail ?: info?.image else null)?.let { NetImage(it, 380.dp, Modifier.fillMaxSize()) }
            Box(
                Modifier.fillMaxSize().clickable { VideoActivity.open(context, embed) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, "Play on ${embed.site}", tint = Color.White, modifier = Modifier.size(36.dp)) }
            }
        }
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                info?.title?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(embed.site, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(open) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser") }
        }
    }
}
