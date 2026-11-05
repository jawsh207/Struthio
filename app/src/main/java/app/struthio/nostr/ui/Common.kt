package app.struthio.nostr.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.struthio.nostr.core.Hex
import app.struthio.nostr.core.sha256
import app.struthio.nostr.core.splitEmoji
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.UserError
import app.struthio.nostr.net.AppLog
import app.struthio.nostr.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

val LocalCore = staticCompositionLocalOf<Core> { error("no core") }
val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

/** Side margin of every page, shared by search bars, cards and lists, as in Dialtone. */
val PageGutter = 16.dp

/** A screen's value that follows the cache. */
@Composable
fun <T> liveQuery(initial: T, vararg keys: Any?, query: () -> T): State<T> {
    val core = LocalCore.current
    return produceState(initial, core, *keys) {
        value = withContext(Dispatchers.IO) { query() }
        core.changes.collectLatest {
            delay(300)
            value = withContext(Dispatchers.IO) { query() }
        }
    }
}

/** Runs an action and shows any failure as a message. */
fun CoroutineScope.act(nav: Nav, block: suspend () -> Unit) = launch(Dispatchers.IO) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UserError) {
        AppLog.add("action failed: ${e.message}")
        nav.say(e.message ?: "Something went wrong.")
    } catch (e: Exception) {
        AppLog.add("action failed: $e")
        nav.say(e.message ?: "Something went wrong.")
    }
}

private object Images {
    private val heap = (Runtime.getRuntime().maxMemory() / 8).toInt()
    val memory = object : LruCache<String, Bitmap>(heap) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    // Avatars are many and small, so they get their own lane and can't hold up pictures in notes.
    private val avatars = Semaphore(4)
    private val pictures = Semaphore(4)
    private val fetching = ConcurrentHashMap<String, Mutex>()
    private val failed = ConcurrentHashMap<String, Long>()

    /** One download per image however many rows show it. Cached images never wait for the network. */
    suspend fun file(url: String, dir: File, small: Boolean): File {
        val name = Hex.encode(sha256(url.toByteArray()))
        if (File(dir, name).length() > 0) return Http.cached(url, dir, name)
        val since = failed[name]
        if (since != null && SystemClock.elapsedRealtime() - since < RETRY_MS) throw IOException("failed recently")
        return fetching.getOrPut(name) { Mutex() }.withLock {
            (if (small) avatars else pictures).withPermit {
                try {
                    Http.cached(url, dir, name)
                } catch (e: IOException) {
                    failed[name] = SystemClock.elapsedRealtime()
                    throw e
                }
            }
        }
    }

    private const val RETRY_MS = 120_000L

    fun decode(file: java.io.File, widthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.path, options)
    }
}

@Composable
fun rememberImage(url: String?, width: Dp): State<Bitmap?> {
    val core = LocalCore.current
    val widthPx = with(LocalDensity.current) { width.roundToPx() }
    return produceState(url?.let { Images.memory.get(it + widthPx) }, url, widthPx) {
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = Images.file(url, core.imageDir, width <= 96.dp)
                Images.decode(file, widthPx)?.also { Images.memory.put(url + widthPx, it) }
            }.getOrNull()
        }
    }
}

@Composable
fun NetImage(url: String, width: Dp, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val bitmap by rememberImage(url, width)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = scale)
        }
    }
}

@Composable
fun Avatar(pubkey: String, size: Dp = 48.dp, onClick: (() -> Unit)? = null) {
    val core = LocalCore.current
    LaunchedEffect(pubkey) { core.wantProfile(pubkey) }
    val profile = core.profiles[pubkey]
    val index = pubkey.takeLast(2).toInt(16)
    val shape = when (index % 8) {
        0 -> MaterialShapes.Cookie9Sided
        1 -> MaterialShapes.Cookie7Sided
        2 -> MaterialShapes.Sunny
        3 -> MaterialShapes.Clover4Leaf
        4 -> MaterialShapes.Pentagon
        5 -> MaterialShapes.Arch
        6 -> MaterialShapes.Gem
        else -> MaterialShapes.Puffy
    }.toShape()
    val colors = MaterialTheme.colorScheme
    val (back, front) = when (index % 3) {
        0 -> colors.tertiaryContainer to colors.onTertiaryContainer
        1 -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    var modifier = Modifier.size(size).clip(shape).background(back)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmapState = rememberImage(profile?.picture, size)
        val bitmap = bitmapState.value
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Text(initials(core.displayName(pubkey)), color = front, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
        }
    }
}

private fun initials(name: String): String {
    val words = name.split(' ').filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        else -> name.take(2)
    }.uppercase()
}

@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxWidth()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}

/** Rows on a page are one card split into segments: big outer corners, small inner ones. */
fun segmentShape(index: Int, count: Int): Shape {
    val big = 24.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
}

/** Text with any :shortcodes: from [emoji] drawn as their images (NIP-30). */
@Composable
fun EmojiText(
    text: String,
    emoji: Map<String, String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        buildAnnotatedString { appendEmoji(text, emoji) },
        modifier,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = emojiImages(emoji),
        style = style,
    )
}

fun AnnotatedString.Builder.appendEmoji(text: String, emoji: Map<String, String>) {
    splitEmoji(text, emoji).forEach { (piece, isCode) ->
        if (isCode) appendInlineContent("emoji:$piece", ":$piece:") else append(piece)
    }
}

fun emojiImages(emoji: Map<String, String>): Map<String, InlineTextContent> = emoji.entries.associate { (code, url) ->
    "emoji:$code" to InlineTextContent(Placeholder(1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
        val bitmap by rememberImage(url, 40.dp)
        bitmap?.let { Image(it.asImageBitmap(), code, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** A page title, with the button that clears everything unread opposite it. */
@Composable
fun UnreadHeader(title: String, hasUnread: Boolean, onMarkAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(vertical = 16.dp))
        IconButton(onMarkAll, enabled = hasUnread) { Icon(Icons.Rounded.DoneAll, "Mark all as read") }
    }
}

/** An avatar, with a dot on it while something from them is unread. */
@Composable
fun UnreadAvatar(pubkey: String, unread: Boolean, size: Dp = 48.dp) {
    Box {
        Avatar(pubkey, size)
        if (unread) {
            Box(Modifier.align(Alignment.TopEnd).size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
fun CardRow(
    shape: Shape,
    onClick: (() -> Unit)?,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    var modifier = Modifier.fillMaxWidth().clip(shape)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    ListItem(
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = colors,
        modifier = modifier,
    )
}

fun relativeTime(createdAt: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val s = (now - createdAt).coerceAtLeast(0)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        s < 7 * 86400 -> "${s / 86400}d"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(createdAt * 1000))
    }
}

fun compact(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0).replace(".0k", "k")
    else -> n.toString()
}
