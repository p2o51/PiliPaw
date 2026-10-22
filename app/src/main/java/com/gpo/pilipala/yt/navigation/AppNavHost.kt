package com.gpo.pilipala.yt.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.rememberNavigationEventState
import com.gpo.pilipala.yt.navigation.transition.BackInputBlocker
import com.gpo.pilipala.yt.navigation.transition.BackPhase
import com.gpo.pilipala.yt.navigation.transition.BeneathPage
import com.gpo.pilipala.yt.navigation.transition.LocalPageTransitions
import com.gpo.pilipala.yt.navigation.transition.PageBackHandler
import com.gpo.pilipala.yt.navigation.transition.PageEntryFrame
import com.gpo.pilipala.yt.navigation.transition.pageClosingTransform
import com.gpo.pilipala.yt.navigation.transition.pagePopTransform
import com.gpo.pilipala.yt.navigation.transition.pagePushTransform
import com.gpo.pilipala.yt.navigation.transition.rememberPageTransitions
import com.gpo.pilipala.yt.navigation.transition.voteHighFrameRate
import com.gpo.pilipala.yt.feature.channel.ChannelScreen
import com.gpo.pilipala.yt.feature.player.PlayerOverlayHost
import com.gpo.pilipala.yt.feature.settings.SettingsScreen
import com.gpo.pilipala.yt.ui.shell.MainShell

/**
 * Single NavDisplay for the whole app, wrapped in the page-transition host
 * (navigation/transition): pushes grow out of the tapped card (container
 * transform) or enter on Yoin's forward mirror; back is the in-window AOSP
 * cross-activity replica for every pushed page (predictive-back Pattern B).
 *
 * The player is not a page: [PlayerOverlayHost] floats over the whole stack as Yoin's
 * Now Playing does (mini bar ⇄ full player, one progress), so a video keeps playing while
 * the user browses underneath.
 *
 * NavDisplay's own predictive pop is not used: it is driven through the
 * scene-state overload with a NavigationEventState that no handler feeds, and
 * [PageBackHandler] (composed first, so in-page BackHandlers outrank it)
 * owns the gesture.
 */
@Composable
fun AppNavHost(navigator: Navigator) {
    val backStack = navigator.backStack
    val transitions = rememberPageTransitions(backStack)
    val configuration = LocalConfiguration.current
    SideEffect {
        navigator.transitions = transitions
        transitions.configStamp = configuration.uiMode * 31 + configuration.densityDpi
    }
    val density = LocalDensity.current
    val decorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
        rememberViewModelStoreNavEntryDecorator<NavKey>(),
        remember(transitions) {
            NavEntryDecorator<NavKey>(onPop = { key -> transitions.onEntryPopped(key) }) { entry ->
                PageEntryFrame(transitions, entry.contentKey) { entry.Content() }
            }
        },
    )
    val entries = rememberDecoratedNavEntries(backStack, decorators, appEntryProvider)

    CompositionLocalProvider(LocalNavigator provides navigator, LocalPageTransitions provides transitions) {
        // First in composition = lowest back priority (see PageBackHandler).
        PageBackHandler(transitions, canGoBack = backStack.size > 1)
        val backdrop = MaterialTheme.colorScheme.surfaceContainerHighest
        Box(
            Modifier
                .fillMaxSize()
                .background(backdrop)
                .onSizeChanged {
                    transitions.hostSize = it
                    // A whole back gesture: the window's width, up to a wide phone's worth more.
                    transitions.gestureSpanPx = minOf(it.width.toFloat(), with(density) { GestureSpanMax.toPx() })
                }
                .voteHighFrameRate(transitions.phase != BackPhase.Idle),
        ) {
            BeneathPage(transitions, backdrop)
            Box(
                Modifier
                    .fillMaxSize()
                    .pageClosingTransform(transitions)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                val sceneState = rememberSceneState(
                    entries,
                    listOf(SinglePaneSceneStrategy()),
                    emptyList(),
                    null,
                    { navigator.pop() },
                )
                val scene = sceneState.currentScene
                val navigationState = rememberNavigationEventState(
                    currentInfo = SceneInfo(scene),
                    backInfo = sceneState.previousScenes.map { SceneInfo(it) },
                )
                NavDisplay(
                    sceneState,
                    navigationState,
                    Modifier.fillMaxSize(),
                    Alignment.TopStart,
                    null,
                    { pagePushTransform(transitions, density) },
                    { pagePopTransform() },
                    { _ -> pagePopTransform() },
                )
            }
            BeneathPage(transitions, backdrop, over = true)
            BackInputBlocker(transitions)
            // Last in composition: over every page, and its back handlers outrank the pages'
            // while it is expanded (predictive-back ShellOverlayDown).
            PlayerOverlayHost()
        }
    }
}

private val appEntryProvider = entryProvider<NavKey> {
    entry<Route.Main> { MainShell() }
    ytPages()
}

/**
 * The YouTube part's pushed pages, for any host: its own (above) or the app's one stack
 * (navigation/AppNavHost.kt), where they sit beside Bilibili's pages.
 */
object AppNavHostPages {
    fun androidx.navigation3.runtime.EntryProviderScope<NavKey>.youtubePages() = ytPages()
}

private fun androidx.navigation3.runtime.EntryProviderScope<NavKey>.ytPages() {
    // YouTube's search is the app's one search (PiliPaw's native bar and result page), on YouTube.
    entry<Route.Search> {
        YouTubeSearchScope()
        com.gpo.pilipala.feature.search.SearchScreen(com.gpo.pilipala.navigation.Route.Search(it.hint))
    }
    entry<Route.SearchResult> {
        YouTubeSearchScope()
        com.gpo.pilipala.feature.search.SearchResultScreen(com.gpo.pilipala.navigation.Route.SearchResult(it.query))
    }
    entry<Route.Channel> { ChannelScreen(it) }
    // The YouTube account's page (from the avatar menu, or 我的 › YouTube): account, library, its own options.
    entry<Route.Settings> { SettingsScreen(title = "YouTube") }
    entry<Route.SignIn> { com.gpo.pilipala.yt.feature.account.SignInScreen() }
    entry<Route.AccountVideos> { com.gpo.pilipala.yt.feature.account.AccountVideosScreen(it) }
    entry<Route.LocalList> { com.gpo.pilipala.yt.feature.library.LocalListScreen(it) }
    entry<Route.Playlists> { com.gpo.pilipala.yt.feature.library.PlaylistsScreen() }
    entry<Route.LocalPlaylist> { com.gpo.pilipala.yt.feature.library.LocalPlaylistScreen(it) }
    entry<Route.FeedEditor> { com.gpo.pilipala.yt.feature.feeds.FeedEditorScreen(it) }
    entry<Route.Mutes> { com.gpo.pilipala.yt.feature.mute.MuteScreen() }
    entry<Route.Subscriptions> { com.gpo.pilipala.yt.feature.subscriptions.SubscriptionsScreen(pushed = true) }
}

/** Searching from the YouTube part: with both sources ticked, the search narrows to YouTube. */
@androidx.compose.runtime.Composable
private fun YouTubeSearchScope() {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val scope = com.gpo.pilipala.feature.sources.SearchScope
        if (scope.mixing) scope.set(com.gpo.pilipala.core.source.VideoSource.YouTube)
    }
}

/** The finger travel of a whole back gesture on a wide window (a phone needs its own width). */
private val GestureSpanMax = 600.dp
