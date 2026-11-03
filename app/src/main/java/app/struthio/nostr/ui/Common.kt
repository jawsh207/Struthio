package app.struthio.nostr.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.struthio.nostr.core.Hex
import app.struthio.nostr.core.sha256
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.UserError
import app.struthio.nostr.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

val LocalCore = staticCompositionLocalOf<Core> { error("no core") }
val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

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
fun CoroutineScope.act(nav: Nav, block: suspend () -> Unit) = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UserError) {
        nav.say(e.message ?: "Something went wrong.")
    } catch (e: Exception) {
        nav.say(e.message ?: "Something went wrong.")
    }
}

private object Images {
    private val heap = (Runtime.getRuntime().maxMemory() / 8).toInt()
    val memory = object : LruCache<String, Bitmap>(heap) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    val network = Semaphore(5)

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
                val name = Hex.encode(sha256(url.toByteArray()))
                val file = Images.network.withPermit { Http.cached(url, core.imageDir, name) }
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
    val shape = when (index % 5) {
        0 -> MaterialShapes.Cookie9Sided
        1 -> MaterialShapes.Clover4Leaf
        2 -> MaterialShapes.Cookie7Sided
        3 -> MaterialShapes.Puffy
        else -> MaterialShapes.Sunny
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
