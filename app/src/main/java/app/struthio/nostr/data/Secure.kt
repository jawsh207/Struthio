package app.struthio.nostr.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Signer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secrets at rest: AES-GCM under a hardware-backed Android Keystore key. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun put(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value)
        prefs.edit().putString(name, Base64.getEncoder().encodeToString(sealed)).apply()
    }

    fun get(name: String): ByteArray? {
        val sealed = Base64.getDecoder().decode(prefs.getString(name, null) ?: return null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private companion object {
        const val ALIAS = "struthio"
    }
}

/** Reply from the signer app after an activity round trip. */
class SignerResult(val ok: Boolean, val rejected: Boolean, val result: String?, val event: String?, val packageName: String?)

/** The activity plugs in here so that NIP-55 intents can be launched from anywhere. */
object SignerBridge {
    var launch: ((android.content.Intent, CompletableDeferred<SignerResult>) -> Unit)? = null
    private val mutex = Mutex()

    suspend fun request(intent: android.content.Intent): SignerResult = mutex.withLock {
        val launcher = launch ?: error("open Struthio to approve this")
        val deferred = CompletableDeferred<SignerResult>()
        withContext(Dispatchers.Main) { launcher(intent, deferred) }
        deferred.await()
    }
}

/** NIP-55: the key stays in the signer app. Remembered permissions answer silently. */
class ExternalSigner(
    private val context: Context,
    override val pubkey: String,
    private val packageName: String,
) : Signer {

    private fun resolve(type: String, payload: String, peer: String): Pair<String?, String?>? = try {
        context.contentResolver.query(
            Uri.parse("content://$packageName.$type"),
            arrayOf(payload, peer, pubkey),
            null, null, null,
        )?.use { c ->
            if (c.getColumnIndex("rejected") > -1) throw SecurityException("rejected")
            if (c.moveToFirst()) {
                val result = c.getColumnIndex("result").takeIf { it >= 0 }?.let(c::getString)
                val event = c.getColumnIndex("event").takeIf { it >= 0 }?.let(c::getString)
                result to event
            } else {
                null
            }
        }
    } catch (e: SecurityException) {
        if (e.message == "rejected") throw e
        null
    } catch (_: Exception) {
        null
    }

    private suspend fun ask(method: String, payload: String, peer: String?): SignerResult {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("nostrsigner:" + Uri.encode(payload))).apply {
            `package` = packageName
            putExtra("type", method)
            putExtra("current_user", pubkey)
            peer?.let { putExtra("pubkey", it) }
        }
        val reply = SignerBridge.request(intent)
        if (!reply.ok) error("the signer app failed")
        if (reply.rejected) error("request rejected in the signer app")
        return reply
    }

    override suspend fun sign(draft: Draft): Event = withContext(Dispatchers.IO) {
        val json = draft.toJson()
        val signature = resolve("SIGN_EVENT", json, "")?.first ?: ask("sign_event", json, null).result
        val event = Event(draft.id, draft.pubkey, draft.createdAt, draft.kind, draft.tags, draft.content, signature ?: error("no signature"))
        check(event.isValid()) { "the signer returned an invalid signature" }
        event
    }

    override suspend fun nip44Encrypt(plaintext: String, peer: String): String = withContext(Dispatchers.IO) {
        resolve("NIP44_ENCRYPT", plaintext, peer)?.first
            ?: ask("nip44_encrypt", plaintext, peer).result
            ?: error("no ciphertext")
    }

    override suspend fun nip44Decrypt(payload: String, peer: String, background: Boolean): String = withContext(Dispatchers.IO) {
        resolve("NIP44_DECRYPT", payload, peer)?.first
            ?: if (background) {
                error("allow decryption in the signer app")
            } else {
                ask("nip44_decrypt", payload, peer).result ?: error("no plaintext")
            }
    }

    companion object {
        fun isInstalled(context: Context): Boolean {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
            return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        }

        /** The permissions Struthio asks to have remembered, so routine work stays silent. */
        const val PERMISSIONS = """[{"type":"sign_event","kind":22242},{"type":"sign_event","kind":7},{"type":"sign_event","kind":1},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]"""
    }
}
