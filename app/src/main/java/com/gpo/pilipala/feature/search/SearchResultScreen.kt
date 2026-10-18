package com.gpo.pilipala.feature.search

import androidx.compose.ui.text.TextRange
import com.gpo.pilipala.ui.components.seam.seamViewport
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.input.setTextAndSelectAll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.feature.feeds.FeedSource
import com.gpo.pilipala.feature.feeds.FeedSourceSheet
import com.gpo.pilipala.feature.feeds.FeedStore
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

/**
 * One result tab: paging + filters. Loads on first [start], not on creation.
 * [initialOrder] is the video tab's starting order (the search's sort).
 */
internal class SearchTabViewModel(
    val keyword: String,
    val tab: SearchTab,
    initialOrder: String? = null,
) : ViewModel() {
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

    var order by mutableStateOf(
        when (tab) {
            SearchTab.Video -> initialOrder?.let(::searchSortOrder) ?: "totalrank"
            SearchTab.Article -> "totalrank"
            else -> ""
        },
    )
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

    /** mids that follow the user back while followed (attribute 6): 互相关注. */
    val mutual = mutableStateMapOf<Long, Boolean>()

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
                .getOrNull()?.let { putRelations(it) }
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

    private fun putRelations(attributes: Map<Long, Int>) {
        attributes.forEach { (mid, a) ->
            following[mid] = a == 1 || a == 2 || a == 6
            mutual[mid] = a == 6
        }
    }

    fun toggleFollow(mid: Long, onError: (String) -> Unit) {
        val now = following[mid] == true
        val wasMutual = mutual[mid] == true
        following[mid] = !now
        if (now) mutual[mid] = false
        viewModelScope.launch {
            runCatching { SearchApi.setFollow(mid, !now) }
                .onSuccess {
                    // Followed someone who follows the user: 互相关注 (read-only relation check).
                    if (!now) {
                        runCatching { SearchApi.relations(listOf(mid)) }.getOrNull()
                            ?.takeIf { following[mid] == true }
                            ?.let { putRelations(it) }
                    }
                }
                .onFailure {
                    following[mid] = now
                    mutual[mid] = wasMutual
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
    val vms = tabs.map { t ->
        viewModel(key = "search_tab_${t.type}") {
            // The video tab starts on the sort the search was submitted with.
            SearchTabViewModel(keyword, t, if (t == SearchTab.Video) SearchOrderHandoff.take(keyword) else null)
        }
    }
    val videoVm = vms[tabs.indexOf(SearchTab.Video)]
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    var feedSheet by rememberSaveable { mutableStateOf(false) }
    val search = rememberPiliSearchState(initialText = keyword, initialOrder = videoVm.order)
    val pageSpec = YoinMotion.defaultSpatialSpec<Float>()

    // The search opens on this page's keyword (all selected) and the video tab's order;
    // folding it away without a submit puts the keyword back.
    LaunchedEffect(search) {
        var last: SearchBarValue? = null
        snapshotFlow { search.bar.targetValue }.collect { v ->
            if (v == SearchBarValue.Expanded && last == SearchBarValue.Collapsed) {
                search.order = searchSortOrder(videoVm.order)
                // The tap that opened it placed a cursor; open on the whole keyword selected.
                search.input.edit { selection = TextRange(0, length) }
            }
            // Folding away without a submit puts this page's keyword back (a submit keeps the
            // typed text through the fold; the branches that stay on this page reset it).
            if (v == SearchBarValue.Collapsed && !search.submitted) search.input.setTextAndSelectAll(keyword)
            last = v
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.surface)
            .statusBarsPadding(),
    ) {
        ResultSearchBar(
            search = search,
            keyword = keyword,
            onSubmit = { request ->
                val target = request.target
                if (target is SearchTarget.Keyword && target.text == keyword) {
                    // Same keyword: the sort lands on this page's video tab.
                    videoVm.selectOrder(request.order)
                    val page = tabs.indexOf(SearchTab.Video)
                    if (pagerState.currentPage != page) scope.launch { pagerState.animateScrollToPage(page, animationSpec = pageSpec) }
                } else {
                    // A new keyword replaces this page; a video or link opens over it, and this
                    // page keeps its own keyword in the field for when it comes back.
                    if (target !is SearchTarget.Keyword) search.input.setTextAndSelectAll(keyword)
                    nav.openSearch(request, replace = target is SearchTarget.Keyword)
                }
            },
            onAddToFeed = { feedSheet = true },
        )
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

    if (feedSheet) {
        // The keyword joins a feed with the video tab's order when that is 综合 or 最新发布.
        FeedSourceSheet(
            keyword = keyword,
            keywordOrder = feedKeywordOrder(videoVm.order),
            onDismiss = { feedSheet = false },
        )
    }
}

/** The page's native search bar: it opens the search again on [keyword]. */
@Composable
private fun ResultSearchBar(
    search: PiliSearchState,
    keyword: String,
    onSubmit: (SearchRequest) -> Unit,
    onAddToFeed: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton()
        Spacer(Modifier.width(8.dp))
        PiliSearchBar(
            state = search,
            hint = keyword,
            onSubmit = onSubmit,
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onAddToFeed, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.LibraryAdd, contentDescription = "添加到 Feed", tint = scheme.onSurfaceVariant)
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
    val feeds by FeedStore.feeds.collectAsState()
    val feedMids = remember(feeds) {
        feeds.flatMap { it.sources }.filterIsInstance<FeedSource.Up>().mapTo(HashSet()) { it.mid }
    }
    var feedUser by remember { mutableStateOf<UserHit?>(null) }
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
        modifier = Modifier.fillMaxSize().seamViewport(gridState),
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
                    mutual = vm.mutual[hit.mid] == true,
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
                    inFeed = hit.mid in feedMids,
                    onAddToFeed = { feedUser = hit },
                )
                is ArticleHit -> ArticleRow(hit, m)
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
            ResultFooter(vm)
        }
    }
    feedUser?.let { hit ->
        FeedSourceSheet(upMid = hit.mid, upName = hit.name, upFace = hit.face, onDismiss = { feedUser = null })
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
