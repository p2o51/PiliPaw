package com.gpo.pilipala.navigation

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gpo.pilipala.feature.article.OpusScreen
import com.gpo.pilipala.feature.article.ReadScreen
import com.gpo.pilipala.feature.bangumi.BangumiDetailScreen
import com.gpo.pilipala.feature.dynamics.DynamicDetailScreen
import com.gpo.pilipala.feature.dynamics.MemberDynamicsScreen
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
import com.gpo.pilipala.ui.shell.MainShell
import com.gpo.pilipala.ui.theme.YoinMotion

/**
 * Single NavDisplay for the whole app. Push/pop ride the AOSP cross-activity
 * slide (Yoin: platform mimicry for page changes), predictive back scrubs the
 * same transition.
 */
@Composable
fun AppNavHost(backStack: NavBackStack<NavKey>) {
    val navigator = remember(backStack) { Navigator(backStack) }
    val offset = with(LocalDensity.current) { 96.dp.roundToPx() }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            NavDisplay(
                backStack = backStack,
                onBack = { navigator.pop() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                transitionSpec = {
                    YoinMotion.crossActivitySlideIn(offset * 4) togetherWith YoinMotion.crossActivitySlideOut(-offset)
                },
                popTransitionSpec = {
                    YoinMotion.crossActivitySlideIn(-offset) togetherWith YoinMotion.crossActivitySlideOut(offset * 4)
                },
                predictivePopTransitionSpec = {
                    YoinMotion.crossActivitySlideIn(-offset) togetherWith YoinMotion.crossActivitySlideOut(offset * 4)
                },
                entryProvider = entryProvider {
                    entry<Route.Main> { MainShell() }
                    entry<Route.Video> { VideoDetailScreen(it) }
                    entry<Route.Bangumi> { BangumiDetailScreen(it) }
                    entry<Route.LiveRoom> { LiveRoomScreen(it) }
                    entry<Route.Search> { SearchScreen(it) }
                    entry<Route.SearchResult> { SearchResultScreen(it) }
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
                },
            )
        }
    }
}
