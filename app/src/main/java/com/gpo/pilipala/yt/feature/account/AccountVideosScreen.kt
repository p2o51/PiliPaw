package com.gpo.pilipala.yt.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.extractor.YtFeeds
import com.gpo.pilipala.yt.feature.player.miniPlayerInset
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.yt.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.yt.ui.components.PawTopBar
import com.gpo.pilipala.yt.ui.components.Pager
import com.gpo.pilipala.yt.ui.components.VideoCard
import com.gpo.pilipala.yt.ui.components.videoGridMinWidth
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

/** 稍后再看 / 赞过的视频: the account's list as a video grid, page by page. */
@Composable
fun AccountVideosScreen(route: Route.AccountVideos) {
    val list = AccountList.valueOf(route.list)
    val vm = viewModel(key = route.list) { AccountVideosViewModel(list) }
    val pager = vm.pager
    val nav = LocalNavigator.current
    val grid = rememberLazyGridState()
    LoadMoreEffect(grid) { pager.loadMore() }
    Column(Modifier.fillMaxSize()) {
        PawTopBar(title = list.title, onBack = { nav.pop() })
        when {
            pager.initialLoading -> LoadingState()
            pager.error != null && pager.items.isEmpty() -> ErrorState(pager.error.orEmpty(), onRetry = pager::refresh)
            pager.items.isEmpty() -> EmptyState("还没有视频", icon = YoinSymbols.Library)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(videoGridMinWidth),
                state = grid,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = miniPlayerInset() + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(pager.items, key = { it.url }) { VideoCard(it) }
            }
        }
    }
}
