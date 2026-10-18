package com.gpo.pilipala.navigation

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
import com.gpo.pilipala.feature.article.OpusScreen
import com.gpo.pilipala.feature.article.ReadScreen
import com.gpo.pilipala.feature.bangumi.BangumiDetailScreen
import com.gpo.pilipala.feature.dynamics.DynamicDetailScreen
import com.gpo.pilipala.feature.dynamics.MemberDynamicsScreen
import com.gpo.pilipala.feature.dynamics.VoteScreen
import com.gpo.pilipala.feature.feeds.FeedEditorScreen
import com.gpo.pilipala.feature.library.BlacklistScreen
import com.gpo.pilipala.feature.library.FansScreen
import com.gpo.pilipala.feature.library.FavDetailScreen
import com.gpo.pilipala.feature.library.FavScreen
import com.gpo.pilipala.feature.library.FollowScreen
import com.gpo.pilipala.feature.library.HistoryScreen
import com.gpo.pilipala.feature.library.LaterScreen
import com.gpo.pilipala.feature.library.SubDetailScreen
import com.gpo.pilipala.feature.library.SubscriptionScreen
import com.gpo.pilipala.feature.live.LiveRoomScreen
import com.gpo.pilipala.feature.login.LoginScreen
import com.gpo.pilipala.feature.member.MemberArchiveScreen
import com.gpo.pilipala.feature.member.MemberArticleScreen
import com.gpo.pilipala.feature.member.MemberCoinScreen
import com.gpo.pilipala.feature.member.MemberLikeScreen
import com.gpo.pilipala.feature.member.MemberScreen
import com.gpo.pilipala.feature.member.MemberSeasonsScreen
import com.gpo.pilipala.feature.message.MessageListScreen
import com.gpo.pilipala.feature.message.MessagesScreen
import com.gpo.pilipala.feature.message.WhisperDetailScreen
import com.gpo.pilipala.feature.reply.ReplyDetailScreen
import com.gpo.pilipala.feature.search.SearchResultScreen
import com.gpo.pilipala.feature.search.SearchScreen
import com.gpo.pilipala.feature.settings.AboutScreen
import com.gpo.pilipala.feature.settings.SettingsPageScreen
import com.gpo.pilipala.feature.settings.SettingsScreen
import com.gpo.pilipala.feature.video.VideoDetailScreen
import com.gpo.pilipala.feature.web.WebScreen
import com.gpo.pilipala.navigation.transition.BackInputBlocker
import com.gpo.pilipala.navigation.transition.BackPhase
import com.gpo.pilipala.navigation.transition.BeneathPage
import com.gpo.pilipala.navigation.transition.LocalPageTransitions
import com.gpo.pilipala.navigation.transition.PageBackHandler
import com.gpo.pilipala.navigation.transition.PageEntryFrame
import com.gpo.pilipala.navigation.transition.pageClosingTransform
import com.gpo.pilipala.navigation.transition.pagePopTransform
import com.gpo.pilipala.navigation.transition.pagePushTransform
import com.gpo.pilipala.navigation.transition.rememberPageTransitions
import com.gpo.pilipala.navigation.transition.voteHighFrameRate
import com.gpo.pilipala.ui.shell.MainShell

/**
 * Single NavDisplay for the whole app, wrapped in the page-transition host
 * (navigation/transition): pushes grow out of the tapped card (container
 * transform) or enter on Yoin's forward mirror; back is the in-window AOSP
 * cross-activity replica for every pushed page (predictive-back Pattern B).
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
                .onSizeChanged { transitions.hostSize = it }
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
        }
    }
}

private val appEntryProvider = entryProvider<NavKey> {
    entry<Route.Main> { MainShell() }
    entry<Route.Video> { VideoDetailScreen(it) }
    entry<Route.Bangumi> { BangumiDetailScreen(it) }
    entry<Route.LiveRoom> { LiveRoomScreen(it) }
    entry<Route.Search> { SearchScreen(it) }
    entry<Route.SearchResult> { SearchResultScreen(it) }
    entry<Route.FeedEditor> { FeedEditorScreen(it) }
    entry<Route.Member> { MemberScreen(it) }
    entry<Route.MemberArchive> { MemberArchiveScreen(it) }
    entry<Route.MemberDynamics> { MemberDynamicsScreen(it) }
    entry<Route.MemberSeasons> { MemberSeasonsScreen(it) }
    entry<Route.MemberCoin> { MemberCoinScreen(it) }
    entry<Route.MemberLike> { MemberLikeScreen(it) }
    entry<Route.MemberArticle> { MemberArticleScreen(it) }
    entry<Route.DynamicDetail> { DynamicDetailScreen(it) }
    entry<Route.ReplyDetail> { ReplyDetailScreen(it) }
    entry<Route.Opus> { OpusScreen(it) }
    entry<Route.Read> { ReadScreen(it) }
    entry<Route.Vote> { VoteScreen(it) }
    entry<Route.Fav> { FavScreen(it) }
    entry<Route.FavDetail> { FavDetailScreen(it) }
    entry<Route.Later> { LaterScreen() }
    entry<Route.History> { HistoryScreen() }
    entry<Route.Subscription> { SubscriptionScreen(it) }
    entry<Route.SubDetail> { SubDetailScreen(it) }
    entry<Route.Follow> { FollowScreen(it) }
    entry<Route.Fans> { FansScreen(it) }
    entry<Route.Blacklist> { BlacklistScreen() }
    entry<Route.Messages> { MessagesScreen() }
    entry<Route.MessageList> { MessageListScreen(it) }
    entry<Route.WhisperDetail> { WhisperDetailScreen(it) }
    entry<Route.Login> { LoginScreen() }
    entry<Route.Settings> { SettingsScreen() }
    entry<Route.SettingsPage> { SettingsPageScreen(it) }
    entry<Route.About> { AboutScreen() }
    entry<Route.Web> { WebScreen(it) }
}
