package app.struthio.nostr.core

/** NIP-38: what someone is doing or listening to, from a kind 30315 event. */
data class Status(val text: String, val music: Boolean, val link: String?)

/** The status an event sets, or null when it's cleared (empty) or has expired. */
fun parseStatus(event: Event, now: Long = nowSeconds()): Status? {
    if (event.kind != Kind.STATUS) return null
    val text = event.content.trim()
    if (text.isEmpty()) return null
    val expires = event.tags.value("expiration")?.toLongOrNull()
    if (expires != null && expires <= now) return null
    val link = event.tags.value("r")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    return Status(text, event.tags.value("d") == "music", link)
}

/** NIP-39: an account elsewhere that someone says is theirs, with where the proof is. */
data class Identity(val platform: String, val name: String, val proof: String) {
    /** The account's own page. */
    val profileUrl: String?
        get() = when (platform) {
            "github" -> "https://github.com/$name"
            "twitter" -> "https://twitter.com/$name"
            "mastodon" -> "https://$name"
            "telegram" -> null
            "bluesky" -> "https://bsky.app/profile/$name"
            "discord" -> null
            else -> null
        }

    /** The post or gist holding the proof, where the NIP says it can be found. */
    val proofUrl: String?
        get() = when (platform) {
            "github" -> "https://gist.github.com/$name/$proof"
            "twitter" -> "https://twitter.com/$name/status/$proof"
            "mastodon" -> "https://$name/$proof"
            "telegram" -> "https://t.me/$proof"
            "bluesky" -> "https://bsky.app/profile/$name/post/$proof"
            "discord" -> "https://discord.com/channels/$proof"
            else -> null
        }

    val label: String
        get() = PLATFORMS[platform] ?: platform

    fun tag(): List<String> = listOf("i", "$platform:$name", proof)

    companion object {
        /** The claim types NIP-39 defines, by how they're named in tags and shown. */
        val PLATFORMS = linkedMapOf(
            "github" to "GitHub",
            "twitter" to "X (Twitter)",
            "mastodon" to "Mastodon",
            "bluesky" to "Bluesky",
            "telegram" to "Telegram",
            "discord" to "Discord",
        )

        /** The text a proof should contain, in the NIP's words. */
        fun proofText(npub: String) = "Verifying that I control the following Nostr public key: $npub"
    }
}

/** The accounts a kind 10011 event links. Names are kept as written but compared lowercased. */
fun parseIdentities(event: Event): List<Identity> {
    if (event.kind != Kind.IDENTITIES) return emptyList()
    return event.tags.filter { it.size >= 3 && it[0] == "i" }.mapNotNull { tag ->
        val claim = tag[1]
        val at = claim.indexOf(':')
        if (at <= 0 || at == claim.length - 1) return@mapNotNull null
        val platform = claim.substring(0, at).lowercase()
        if (!platform.all { it in 'a'..'z' || it in '0'..'9' || it in "._-/" }) return@mapNotNull null
        Identity(platform, claim.substring(at + 1).trim(), tag[2].trim())
    }.distinctBy { it.platform to it.name.lowercase() }
}
