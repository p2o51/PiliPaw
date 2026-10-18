package com.gpo.pilipala.feature.home

import com.gpo.pilipala.ui.components.ReservedTitleColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.gpo.pilipala.ui.components.VideoItem
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.pageSharedSource
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.CoverBadge
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.VideoCardV
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.videoGridColumns
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.PiliSymbols
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

private val CoverShape = ContinuousRoundedCornerShape(12.dp)
private val PageMargin = 16.dp

// ── 推荐 ────────────────────────────────────────────────────────────────────

@Composable
internal fun RcmdTab(state: LazyGridState, reselect: Flow<Unit>, headerBehavior: TopAppBarScrollBehavior) {
    val vm: RcmdViewModel = viewModel(key = "home_rcmd") { RcmdViewModel() }
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf<VideoItem?>(null) }
    PagedGrid(
        pager = vm.feed,
        state = state,
        columns = videoGridColumns(),
        key = { it.bvid.ifEmpty { it.target?.toString() ?: (it.title + it.cover) } },
        reselect = reselect,
        headerBehavior = headerBehavior,
    ) { _, item ->
        VideoCardV(
            item,
            feedItemMotion(),
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menu = item
            },
        )
    }
    menu?.let { item ->
        val ref = vm.appRef(item)
        FeedCardSheet(
            item = item,
            onDismiss = { menu = null },
            onRemove = { vm.remove(item) },
            dislike = ref?.let { r -> suspend { FeedActions.dislikeApp(r) } },
        )
    }
}

// ── 热门 ────────────────────────────────────────────────────────────────────

@Composable
internal fun HotTab(state: LazyGridState, reselect: Flow<Unit>, headerBehavior: TopAppBarScrollBehavior) {
    val vm: HotViewModel = viewModel(key = "home_hot") { HotViewModel() }
    val compact = widthClass() == WidthClass.Compact
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf<VideoItem?>(null) }
    PagedGrid(
        pager = vm.feed,
        state = state,
        columns = if (compact) 1 else videoGridColumns(),
        key = { it.bvid },
        // VideoCardH carries its own 16dp row padding.
        horizontalPadding = if (compact) 0.dp else PageMargin,
        topPadding = if (compact) 0.dp else 8.dp,
        verticalSpacing = if (compact) 0.dp else 4.dp,
        reselect = reselect,
        headerBehavior = headerBehavior,
    ) { _, item ->
        val onLong = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menu = item
        }
        if (compact) VideoCardH(item, feedItemMotion(), onLongClick = onLong) else VideoCardV(item, feedItemMotion(), onLongClick = onLong)
    }
    menu?.let { item ->
        FeedCardSheet(item = item, onDismiss = { menu = null }, onRemove = { vm.remove(item) })
    }
}

// ── 直播 ────────────────────────────────────────────────────────────────────

@Composable
internal fun LiveTab(state: LazyGridState, reselect: Flow<Unit>, headerBehavior: TopAppBarScrollBehavior) {
    val vm: LiveViewModel = viewModel(key = "home_live") { LiveViewModel() }
    val following = vm.following
    PagedGrid(
        pager = vm.feed,
        state = state,
        columns = videoGridColumns(),
        key = { it.roomId },
        reselect = reselect,
        headerBehavior = headerBehavior,
        header = if (following.isEmpty()) {
            null
        } else {
            {
                item(span = { GridItemSpan(maxLineSpan) }, key = "followTitle", contentType = "title") {
                    SectionTitle("关注", Modifier.animateItem(), trailing = following.size.toString())
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "followRow", contentType = "row") {
                    LiveFollowRow(following, Modifier.animateItem().bleedHorizontally(PageMargin))
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "rcmdTitle", contentType = "title") {
                    SectionTitle("推荐", Modifier.animateItem())
                }
            }
        },
    ) { _, room ->
        LiveCard(room)
    }
}

@Composable
private fun LiveFollowRow(list: List<LiveFollowItem>, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = PageMargin),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.roomId }) { up ->
            Column(
                Modifier
                    .width(76.dp)
                    .clip(YoinContainerShapes.Card)
                    .clickable { nav.push(Route.LiveRoom(up.roomId, up.cover, up.title)) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Avatar(up.face, 56.dp)
                    Text(
                        "直播",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .clip(ContinuousRoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    up.uname,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun LiveCard(room: LiveRoomItem) {
    val nav = LocalNavigator.current
    Column(
        Modifier
            .pageSharedSource(Route.LiveRoom(room.roomId, room.cover, room.title))
            .clip(YoinContainerShapes.Card)
            .clickable { nav.push(Route.LiveRoom(room.roomId, room.cover, room.title)) },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            NetImage(room.cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = 360.dp)
            CoverLabelScrim()
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val watched = room.watched.ifEmpty { if (room.online > 0) Fmt.count(room.online) else "" }
                if (watched.isNotEmpty()) Stat(PiliSymbols.Views, watched, tint = Color.White, self = FameMode.isSelf(room.uid))
                Spacer(Modifier.weight(1f))
                if (room.area.isNotEmpty()) {
                    Text(
                        room.area,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        ReservedTitleColumn(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)) { reserve ->
            Text(
                room.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                minLines = if (reserve) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Avatar(room.face, 18.dp)
                Text(
                    room.uname,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── 番剧 ────────────────────────────────────────────────────────────────────

@Composable
private fun bangumiColumns(): Int = (LocalConfiguration.current.screenWidthDp / 150).coerceIn(3, 8)

@Composable
internal fun BangumiTab(state: LazyGridState, reselect: Flow<Unit>, headerBehavior: TopAppBarScrollBehavior) {
    val vm: BangumiTabViewModel = viewModel(key = "home_bangumi") { BangumiTabViewModel() }
    val follows = vm.follows
    val compact = widthClass() == WidthClass.Compact
    val followWidth = if (compact) 116.dp else 140.dp
    PagedGrid(
        pager = vm.feed,
        state = state,
        columns = bangumiColumns(),
        key = { it.seasonId },
        horizontalSpacing = 10.dp,
        reselect = reselect,
        headerBehavior = headerBehavior,
        header = {
            if (follows != null || vm.followsLoading) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "followTitle", contentType = "title") {
                    SectionTitle("最近追番", Modifier.animateItem(), trailing = follows?.size?.takeIf { it > 0 }?.toString())
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "followRow", contentType = "row") {
                    Box(Modifier.animateItem()) { when {
                        follows.isNullOrEmpty() && vm.followsLoading -> Box(
                            Modifier.fillMaxWidth().height(followWidth * 4 / 3),
                            contentAlignment = Alignment.Center,
                        ) { LoadingIndicator() }
                        follows.isNullOrEmpty() -> Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(YoinContainerShapes.Card)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "还没有追番",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> LazyRow(
                            modifier = Modifier.fillMaxWidth().bleedHorizontally(PageMargin),
                            contentPadding = PaddingValues(horizontal = PageMargin),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(follows, key = { it.seasonId }) { BangumiCard(it, Modifier.width(followWidth), showProgress = true) }
                        }
                    } }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "indexTitle", contentType = "title") {
                SectionTitle("推荐", Modifier.animateItem())
            }
        },
    ) { _, item ->
        BangumiCard(item)
    }
}

@Composable
private fun BangumiCard(item: BangumiItem, modifier: Modifier = Modifier, showProgress: Boolean = false) {
    val nav = LocalNavigator.current
    Column(
        modifier
            .clip(YoinContainerShapes.Card)
            .clickable { nav.push(Route.Bangumi(seasonId = item.seasonId, cover = item.cover)) },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            NetImage(item.cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = 240.dp)
            if (item.badge.isNotEmpty()) CoverBadge(item.badge, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (item.order.isNotEmpty() || item.score.isNotEmpty()) {
                CoverLabelScrim()
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.order.isNotEmpty()) {
                        Text(
                            item.order,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (item.score.isNotEmpty()) {
                        Text(
                            item.score,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = if (showProgress) item.progress.ifEmpty { item.indexShow } else item.indexShow
            if (second.isNotEmpty()) {
                Text(
                    second,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (showProgress && item.progress.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Scrim under the white labels at a cover's bottom edge: a fixed 48dp ramp, so the
 * labels stay legible on bright covers whatever the label row's own height.
 */
@Composable
private fun BoxScope.CoverLabelScrim() {
    Box(
        Modifier
            .matchParentSize()
            .clip(CoverShape)
            .drawWithCache {
                val h = minOf(48.dp.toPx(), size.height)
                val top = size.height - h
                val brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.4f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Black.copy(alpha = 0.7f),
                    startY = top,
                    endY = size.height,
                )
                onDrawBehind { drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, h)) }
            },
    )
}
