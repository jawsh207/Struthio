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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.GalleryItem
import app.struthio.nostr.core.galleryMedia
import app.struthio.nostr.data.Feed
import app.struthio.nostr.data.FeedSource
import kotlinx.coroutines.flow.Flow

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
    val sessionLoaded by core.sessionLoaded.collectAsState()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableStateOf(false) }
    // Only a confirmed empty follow list moves the feed away from Following.
    LaunchedEffect(sessionLoaded, follows.isEmpty()) {
        if (sessionLoaded && follows.isEmpty() && !picked) selected = 1
        if (follows.isNotEmpty() && !picked) selected = 0
    }
    val feeds = remember { HashMap<Int, Feed>() }
    val feed = feeds.getOrPut(selected) { Feed(core, SOURCES[selected].second) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.stop() }
    }
    if (selected == 0) {
        LaunchedEffect(follows) { if (follows.isNotEmpty()) feed.refresh() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(start = PageGutter, end = PageGutter, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchPill(onSearch = { nav.push(Screen.Search) }, onSettings = { nav.push(Screen.Settings) })
                FeedSelector(selected) {
                    picked = true
                    selected = it
                }
            }
            NoteList(feed, Modifier.weight(1f), scrollToTop = LocalNav.current.homeTapped, empty = if (selected == 0) "Follow people to fill this feed. Trending and Global work right away." else "Nothing here yet.")
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
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                "Search notes, people, #tags",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceBright,
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
fun NoteList(
    feed: Feed,
    modifier: Modifier = Modifier,
    scrollToTop: Flow<Unit>? = null,
    empty: String = "Nothing here yet.",
    /** Shows only some of the feed, as a profile's tabs do. */
    filter: ((Event) -> Boolean)? = null,
    /** Pictures and videos in a grid rather than notes in a list. */
    gallery: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    val all by feed.notes.collectAsState()
    val notes = remember(all, filter) { if (filter == null) all else all.filter(filter) }
    val tiles = remember(notes, gallery) { if (gallery) notes.flatMap { e -> galleryMedia(e).map { e to it } }.chunked(3) else emptyList() }
    val loading by feed.loading.collectAsState()
    val list = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = list.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    // Keyed on the whole feed too: when a tab shows few of its notes, each page loaded may still be
    // near the end, and the next one is needed.
    LaunchedEffect(nearEnd, all.size) { if (nearEnd) feed.loadMore() }
    LaunchedEffect(scrollToTop) {
        scrollToTop?.collect {
            // From far down, jump closer first so the animation stays short.
            if (list.firstVisibleItemIndex > 12) list.scrollToItem(12)
            list.animateScrollToItem(0)
        }
    }
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
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            header?.let { item(key = "header") { it() } }
            if (gallery) {
                itemsIndexed(tiles, key = { _, row -> row.first().let { (e, m) -> e.id + m.url } }) { _, row ->
                    GalleryRow(row)
                }
            } else {
                itemsIndexed(notes, key = { _, it -> it.id }, contentType = { _, it -> it.kind }) { index, note ->
                    FeedItem(note, shape = segmentShape(index, notes.size))
                }
            }
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
