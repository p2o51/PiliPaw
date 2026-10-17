package com.gpo.pilipala.feature.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.BackButton
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.VideoCardV
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.videoGridColumns
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── ViewModels ──────────────────────────────────────────────────────────────

internal class SearchCountsViewModel(keyword: String) : ViewModel() {
    var counts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    init {
        viewModelScope.launch {
            runCatching { SearchApi.counts(keyword) }.onSuccess { counts = it }
        }
    }
}

/** One result tab: paging + filters. Loads on first [start], not on creation. */
internal class SearchTabViewModel(val keyword: String, val tab: SearchTab) : ViewModel() {
    var items by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var initialLoading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var moreError by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(true)
        private set

    var order by mutableStateOf(if (tab == SearchTab.Video || tab == SearchTab.Article) "totalrank" else "")
        private set
    var duration by mutableIntStateOf(0)
        private set
    var tids by mutableIntStateOf(-1)
        private set

    /** Bumped whenever the list should return to its top (new results, tab re-tap). */
    var topSignal by mutableIntStateOf(0)
        private set

    /** mid → following, for the 用户 tab. */
    val following = mutableStateMapOf<Long, Boolean>()

    private var page = 0
    private var job: Job? = null
    private var generation = 0
    private var started = false

    val filtered: Boolean get() = duration != 0 || tids != -1

    fun start() {
        if (started) return
        started = true
        refresh()
    }

    fun refresh() {
        job?.cancel()
        loadingMore = false
        val gen = ++generation
        job = viewModelScope.launch {
            refreshing = true
            error = null
            moreError = null
            try {
                val p = load(1)
                items = p.items.distinctBy { it.key }
                page = 1
                hasMore = p.numPages > 1 && p.items.isNotEmpty()
                topSignal++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.friendlyMessage()
            } finally {
                if (gen == generation) {
                    refreshing = false
                    initialLoading = false
                }
            }
        }
    }

    fun loadMore() {
        if (refreshing || loadingMore || !hasMore || items.isEmpty()) return
        val gen = generation
        job = viewModelScope.launch {
            loadingMore = true
            moreError = null
            try {
                val p = load(page + 1)
                items = (items + p.items).distinctBy { it.key }
                page = p.page
                hasMore = p.page < p.numPages && p.items.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                moreError = e.friendlyMessage()
            } finally {
                if (gen == generation) loadingMore = false
            }
        }
    }

    /** Parsing and mapping a result page (tag stripping, highlights) runs off the main thread. */
    private suspend fun load(p: Int): SearchPage {
        val order = order.ifEmpty { null }
        val duration = duration
        val tids = tids
        val res = withContext(Dispatchers.Default) { SearchApi.search(tab, keyword, p, order, duration, tids) }
        if (tab == SearchTab.User && BiliClient.isLoggedIn) {
            val mids = res.items.filterIsInstance<UserHit>().map { it.mid }
            runCatching { withContext(Dispatchers.Default) { SearchApi.relations(mids) } }
                .getOrNull()?.let { following.putAll(it) }
        }
        return res
    }

    fun requestTop() {
        topSignal++
    }

    fun selectOrder(o: String) {
        if (o == order) return
        order = o
        refresh()
    }

    fun selectDuration(d: Int) {
        if (d == duration) return
        duration = d
        refresh()
    }

    fun selectTids(t: Int) {
        if (t == tids) return
        tids = t
        refresh()
    }

    fun resetFilters() {
        if (!filtered) return
        duration = 0
        tids = -1
        refresh()
    }

    fun toggleFollow(mid: Long, onError: (String) -> Unit) {
        val now = following[mid] == true
        following[mid] = !now
        viewModelScope.launch {
            runCatching { SearchApi.setFollow(mid, !now) }.onFailure {
                following[mid] = now
                onError(it.friendlyMessage())
            }
        }
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun SearchResultScreen(route: Route.SearchResult) {
    val nav = LocalNavigator.current
    val keyword = route.keyword
    val tabs = SearchTab.entries
    val countsVm = viewModel { SearchCountsViewModel(keyword) }
    val vms = tabs.map { t -> viewModel(key = "search_tab_${t.type}") { SearchTabViewModel(keyword, t) } }
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.surface)
            .statusBarsPadding(),
    ) {
        ResultSearchBar(keyword) {
            SearchHandoff.offer(keyword)
            nav.replace(Route.Search(keyword))
        }
        PrimaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = scheme.surface,
            contentColor = scheme.onSurface,
            edgePadding = 16.dp,
            divider = {},
            indicator = {
                TabRowDefaults.PrimaryIndicator(
                    Modifier.tabIndicatorOffset(pagerState.currentPage, matchContentSize = true),
                    width = Dp.Unspecified,
                )
            },
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = pagerState.currentPage == i,
                    onClick = {
                        if (pagerState.currentPage == i) {
                            vms[i].requestTop()
                        } else {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            scope.launch { pagerState.animateScrollToPage(i) }
                        }
                    },
                    selectedContentColor = scheme.primary,
                    unselectedContentColor = scheme.onSurfaceVariant,
                    text = { TabLabel(t.label, countsVm.counts[t.type]) },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            key = { tabs[it].type },
        ) { page ->
            SearchTabPage(vms[page])
        }
    }
}

@Composable
private fun ResultSearchBar(keyword: String, onEdit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton()
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .weight(1f, fill = false)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .clickable(onClick = onEdit)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(YoinSymbols.Search, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                keyword,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabLabel(label: String, count: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        AnimatedVisibility(
            visible = count != null && count > 0,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandHorizontally(YoinMotion.defaultSpatialSpec()),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkHorizontally(YoinMotion.fastSpatialSpec()),
        ) {
            val n = count ?: 0
            Text(
                if (n >= 1000) "999+" else n.toString(),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class TabState { Loading, Error, Empty, Content }

@Composable
private fun SearchTabPage(vm: SearchTabViewModel) {
    LaunchedEffect(vm) { vm.start() }
    val toast = rememberToast()
    LaunchedEffect(vm.error) {
        val e = vm.error
        if (e != null && vm.items.isNotEmpty()) toast(e)
    }
    Column(Modifier.fillMaxSize()) {
        when (vm.tab) {
            SearchTab.Video -> VideoFilterBar(vm)
            SearchTab.Article -> ConnectedChoiceRow(
                choices = ArticleOrders,
                selected = vm.order,
                onSelect = vm::selectOrder,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            else -> Unit
        }
        val state = when {
            vm.items.isNotEmpty() -> TabState.Content
            vm.initialLoading || vm.refreshing -> TabState.Loading
            vm.error != null -> TabState.Error
            else -> TabState.Empty
        }
        AnimatedContent(
            targetState = state,
            transitionSpec = fadeThrough(),
            modifier = Modifier.weight(1f),
            label = "searchTabState",
        ) { s ->
            when (s) {
                TabState.Loading -> LoadingState()
                TabState.Error -> ErrorState(vm.error.orEmpty(), onRetry = vm::refresh)
                TabState.Empty -> EmptyState("无结果", icon = YoinSymbols.Search)
                TabState.Content -> RefreshBox(
                    isRefreshing = vm.refreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    ResultGrid(vm)
                }
            }
        }
    }
}

@Composable
private fun ResultGrid(vm: SearchTabViewModel) {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val wc = widthClass()
    val gridState = rememberLazyGridState()
    var seenTop by rememberSaveable { mutableIntStateOf(vm.topSignal) }
    LaunchedEffect(vm.topSignal) {
        if (vm.topSignal != seenTop) {
            seenTop = vm.topSignal
            if (gridState.firstVisibleItemIndex > 30) gridState.scrollToItem(0) else gridState.animateScrollToItem(0)
        }
    }
    LoadMoreEffect(gridState) { vm.loadMore() }

    val compactVideo = vm.tab == SearchTab.Video && wc == WidthClass.Compact
    val cols = videoGridColumns()
    val columns = when (vm.tab) {
        SearchTab.Video -> if (compactVideo) GridCells.Fixed(1) else GridCells.Fixed(cols)
        SearchTab.Live -> GridCells.Fixed(cols)
        SearchTab.Bangumi, SearchTab.Film -> GridCells.Adaptive(360.dp)
        SearchTab.User, SearchTab.Article -> GridCells.Adaptive(380.dp)
    }
    val hPad = when (vm.tab) {
        SearchTab.Video -> if (compactVideo) 0.dp else 16.dp
        SearchTab.User, SearchTab.Article -> 4.dp
        else -> 16.dp
    }
    val vSpace = when {
        compactVideo -> 0.dp
        vm.tab == SearchTab.User || vm.tab == SearchTab.Article -> 2.dp
        else -> 12.dp
    }
    val hSpace = if (vm.tab == SearchTab.User || vm.tab == SearchTab.Article) 4.dp else 12.dp
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyVerticalGrid(
        columns = columns,
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = hPad, end = hPad, top = 4.dp, bottom = navBottom + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(hSpace),
        verticalArrangement = Arrangement.spacedBy(vSpace),
    ) {
        items(vm.items, key = { it.key }, contentType = { it::class.simpleName }) { hit ->
            val m = Modifier.animateItem()
            when (hit) {
                is VideoHit -> if (compactVideo) VideoCardH(hit.item, m) else VideoCardV(hit.item, m)
                is MediaHit -> MediaCard(hit, m)
                is LiveHit -> LiveCard(hit, m)
                is UserHit -> UserRow(
                    hit = hit,
                    following = vm.following[hit.mid] == true,
                    showFollow = hit.mid != BiliClient.mid,
                    onToggleFollow = {
                        if (!BiliClient.isLoggedIn) {
                            nav.push(Route.Login)
                        } else {
                            val on = vm.following[hit.mid] != true
                            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            vm.toggleFollow(hit.mid) { toast(it) }
                        }
                    },
                    modifier = m,
                )
                is ArticleHit -> ArticleRow(hit, m)
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
            ResultFooter(vm)
        }
    }
}

@Composable
private fun ResultFooter(vm: SearchTabViewModel) {
    val state = when {
        vm.loadingMore -> 1
        vm.moreError != null -> 2
        else -> 0
    }
    AnimatedContent(
        targetState = state,
        transitionSpec = fadeThrough(),
        label = "searchFooter",
    ) { s ->
        when (s) {
            1 -> LoadingFooter()
            2 -> Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    vm.moreError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = vm::loadMore) { Text("重试") }
            }
            else -> Spacer(Modifier.fillMaxWidth().height(8.dp))
        }
    }
}

// ── Filters ─────────────────────────────────────────────────────────────────

@Composable
private fun VideoFilterBar(vm: SearchTabViewModel) {
    var sheet by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (vm.filtered) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        YoinMotion.defaultEffectsSpec(),
        label = "filterContainer",
    )
    val content by animateColorAsState(
        if (vm.filtered) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        YoinMotion.defaultEffectsSpec(),
        label = "filterContent",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectedChoiceRow(
            choices = VideoOrders,
            selected = vm.order,
            onSelect = vm::selectOrder,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
        )
        FilledTonalIconButton(
            onClick = { sheet = true },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = container, contentColor = content),
            modifier = Modifier.padding(end = 12.dp),
        ) {
            Icon(YoinSymbols.Filter, contentDescription = "筛选")
        }
    }
    if (sheet) VideoFilterSheet(vm, onDismiss = { sheet = false })
}

@Composable
private fun VideoFilterSheet(vm: SearchTabViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "筛选",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                AnimatedVisibility(
                    visible = vm.filtered,
                    enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                    exit = fadeOut(YoinMotion.fastEffectsSpec()),
                ) {
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.resetFilters()
                    }) { Text("重置") }
                }
            }
            Spacer(Modifier.height(16.dp))
            SheetSection("时长") {
                DurationFilters.forEach { c ->
                    SearchChip(c.label, selected = vm.duration == c.value) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        vm.selectDuration(c.value)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            SheetSection("分区") {
                ZoneFilters.forEach { c ->
                    SearchChip(c.label, selected = vm.tids == c.value) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        vm.selectTids(c.value)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
