package app.struthio.nostr.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.net.URLDecoder

/** NIP-10 and NIP-22: where a note sits in a thread. */
data class ThreadRef(val root: String?, val parent: String?, val rootAuthor: String? = null, val parentAuthor: String? = null)

fun threadRef(event: Event): ThreadRef {
    if (event.kind == Kind.COMMENT) {
        val root = event.tags.first("E")
        val parent = event.tags.first("e")
        return ThreadRef(root?.get(1), parent?.get(1) ?: root?.get(1), event.tags.value("P"), event.tags.value("p"))
    }
    if (event.kind != Kind.NOTE) return ThreadRef(null, null)
    val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" && Hex.isKey(it[1]) }
    if (eTags.isEmpty()) return ThreadRef(null, null)
    val rootTag = eTags.firstOrNull { it.getOrNull(3) == "root" }
    val replyTag = eTags.firstOrNull { it.getOrNull(3) == "reply" }
    if (rootTag != null || replyTag != null) {
        val parent = replyTag ?: rootTag!!
        return ThreadRef(rootTag?.get(1) ?: parent[1], parent[1], rootTag?.getOrNull(4), parent.getOrNull(4))
    }
    // Older events on the network use position; Struthio only reads this form, never writes it.
    if (eTags.any { it.getOrNull(3) == "mention" }) return ThreadRef(null, null)
    return ThreadRef(eTags.first()[1], eTags.last()[1])
}

/** Tags for a reply: NIP-10 under a kind 1 note, NIP-22 for anything else. */
fun replyTags(parent: Event, hint: String): Pair<Int, Tags> {
    val tags = ArrayList<List<String>>()
    if (parent.kind == Kind.NOTE) {
        val ref = threadRef(parent)
        if (ref.root == null) {
            tags += listOf("e", parent.id, hint, "root", parent.pubkey)
        } else {
            tags += listOf("e", ref.root, hint, "root", ref.rootAuthor ?: "").dropLastWhile { it.isEmpty() }
            tags += listOf("e", parent.id, hint, "reply", parent.pubkey)
        }
        (parent.tags.values("p") + parent.pubkey).distinct().forEach { tags += listOf("p", it) }
        return Kind.NOTE to tags
    }
    if (parent.kind == Kind.COMMENT) {
        parent.tags.filter { it.isNotEmpty() && it[0] in setOf("E", "A", "I", "K", "P") }.forEach { tags += it }
    } else {
        tags += listOf("E", parent.id, hint, parent.pubkey)
        tags += listOf("K", parent.kind.toString())
        tags += listOf("P", parent.pubkey, hint)
    }
    tags += listOf("e", parent.id, hint, parent.pubkey)
    tags += listOf("k", parent.kind.toString())
    tags += listOf("p", parent.pubkey, hint)
    return Kind.COMMENT to tags
}

/** Parts of a note body. */
sealed interface Part {
    data class Text(val text: String) : Part
    data class Link(val url: String) : Part
    data class Tag(val tag: String) : Part
    data class Mention(val raw: String, val entity: Entity) : Part
    data class Lightning(val invoice: Invoice) : Part
}

private const val INVOICE_PATTERN = """(?<![\p{L}\p{N}])(?i:(?:lightning:)?lnbc[a-z0-9]{50,})"""
private val INVOICE = Regex(INVOICE_PATTERN)

private val TOKEN = Regex(
    """nostr:(?:npub|nprofile|note|nevent|naddr)1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+""" +
        """|https?://[^\s<>"'`]+""" +
        "|$INVOICE_PATTERN" +
        """|(?<![\p{L}\p{N}_/&#])#[\p{L}\p{N}_]+""",
)

fun parseContent(content: String): List<Part> {
    val parts = ArrayList<Part>()
    var index = 0
    fun text(end: Int) {
        if (end > index) parts += Part.Text(content.substring(index, end))
    }
    for (match in TOKEN.findAll(content)) {
        val raw = match.value
        when {
            raw.startsWith("nostr:") -> {
                val entity = Nip19.decode(raw) ?: continue
                text(match.range.first)
                parts += Part.Mention(raw, entity)
                index = match.range.last + 1
            }
            !raw.startsWith("#") && !raw.startsWith("http", ignoreCase = true) -> {
                val invoice = parseInvoice(raw) ?: continue
                text(match.range.first)
                parts += Part.Lightning(invoice)
                index = match.range.last + 1
            }
            raw.startsWith("#") -> {
                if (raw.drop(1).all { it.isDigit() }) continue
                text(match.range.first)
                parts += Part.Tag(raw.drop(1))
                index = match.range.last + 1
            }
            else -> {
                val url = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
                text(match.range.first)
                parts += Part.Link(url)
                index = match.range.first + url.length
            }
        }
    }
    text(content.length)
    return parts
}

private val BARE_ENTITY = Regex("""(?<![\w:/])(npub|note|nevent|nprofile)1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{20,}""")

/** Turns pasted entities into NIP-21 URIs and returns the content with the p and q tags they need (NIP-27, NIP-18). */
fun prepareContent(text: String): Pair<String, Tags> {
    val content = BARE_ENTITY.replace(text) { if (Nip19.decode(it.value) != null) "nostr:${it.value}" else it.value }
    val tags = ArrayList<List<String>>()
    for (part in parseContent(content)) {
        when (part) {
            is Part.Mention -> when (val e = part.entity) {
                is Entity.Profile -> tags += listOf("p", e.pubkey)
                is Entity.Note -> {
                    tags += listOf("q", e.id, e.relays.firstOrNull() ?: "", e.author ?: "").dropLastWhile { it.isEmpty() }
                    e.author?.let { tags += listOf("p", it) }
                }
                else -> Unit
            }
            is Part.Tag -> tags += listOf("t", part.tag.lowercase())
            else -> Unit
        }
    }
    return content to tags.distinct()
}

private val IMAGE_EXT = Regex(""".*\.(jpe?g|png|webp|gif|avif)(\?.*)?$""", RegexOption.IGNORE_CASE)

data class Media(val url: String, val aspect: Float?)

/** Images in a note: NIP-92 imeta first, then URLs that look like images. */
fun noteImages(event: Event): List<Media> {
    val meta = HashMap<String, Pair<String?, Float?>>()
    for (tag in event.tags) {
        if (tag.isEmpty() || tag[0] != "imeta") continue
        var url: String? = null
        var mime: String? = null
        var aspect: Float? = null
        for (entry in tag.drop(1)) {
            val at = entry.indexOf(' ')
            if (at < 0) continue
            val value = entry.substring(at + 1)
            when (entry.substring(0, at)) {
                "url" -> url = value
                "m" -> mime = value
                "dim" -> {
                    val (w, h) = value.split('x').map { it.toFloatOrNull() }.let { it.getOrNull(0) to it.getOrNull(1) }
                    if (w != null && h != null && h > 0f) aspect = w / h
                }
            }
        }
        url?.let { meta[it] = mime to aspect }
    }
    return parseContent(event.content).filterIsInstance<Part.Link>().mapNotNull { link ->
        val info = meta[link.url]
        when {
            info?.first?.startsWith("image/") == true -> Media(link.url, info.second)
            info?.first != null -> null
            IMAGE_EXT.matches(link.url) -> Media(link.url, info?.second)
            else -> null
        }
    }.distinct()
}

/** The note inside a repost, when the repost carries it and it is authentic. */
fun repostedEvent(repost: Event): Event? =
    repost.content.takeIf { it.isNotBlank() }?.let(Event::parse)?.takeIf { it.isValid() }

/** What a Bitcoin mainnet BOLT-11 invoice asks for. The amount is null when the payer chooses. */
data class Invoice(val text: String, val msats: Long?, val memo: String?, val expiresAt: Long)

private const val SIGNATURE_WORDS = 104
private const val TIMESTAMP_WORDS = 7
private const val DEFAULT_EXPIRY = 3600L
private const val TAG_DESCRIPTION = 13
private const val TAG_EXPIRY = 6

fun invoicesIn(text: String): List<Invoice> = INVOICE.findAll(text).mapNotNull { parseInvoice(it.value) }.toList()

/** The text with each invoice swapped for [with], leaving anything that only looks like one. */
fun replaceInvoices(text: String, with: (Invoice) -> String): String =
    INVOICE.replace(text) { match -> parseInvoice(match.value)?.let(with) ?: match.value }

fun formatSats(msats: Long): String =
    if (msats % 1000 == 0L) "%,d sats".format(Locale.US, msats / 1000) else "%,.3f sats".format(Locale.US, msats / 1000.0)

/** For places with room for one line: notifications, previews and the like. */
fun summarizeInvoices(text: String): String = replaceInvoices(text) { invoice ->
    "Lightning invoice" + (invoice.msats?.let { " for " + formatSats(it) } ?: "")
}

/** Null unless the text is a whole invoice with a valid checksum. */
fun parseInvoice(raw: String): Invoice? {
    val text = raw.trim().lowercase().removePrefix("lightning:")
    val (hrp, words) = runCatching { Bech32.decodeWords(text) }.getOrNull() ?: return null
    if (!Regex("lnbc(\\d+[munp]?)?").matches(hrp) || words.size < TIMESTAMP_WORDS + SIGNATURE_WORDS) return null
    val digits = hrp.removePrefix("lnbc")
    val msats = if (digits.isEmpty()) null else bolt11Msats(text) ?: return null
    fun number(from: Int, count: Int): Long {
        var value = 0L
        for (i in from until from + count) value = value * 32 + words[i]
        return value
    }
    val created = number(0, TIMESTAMP_WORDS)
    val end = words.size - SIGNATURE_WORDS
    var memo: String? = null
    var expiry = DEFAULT_EXPIRY
    var at = TIMESTAMP_WORDS
    while (at + 3 <= end) {
        val tag = words[at].toInt()
        val length = number(at + 1, 2).toInt()
        val start = at + 3
        if (start + length > end) return null
        when (tag) {
            TAG_DESCRIPTION -> memo = runCatching {
                String(Bech32.wordsToBytes(words.copyOfRange(start, start + length)))
            }.getOrNull()
            TAG_EXPIRY -> if (length <= 8) expiry = number(start, length)
        }
        at = start + length
    }
    return Invoice(text, msats, memo, created + expiry)
}

/** Amount of a BOLT-11 invoice in millisatoshis, from the human-readable part. */
fun bolt11Msats(invoice: String): Long? {
    val text = invoice.lowercase().removePrefix("lightning:")
    val match = Regex("^ln(?:bcrt|bc|tbs|tb)(\\d+)?([munp])?1").find(text) ?: return null
    val digits = match.groupValues[1]
    if (digits.isEmpty()) return null
    val amount = digits.toLongOrNull() ?: return null
    return when (match.groupValues[2]) {
        "" -> amount * 100_000_000_000L
        "m" -> amount * 100_000_000L
        "u" -> amount * 100_000L
        "n" -> amount * 100L
        "p" -> if (amount % 10 == 0L) amount / 10 else null
        else -> null
    }
}

/** A validated view of a NIP-57 zap receipt. The provider key still has to be checked against the recipient's LNURL. */
data class Zap(
    val receipt: String,
    val target: String?,
    val recipient: String,
    val sender: String,
    val msats: Long,
    val provider: String,
    val comment: String,
    val createdAt: Long,
)

fun parseZapReceipt(event: Event): Zap? {
    if (event.kind != Kind.ZAP_RECEIPT) return null
    val request = Event.parse(event.tags.value("description") ?: return null) ?: return null
    if (request.kind != Kind.ZAP_REQUEST || !request.isValid()) return null
    val invoiceMsats = bolt11Msats(event.tags.value("bolt11") ?: return null) ?: return null
    val requested = request.tags.value("amount")?.toLongOrNull()
    if (requested != null && requested != invoiceMsats) return null
    return Zap(
        event.id,
        event.tags.value("e"),
        event.tags.value("p") ?: return null,
        request.pubkey,
        invoiceMsats,
        event.pubkey,
        request.content,
        event.createdAt,
    )
}

/** Who receives a zap on this note: NIP-57 Appendix G splits, else the author. */
fun zapSplits(event: Event): List<Pair<String, Double>> {
    val tags = event.tags.filter { it.size >= 2 && it[0] == "zap" && Hex.isKey(it[1]) }
    if (tags.isEmpty()) return listOf(event.pubkey to 1.0)
    val anyWeight = tags.any { it.getOrNull(3)?.toDoubleOrNull() != null }
    val weights = tags.map { it[1] to if (anyWeight) (it.getOrNull(3)?.toDoubleOrNull() ?: 0.0) else 1.0 }
    val total = weights.sumOf { it.second }
    if (total <= 0) return listOf(event.pubkey to 1.0)
    return weights.filter { it.second > 0 }.map { it.first to it.second / total }
}

/** A parsed nostr+walletconnect:// URI. */
class NwcUri(val wallet: String, val relays: List<String>, val secret: ByteArray, val lud16: String?) {
    companion object {
        fun parse(text: String): NwcUri? = try {
            val uri = URI(text.trim().replace("nostr+walletconnect:", "nwc:"))
            require(uri.scheme == "nwc")
            val wallet = uri.host ?: uri.authority ?: uri.schemeSpecificPart.removePrefix("//").substringBefore('?')
            val query = uri.rawQuery ?: uri.schemeSpecificPart.substringAfter('?', "")
            val params = query.split('&').filter { it.contains('=') }.map {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val relays = params.filter { it.first == "relay" }.map { it.second }
            val secret = params.first { it.first == "secret" }.second
            require(Hex.isKey(wallet) && Hex.isKey(secret) && relays.isNotEmpty())
            NwcUri(wallet, relays, Hex.decode(secret), params.firstOrNull { it.first == "lud16" }?.second)
        } catch (_: Exception) {
            null
        }
    }
}

fun nwcRequestJson(method: String, params: JSONObject = JSONObject()): String =
    JSONObject().put("method", method).put("params", params).toString()

/** NIP-65: which relays a kind 10002 event declares. */
data class RelayList(val read: List<String>, val write: List<String>)

fun parseRelayList(event: Event): RelayList {
    val read = ArrayList<String>()
    val write = ArrayList<String>()
    for (tag in event.tags) {
        if (tag.size < 2 || tag[0] != "r") continue
        val url = normalizeRelay(tag[1]) ?: continue
        when (tag.getOrNull(2)) {
            "read" -> read += url
            "write" -> write += url
            else -> {
                read += url
                write += url
            }
        }
    }
    return RelayList(read.distinct(), write.distinct())
}

fun normalizeRelay(url: String): String? {
    val u = url.trim().trimEnd('/').lowercase()
    if (!u.startsWith("wss://") || u.length < 9) return null
    if (u.any { it.isWhitespace() }) return null
    return u
}

/** A relay's NIP-11 document. Every field is optional, so null means the relay didn't say. */
data class RelayInfo(
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val banner: String? = null,
    val operator: String? = null,
    val contact: String? = null,
    val software: String? = null,
    val version: String? = null,
    val terms: String? = null,
    val paymentsUrl: String? = null,
    val nips: List<Int> = emptyList(),
    val authRequired: Boolean? = null,
    val paymentRequired: Boolean? = null,
    val restrictedWrites: Boolean? = null,
    val fees: List<String> = emptyList(),
    val limits: List<Pair<String, String>> = emptyList(),
) {
    /** The one thing worth knowing from a list of relays. */
    val tag: String?
        get() = when {
            paymentRequired == true || fees.isNotEmpty() -> "Paid"
            restrictedWrites == true -> "Restricted"
            authRequired == true -> "Sign-in required"
            else -> null
        }
}

fun parseRelayInfo(json: String): RelayInfo? = runCatching {
    val o = JSONObject(json)
    fun text(from: JSONObject, name: String) = from.optString(name).takeIf { it.isNotBlank() && it != "null" }
    fun flag(from: JSONObject, name: String) = if (from.has(name)) from.optBoolean(name) else null
    val limits = o.optJSONObject("limitation") ?: JSONObject()
    val fees = o.optJSONObject("fees") ?: JSONObject()
    fun feeLines(group: String, label: String) = fees.optJSONArray(group)?.let { list ->
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { "$label: " + feeText(it) }
    }.orEmpty()
    RelayInfo(
        name = text(o, "name"),
        description = text(o, "description"),
        icon = text(o, "icon"),
        banner = text(o, "banner"),
        operator = text(o, "pubkey")?.takeIf(Hex::isKey),
        contact = text(o, "contact"),
        software = text(o, "software")?.trimEnd('/')?.removeSuffix(".git")?.substringAfterLast('/'),
        version = text(o, "version"),
        terms = text(o, "terms_of_service"),
        paymentsUrl = text(o, "payments_url"),
        nips = o.optJSONArray("supported_nips")?.let { list ->
            (0 until list.length()).map { list.optInt(it, -1) }.filter { it >= 0 }.distinct().sorted()
        }.orEmpty(),
        authRequired = flag(limits, "auth_required"),
        paymentRequired = flag(limits, "payment_required"),
        restrictedWrites = flag(limits, "restricted_writes"),
        fees = feeLines("admission", "To join") + feeLines("subscription", "Subscription") + feeLines("publication", "Per post"),
        limits = RELAY_LIMITS.mapNotNull { (key, label) -> limits.optLong(key, 0).takeIf { it > 0 }?.let { label to "%,d".format(Locale.US, it) } },
    )
}.getOrNull()

private fun feeText(fee: JSONObject): String {
    val amount = fee.optLong("amount")
    val unit = fee.optString("unit").lowercase()
    val price = when (unit) {
        "msat", "msats" -> "%,d sats".format(Locale.US, amount / 1000)
        "sat", "sats" -> "%,d sats".format(Locale.US, amount)
        else -> "%,d %s".format(Locale.US, amount, unit)
    }
    val days = fee.optLong("period") / 86_400
    return if (days > 0) "$price every $days days" else price
}

private val RELAY_LIMITS = listOf(
    "max_content_length" to "Longest note (characters)",
    "max_message_length" to "Largest message (bytes)",
    "max_event_tags" to "Tags per note",
    "max_subscriptions" to "Open requests at once",
    "max_limit" to "Results per request",
    "min_pow_difficulty" to "Proof of work needed",
)

/** What a NIP means to someone using the app. */
fun nipName(nip: Int): String = when (nip) {
    9 -> "Deleting notes"
    17, 59 -> "Private messages"
    40 -> "Expiring notes"
    42 -> "Sign-in"
    45 -> "Counts"
    50 -> "Search"
    65 -> "Relay lists"
    70 -> "Protected notes"
    77 -> "Fast sync"
    else -> "NIP-$nip"
}

private val EMOJI_CODE = Regex(":([A-Za-z0-9_-]+):")

/** NIP-30: the custom emoji an event declares, by shortcode. */
fun emojiMap(tags: Tags): Map<String, String> = tags
    .filter { it.size >= 3 && it[0] == "emoji" && it[1].matches(Regex("[A-Za-z0-9_-]+")) && it[2].startsWith("https://") }
    .associate { it[1] to it[2] }

/** Text split around the shortcodes that have an image. The bool marks a shortcode. */
fun splitEmoji(text: String, emoji: Map<String, String>): List<Pair<String, Boolean>> {
    if (emoji.isEmpty()) return listOf(text to false)
    val out = ArrayList<Pair<String, Boolean>>()
    var index = 0
    for (match in EMOJI_CODE.findAll(text)) {
        val code = match.groupValues[1]
        if (code !in emoji) continue
        if (match.range.first > index) out += text.substring(index, match.range.first) to false
        out += code to true
        index = match.range.last + 1
    }
    if (index < text.length) out += text.substring(index) to false
    return out
}

fun stripEmoji(text: String, emoji: Map<String, String>): String =
    splitEmoji(text, emoji).filterNot { it.second }.joinToString("") { it.first }.trim()

/** A custom reaction, kept as its shortcode so it can be drawn from the event's emoji tags. */
fun customGlyph(content: String, tags: Tags): String? =
    content.takeIf { EMOJI_CODE.matches(it) && content.trim(':') in emojiMap(tags) }

data class PollOption(val id: String, val label: String)

/** NIP-88. */
data class Poll(
    val options: List<PollOption>,
    val relays: List<String>,
    val multiple: Boolean,
    val endsAt: Long?,
)

fun parsePoll(event: Event): Poll? {
    if (event.kind != Kind.POLL) return null
    val options = event.tags.filter { it.size >= 3 && it[0] == "option" }.map { PollOption(it[1], it[2]) }.distinctBy { it.id }
    if (options.size < 2) return null
    return Poll(
        options,
        event.tags.values("relay").mapNotNull(::normalizeRelay),
        event.tags.value("polltype") == "multiplechoice",
        event.tags.value("endsAt")?.toLongOrNull(),
    )
}

data class PollResult(val counts: Map<String, Int>, val voters: Int, val mine: Set<String>)

/** One vote per person, their latest within the poll's time. */
fun countVotes(poll: Poll, votes: List<Event>, me: String?): PollResult {
    val known = poll.options.map { it.id }.toSet()
    val latest = votes
        .filter { it.kind == Kind.POLL_VOTE && (poll.endsAt == null || it.createdAt <= poll.endsAt) }
        .groupBy { it.pubkey }
        .mapValues { (_, list) -> list.maxBy { it.createdAt } }
    val choices = latest.mapValues { (_, vote) ->
        val responses = vote.tags.values("response")
        (if (poll.multiple) responses else responses.take(1)).filter { it in known }.toSet()
    }.filterValues { it.isNotEmpty() }
    val counts = HashMap<String, Int>()
    choices.values.forEach { set -> set.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    return PollResult(counts, choices.size, me?.let { choices[it] }.orEmpty())
}

fun pollTags(options: List<String>, multiple: Boolean, endsAt: Long?, relays: List<String>): Tags = buildList {
    options.forEach { add(listOf("option", Hex.encode(randomBytes(5)), it)) }
    relays.forEach { add(listOf("relay", it)) }
    add(listOf("polltype", if (multiple) "multiplechoice" else "singlechoice"))
    endsAt?.let { add(listOf("endsAt", it.toString())) }
}

/** Greedy cover: the fewest relays that reach each author [redundancy] times. */
fun chooseRelays(
    authors: Collection<String>,
    relaysOf: (String) -> List<String>,
    maxRelays: Int,
    redundancy: Int = 2,
): Map<String, Set<String>> {
    val byRelay = HashMap<String, MutableSet<String>>()
    for (author in authors) relaysOf(author).forEach { byRelay.getOrPut(it) { HashSet() } += author }
    val need = HashMap<String, Int>()
    authors.forEach { need[it] = redundancy }
    val chosen = LinkedHashMap<String, Set<String>>()
    while (chosen.size < maxRelays && byRelay.isNotEmpty()) {
        val best = byRelay.entries.maxByOrNull { (_, members) -> members.count { (need[it] ?: 0) > 0 } } ?: break
        val members = best.value.filter { (need[it] ?: 0) > 0 }
        if (members.isEmpty()) break
        chosen[best.key] = members.toSet()
        members.forEach { need[it] = need.getValue(it) - 1 }
        byRelay.remove(best.key)
    }
    return chosen
}

const val DEFAULT_REACTION = "\uD83D\uDC9C"

/** What a kind 7 shows: likes are a purple heart here, dislikes and custom emoji show nothing. */
fun reactionGlyph(content: String): String? = when {
    content == "+" || content.isEmpty() -> DEFAULT_REACTION
    content == "-" || content.startsWith(":") -> null
    else -> content
}

/** An encrypted file from a kind 15 rumor (NIP-17). */
data class FileMessage(
    val url: String,
    val type: String,
    val key: String,
    val nonce: String,
    val hash: String?,
    val aspect: Float?,
)

fun parseFileMessage(rumor: Draft): FileMessage? {
    if (rumor.kind != Kind.FILE_DM) return null
    if (rumor.tags.value("encryption-algorithm") != "aes-gcm") return null
    val dim = rumor.tags.value("dim")?.split('x')?.mapNotNull { it.toFloatOrNull() }
    return FileMessage(
        rumor.content.trim().takeIf { it.startsWith("https://") } ?: return null,
        rumor.tags.value("file-type") ?: "application/octet-stream",
        rumor.tags.value("decryption-key") ?: return null,
        rumor.tags.value("decryption-nonce") ?: return null,
        rumor.tags.value("x"),
        dim?.takeIf { it.size == 2 && it[1] > 0f }?.let { it[0] / it[1] },
    )
}

fun fileToJson(file: FileMessage): String = JSONObject()
    .put("url", file.url).put("type", file.type).put("key", file.key).put("nonce", file.nonce)
    .put("hash", file.hash ?: "").put("aspect", (file.aspect ?: 0f).toDouble()).toString()

fun fileFromJson(json: String): FileMessage? = runCatching {
    val o = JSONObject(json)
    FileMessage(
        o.getString("url"), o.getString("type"), o.getString("key"), o.getString("nonce"),
        o.optString("hash").takeIf { it.isNotEmpty() }, o.optDouble("aspect", 0.0).toFloat().takeIf { it > 0f },
    )
}.getOrNull()

/** AES-GCM for kind 15 attachments; the tag sits at the end of the ciphertext. */
object FileCipher {
    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray) =
        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
        }

    fun encrypt(plain: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray =
        cipher(javax.crypto.Cipher.ENCRYPT_MODE, key, nonce).doFinal(plain)

    fun decrypt(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray =
        cipher(javax.crypto.Cipher.DECRYPT_MODE, key, nonce).doFinal(data)
}

/** BUD-11 authorization for one upload, scoped to the blob and the server. */
fun blossomAuthTags(sha256: String, serverHost: String, now: Long): Tags = listOf(
    listOf("t", "upload"),
    listOf("x", sha256),
    listOf("server", serverHost),
    listOf("expiration", (now + 300).toString()),
)

/** NIP-92 attachment metadata for an uploaded image. */
fun imetaTag(url: String, mime: String, sha256: String, width: Int, height: Int, size: Int): List<String> =
    listOf("imeta", "url $url", "m $mime", "x $sha256", "dim ${width}x$height", "size $size")

// Android plays most of these itself, including HLS. Anything it refuses, such as AVI or DASH,
// gets the "open in another app" button instead.
private val VIDEO_EXT = Regex(
    """.*\.(mp4|m4v|mov|webm|mkv|3gp|3g2|ogv|ts|m2ts|mts|mpg|mpeg|avi|wmv|flv|m3u8|mpd)([?#].*)?$""",
    RegexOption.IGNORE_CASE,
)

private val STREAM_TYPES = setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml")

/** Videos in a note: imeta type first, then file extension. */
fun noteVideos(event: Event): List<String> {
    val declared = event.tags.filter { it.firstOrNull() == "imeta" }.mapNotNull { tag ->
        val url = tag.firstOrNull { it.startsWith("url ") }?.removePrefix("url ")
        val mime = tag.firstOrNull { it.startsWith("m ") }?.removePrefix("m ")
        url?.takeIf { mime != null && (mime.startsWith("video/") || mime.lowercase() in STREAM_TYPES) }
    }
    val links = parseContent(event.content).filterIsInstance<Part.Link>().map { it.url }
    return links.filter { (it in declared || VIDEO_EXT.matches(it)) && it.startsWith("https://") }.distinct()
}

/** What a link turned out to be: a page to show as a card, or media to show directly. */
data class LinkInfo(
    val url: String,
    val title: String,
    val description: String,
    val image: String?,
    val media: String? = null,
)

/** "video" or "image" for media a server describes, from its Content-Type. */
fun mediaKind(contentType: String): String? = when {
    contentType.startsWith("video/") || contentType in STREAM_TYPES -> "video"
    contentType.startsWith("image/") -> "image"
    else -> null
}

private val META_TAG = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
private val TITLE_TAG = Regex("""<title[^>]*>([^<]{1,300})</title>""", RegexOption.IGNORE_CASE)

private fun attribute(tag: String, name: String): String? =
    Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
        ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

/** Open Graph card data from the start of an HTML page. */
fun parseLinkInfo(url: String, html: String): LinkInfo? {
    val meta = HashMap<String, String>()
    for (match in META_TAG.findAll(html)) {
        val key = (attribute(match.value, "property") ?: attribute(match.value, "name"))?.lowercase() ?: continue
        val value = attribute(match.value, "content")?.trim() ?: continue
        if (value.isNotEmpty()) meta.putIfAbsent(key, value)
    }
    val title = meta["og:title"] ?: meta["twitter:title"] ?: TITLE_TAG.find(html)?.groupValues?.get(1)?.trim() ?: return null
    if (title.isEmpty()) return null
    val description = meta["og:description"] ?: meta["twitter:description"] ?: meta["description"] ?: ""
    val image = (meta["og:image"] ?: meta["twitter:image"])?.takeIf { it.startsWith("https://") }
    return LinkInfo(url, title.take(160), description.take(240), image)
}

/** Only ordinary public names: a note must not make the phone probe its own network. */
/** "name@example.com" on a public domain, the form NIP-05 addresses take. */
fun isNip05Address(text: String): Boolean =
    NIP05_ADDRESS.matches(text) && isPreviewable("https://" + text.substringAfterLast('@') + "/")

private val NIP05_ADDRESS = Regex("[a-z0-9._-]+@[a-z0-9.-]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE)

fun isPreviewable(url: String): Boolean = runCatching {
    val host = URI(url).host?.lowercase() ?: return false
    url.startsWith("https://") && host.contains('.') && !host.endsWith(".local") && !host.endsWith(".lan") &&
        !host.endsWith(".internal") && !host.last().isDigit() && !host.contains(':')
}.getOrDefault(false)

/**
 * Follows of follows: the accounts at least [threshold] of the people you follow also follow,
 * together with the people you follow yourself.
 */
fun trustedNetwork(me: String, follows: Set<String>, theirLists: Collection<Collection<String>>, threshold: Int): Set<String> {
    val counts = HashMap<String, Int>()
    theirLists.forEach { list -> list.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    return counts.filterValues { it >= threshold }.keys + follows + me
}

fun reportTags(event: Event, type: String): Tags =
    listOf(listOf("p", event.pubkey, type), listOf("e", event.id, type))

fun roomOf(rumor: Draft): Set<String> = (rumor.tags.values("p") + rumor.pubkey).toSet()

fun jsonStrings(values: Collection<String>): JSONArray = JSONArray().also { a -> values.forEach { a.put(it) } }
