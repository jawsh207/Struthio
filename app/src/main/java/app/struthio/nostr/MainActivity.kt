package app.struthio.nostr

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.struthio.nostr.data.SignerBridge
import app.struthio.nostr.data.SignerResult
import app.struthio.nostr.ui.App
import app.struthio.nostr.ui.ZinoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val core get() = (application as ZinoApp).core
    private val deepLink = MutableStateFlow<String?>(null)
    private var signerReply: CompletableDeferred<SignerResult>? = null

    private val signerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        signerReply?.complete(
            SignerResult(
                ok = result.resultCode == RESULT_OK,
                rejected = data?.getBooleanExtra("rejected", false) == true,
                result = data?.getStringExtra("result"),
                event = data?.getStringExtra("event"),
                packageName = data?.getStringExtra("package"),
            ),
        )
        signerReply = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        SignerBridge.launch = { intent, reply ->
            signerReply = reply
            try {
                signerLauncher.launch(intent)
            } catch (e: Exception) {
                signerReply = null
                reply.completeExceptionally(e)
            }
        }
        deepLink.value = intent?.dataString
        setContent {
            ZinoTheme {
                App(core, deepLink)
            }
        }
    }

    /**
     * Light/dark, text size, display size and language changes are handled in place rather than
     * by rebuilding the activity, which would drop every open screen and land back on Home.
     * Compose redraws itself; the system bar icons need telling.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink.value = intent.dataString
    }

    override fun onStart() {
        super.onStart()
        core.pool.activityStarted()
    }

    override fun onStop() {
        super.onStop()
        core.pool.activityStopped()
    }
}
