package app.struthio.nostr.core

import org.json.JSONArray
import org.json.JSONObject

object Kind {
    const val METADATA = 0
    const val NOTE = 1
    const val FOLLOWS = 3
    const val DELETION = 5
    const val REPOST = 6
    const val REACTION = 7
    const val SEAL = 13
    const val DM = 14
    const val FILE_DM = 15
    const val GENERIC_REPOST = 16
    const val GIFT_WRAP = 1059
    const val COMMENT = 1111
    const val ZAP_REQUEST = 9734
    const val ZAP_RECEIPT = 9735
    const val MUTES = 10000
    const val RELAYS = 10002
    const val SEARCH_RELAYS = 10007
    const val DM_RELAYS = 10050
    const val MEDIA_SERVERS = 10063
    const val NWC_INFO = 13194
    const val AUTH = 22242
    const val BLOSSOM_AUTH = 24242
    const val NWC_REQUEST = 23194
    const val NWC_RESPONSE = 23195

    fun isReplaceable(kind: Int) = kind == METADATA || kind == FOLLOWS || kind in 10000..19999
    fun isAddressable(kind: Int) = kind in 30000..39999
    fun isEphemeral(kind: Int) = kind in 20000..29999
}

typealias Tags = List<List<String>>

fun Tags.first(name: String): List<String>? = firstOrNull { it.size >= 2 && it[0] == name }
fun Tags.value(name: String): String? = first(name)?.get(1)
fun Tags.values(name: String): List<String> = filter { it.size >= 2 && it[0] == name }.map { it[1] }

/** JSON string escaping exactly as NIP-01 prescribes for event ids. */
private fun StringBuilder.jsonString(value: String) {
    append('"')
    for (c in value) {
        when (c) {
            '\n' -> append("\\n")
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
    append('"')
}

private fun StringBuilder.jsonTags(tags: Tags) {
    append('[')
    tags.forEachIndexed { i, tag ->
        if (i > 0) append(',')
        append('[')
        tag.forEachIndexed { j, v ->
            if (j > 0) append(',')
            jsonString(v)
        }
        append(']')
    }
    append(']')
}

fun quoteJson(value: String) = StringBuilder().apply { jsonString(value) }.toString()

fun tagsToJson(tags: Tags) = StringBuilder().apply { jsonTags(tags) }.toString()

fun parseTags(array: JSONArray?): Tags {
    if (array == null) return emptyList()
    return List(array.length()) { i ->
        val tag = array.optJSONArray(i)
        if (tag == null) emptyList() else List(tag.length()) { j -> tag.optString(j) }
    }
}

fun computeEventId(pubkey: String, createdAt: Long, kind: Int, tags: Tags, content: String): String {
    val json = StringBuilder(content.length + 128).apply {
        append("[0,")
        jsonString(pubkey)
        append(',').append(createdAt).append(',').append(kind).append(',')
        jsonTags(tags)
        append(',')
        jsonString(content)
        append(']')
    }
    return Hex.encode(sha256(json.toString().toByteArray()))
}

/** An event template: not yet signed. The id is what a rumor carries too. */
class Draft(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: Tags,
    val content: String,
) {
    val id: String by lazy { computeEventId(pubkey, createdAt, kind, tags, content) }

    fun toJson(): String = StringBuilder(content.length + 256).apply {
        append("{\"id\":\"").append(id).append("\",\"pubkey\":\"").append(pubkey)
        append("\",\"created_at\":").append(createdAt).append(",\"kind\":").append(kind).append(",\"tags\":")
        jsonTags(tags)
        append(",\"content\":")
        jsonString(content)
        append('}')
    }.toString()
}

class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: Tags,
    val content: String,
    val sig: String,
) {
    fun toJson(): String = StringBuilder(content.length + 400).apply {
        append("{\"id\":\"").append(id).append("\",\"pubkey\":\"").append(pubkey)
        append("\",\"created_at\":").append(createdAt).append(",\"kind\":").append(kind).append(",\"tags\":")
        jsonTags(tags)
        append(",\"content\":")
        jsonString(content)
        append(",\"sig\":\"").append(sig).append("\"}")
    }.toString()

    fun hasValidId() = computeEventId(pubkey, createdAt, kind, tags, content) == id

    fun hasValidSignature() = try {
        Keys.verify(Hex.decode(sig), Hex.decode(id), Hex.decode(pubkey))
    } catch (_: Exception) {
        false
    }

    fun isValid() = hasValidId() && hasValidSignature()

    fun isExpired(now: Long = nowSeconds()) =
        tags.value("expiration")?.toLongOrNull()?.let { it <= now } ?: false

    companion object {
        fun parse(o: JSONObject): Event? = try {
            val id = o.getString("id")
            val pubkey = o.getString("pubkey")
            val sig = o.getString("sig")
            if (Hex.isKey(id) && Hex.isKey(pubkey) && sig.length == 128) {
                Event(id, pubkey, o.getLong("created_at"), o.getInt("kind"), parseTags(o.getJSONArray("tags")), o.getString("content"), sig)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        fun parse(json: String): Event? = try {
            parse(JSONObject(json))
        } catch (_: Exception) {
            null
        }

        fun parseRumor(json: String): Draft? = try {
            val o = JSONObject(json)
            Draft(o.getString("pubkey"), o.getLong("created_at"), o.getInt("kind"), parseTags(o.getJSONArray("tags")), o.getString("content"))
        } catch (_: Exception) {
            null
        }
    }
}

fun nowSeconds() = System.currentTimeMillis() / 1000

class Filter(
    val ids: Collection<String>? = null,
    val authors: Collection<String>? = null,
    val kinds: Collection<Int>? = null,
    val tags: Map<String, Collection<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null,
) {
    fun toJson(): String = StringBuilder("{").apply {
        var first = true
        fun key(name: String) {
            if (!first) append(',')
            first = false
            jsonString(name)
            append(':')
        }
        fun strings(name: String, values: Collection<String>) {
            key(name)
            append('[')
            values.forEachIndexed { i, v ->
                if (i > 0) append(',')
                jsonString(v)
            }
            append(']')
        }
        ids?.let { strings("ids", it) }
        authors?.let { strings("authors", it) }
        kinds?.let {
            key("kinds")
            append(it.joinToString(",", "[", "]"))
        }
        tags.forEach { (name, values) -> strings("#$name", values) }
        since?.let { key("since"); append(it) }
        until?.let { key("until"); append(it) }
        limit?.let { key("limit"); append(it) }
        search?.let { key("search"); jsonString(it) }
        append('}')
    }.toString()
}

/** Signs and encrypts on behalf of the user: with a local key or an external NIP-55 signer. */
interface Signer {
    val pubkey: String
    suspend fun sign(draft: Draft): Event
    suspend fun nip44Encrypt(plaintext: String, peer: String): String

    /** [background] means no prompt may be shown; a signer that needs one throws instead. */
    suspend fun nip44Decrypt(payload: String, peer: String, background: Boolean = true): String
}

class LocalSigner(private val secret: ByteArray) : Signer {
    override val pubkey: String = Keys.publicKey(secret)
    private val conversations = HashMap<String, ByteArray>()

    private fun conversation(peer: String): ByteArray = synchronized(conversations) {
        conversations.getOrPut(peer) { Nip44.conversationKey(secret, peer) }
    }

    fun signNow(draft: Draft): Event {
        require(draft.pubkey == pubkey)
        val sig = Hex.encode(Keys.sign(Hex.decode(draft.id), secret))
        return Event(draft.id, draft.pubkey, draft.createdAt, draft.kind, draft.tags, draft.content, sig)
    }

    override suspend fun sign(draft: Draft) = signNow(draft)
    override suspend fun nip44Encrypt(plaintext: String, peer: String) = Nip44.encrypt(plaintext, conversation(peer))
    override suspend fun nip44Decrypt(payload: String, peer: String, background: Boolean) = Nip44.decrypt(payload, conversation(peer))
}

/** Signed in with an npub: everything can be read, nothing can be signed. */
class ReadOnlySigner(override val pubkey: String) : Signer {
    private fun refuse(): Nothing = throw IllegalStateException("This account is read-only. Sign in with your private key or a signer app.")
    override suspend fun sign(draft: Draft): Event = refuse()
    override suspend fun nip44Encrypt(plaintext: String, peer: String): String = refuse()
    override suspend fun nip44Decrypt(payload: String, peer: String, background: Boolean): String = refuse()
}
