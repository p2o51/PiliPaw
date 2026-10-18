package com.gpo.pilipala.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.account.UserInfo
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.feeds.FeedManageSheet
import com.gpo.pilipala.feature.feeds.FeedStore
import com.gpo.pilipala.feature.feeds.MaxFeeds
import com.gpo.pilipala.feature.search.PiliSearchBar
import com.gpo.pilipala.feature.search.PiliSearchState
import com.gpo.pilipala.feature.search.SearchRequest
import com.gpo.pilipala.feature.search.openSearch
import com.gpo.pilipala.feature.search.rememberPiliSearchState
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.launch.splashAvatarSlot
import com.gpo.pilipala.ui.launch.splashReveal
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.pilipala.ui.shell.LocalTabReselect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One home tab: a built-in list or a user-built feed ([feedId]). [key] is stable across reorders. */
@Immutable
private data class HomeTab(val key: String, val label: String, val feedId: String? = null)

private val BuiltinTabs = listOf(
    HomeTab("live", "直播"),
    HomeTab("rcmd", "推荐"),
    HomeTab("hot", "热门"),
    HomeTab("bangumi", "番剧"),
)

@Composable
fun HomeScreen() {
    val nav = LocalNavigator.current
    val headerVm: HomeHeaderViewModel = viewModel(key = "home_header") { HomeHeaderViewModel() }
    val user by Account.user.collectAsState()
    val feeds by FeedStore.feeds.collectAsState()
    // Built-ins, then the user's feeds in their order.
    val tabs = remember(feeds) { BuiltinTabs + feeds.map { HomeTab(it.tabKey, it.name, it.id) } }
    val labels = remember(tabs) { tabs.map { it.label } }
    val latestTabs by rememberUpdatedState(tabs)
    // The selected tab is kept by key, so it stays put while feeds are added, moved or renamed.
    var selectedKey by rememberSaveable {
        mutableStateOf(BuiltinTabs[AppSettings.homeDefaultTab.value.coerceIn(0, BuiltinTabs.lastIndex)].key)
    }
    val pagerState = rememberPagerState(initialPage = remember { tabs.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0) }) { tabs.size }
    val scope = rememberCoroutineScope()
    val behavior = rememberHeaderScrollBehavior()
    val pageSpec = YoinMotion.defaultSpatialSpec<Float>()
    var managing by rememberSaveable { mutableStateOf<String?>(null) }
    val search = rememberPiliSearchState()

    // One scroll state per tab, hoisted so it survives the pager disposing pages.
    val liveState = rememberLazyGridState()
    val rcmdState = rememberLazyGridState()
    val hotState = rememberLazyGridState()
    val bangumiState = rememberLazyGridState()
    val feedStates = remember { mutableMapOf<String, LazyGridState>() }

    // A settled page names the selected tab.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page -> latestTabs.getOrNull(page)?.let { selectedKey = it.key } }
    }
    // The tab list changed: follow the selected tab to its new index, or step left when it is gone.
    LaunchedEffect(tabs) {
        val index = tabs.indexOfFirst { it.key == selectedKey }
        if (index >= 0) {
            if (index != pagerState.currentPage) pagerState.scrollToPage(index)
        } else {
            val fallback = (pagerState.currentPage - 1).coerceIn(0, tabs.lastIndex)
            selectedKey = tabs[fallback].key
            pagerState.animateScrollToPage(fallback, animationSpec = pageSpec)
        }
    }
    // A feed just created: switch to its tab once it is in the row.
    val focus by FeedStore.focusRequest.collectAsState()
    LaunchedEffect(focus, tabs) {
        val id = focus ?: return@LaunchedEffect
        val index = tabs.indexOfFirst { it.feedId == id }
        if (index < 0) return@LaunchedEffect
        selectedKey = tabs[index].key
        pagerState.animateScrollToPage(index, animationSpec = pageSpec)
        FeedStore.consumeFocus(id)
    }

    // Reselects: the bottom-bar tab (acts on the visible page) or the visible segment.
    val shellReselect = LocalTabReselect.current
    val pageReselects = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    LaunchedEffect(shellReselect) {
        shellReselect.collect { latestTabs.getOrNull(pagerState.currentPage)?.let { pageReselects.tryEmit(it.key) } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .nestedScroll(behavior.nestedScrollConnection),
    ) {
        CollapsingHeader(behavior) {
            HomeHeader(
                user = user,
                hint = headerVm.searchHint,
                search = search,
                onAvatar = {
                    val u = user
                    if (u != null) nav.push(Route.Member(u.mid, u.face, u.name)) else nav.push(Route.Login)
                },
                onSearch = { nav.openSearch(it) },
                onMessages = { nav.push(if (user != null) Route.Messages else Route.Login) },
            )
        }
        SegmentTabs(
            labels = labels,
            selected = pagerState.targetPage,
            onSelect = { i ->
                if (i == pagerState.currentPage && i == pagerState.targetPage) {
                    tabs.getOrNull(i)?.let { pageReselects.tryEmit(it.key) }
                } else {
                    scope.launch { pagerState.animateScrollToPage(i, animationSpec = pageSpec) }
                }
            },
            // combinedClickable already ticks the long-press haptic.
            onLongClick = { i -> tabs.getOrNull(i)?.feedId?.let { managing = it } },
            longClickable = { i -> tabs.getOrNull(i)?.feedId != null },
            trailing = if (feeds.size < MaxFeeds) {
                {
                    Box(Modifier.splashReveal()) {
                        SegmentIconChip(YoinSymbols.Add, contentDescription = "新建 Feed", onClick = { nav.push(Route.FeedEditor()) })
                    }
                }
            } else {
                null
            },
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            key = { latestTabs.getOrNull(it)?.key ?: it },
        ) { page ->
            val tab = tabs.getOrNull(page) ?: return@HorizontalPager
            val reselect = remember(tab.key) { pageReselects.filter { it == tab.key }.map { } }
            val feedId = tab.feedId
            when {
                feedId != null -> CustomFeedTab(feedId, feedStates.getOrPut(tab.key) { LazyGridState() }, reselect, behavior)
                tab.key == "live" -> LiveTab(liveState, reselect, behavior)
                tab.key == "rcmd" -> RcmdTab(rcmdState, reselect, behavior)
                tab.key == "hot" -> HotTab(hotState, reselect, behavior)
                else -> BangumiTab(bangumiState, reselect, behavior)
            }
        }
    }

    managing?.let { id -> FeedManageSheet(id, onDismiss = { managing = null }) }
}

@Composable
private fun HomeHeader(
    user: UserInfo?,
    hint: String,
    search: PiliSearchState,
    onAvatar: () -> Unit,
    onSearch: (SearchRequest) -> Unit,
    onMessages: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderAvatar(user, onAvatar)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).splashReveal(), contentAlignment = Alignment.Center) {
            // The native search: it opens full screen over the shell, the bottom bar included.
            PiliSearchBar(
                state = search,
                hint = hint,
                onSubmit = onSearch,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                // Back on the home page the bar shows the suggested word again.
                clearOnSubmit = true,
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onMessages,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp).splashReveal(),
        ) {
            Icon(PiliSymbols.Mail, contentDescription = "消息")
        }
    }
}

@Composable
private fun HeaderAvatar(user: UserInfo?, onClick: () -> Unit) {
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val enterSpec = YoinMotion.defaultEffectsSpec<Float>()
    val exitSpec = YoinMotion.fastEffectsSpec<Float>()
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(48.dp),
    ) {
        // The launch handoff lands the PiliPaw mark here, then this fades in over it.
        AnimatedContent(
            targetState = user?.face,
            modifier = Modifier.splashAvatarSlot(),
            transitionSpec = { fadeIn(enterSpec).togetherWith(fadeOut(exitSpec)) },
            label = "homeAvatar",
        ) { face ->
            if (face != null) {
                NetImage(face, Modifier.size(40.dp), contentDescription = "我的", shape = cookie, thumbWidth = 80.dp)
            } else {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, cookie),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        YoinSymbols.Person,
                        contentDescription = "登录",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
