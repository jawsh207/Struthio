package app.struthio.nostr.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun BackBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    val nav = LocalNav.current
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = { nav.pop() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
        },
        actions = actions,
    )
}
