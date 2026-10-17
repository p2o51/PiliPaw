package com.gpo.pilipala.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.LocalBottomBarInset
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Infinite feed state for the home tabs. Unlike the shared Pager it separates
 * the first-page error (full-page ErrorState) from a load-more error (retry
 * footer), keeps user refreshes distinct from the initial load, and reports a
 * failed refresh over existing items as a one-shot message.
 */
@Stable
internal class FeedPager<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any,
    private val loader: suspend (cursor: String?) -> Page<T>,
) {
    var items by mutableStateOf<List<T>>(emptyList())
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

    /** Bumps after every load-more so a still-visible footer asks again. */
    var version by mutableIntStateOf(0)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var cursor: String? = null
    private var job: Job? = null
    private var emptyStreak = 0

    fun refresh() {
        job?.cancel()
        job = scope.launch {
            if (items.isEmpty()) initialLoading = true else refreshing = true
            error = null
            moreError = null
            try {
                val page = loader(null)
                items = page.items.distinctBy(key)
                hasMore = page.hasMore
                cursor = page.nextCursor
                emptyStreak = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (items.isEmpty()) error = e.friendlyMessage() else _messages.tryEmit(e.friendlyMessage())
            } finally {
                initialLoading = false
                refreshing = false
            }
        }
    }

    fun loadMore() {
        if (job?.isActive == true || !hasMore || items.isEmpty() || moreError != null) return
        job = scope.launch {
            loadingMore = true
            try {
                val page = loader(cursor)
                val merged = (items + page.items).distinctBy(key)
                val added = merged.size - items.size
                items = merged
                cursor = page.nextCursor
                emptyStreak = if (added == 0) emptyStreak + 1 else 0
                hasMore = page.hasMore && emptyStreak < 3
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                moreError = e.friendlyMessage()
            } finally {
                loadingMore = false
                version++
            }
        }
    }

    fun retryMore() {
        moreError = null
        loadMore()
    }

    /** Drops matching items locally (long-press actions, blacklist updates). */
    fun removeWhere(predicate: (T) -> Boolean) {
        val kept = items.filterNot(predicate)
        if (kept.size != items.size) items = kept
    }
}

/** Feed items fade in, slide when neighbours leave, and fade out when removed. */
@Composable
internal fun LazyGridItemScope.feedItemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = YoinMotion.defaultEffectsSpec(),
    placementSpec = YoinMotion.defaultSpatialSpec(),
    fadeOutSpec = YoinMotion.fastEffectsSpec(),
)

/** Floating bar + gesture/navigation bar: what a main-tab list must clear at the bottom. */
@Composable
internal fun mainTabBottomInset(): Dp =
    LocalBottomBarInset.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Lets a full-span item ignore the grid's horizontal content padding (bleeding rows). */
internal fun Modifier.bleedHorizontally(padding: Dp): Modifier = layout { measurable, constraints ->
    val extra = padding.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth + extra).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
        ),
    )
    layout(constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: placeable.width, placeable.height) {
        placeable.place(-extra / 2, 0)
    }
}

private enum class FeedPhase { Loading, Error, Content }

/**
 * Loading / error / content for a [FeedPager], with pull-to-refresh, a
 * load-more footer and an optional full-span header. Phase changes
 * cross-fade on the effects spring.
 */
@Composable
internal fun <T> PagedGrid(
    pager: FeedPager<T>,
    state: LazyGridState,
    columns: Int,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    topPadding: Dp = 8.dp,
    horizontalSpacing: Dp = 12.dp,
    verticalSpacing: Dp = 4.dp,
    emptyLabel: String = "空空如也",
    onRefresh: () -> Unit = { pager.refresh() },
    reselect: Flow<Unit>? = null,
    headerBehavior: TopAppBarScrollBehavior? = null,
    header: (LazyGridScope.() -> Unit)? = null,
    itemContent: @Composable LazyGridItemScope.(index: Int, item: T) -> Unit,
) {
    val toast = rememberToast()
    LaunchedEffect(pager) { pager.messages.collect { toast(it) } }
    if (reselect != null) ReselectEffect(reselect, state, headerBehavior, onRefresh)
    val phase = when {
        pager.items.isEmpty() && pager.initialLoading -> FeedPhase.Loading
        pager.items.isEmpty() && pager.error != null -> FeedPhase.Error
        else -> FeedPhase.Content
    }
    val bottom = mainTabBottomInset()
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = phase,
        modifier = modifier.fillMaxSize(),
        transitionSpec = { fadeIn(enter).togetherWith(fadeOut(exit)) },
        label = "feedPhase",
    ) { p ->
        when (p) {
            FeedPhase.Loading -> LoadingState(Modifier.padding(bottom = bottom))
            FeedPhase.Error -> ErrorState(pager.error.orEmpty(), Modifier.padding(bottom = bottom), onRetry = onRefresh)
            FeedPhase.Content -> {
                LoadMoreEffect(state) { pager.loadMore() }
                RefreshBox(isRefreshing = pager.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = horizontalPadding,
                            end = horizontalPadding,
                            top = topPadding,
                            bottom = bottom + 8.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
                        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                    ) {
                        header?.invoke(this)
                        if (pager.items.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "empty", contentType = "empty") {
                                Box(Modifier.fillMaxWidth().height(360.dp)) { EmptyState(emptyLabel) }
                            }
                        } else {
                            itemsIndexed(pager.items, key = { _, it -> key(it) }, contentType = { _, _ -> "item" }) { i, it ->
                                itemContent(i, it)
                            }
                            item(span = { GridItemSpan(maxLineSpan) }, key = "footer", contentType = "footer") {
                                FeedFooter(pager)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> FeedFooter(pager: FeedPager<T>) {
    val moreError = pager.moreError
    when {
        moreError != null -> Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(moreError, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { pager.retryMore() }) { Text("重试") }
        }
        pager.hasMore -> {
            LaunchedEffect(pager.version) { pager.loadMore() }
            LoadingFooter()
        }
        else -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            Text("没有更多了", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}
