package app.struthio.nostr.core

/** One `imeta` tag (NIP-92) as name to values; a name like `image` or `fallback` may repeat. */
fun imetas(event: Event): List<Map<String, List<String>>> = event.tags.filter { it.firstOrNull() == "imeta" }.map { tag ->
    val fields = LinkedHashMap<String, MutableList<String>>()
    for (entry in tag.drop(1)) {
        val at = entry.indexOf(' ')
        if (at <= 0) continue
        fields.getOrPut(entry.substring(0, at)) { ArrayList() } += entry.substring(at + 1).trim()
    }
    fields
}

private fun Map<String, List<String>>.one(name: String) = this[name]?.firstOrNull()?.takeIf { it.isNotBlank() }

private fun aspectOf(dim: String?): Float? {
    val parts = dim?.split('x')?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
    if (parts.size != 2 || parts[1] <= 0f) return null
    return parts[0] / parts[1]
}

/** The address of an addressable event, as used in `a` tags and naddr links: `kind:pubkey:d`. */
fun addressOf(event: Event): String = "${event.kind}:${event.pubkey}:${event.tags.value("d").orEmpty()}"

/** A post's `title` tag, if it has one. */
fun titleOf(event: Event): String? = event.tags.value("title")?.trim()?.takeIf { it.isNotEmpty() }

/** NIP-68: the pictures of a picture post, in order. Only the image types the NIP accepts. */
fun pictures(event: Event): List<Media> = imetas(event).mapNotNull { meta ->
    val url = meta.one("url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
    val mime = meta.one("m")?.lowercase()
    if (mime != null && mime !in PICTURE_TYPES) return@mapNotNull null
    Media(url, aspectOf(meta.one("dim")))
}

private val PICTURE_TYPES = setOf("image/apng", "image/avif", "image/gif", "image/jpeg", "image/png", "image/webp")

/** NIP-71: what a video post plays. */
data class VideoPost(
    val title: String?,
    val url: String,
    val thumbnail: String?,
    val seconds: Double?,
    /** Short (kind 22) or taller than wide: shown in a portrait frame. */
    val portrait: Boolean,
)

/**
 * The version of a video post to play. Several may be listed, in different sizes and formats;
 * MP4 plays everywhere, so it's preferred over streams. Audio-only tracks are skipped.
 */
fun videoPost(event: Event): VideoPost? {
    if (event.kind !in Kind.VIDEOS) return null
    val videos = imetas(event).filter { meta ->
        val url = meta.one("url") ?: return@filter false
        val mime = meta.one("m")?.lowercase()
        url.startsWith("https://") && (mime == null || mime.startsWith("video/") || mime in STREAM_TYPES)
    }
    val pick = videos.firstOrNull { it.one("m")?.lowercase() == "video/mp4" }
        ?: videos.firstOrNull { it.one("m")?.lowercase()?.startsWith("video/") == true }
        ?: videos.firstOrNull()
        ?: return null
    val aspect = aspectOf(pick.one("dim"))
    return VideoPost(
        titleOf(event),
        pick.one("url")!!,
        pick["image"]?.firstOrNull { it.startsWith("https://") },
        pick.one("duration")?.toDoubleOrNull() ?: event.tags.value("duration")?.toDoubleOrNull(),
        event.kind == Kind.SHORT_VIDEO || event.kind == Kind.EDITABLE_SHORT_VIDEO || (aspect != null && aspect < 1f),
    )
}

/** A length like 4:05 or 1:02:09. */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** NIP-23: what an article card shows. */
data class Article(val title: String, val summary: String?, val image: String?, val publishedAt: Long?)

fun article(event: Event): Article? {
    if (event.kind != Kind.ARTICLE) return null
    val title = titleOf(event) ?: event.content.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('#', ' ')?.take(120)
        ?: return null
    return Article(
        title,
        event.tags.value("summary")?.trim()?.takeIf { it.isNotEmpty() },
        event.tags.value("image")?.trim()?.takeIf { it.startsWith("https://") },
        event.tags.value("published_at")?.toLongOrNull(),
    )
}
