package app.struthio.nostr.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer

/** NIP-49: a private key sealed with a password, as an ncryptsec string. */
object KeyBackup {
    private const val VERSION: Byte = 2
    private const val LOG_N = 16
    private const val MAX_LOG_N = 22
    private const val LENGTH = 91

    /** 2 says this app doesn't track whether the key was ever handled insecurely. */
    private const val KEY_SECURITY: Byte = 2

    fun encrypt(secret: ByteArray, password: String, logN: Int = LOG_N): String {
        val salt = randomBytes(16)
        val nonce = randomBytes(24)
        val ad = byteArrayOf(KEY_SECURITY)
        val key = scrypt(normalized(password), salt, logN)
        val sealed = XChaCha20Poly1305.seal(key, nonce, ad, secret)
        key.fill(0)
        return Bech32.encode("ncryptsec", byteArrayOf(VERSION, logN.toByte()) + salt + nonce + ad + sealed)
    }

    /** Null means the password is wrong or the backup is damaged, which can't be told apart. */
    fun decrypt(text: String, password: String): ByteArray? {
        val (hrp, data) = runCatching { Bech32.decode(text.trim()) }.getOrNull() ?: return null
        if (hrp != "ncryptsec" || data.size != LENGTH || data[0] != VERSION) return null
        val logN = data[1].toInt() and 0xff
        if (logN !in 1..MAX_LOG_N) return null
        val salt = data.copyOfRange(2, 18)
        val nonce = data.copyOfRange(18, 42)
        val ad = data.copyOfRange(42, 43)
        val key = scrypt(normalized(password), salt, logN)
        return XChaCha20Poly1305.open(key, nonce, ad, data.copyOfRange(43, data.size)).also { key.fill(0) }
    }

    private fun normalized(password: String) = Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray()

    private fun pbkdf2(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var filled = 0
        var block = 1
        while (filled < length) {
            val index = ByteBuffer.allocate(4).putInt(block++).array()
            val part = hmacSha256(password, salt, index)
            val count = minOf(part.size, length - filled)
            part.copyInto(out, filled, 0, count)
            filled += count
        }
        return out
    }

    internal fun scrypt(password: ByteArray, salt: ByteArray, logN: Int, r: Int = 8): ByteArray {
        val n = 1 shl logN
        val words = 32 * r
        val b = pbkdf2(password, salt, 128 * r)
        val x = IntArray(words)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(x)
        val v = IntArray(words * n)
        val y = IntArray(words)
        val mix = IntArray(16)
        val scratch = IntArray(16)
        for (i in 0 until n) {
            x.copyInto(v, i * words)
            blockMix(x, y, mix, scratch, r)
        }
        for (i in 0 until n) {
            val j = x[words - 16] and (n - 1)
            for (k in 0 until words) x[k] = x[k] xor v[j * words + k]
            blockMix(x, y, mix, scratch, r)
        }
        v.fill(0)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(x)
        return pbkdf2(password, b, 32)
    }

    private fun blockMix(b: IntArray, y: IntArray, x: IntArray, z: IntArray, r: Int) {
        b.copyInto(x, 0, (2 * r - 1) * 16, 2 * r * 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) x[k] = x[k] xor b[i * 16 + k]
            salsa8(x, z)
            x.copyInto(y, i * 16)
        }
        for (i in 0 until r) {
            y.copyInto(b, i * 16, i * 2 * 16, i * 2 * 16 + 16)
            y.copyInto(b, (i + r) * 16, (i * 2 + 1) * 16, (i * 2 + 1) * 16 + 16)
        }
    }

    private fun rotl(v: Int, c: Int) = (v shl c) or (v ushr (32 - c))

    private fun salsa8(b: IntArray, x: IntArray) {
        b.copyInto(x)
        repeat(4) {
            x[4] = x[4] xor rotl(x[0] + x[12], 7); x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13); x[0] = x[0] xor rotl(x[12] + x[8], 18)
            x[9] = x[9] xor rotl(x[5] + x[1], 7); x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13); x[5] = x[5] xor rotl(x[1] + x[13], 18)
            x[14] = x[14] xor rotl(x[10] + x[6], 7); x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13); x[10] = x[10] xor rotl(x[6] + x[2], 18)
            x[3] = x[3] xor rotl(x[15] + x[11], 7); x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13); x[15] = x[15] xor rotl(x[11] + x[7], 18)
            x[1] = x[1] xor rotl(x[0] + x[3], 7); x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13); x[0] = x[0] xor rotl(x[3] + x[2], 18)
            x[6] = x[6] xor rotl(x[5] + x[4], 7); x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13); x[5] = x[5] xor rotl(x[4] + x[7], 18)
            x[11] = x[11] xor rotl(x[10] + x[9], 7); x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13); x[10] = x[10] xor rotl(x[9] + x[8], 18)
            x[12] = x[12] xor rotl(x[15] + x[14], 7); x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13); x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }
        for (i in 0 until 16) b[i] += x[i]
    }
}

/** The authenticated cipher NIP-49 calls for, on top of the ChaCha20 already here for NIP-44. */
internal object XChaCha20Poly1305 {
    private val MODULUS = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
    private val CLAMP = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)
    private val MAX_TAG = BigInteger.ONE.shiftLeft(128)

    private fun stream(key: ByteArray, nonce: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val subkey = ChaCha20.hchacha(key, nonce.copyOfRange(0, 16))
        val inner = ByteArray(4) + nonce.copyOfRange(16, 24)
        // Keystream block 0 is the one-time Poly1305 key, and the message starts at block 1.
        val out = ChaCha20.apply(subkey, inner, ByteArray(64) + input)
        return out.copyOfRange(0, 32) to out.copyOfRange(64, out.size)
    }

    private fun little(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

    private fun tag(polyKey: ByteArray, ad: ByteArray, sealed: ByteArray): ByteArray {
        fun padded(data: ByteArray) = data + ByteArray((16 - data.size % 16) % 16)
        val lengths = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(ad.size.toLong()).putLong(sealed.size.toLong()).array()
        val message = padded(ad) + padded(sealed) + lengths
        val r = little(polyKey.copyOfRange(0, 16)).and(CLAMP)
        val s = little(polyKey.copyOfRange(16, 32))
        var sum = BigInteger.ZERO
        var offset = 0
        while (offset < message.size) {
            val block = message.copyOfRange(offset, minOf(offset + 16, message.size))
            sum = sum.add(little(block + byteArrayOf(1))).multiply(r).mod(MODULUS)
            offset += 16
        }
        val bytes = sum.add(s).mod(MAX_TAG).toByteArray().reversedArray()
        return ByteArray(16) { bytes.getOrElse(it) { 0 } }
    }

    fun seal(key: ByteArray, nonce: ByteArray, ad: ByteArray, plain: ByteArray): ByteArray {
        val (polyKey, cipher) = stream(key, nonce, plain)
        return cipher + tag(polyKey, ad, cipher)
    }

    fun open(key: ByteArray, nonce: ByteArray, ad: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < 16) return null
        val cipher = sealed.copyOfRange(0, sealed.size - 16)
        val (polyKey, plain) = stream(key, nonce, cipher)
        val expected = tag(polyKey, ad, cipher)
        return plain.takeIf { MessageDigest.isEqual(expected, sealed.copyOfRange(sealed.size - 16, sealed.size)) }
    }
}
