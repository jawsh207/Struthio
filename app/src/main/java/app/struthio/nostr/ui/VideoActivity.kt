package app.struthio.nostr.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import app.struthio.nostr.core.VideoEmbed
import app.struthio.nostr.net.AppLog

/**
 * Plays an embedded video full screen in a plain web view. Drawn inside a Compose screen, the
 * web view's video layer can be hidden by the screen's clipping, leaving sound over a black or white box.
 */
class VideoActivity : Activity() {
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var fullscreen: View? = null
    private var hideFullscreen: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player = intent.getStringExtra(EXTRA_PLAYER) ?: return finish()
        val site = intent.getStringExtra(EXTRA_SITE).orEmpty()
        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        setContentView(root)
        hideSystemBars()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, OnBackInvokedCallback { back() })
        }

        val view = WebView(this).apply {
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
                    if (!request.isForMainFrame) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // What the player page says, so a refusal like "Error 153" shows up in the logs.
                    view.postDelayed({
                        view.evaluateJavascript("(document.body&&document.body.innerText||'').slice(0,200)") { text ->
                            val shown = text.trim('"').replace("\\n", " ").trim()
                            if (REFUSALS.any { it in shown }) AppLog.add("video $site: shows \"${shown.take(120)}\"")
                        }
                    }, 6_000)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    AppLog.add("video $site: ${request.url.host} failed: ${error.description}")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    AppLog.add("video $site: ${request.url.host}${request.url.path} answered ${response.statusCode}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                // The player's own full-screen button.
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreen?.let { root.removeView(it) }
                    fullscreen = view
                    hideFullscreen = callback
                    root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }

                override fun onHideCustomView() = leaveFullscreen()

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        AppLog.add("video $site: ${message.message().take(160)}")
                    }
                    return true
                }
            }
        }
        web = view
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Starts at once, with the app named as referrer as YouTube asks of apps ("Error 153" without one).
        val start = player + (if ('?' in player) "&" else "?") + "autoplay=1"
        view.loadUrl(start, mapOf("Referer" to ORIGIN))
    }

    private fun leaveFullscreen() {
        fullscreen?.let { root.removeView(it) }
        fullscreen = null
        hideFullscreen?.onCustomViewHidden()
        hideFullscreen = null
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /** Back closes the player's own full screen first, then the player. */
    private fun back() {
        if (fullscreen != null) leaveFullscreen() else finish()
    }

    @Deprecated("Used before Android 13; newer versions go through the back callback.")
    override fun onBackPressed() = back()

    override fun onPause() {
        super.onPause()
        web?.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            root.removeView(it)
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PLAYER = "player"
        private const val EXTRA_SITE = "site"
        private const val ORIGIN = "https://app.struthio.nostr/"

        /** Words a player page shows when it won't play, worth a line in the logs. */
        private val REFUSALS = listOf("Error", "error", "Sign in", "unavailable", "not available", "blocked")

        fun open(context: Context, embed: VideoEmbed) {
            context.startActivity(
                Intent(context, VideoActivity::class.java)
                    .putExtra(EXTRA_PLAYER, embed.player)
                    .putExtra(EXTRA_SITE, embed.site),
            )
        }
    }
}
