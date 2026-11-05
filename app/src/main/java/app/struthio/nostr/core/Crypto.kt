package app.struthio.nostr.core

import fr.acinq.secp256k1.Secp256k1
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hex {
    private const val CHARS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = CHARS[v ushr 4]
            out[i * 2 + 1] = CHARS[v and 15]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) {
            val hi = Character.digit(hex[it * 2], 16)
            val lo = Character.digit(hex[it * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex" }
            (hi * 16 + lo).toByte()
        }
    }

    fun isKey(s: String?) = s != null && s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
}

val secureRandom = SecureRandom()

fun randomBytes(n: Int) = ByteArray(n).also { secureRandom.nextBytes(it) }

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    parts.forEach(mac::update)
    return mac.doFinal()
}

/** BIP-340 keys and signatures, through libsecp256k1. */
object Keys {
    fun generate(): ByteArray {
        while (true) {
            val key = randomBytes(32)
            if (Secp256k1.secKeyVerify(key)) return key
        }
    }

    fun isValidSecret(key: ByteArray) = key.size == 32 && Secp256k1.secKeyVerify(key)

    fun publicKey(secret: ByteArray): String =
        Hex.encode(Secp256k1.pubkeyCreate(secret).copyOfRange(1, 33))

    fun sign(hash: ByteArray, secret: ByteArray): ByteArray =
        Secp256k1.signSchnorr(hash, secret, randomBytes(32))

    fun verify(signature: ByteArray, hash: ByteArray, pubkey: ByteArray): Boolean = try {
        Secp256k1.verifySchnorr(signature, hash, pubkey)
    } catch (_: Exception) {
        false
    }

    /** Unhashed x coordinate of the shared point, as NIP-44 requires. */
    fun sharedX(secret: ByteArray, pubkeyHex: String): ByteArray {
        val compressed = byteArrayOf(2) + Hex.decode(pubkeyHex)
        return Secp256k1.pubKeyTweakMul(compressed, secret).copyOfRange(1, 33)
    }
}

/** ChaCha20 from RFC 8439, starting at block counter 0. */
internal object ChaCha20 {
    private fun rotl(v: Int, c: Int) = (v shl c) or (v ushr (32 - c))

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    /** The key derivation step of XChaCha20: a subkey from the key and 16 bytes of nonce. */
    fun hchacha(key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 16)
        val k = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        val n = ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN)
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = k.getInt(i * 4)
        for (i in 0 until 4) s[12 + i] = n.getInt(i * 4)
        repeat(10) {
            quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13)
            quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12)
            quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
        }
        val out = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) out.putInt(s[i])
        for (i in 12 until 16) out.putInt(s[i])
        return out.array()
    }

    fun apply(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val k = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        val n = ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN)
        val state = IntArray(16)
        state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = k.getInt(i * 4)
        state[12] = 0
        for (i in 0 until 3) state[13 + i] = n.getInt(i * 4)

        val out = ByteArray(input.size)
        val working = IntArray(16)
        val block = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (offset < input.size) {
            state.copyInto(working)
            repeat(10) {
                quarter(working, 0, 4, 8, 12); quarter(working, 1, 5, 9, 13)
                quarter(working, 2, 6, 10, 14); quarter(working, 3, 7, 11, 15)
                quarter(working, 0, 5, 10, 15); quarter(working, 1, 6, 11, 12)
                quarter(working, 2, 7, 8, 13); quarter(working, 3, 4, 9, 14)
            }
            block.clear()
            for (i in 0 until 16) block.putInt(working[i] + state[i])
            val bytes = block.array()
            val count = minOf(64, input.size - offset)
            for (i in 0 until count) out[offset + i] = (input[offset + i].toInt() xor bytes[i].toInt()).toByte()
            offset += count
            state[12]++
        }
        return out
    }
}

/** NIP-44 version 2 payload encryption. */
object Nip44 {
    private val SALT = "nip44-v2".toByteArray()
    private const val MAX_PAYLOAD_CHARS = 2_000_000

    fun conversationKey(secret: ByteArray, pubkeyHex: String): ByteArray =
        hmacSha256(SALT, Keys.sharedX(secret, pubkeyHex))

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(conversationKey.size == 32 && nonce.size == 32)
        // HKDF-expand for L = 76: three 32-byte blocks.
        val t1 = hmacSha256(conversationKey, nonce, byteArrayOf(1))
        val t2 = hmacSha256(conversationKey, t1, nonce, byteArrayOf(2))
        val t3 = hmacSha256(conversationKey, t2, nonce, byteArrayOf(3))
        val okm = t1 + t2 + t3
        return Triple(okm.copyOfRange(0, 32), okm.copyOfRange(32, 44), okm.copyOfRange(44, 76))
    }

    internal fun paddedLength(unpadded: Long): Long {
        if (unpadded <= 32) return 32
        var nextPower = 1L
        while (nextPower < unpadded) nextPower = nextPower shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((unpadded - 1) / chunk + 1)
    }

    private fun pad(plain: ByteArray): ByteArray {
        require(plain.isNotEmpty()) { "invalid plaintext length" }
        val prefix = if (plain.size >= 65536) 6 else 2
        val out = ByteArray(prefix + paddedLength(plain.size.toLong()).toInt())
        val buffer = ByteBuffer.wrap(out)
        if (prefix == 6) {
            buffer.putShort(0)
            buffer.putInt(plain.size)
        } else {
            buffer.putShort(plain.size.toShort())
        }
        plain.copyInto(out, prefix)
        return out
    }

    private fun unpad(padded: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(padded)
        val first = buffer.short.toInt() and 0xffff
        val length: Long
        val prefix: Int
        if (first == 0) {
            length = buffer.int.toLong() and 0xffffffffL
            require(length >= 65536) { "invalid padding" }
            prefix = 6
        } else {
            length = first.toLong()
            prefix = 2
        }
        require(
            length > 0 && prefix + length <= padded.size &&
                padded.size.toLong() == prefix + paddedLength(length),
        ) { "invalid padding" }
        return padded.copyOfRange(prefix, prefix + length.toInt())
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = randomBytes(32)): String {
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val cipher = ChaCha20.apply(chachaKey, chachaNonce, pad(plaintext.toByteArray()))
        val mac = hmacSha256(hmacKey, nonce, cipher)
        return Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + cipher + mac)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(payload.isNotEmpty() && payload[0] != '#') { "unknown version" }
        require(payload.length in 132..MAX_PAYLOAD_CHARS) { "invalid payload size" }
        val data = Base64.getDecoder().decode(payload)
        require(data.size >= 99) { "invalid data size" }
        require(data[0].toInt() == 2) { "unknown version ${data[0]}" }
        val nonce = data.copyOfRange(1, 33)
        val cipher = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        require(MessageDigest.isEqual(hmacSha256(hmacKey, nonce, cipher), mac)) { "invalid MAC" }
        return String(unpad(ChaCha20.apply(chachaKey, chachaNonce, cipher)))
    }
}
