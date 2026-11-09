package app.struthio.nostr.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import app.struthio.nostr.core.LinkInfo
import app.struthio.nostr.core.VideoEmbed
import app.struthio.nostr.net.AppLog

/**
 * A video from a hosting site. Nothing is fetched from that site until play is tapped, apart
 * from the link card, which follows the Link previews setting as before.
 */
@Composable
fun EmbedCard(url: String, embed: VideoEmbed) {
    val core = LocalCore.current
    val context = LocalContext.current
    val active = LocalScreenActive.current
    var playing by remember(url) { mutableStateOf(false) }
    // Leaving the screen ends playback, as hidden screens stay alive now.
    LaunchedEffect(active) { if (!active) playing = false }
    val info by produceState<LinkInfo?>(null, url) { if (core.linkPreviewsEnabled()) value = core.linkInfo(url) }
    val open = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        Unit
    }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            if (playing) {
                EmbedPlayer(embed)
            } else {
                info?.image?.let { NetImage(it, 380.dp, Modifier.fillMaxSize()) }
                Box(
                    Modifier.fillMaxSize().clickable { playing = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.PlayArrow, "Play on ${embed.site}", tint = Color.White, modifier = Modifier.size(36.dp)) }
                }
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

private const val ORIGIN = "https://app.struthio.nostr/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbedPlayer(embed: VideoEmbed) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            WebView(it).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    // Anything the player tries to navigate to opens in the browser instead.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.isForMainFrame) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // What the player page says, so a refusal like "Error 153" shows up in the logs.
                        view.postDelayed({
                            view.evaluateJavascript("(document.body&&document.body.innerText||'').slice(0,200)") { text ->
                                val shown = text.trim('"').replace("\\n", " ").trim()
                                android.util.Log.i("StruthioEmbed", "page text: $shown")
                                if (shown.isNotEmpty()) AppLog.add("video ${embed.site}: shows \"${shown.take(120)}\"")
                            }
                        }, 6_000)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        AppLog.add("video ${embed.site}: ${request.url.host} failed: ${error.description}")
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        AppLog.add("video ${embed.site}: ${request.url.host}${request.url.path} answered ${response.statusCode}")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            AppLog.add("video ${embed.site}: ${message.message().take(160)}")
                        }
                        android.util.Log.i("StruthioEmbed", "${message.messageLevel()} ${message.message()}")
                        return true
                    }
                }
                // The player page itself, with the app named as referrer, as YouTube asks of apps.
                // Players refuse to start without one ("Error 153").
                loadUrl(embed.player, mapOf("Referer" to ORIGIN))
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        },
        modifier = Modifier.fillMaxSize(),
    )
}
