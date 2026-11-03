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
