package com.gpo.pilipala.yt.feature.account

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.feature.library.RowGridMinCell
import com.gpo.pilipala.feature.sources.toCard
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.extractor.YtFeeds
import com.gpo.pilipala.yt.feature.library.YtLibraryScaffold
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.yt.ui.components.LoadMoreEffect
import com.gpo.pilipala.yt.ui.components.Pager
import com.gpo.yoin.symbols.YoinSymbols
/** The account lists 我的 opens. */
enum class AccountList(val title: String, val feed: YtFeeds.Feed) {
    WatchLater("稍后再看", YtFeeds.Feed.WatchLater),
    Liked("赞过的视频", YtFeeds.Feed.Liked),
}

class AccountVideosViewModel(list: AccountList) : ViewModel() {
    val pager = Pager<VideoItem>(viewModelScope, dedupKey = { it.url }) { cursor -> YtFeeds.videos(list.feed, cursor) }

    init { pager.refresh() }
}

/**
 * 稍后再看 / 赞过的视频: the account's list as PiliPaw's row cards in a grid, page by page, under
 * the large bar; loading, error and empty states cross-fade.
 */
@Composable
fun AccountVideosScreen(route: Route.AccountVideos) {
    val list = AccountList.valueOf(route.list)
    val vm = viewModel(key = route.list) { AccountVideosViewModel(list) }
    val pager = vm.pager
    val grid = rememberLazyGridState()
    LoadMoreEffect(grid) { pager.loadMore() }
    val state = when {
        pager.items.isNotEmpty() -> 3
        pager.initialLoading -> 0
        pager.error != null -> 1
        else -> 2
    }
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    YtLibraryScaffold(title = list.title) { padding ->
        AnimatedContent(
            state,
            transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
            label = "accountVideos",
        ) { s ->
            when (s) {
                0 -> LoadingState(Modifier.padding(padding))
                1 -> ErrorState(pager.error.orEmpty(), Modifier.padding(padding), onRetry = pager::refresh)
                2 -> if (list == AccountList.WatchLater) {
                    EmptyState("稍后再看为空", Modifier.padding(padding), icon = YoinSymbols.WatchLater)
                } else {
                    EmptyState("暂无赞过的视频", Modifier.padding(padding), icon = YoinSymbols.ThumbUp)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(RowGridMinCell),
                    state = grid,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding,
                ) {
                    items(pager.items, key = { it.url }) { v ->
                        VideoCardH(v.toCard(), Modifier.animateItem(), onClick = { Player.play(v.url, v) })
                    }
                    if (pager.isLoadingMore) item(key = "more", span = { GridItemSpan(maxLineSpan) }) { LoadingFooter() }
                }
            }
        }
    }
}
