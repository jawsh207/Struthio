package app.struthio.nostr.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.data.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data class Thread(val id: String, val relays: List<String> = emptyList(), val author: String? = null) : Screen
    data class Profile(val pubkey: String) : Screen
    data class People(val pubkey: String, val followers: Boolean) : Screen
    data class Hashtag(val tag: String) : Screen
    data class Compose(val quote: String? = null) : Screen
    data class Chat(val room: String) : Screen
    data object NewMessage : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Relays : Screen
    data object AccountCheck : Screen
    data object EditProfile : Screen
    data object Bookmarks : Screen
}

class Nav(private val scope: CoroutineScope, private val snackbar: SnackbarHostState) {
    val stack = mutableStateListOf<Screen>()

    fun push(screen: Screen) {
        stack += screen
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun say(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
}

private class Tab(val label: String, val on: ImageVector, val off: ImageVector)

private val TABS = listOf(
    Tab("Home", Icons.Rounded.Home, Icons.Outlined.Home),
    Tab("Messages", Icons.Rounded.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    Tab("Wallet", Icons.Rounded.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet),
    Tab("Notifications", Icons.Rounded.Notifications, Icons.Outlined.NotificationsNone),
)

@Composable
fun App(core: Core, deepLink: MutableStateFlow<String?>) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val nav = remember { Nav(scope, snackbar) }
    val signer by core.signer.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var pendingWallet by remember { mutableStateOf<String?>(null) }
    val link by deepLink.collectAsState()

    LaunchedEffect(link, signer) {
        val target = link ?: return@LaunchedEffect
        if (signer == null) return@LaunchedEffect
        deepLink.value = null
        if (target.startsWith("nostr+walletconnect:")) {
            pendingWallet = target
            tab = 2
            nav.stack.clear()
        } else {
            when (val entity = Nip19.decode(target)) {
                is Entity.Profile -> nav.push(Screen.Profile(entity.pubkey))
                is Entity.Note -> nav.push(Screen.Thread(entity.id, entity.relays, entity.author))
                else -> Unit
            }
        }
    }

    CompositionLocalProvider(LocalCore provides core, LocalNav provides nav) {
        if (signer == null) {
            Scaffold(contentWindowInsets = WindowInsets(0), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                Onboarding(Modifier.padding(padding))
            }
            return@CompositionLocalProvider
        }
        BackHandler(nav.stack.isNotEmpty()) { nav.pop() }
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !core.prefs.getBoolean("asked_notifications", false)) {
                core.prefs.edit().putBoolean("asked_notifications", true).apply()
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val top = nav.stack.lastOrNull()
        val me = signer!!.pubkey
        val alertsRead = core.prefs.getLong("alerts_read", 0L)
        val unreadAlerts by liveQuery(0, tab, alertsRead) { if (tab == 3) 0 else core.store.unreadAlerts(me, core.prefs.getLong("alerts_read", 0L)) }
        val unreadDms by liveQuery(0, tab) { if (tab == 1) 0 else core.store.unreadMessages(me, core.prefs.getLong("dms_read", 0L)) }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (top == null) {
                    ShortNavigationBar(windowInsets = ShortNavigationBarDefaults.windowInsets.union(WindowInsets.ime)) {
                        TABS.forEachIndexed { index, item ->
                            val badge = when (index) {
                                1 -> unreadDms
                                3 -> unreadAlerts
                                else -> 0
                            }
                            ShortNavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = {
                                    BadgedBox(badge = { if (badge > 0) Badge { Text(if (badge > 99) "99+" else badge.toString()) } }) {
                                        Icon(if (tab == index) item.on else item.off, item.label)
                                    }
                                },
                                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (top) {
                    null -> when (tab) {
                        0 -> HomeScreen()
                        1 -> MessagesScreen()
                        2 -> WalletScreen(pendingWallet) { pendingWallet = null }
                        else -> AlertsScreen()
                    }
                    is Screen.Thread -> ThreadScreen(top)
                    is Screen.Profile -> ProfileScreen(top.pubkey)
                    is Screen.People -> PeopleScreen(top)
                    is Screen.Hashtag -> HashtagScreen(top.tag)
                    is Screen.Compose -> ComposeScreen(top.quote)
                    is Screen.Chat -> ChatScreen(top.room)
                    Screen.NewMessage -> NewMessageScreen()
                    Screen.Search -> SearchScreen()
                    Screen.Settings -> SettingsScreen()
                    Screen.Relays -> RelaysScreen()
                    Screen.AccountCheck -> AccountCheckScreen()
                    Screen.EditProfile -> EditProfileScreen()
                    Screen.Bookmarks -> BookmarksScreen()
                }
            }
        }
    }
}
