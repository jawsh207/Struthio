package app.struthio.nostr.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.struthio.nostr.data.Feed
import app.struthio.nostr.data.FeedSource

private val SOURCES = listOf(
    "Following" to FeedSource.Following,
    "Trending" to FeedSource.Trending,
    "Global" to FeedSource.Global,
    "Relays" to FeedSource.MyRelays,
)

@Composable
fun HomeScreen() {
    val core = LocalCore.current
    val nav = LocalNav.current
    val follows by core.follows.collectAsState()
    var selected by rememberSaveable { mutableIntStateOf(if (follows.isEmpty()) 1 else 0) }
    val feeds = remember { HashMap<Int, Feed>() }
    val feed = feeds.getOrPut(selected) { Feed(core, SOURCES[selected].second) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.stop() }
    }
    if (selected == 0) {
        LaunchedEffect(follows.size) { if (follows.isNotEmpty()) feed.refresh() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchPill(onSearch = { nav.push(Screen.Search) }, onSettings = { nav.push(Screen.Settings) })
                FeedSelector(selected) { selected = it }
            }
            NoteList(feed, Modifier.weight(1f), empty = if (selected == 0) "Follow people to fill this feed. Trending and Global work right away." else "Nothing here yet.")
        }
        FloatingActionButton(
            onClick = { nav.push(Screen.Compose()) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(Icons.Outlined.Edit, "New note")
        }
    }
}

@Composable
private fun SearchPill(onSearch: () -> Unit, onSettings: () -> Unit) {
    Surface(
        onClick = onSearch,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                "Search notes, people, #tags",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onSettings,
                colors = IconButtonDefaults.filledTonalIconButtonColors(),
            ) {
                Icon(Icons.Filled.Settings, "Settings")
            }
        }
    }
}

/** Connected button group: the selected item grows into a pill, the way the mockup shows. */
@Composable
private fun FeedSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SOURCES.forEachIndexed { index, (label, _) ->
            val active = index == selected
            val full = 22.dp
            val inner = 8.dp
            val start by animateDpAsState(if (active || index == 0) full else inner, label = "start")
            val end by animateDpAsState(if (active || index == SOURCES.lastIndex) full else inner, label = "end")
            Surface(
                onClick = { onSelect(index) },
                shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(if (active) 1.3f else 1f).height(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun NoteList(feed: Feed, modifier: Modifier = Modifier, empty: String = "Nothing here yet.", header: (@Composable () -> Unit)? = null) {
    val notes by feed.notes.collectAsState()
    val loading by feed.loading.collectAsState()
    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) feed.loadMore() }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(loading) { if (!loading) refreshing = false }
    PullToRefreshBox(
        refreshing,
        onRefresh = {
            refreshing = true
            feed.loading.value = true
            feed.refresh()
        },
        modifier = modifier,
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header?.let { item(key = "header") { it() } }
            items(notes, key = { it.id }, contentType = { it.kind }) { FeedItem(it) }
            if (notes.isEmpty()) {
                item(key = "state") {
                    if (loading) {
                        Loading(Modifier.fillMaxWidth().padding(48.dp))
                    } else {
                        Text(
                            empty,
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
