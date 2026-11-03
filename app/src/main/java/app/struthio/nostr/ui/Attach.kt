package app.struthio.nostr.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.struthio.nostr.core.DEFAULT_REACTION
import app.struthio.nostr.data.Upload
import java.text.BreakIterator

/** Picks one image through the system photo picker and uploads it. */
@Composable
fun AttachImageButton(onUploaded: (Upload) -> Unit) {
    val core = LocalCore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            scope.act(nav) {
                try {
                    onUploaded(core.media.upload(core.media.prepareImage(uri)))
                } finally {
                    busy = false
                }
            }
        }
    }
    IconButton(
        { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = !busy,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.AddPhotoAlternate, "Add image")
        }
    }
}

@Composable
fun AttachedImages(items: List<Upload>, onRemove: (Upload) -> Unit) {
    if (items.isEmpty()) return
    Row(Modifier.padding(horizontal = PageGutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { upload ->
            Box {
                NetImage(upload.url, 88.dp, Modifier.size(88.dp).clip(RoundedCornerShape(16.dp)))
                IconButton({ onRemove(upload) }, Modifier.align(Alignment.TopEnd).size(32.dp)) {
                    Icon(Icons.Rounded.Close, "Remove image")
                }
            }
        }
    }
}

private val EMOJI = listOf(
    "💜", "❤️", "🧡", "💛", "💚", "💙", "🖤", "🤍", "👍", "👎", "🤙", "🙌", "👏", "🫡", "🙏", "💪",
    "😂", "🤣", "😅", "😊", "😍", "🥰", "😘", "😎", "🤔", "🫠", "😮", "😢", "😭", "😡", "🤯", "🥳",
    "🔥", "⚡", "✨", "💯", "🎉", "🚀", "👀", "💎", "🌱", "☕", "🍻", "🍕", "🐸", "🦤", "🌽", "₿",
)

private fun firstGrapheme(text: String): String {
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(text)
    val end = breaks.next()
    return if (end == BreakIterator.DONE) text else text.substring(0, end)
}

/** Long press on the reaction button: any emoji, with the purple heart first. */
@Composable
fun EmojiSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var other by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("React", style = MaterialTheme.typography.titleLarge)
            LazyVerticalGrid(GridCells.Fixed(8), Modifier.fillMaxWidth()) {
                items(EMOJI) { emoji ->
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 24.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(other, { other = it }, label = { Text("Any emoji") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton({ onPick(firstGrapheme(other.trim()).ifEmpty { DEFAULT_REACTION }) }, enabled = other.isNotBlank()) { Text("React") }
            }
        }
    }
}
