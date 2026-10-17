package com.gpo.pilipala.feature.rank

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.feature.home.Blacklist
import com.gpo.pilipala.feature.home.CollapsingHeader
import com.gpo.pilipala.feature.home.FeedCardSheet
import com.gpo.pilipala.feature.home.SegmentTabs
import com.gpo.pilipala.feature.home.mainTabBottomInset
import com.gpo.pilipala.feature.home.rememberHeaderScrollBehavior
import com.gpo.pilipala.feature.home.toArchiveItem
import com.gpo.pilipala.feature.home.withoutBlocked
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.shell.LocalTabReselect
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Zones as in Flutter's rank_type.dart (rid per tab). */
private data class RankZone(val label: String, val rid: Int)

private val RankZones = listOf(
    RankZone("全站", 0),
    RankZone("动画", 1005),
    RankZone("音乐", 1003),
    RankZone("舞蹈", 1004),
    RankZone("游戏", 1008),
    RankZone("知识", 1010),
    RankZone("科技", 1012),
    RankZone("运动", 1018),
    RankZone("汽车", 1013),
    RankZone("美食", 1020),
    RankZone("动物圈", 1024),
    RankZone("鬼畜", 1007),
    RankZone("时尚", 1014),
    RankZone("娱乐", 1002),
    RankZone("影视", 1001),
)

@Composable
fun RankScreen() {
    val pagerState = rememberPagerState { RankZones.size }
    val scope = rememberCoroutineScope()
    val behavior = rememberHeaderScrollBehavior()
    val pageSpec = YoinMotion.defaultSpatialSpec<Float>()
    val shellReselect = LocalTabReselect.current
    val pageReselects = remember { MutableSharedFlow<Int>(extraBufferCapacity = 1) }
    LaunchedEffect(shellReselect) { shellReselect.collect { pageReselects.tryEmit(pagerState.currentPage) } }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // The page spans the window, so its own width picks the arrangement.
        val layout = rankLayoutFor(maxWidth)
        val margin = layout.margin
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .nestedScroll(behavior.nestedScrollConnection),
        ) {
            CollapsingHeader(behavior) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = margin + 4.dp, end = margin + 4.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("排行榜", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            SegmentTabs(
                labels = RankZones.map { it.label },
                selected = pagerState.targetPage,
                onSelect = { i ->
                    if (i == pagerState.currentPage && i == pagerState.targetPage) {
                        pageReselects.tryEmit(i)
                    } else {
                        scope.launch { pagerState.animateScrollToPage(i, animationSpec = pageSpec) }
                    }
                },
                horizontalPadding = margin,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                key = { RankZones[it].rid },
            ) { page ->
                val reselect = remember(page) { pageReselects.filter { it == page }.map { } }
                RankZonePage(RankZones[page].rid, layout, reselect, behavior)
            }
        }
    }
}

// ── Data ────────────────────────────────────────────────────────────────────

private class RankZoneViewModel(private val rid: Int) : ViewModel() {
    var items by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages
    private var job: Job? = null

    init {
        load()
        viewModelScope.launch {
            Blacklist.ensureLoaded()
            Blacklist.mids.collect { blocked ->
                if (items.any { it.ownerMid in blocked }) items = items.filterNot { it.ownerMid in blocked }
            }
        }
    }

    fun remove(item: VideoItem) {
        items = items.filterNot { it.bvid == item.bvid }
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            if (items.isEmpty()) loading = true else refreshing = true
            error = null
            try {
                val data = BiliClient.getData("/x/web-interface/ranking/v2", mapOf("rid" to rid, "type" to "all")).asObj
                items = data.arr("list").objects().map { it.toArchiveItem() }.filter { it.bvid.isNotEmpty() }.distinctBy { it.bvid }
                    .withoutBlocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (items.isEmpty()) error = e.friendlyMessage() else _messages.tryEmit(e.friendlyMessage())
            } finally {
                loading = false
                refreshing = false
            }
        }
    }
}

// ── Page ────────────────────────────────────────────────────────────────────

private enum class RankPhase { Loading, Error, Empty, Content }

@Composable
private fun RankZonePage(
    rid: Int,
    layout: RankLayout,
    reselect: Flow<Unit>,
    headerBehavior: TopAppBarScrollBehavior,
) {
    val vm: RankZoneViewModel = viewModel(key = "rank_zone_$rid") { RankZoneViewModel(rid) }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    LaunchedEffect(vm) { vm.messages.collect { toast(it) } }
    val listState = rememberLazyGridState()
    val podiumScroll = rememberScrollState()
    RankReselectEffect(reselect, listState, podiumScroll, headerBehavior, vm::load)
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf<VideoItem?>(null) }
    menu?.let { item ->
        FeedCardSheet(item = item, onDismiss = { menu = null }, onRemove = { vm.remove(item) })
    }
    val bottom = mainTabBottomInset()
    val enterSpec = YoinMotion.defaultEffectsSpec<Float>()
    val exitSpec = YoinMotion.fastEffectsSpec<Float>()
    val phase = when {
        vm.items.isEmpty() && vm.loading -> RankPhase.Loading
        vm.items.isEmpty() && vm.error != null -> RankPhase.Error
        vm.items.isEmpty() -> RankPhase.Empty
        else -> RankPhase.Content
    }
    AnimatedContent(
        targetState = phase,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { fadeIn(enterSpec).togetherWith(fadeOut(exitSpec)) },
        label = "rankPhase",
    ) { p ->
        when (p) {
            RankPhase.Loading -> LoadingState(Modifier.padding(bottom = bottom))
            RankPhase.Error -> ErrorState(vm.error.orEmpty(), Modifier.padding(bottom = bottom), onRetry = vm::load)
            RankPhase.Empty -> EmptyState(modifier = Modifier.padding(bottom = bottom))
            RankPhase.Content -> RefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::load, modifier = Modifier.fillMaxSize()) {
                RankBoard(
                    items = vm.items,
                    layout = layout,
                    listState = listState,
                    podiumScroll = podiumScroll,
                    bottomPadding = bottom + 16.dp,
                    onOpen = { nav.push(it.rankRoute()) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = it
                    },
                )
            }
        }
    }
}

/**
 * Reselecting the zone (its chip, or the shell's tab): the header and both
 * panes come back to the top on the spatial spring, or the zone refreshes
 * when everything is already there.
 */
@Composable
private fun RankReselectEffect(
    events: Flow<Unit>,
    list: LazyGridState,
    podium: ScrollState,
    header: TopAppBarScrollBehavior,
    onRefresh: () -> Unit,
) {
    val refresh by rememberUpdatedState(onRefresh)
    val spring = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(events, list, podium, header) {
        events.collect {
            val atTop = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 && podium.value == 0
            if (atTop) {
                refresh()
            } else {
                launch {
                    val bar = header.state
                    animate(bar.heightOffset, 0f, animationSpec = spring) { v, _ -> bar.heightOffset = v }
                }
                if (podium.value != 0) launch { podium.animateScrollTo(0, spring) }
                if (list.firstVisibleItemIndex > 12) list.scrollToItem(12)
                list.animateScrollToItem(0)
            }
        }
    }
}
