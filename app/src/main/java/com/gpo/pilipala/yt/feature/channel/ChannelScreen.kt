package com.gpo.pilipala.yt.feature.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.yt.feature.player.SubscribeButton
import com.gpo.pilipala.yt.feature.player.miniPlayerInset
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.yt.ui.components.ExpressivePageBackground
import com.gpo.pilipala.yt.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.yt.ui.components.PawTopBar
import com.gpo.pilipala.yt.ui.components.Pager
import com.gpo.pilipala.yt.ui.components.ValueLabel
import com.gpo.pilipala.yt.ui.components.VideoCard
import com.gpo.pilipala.yt.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.yt.ui.components.videoGridMinWidth
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.yt.ui.theme.CoverTheme
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.channel.ChannelInfo

class ChannelViewModel(private val url: String) : ViewModel() {
    var info by mutableStateOf<ChannelInfo?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** One ChannelInfo request feeds both the header and the first page of uploads. */
    private var request: Deferred<ChannelInfo>? = null

    val videos = Pager<VideoItem>(viewModelScope, dedupKey = { it.url }) { cursor ->
        if (cursor == null) Yt.channelFirstPage(fetch().await()) else Yt.channelVideos(url, cursor)
    }

    init { load() }

    private fun fetch(): Deferred<ChannelInfo> =
        request?.takeUnless { it.isCompleted && it.getCompletionExceptionOrNull() != null }
            ?: viewModelScope.async { Yt.channel(url) }.also { request = it }

    fun load() {
        error = null
        if (request?.isCompleted == true && request?.getCompletionExceptionOrNull() != null) request = null
        val r = fetch()
        viewModelScope.launch {
            runCatching { r.await() }
                .onSuccess { info = it }
                .onFailure { error = it.friendlyMessage() }
        }
        videos.refresh()
    }
}

/**
 * A channel: banner, avatar, name and subscribers, 订阅 (local), then its uploads as cards.
 * The page takes its colours from the avatar (CoverTheme), as PiliPaw's UP space does.
 */
@Composable
fun ChannelScreen(route: Route.Channel) {
    val vm = viewModel(key = route.url) { ChannelViewModel(route.url) }
    val info = vm.info
    val avatar = info?.avatarUrl ?: route.avatar
    val name = info?.name ?: route.name
    CoverTheme(avatar) {
        ExpressivePageBackground(Modifier.fillMaxSize()) {
            Scaffold(containerColor = Color.Transparent, topBar = { PawTopBar(name, containerColor = Color.Transparent) }) { inner ->
                val pager = vm.videos
                val grid = rememberLazyGridState()
                LoadMoreEffect(grid) { pager.loadMore() }
                when {
                    vm.error != null && info == null -> ErrorState(vm.error.orEmpty(), Modifier.padding(inner), onRetry = vm::load)
                    info == null && pager.initialLoading -> LoadingState(Modifier.padding(inner))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(videoGridMinWidth),
                        state = grid,
                        modifier = Modifier.fillMaxSize().padding(top = inner.calculateTopPadding()).seamViewport(grid, pageColor = Color.Transparent),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = inner.calculateBottomPadding() + miniPlayerInset() + 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                            Header(route, info)
                        }
                        items(pager.items, key = { it.url }) { VideoCard(it, showChannel = false) }
                        if (pager.isLoadingMore) item(key = "more", span = { GridItemSpan(maxLineSpan) }) { LoadingFooter() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(route: Route.Channel, info: ChannelInfo?) {
    val channel = ChannelItem(
        // The canonical /channel/UC… once loaded (a link may be an @handle): what subscriptions key on.
        url = info?.url?.takeIf { it.contains("/channel/") } ?: route.url,
        name = info?.name ?: route.name,
        avatar = info?.avatarUrl ?: route.avatar,
        subscribers = info?.subscriberCount ?: -1,
        videos = -1,
        verified = info?.isVerified ?: false,
    )
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        info?.bannerUrl?.takeIf { it.isNotEmpty() }?.let { banner ->
            NetImage(banner, Modifier.fillMaxWidth().aspectRatio(6.2f), shape = ContinuousRoundedCornerShape(16.dp), thumbWidth = null)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(channel.avatar, size = 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(channel.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (channel.subscribers >= 0) ValueLabel(Fmt.count(channel.subscribers), "订阅者")
            }
            SubscribeButton(channel)
        }
        info?.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
