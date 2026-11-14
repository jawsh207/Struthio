package app.struthio.nostr.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.roundToInt
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
    val previews = remember { core.linkPreviewsEnabled() }
    val info by produceState<LinkInfo?>(null, url) { if (previews) value = core.linkInfo(url) }
    val open = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        Unit
    }
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            if (playing) {
                EmbedPlayer(embed)
            } else {
                // The site's own still when it's known from the address, otherwise the link preview's image.
                (if (previews) embed.thumbnail ?: info?.image else null)?.let { NetImage(it, 380.dp, Modifier.fillMaxSize()) }
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

private const val ORIGIN = "https://app.zino.nostr/"

/** Words a player page shows when it won't play, worth a line in the logs. */
private val REFUSALS = listOf("Error", "error", "Sign in", "unavailable", "not available", "blocked")

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbedPlayer(embed: VideoEmbed) {
    val context = LocalContext.current
    val activity = context as? Activity
    // The player's own full-screen button, while it's showing: the view it handed over and how to end it.
    var fullscreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }

    fun leaveFullscreen() {
        val (view, callback) = fullscreen ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreen = null
        callback.onCustomViewHidden()
        // Back to the app's own portrait lock.
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= 30) activity?.window?.insetsController?.show(WindowInsets.Type.systemBars())
    }

    BackHandler(fullscreen != null) { leaveFullscreen() }

    // The player is an ordinary view laid over the screen, not drawn inside it: inside a Compose screen
    // Android's web player hides the picture behind the screen's layers, leaving sound over a black or white box.
    val player = remember {
            WebView(context).apply {
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
                                if (REFUSALS.any { it in shown }) AppLog.add("video ${embed.site}: shows \"${shown.take(120)}\"")
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
                    // Full screen only when the player's own button asks for it.
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        val decor = activity?.window?.decorView as? FrameLayout ?: return callback.onCustomViewHidden()
                        leaveFullscreen()
                        view.setBackgroundColor(android.graphics.Color.BLACK)
                        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        fullscreen = view to callback
                        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        if (Build.VERSION.SDK_INT >= 30) activity.window.insetsController?.hide(WindowInsets.Type.systemBars())
                    }

                    override fun onHideCustomView() = leaveFullscreen()

                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            AppLog.add("video ${embed.site}: ${message.message().take(160)}")
                        }
                        return true
                    }
                }
                // The player page itself, with the app named as referrer, as YouTube asks of apps.
                // Players refuse to start without one ("Error 153").
                // Starts at once, so one tap on Zino's play button is enough.
                val start = embed.player + (if ('?' in embed.player) "&" else "?") + "autoplay=1"
                loadUrl(start, mapOf("Referer" to ORIGIN))
            }
    }
    val host = activity?.window?.decorView as? FrameLayout
    DisposableEffect(player, host) {
        host?.addView(player, FrameLayout.LayoutParams(0, 0))
        onDispose {
            host?.removeView(player)
            player.stopLoading()
            player.loadUrl("about:blank")
            player.destroy()
        }
    }
    // Leaving the app pauses the video; coming back lets it carry on.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.onPause()
                Lifecycle.Event.ON_RESUME -> player.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            leaveFullscreen()
        }
    }


    // Keeps the player over the card as the note scrolls, cut to the part of the card that's on screen.
    Box(Modifier.fillMaxSize().onGloballyPositioned { place(player, host, it) })
}

private fun place(player: WebView, host: FrameLayout?, card: LayoutCoordinates) {
    if (host == null || !card.isAttached) return
    val at = card.positionInWindow()
    val width = card.size.width
    val height = card.size.height
    val params = player.layoutParams
    if (params.width != width || params.height != height) {
        params.width = width
        params.height = height
        player.layoutParams = params
    }
    player.translationX = at.x
    player.translationY = at.y
    val seen = card.boundsInWindow()
    if (seen.width <= 0f || seen.height <= 0f) {
        player.visibility = View.INVISIBLE
    } else {
        player.visibility = View.VISIBLE
        player.clipBounds = android.graphics.Rect(
            (seen.left - at.x).roundToInt(),
            (seen.top - at.y).roundToInt(),
            (seen.right - at.x).roundToInt(),
            (seen.bottom - at.y).roundToInt(),
        )
    }
}
