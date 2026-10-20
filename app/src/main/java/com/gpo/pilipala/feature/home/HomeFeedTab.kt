package com.gpo.pilipala.feature.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.feature.feeds.FeedMixer
import com.gpo.pilipala.feature.feeds.FeedSourceStrip
import com.gpo.pilipala.feature.feeds.FeedStore
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.VideoCardL
import com.gpo.pilipala.ui.components.VideoCardV
import com.gpo.pilipala.ui.components.homeSingleColumn
import com.gpo.pilipala.ui.components.homeVideoColumns
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val FeedPageMargin = 16.dp

/**
 * One user-built feed as a home tab. The [FeedMixer] behind the pager starts
 * over on refresh and whenever the feed's sources or 混合 / 最新 change (a
 * rename does not reload); blocked UPs leave the list as the blacklist grows.
 */
internal class CustomFeedViewModel(private val feedId: String) : ViewModel() {
    @Volatile
    private var mixer: FeedMixer? = null

    val feed = FeedPager(viewModelScope, key = { it.feedKey() }) { cursor -> load(cursor) }

    init {
        feed.refresh()
        viewModelScope.launch {
            FeedStore.feeds
                .map { list -> list.firstOrNull { it.id == feedId }?.content }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (it != null) feed.refresh() }
        }
        viewModelScope.launch {
            Blacklist.ensureLoaded()
            Blacklist.mids.collect { blocked -> feed.removeWhere { it.ownerMid > 0 && it.ownerMid in blocked } }
        }
    }

    fun remove(item: VideoItem) {
        val key = item.feedKey()
        feed.removeWhere { it.feedKey() == key }
    }

    private suspend fun load(cursor: String?): Page<VideoItem> {
        val current = FeedStore.get(feedId) ?: return Page(emptyList(), hasMore = false)
        BiliClient.ensureBuvid()
        Blacklist.ensureLoaded()
        val existing = mixer
        val m = if (cursor == null || existing == null) FeedMixer(current.sources, current.mix).also { mixer = it } else existing
        return m.load(reset = cursor == null)
    }
}

/**
 * A feed tab: the sources strip (tap a source to open it, 编辑) over the same
 * VideoCardV grid as 推荐, with pull to refresh, infinite paging and the
 * card long-press sheet.
 */
@Composable
internal fun CustomFeedTab(
    feedId: String,
    state: LazyGridState,
    reselect: Flow<Unit>,
    headerBehavior: TopAppBarScrollBehavior,
) {
    val vm: CustomFeedViewModel = viewModel(key = "home_feed_$feedId") { CustomFeedViewModel(feedId) }
    val feeds by FeedStore.feeds.collectAsState()
    val feed = feeds.firstOrNull { it.id == feedId } ?: return
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf<VideoItem?>(null) }
    val single = homeSingleColumn()
    PagedGrid(
        pager = vm.feed,
        state = state,
        columns = homeVideoColumns(),
        key = { it.feedKey() },
        horizontalSpacing = if (single) 16.dp else 12.dp,
        reselect = reselect,
        headerBehavior = headerBehavior,
        header = {
            item(span = { GridItemSpan(maxLineSpan) }, key = "feedSources", contentType = "sources") {
                FeedSourceStrip(
                    feed = feed,
                    modifier = Modifier
                        .animateItem(fadeInSpec = YoinMotion.defaultEffectsSpec(), placementSpec = YoinMotion.defaultSpatialSpec(), fadeOutSpec = YoinMotion.fastEffectsSpec())
                        .bleedHorizontally(FeedPageMargin)
                        .padding(bottom = 8.dp),
                    contentPadding = PaddingValues(horizontal = FeedPageMargin),
                )
            }
        },
    ) { _, item ->
        val onLong = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menu = item
        }
        if (single) VideoCardL(item, feedItemMotion(), onLongClick = onLong) else VideoCardV(item, feedItemMotion(), onLongClick = onLong)
    }
    menu?.let { item ->
        FeedCardSheet(item = item, onDismiss = { menu = null }, onRemove = { vm.remove(item) })
    }
}
