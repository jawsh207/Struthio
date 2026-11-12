package app.struthio.nostr.net

import app.struthio.nostr.core.Bech32

/** LUD-01: an LNURL is a bech32-encoded https URL. */
object Bech32Lnurl {
    fun encode(url: String) = Bech32.encode("lnurl", url.toByteArray())

    fun decode(lnurl: String): String? = runCatching {
        val (hrp, data) = Bech32.decode(lnurl)
        if (hrp == "lnurl") String(data) else null
    }.getOrNull()
}

/**
 * A lightning address people can read: the `lud16` as written, or for an LNURL (`lud06`), the
 * name@domain it stands for when it follows the lightning address pattern, else just its site.
 */
fun lightningLabel(lud16: String?, lud06: String?): String? {
    lud16?.trim()?.takeIf { '@' in it && !it.startsWith('@') && !it.endsWith('@') }?.let { return it.lowercase() }
    val url = lud06?.trim()?.lowercase()?.removePrefix("lightning:")?.let(Bech32Lnurl::decode) ?: return null
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
    if (host.isEmpty()) return null
    val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?')
    val name = path.removePrefix(".well-known/lnurlp/").takeIf { path.startsWith(".well-known/lnurlp/") && it.isNotEmpty() && '/' !in it }
    return if (name != null) "$name@$host" else host
}
