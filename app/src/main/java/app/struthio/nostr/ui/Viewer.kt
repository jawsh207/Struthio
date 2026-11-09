package app.struthio.nostr.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.struthio.nostr.core.LinkInfo

@Composable
private fun ZoomableImage(url: String, onDismiss: () -> Unit) {
    val bitmap by rememberImage(url, 1100.dp)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dragY = 0f
                    do {
                        val event = awaitPointerEvent()
                        val zooming = event.changes.size > 1
                        if (zooming || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else {
                            dragY += event.calculatePan().y
                        }
                    } while (event.changes.any { it.pressed })
                    if (dragY > DISMISS_DISTANCE) onDismiss()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            Loading(Modifier.fillMaxSize())
        } else {
            Image(
                image.asImageBitmap(),
                "Image",
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private const val DISMISS_DISTANCE = 260f

/** Full screen images: pinch to zoom, swipe between them, swipe down to close. */
@Composable
fun ImageViewer(urls: List<String>, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(start) { urls.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page -> ZoomableImage(urls[page], onClose) }
            FilledIconButton(
                onClose,
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xB3000000), contentColor = Color.White),
            ) { Icon(Icons.Rounded.Close, "Close") }
        }
    }
}

/**
 * Tap to play with the platform's own player. Before that the card shows the note's thumbnail,
 * or, with link previews on, a still taken from the start of the video.
 */
@Composable
fun VideoCard(url: String, thumbnail: String? = null) {
    val context = LocalContext.current
    val core = LocalCore.current
    val frame by produceState<android.graphics.Bitmap?>(null, url, thumbnail) {
        if (thumbnail == null && core.linkPreviewsEnabled()) value = core.videoFrame(url)
    }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val active = LocalScreenActive.current
    LaunchedEffect(active) { if (!active) playing = false }
    val openExternally = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")) }
        Unit
    }
    Box(
        Modifier.padding(top = 10.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(Color.Black)
            // A tap anywhere on the video belongs to the video, never to the note it's in.
            .clickable(interactionSource = null, indication = null) { if (!playing && !failed) playing = true },
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> TextButton(openExternally) { Text("Open in another app", color = Color.White) }
            playing -> {
                AndroidView(
                    factory = {
                        VideoView(it).apply {
                            // Claims the tap that shows the controls, so the note doesn't open its comments too.
                            isClickable = true
                            setMediaController(MediaController(it).also { controller -> controller.setAnchorView(this) })
                            setOnPreparedListener { loading = false }
                            setOnInfoListener { _, what, _ ->
                                when (what) {
                                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> loading = true
                                    MediaPlayer.MEDIA_INFO_BUFFERING_END, MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> loading = false
                                }
                                false
                            }
                            setOnErrorListener { _, _, _ ->
                                failed = true
                                true
                            }
                            setVideoURI(Uri.parse(url))
                            start()
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxSize(),
                )
                if (loading) CircularProgressIndicator(color = Color.White)
            }
            else -> {
                if (thumbnail != null) {
                    NetImage(thumbnail, 380.dp, Modifier.fillMaxSize())
                } else {
                    frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                FilledIconButton(
                    { playing = true },
                    Modifier.size(64.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xB3000000), contentColor = Color.White),
                ) { Icon(Icons.Rounded.PlayArrow, "Play video", Modifier.size(36.dp)) }
            }
        }
    }
}

/** A card for the first ordinary link in a note, loaded when it scrolls into view. */
@Composable
fun LinkPreview(url: String) {
    val core = LocalCore.current
    val context = LocalContext.current
    val info by produceState<LinkInfo?>(null, url) { value = core.linkInfo(url) }
    val card = info ?: return
    var viewing by remember { mutableStateOf(false) }
    when (card.media) {
        "video" -> {
            VideoCard(url)
            return
        }
        "image" -> {
            NetImage(
                url,
                380.dp,
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { viewing = true },
            )
            if (viewing) ImageViewer(listOf(url), 0) { viewing = false }
            return
        }
    }
    Surface(
        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            card.image?.let { NetImage(it, 380.dp, Modifier.fillMaxWidth().height(150.dp)) }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (card.description.isNotEmpty()) {
                    Text(
                        card.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    Uri.parse(url).host.orEmpty().removePrefix("www."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
