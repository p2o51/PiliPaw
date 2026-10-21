package com.gpo.pilipala.yt.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.pilipala.ui.theme.YoinMotion
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.animateColorAsState
import com.gpo.pilipala.yt.feature.mute.Mutes
import com.gpo.pilipala.yt.feature.feeds.FeedStore
import com.gpo.pilipala.yt.feature.feeds.FeedMixer
import com.gpo.pilipala.yt.feature.feeds.Feed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.extractor.YtFeeds
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.feature.settings.Choice
import com.gpo.pilipala.feature.settings.ConnectedToggleRow
import com.gpo.pilipala.yt.ui.components.LoadMoreEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.feature.account.ProfileButton
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.feature.subscriptions.Subscriptions
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.yt.ui.components.LocalBottomBarInset
import com.gpo.pilipala.yt.ui.components.Page
import com.gpo.pilipala.yt.ui.components.Pager
import com.gpo.pilipala.yt.ui.components.RefreshBox
import com.gpo.pilipala.yt.ui.components.VideoCard
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.yt.ui.components.videoGridMinWidth
import com.gpo.pilipala.yt.ui.shell.LocalTabReselect
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 首页. Signed out: the newest uploads of the local subscriptions, newest first, from each
 * channel's RSS feed (one light request per channel, six at a time); no algorithm. Signed in:
 * the account's own 订阅 feed (its subscriptions' uploads, the default) or YouTube's 推荐.
 */
/** A Home tab: 订阅, 推荐 (signed in) or a custom feed ("feed:<id>"). */
object HomeTabs {
    const val Subscriptions = "subs"
    const val Recommended = "rec"
    fun feed(id: String) = "feed:$id"
}

class HomeViewModel : ViewModel() {
    var tab by mutableStateOf(HomeTabs.Subscriptions)
        private set

    private var mixer: FeedMixer? = null

    val pager = Pager<VideoItem>(viewModelScope, dedupKey = { it.url }) { cursor -> load(cursor) }

    init { pager.refresh() }

    fun select(t: String) {
        if (t == tab) return
        tab = t
        mixer = null
        pager.refresh()
    }

    private suspend fun load(cursor: String?): Page<VideoItem> {
        val t = tab
        if (t.startsWith("feed:")) {
            val feed = FeedStore.get(t.removePrefix("feed:")) ?: return Page(emptyList(), hasMore = false)
            val m = mixer?.takeIf { cursor != null } ?: FeedMixer(feed).also { mixer = it }
            return m.load(reset = cursor == null)
        }
        if (Accounts.youtube.value is Profile.SignedIn) {
            val page = YtFeeds.videos(if (t == HomeTabs.Recommended) YtFeeds.Feed.Home else YtFeeds.Feed.Subscriptions, cursor)
            val hide = AppSettings.hideShorts.value
            return page.copy(items = page.items.filterNot { (hide && it.isShort) || Mutes.hides(it) })
        }
        return local()
    }

    private suspend fun local(): Page<VideoItem> = coroutineScope {
        val channels = Subscriptions.channels.value
        val gate = Semaphore(6)
        // The channel-page fallback carries no uploader avatar: the subscription has it.
        val results = channels.map { c ->
            async {
                gate.withPermit {
                    runCatching { Yt.latest(c.url).map { if (it.channelAvatar.isEmpty()) it.copy(channelAvatar = c.avatar) else it } }
                }
            }
        }.awaitAll()
        // One channel failing still shows the rest; all of them failing is the page's error.
        if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        val all = results.flatMap { it.getOrDefault(emptyList()) }.filterNot { it.isShort || Mutes.hides(it) }.sortedByDescending { it.uploadedAt }
        Page(all, hasMore = false)
    }
}

@Composable
fun HomeScreen() {
    val vm = viewModel { HomeViewModel() }
    val pager = vm.pager
    val nav = LocalNavigator.current
    val subs by Subscriptions.channels.collectAsState()
    val gridState = rememberLazyGridState()
    val reselect = LocalTabReselect.current
    LaunchedEffect(reselect) {
        reselect.collect {
            if (gridState.firstVisibleItemIndex > 0) gridState.animateScrollToItem(0) else pager.refresh()
        }
    }
    // A subscription added or removed elsewhere, or signing in / out: the feed follows.
    val profile by Accounts.youtube.collectAsState()
    val signedIn = profile is Profile.SignedIn
    val feeds by FeedStore.feeds.collectAsState()
    val mutes by Mutes.version.collectAsState()
    // A tab whose feed was deleted, or 推荐 after signing out, falls back to 订阅.
    LaunchedEffect(feeds, signedIn) {
        val t = vm.tab
        if ((t.startsWith("feed:") && feeds.none { HomeTabs.feed(it.id) == t }) || (t == HomeTabs.Recommended && !signedIn)) vm.select(HomeTabs.Subscriptions)
    }
    LaunchedEffect(subs.size, signedIn, mutes, feeds) { if (!pager.initialLoading) pager.refresh() }
    LoadMoreEffect(gridState) { pager.loadMore() }
    val onSubs = vm.tab == HomeTabs.Subscriptions
    val tabs: @Composable () -> Unit = { HomeTabRow(vm.tab, signedIn, feeds, vm::select) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalBottomBarInset.current
    Box(Modifier.fillMaxSize()) {
        when {
            onSubs && !signedIn && subs.isEmpty() -> HomeMessage(onSearch = { nav.push(Route.Search()) }, statusTop = statusTop, tabs = tabs) {
                EmptyState("还没有订阅", icon = YoinSymbols.Library)
                Button(
                    onClick = { nav.push(Route.Search()) },
                    modifier = Modifier.align(Alignment.Center).padding(top = 120.dp),
                ) { Text("搜索") }
            }
            pager.initialLoading -> LoadingState()
            pager.error != null && pager.items.isEmpty() -> HomeMessage(onSearch = { nav.push(Route.Search()) }, statusTop = statusTop, tabs = tabs) {
                ErrorState(pager.error.orEmpty(), onRetry = pager::refresh)
            }
            pager.items.isEmpty() -> HomeMessage(onSearch = { nav.push(Route.Search()) }, statusTop = statusTop, tabs = tabs) {
                EmptyState(if (onSubs) "订阅的频道还没有视频" else "这个 Feed 还没有视频", icon = YoinSymbols.Library)
            }
            else -> RefreshBox(
                isRefreshing = pager.isRefreshing,
                onRefresh = pager::refresh,
                indicatorPadding = PaddingValues(top = statusTop),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(videoGridMinWidth),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().seamViewport(gridState, underStatusBar = true),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = statusTop, bottom = bottom + 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        Column {
                            HomeHeader { nav.push(Route.Search()) }
                            tabs()
                        }
                    }
                    items(pager.items.filterNot(Mutes::hides), key = { it.url }) { VideoCard(it) }
                }
            }
        }
    }
}

/** The header over a full-page state, with the same 16dp sides the grid's content padding gives. */
@Composable
private fun HomeMessage(onSearch: () -> Unit, statusTop: Dp, tabs: @Composable () -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = statusTop, start = 16.dp, end = 16.dp)) {
        HomeHeader(onSearch)
        tabs()
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
    }
}

/**
 * The Home tabs as a row of toggle chips: 订阅, 推荐 (signed in), each custom feed, then + (新建
 * Feed). A long press on a feed's chip edits it.
 */
@Composable
private fun HomeTabRow(selected: String, signedIn: Boolean, feeds: List<Feed>, onSelect: (String) -> Unit) {
    val nav = LocalNavigator.current
    val haptic = LocalHapticFeedback.current
    val tabs = buildList {
        add(HomeTabs.Subscriptions to "订阅")
        if (signedIn) add(HomeTabs.Recommended to "推荐")
        feeds.forEach { add(HomeTabs.feed(it.id) to it.name) }
    }
    LazyRow(
        Modifier.padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs, key = { it.first }) { (key, label) ->
            val feedId = key.removePrefix("feed:").takeIf { key.startsWith("feed:") }
            TabChip(
                label = label,
                selected = key == selected,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(key)
                },
                onLongClick = feedId?.let { id ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        nav.push(Route.FeedEditor(id))
                    }
                },
            )
        }
        if (FeedStore.canCreate) {
            item(key = "add") {
                IconButton(onClick = { nav.push(Route.FeedEditor()) }) { Icon(YoinSymbols.Add, "新建 Feed") }
            }
        }
    }
}

/** A Home tab: a pill, filled with the secondary container when selected (the shape of a toggle button). */
@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh, YoinMotion.defaultEffectsSpec(), label = "tabChip")
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(YoinShapeTokens.Full)
            .background(container)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** The app's name and the search pill. */
@Composable
private fun HomeHeader(onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileButton()
        Text("YouTube", style = MaterialTheme.typography.titleLarge)
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .clip(ContinuousRoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onSearch)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(YoinSymbols.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("搜索", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
