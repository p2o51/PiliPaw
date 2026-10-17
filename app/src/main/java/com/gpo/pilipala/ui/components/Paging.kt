package com.gpo.pilipala.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import com.gpo.pilipala.core.net.BiliException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** One page of results. `hasMore = false` stops further loads. */
data class Page<T>(val items: List<T>, val hasMore: Boolean, val nextCursor: String? = null)

/**
 * Minimal paging state for infinite lists. Usage in a ViewModel:
 * ```
 * val pager = Pager(viewModelScope) { cursor -> repo.load(cursor) }   // cursor: null on first page
 * // The loader MUST return Page(nextCursor = …) whenever hasMore (e.g. the next page number as a string).
 * init { pager.refresh() }
 * ```
 * In the UI: `pager.items`, `pager.isRefreshing`, `pager.error`, `LoadMoreEffect(listState) { pager.loadMore() }`.
 */
@Stable
class Pager<T>(
    private val scope: CoroutineScope,
    private val dedupKey: ((T) -> Any)? = null,
    private val loader: suspend (cursor: String?) -> Page<T>,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(true)
        private set
    /** True until the first load finishes (success or failure). */
    var initialLoading by mutableStateOf(true)
        private set

    private var cursor: String? = null
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = scope.launch {
            isRefreshing = true
            error = null
            runCatching { loader(null) }
                .onSuccess { page ->
                    items = dedup(page.items)
                    hasMore = page.hasMore
                    cursor = page.nextCursor
                }
                .onFailure { error = it.friendlyMessage() }
            isRefreshing = false
            initialLoading = false
        }
    }

    fun loadMore() {
        if (isRefreshing || isLoadingMore || !hasMore || error != null && items.isEmpty()) return
        job = scope.launch {
            isLoadingMore = true
            runCatching { loader(cursor) }
                .onSuccess { page ->
                    items = dedup(items + page.items)
                    hasMore = page.hasMore && page.items.isNotEmpty()
                    cursor = page.nextCursor
                }
                .onFailure { error = it.friendlyMessage() }
            isLoadingMore = false
        }
    }

    /** Local edits (remove an item after delete, etc). */
    fun update(transform: (List<T>) -> List<T>) {
        items = transform(items)
    }

    private fun dedup(list: List<T>): List<T> = dedupKey?.let { k -> list.distinctBy(k) } ?: list
}

fun Throwable.friendlyMessage(): String = when (this) {
    is BiliException -> message
    is java.net.UnknownHostException -> "网络不可用"
    is java.net.SocketTimeoutException -> "请求超时"
    else -> message ?: "出错了"
}

/** Calls [onLoadMore] when the last visible item is within [threshold] of the end. */
@Composable
fun LoadMoreEffect(state: LazyListState, threshold: Int = 6, onLoadMore: () -> Unit) {
    val cb by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }.distinctUntilChanged().collect { if (it) cb() }
    }
}

@Composable
fun LoadMoreEffect(state: LazyGridState, threshold: Int = 8, onLoadMore: () -> Unit) {
    val cb by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }.distinctUntilChanged().collect { if (it) cb() }
    }
}

@Composable
fun LoadMoreEffect(state: LazyStaggeredGridState, threshold: Int = 8, onLoadMore: () -> Unit) {
    val cb by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }.distinctUntilChanged().collect { if (it) cb() }
    }
}
