package app.struthio.nostr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.Hex

/** Text being written, plus who each @name in it stands for. */
class NoteDraft(initial: String = "") {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    val mentions: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** The text to publish: chosen @names become NIP-27 references. */
    fun finalText(relayHint: (String) -> List<String>): String {
        var text = value.text.trim()
        mentions.entries.sortedByDescending { it.key.length }.forEach { (name, pubkey) ->
            text = text.replace("@$name", "nostr:" + Nip19.nprofile(pubkey, relayHint(pubkey)))
        }
        return text
    }

    fun clear() {
        value = TextFieldValue("")
        mentions.clear()
    }

    /** The word after an @ that the cursor sits in, if any. */
    val query: String?
        get() {
            val end = value.selection.end
            val start = value.text.lastIndexOf('@', (end - 1).coerceAtLeast(0))
            if (start < 0 || end <= start) return null
            if (start > 0 && !value.text[start - 1].isWhitespace()) return null
            val word = value.text.substring(start + 1, end)
            return word.takeIf { it.isNotEmpty() && it.length <= 30 && it.none(Char::isWhitespace) }
        }

    fun choose(name: String, pubkey: String) {
        val end = value.selection.end
        val start = value.text.lastIndexOf('@', end - 1)
        val label = name.replace(' ', '_')
        val text = value.text.substring(0, start) + "@$label " + value.text.substring(end)
        value = TextFieldValue(text, TextRange(start + label.length + 2))
        mentions[label] = pubkey
    }
}

/** A text field that offers people as soon as an @name is being typed, follows first. */
@Composable
fun NoteField(draft: NoteDraft, placeholder: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val core = LocalCore.current
    val query = draft.query
    val people by liveQuery(emptyList<String>(), query) {
        if (query == null) {
            emptyList()
        } else {
            val follows = core.follows.value
            core.store.searchProfiles(query, 30).map { it.pubkey }.filter(Hex::isKey).sortedBy { it !in follows }.take(5)
        }
    }
    Column(modifier) {
        if (query != null && people.isNotEmpty()) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(people, key = { _, it -> it }) { index, pubkey ->
                    PersonRow(core, pubkey, segmentShape(index, people.size)) { draft.choose(core.displayName(pubkey), pubkey) }
                }
            }
        }
        OutlinedTextField(
            draft.value,
            { draft.value = it },
            placeholder = { Text(placeholder) },
            shape = RoundedCornerShape(24.dp),
            maxLines = maxLines,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        )
    }
}
