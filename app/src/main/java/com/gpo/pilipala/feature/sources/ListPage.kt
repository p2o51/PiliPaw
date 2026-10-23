package com.gpo.pilipala.feature.sources

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.library.LibraryScaffold
import com.gpo.pilipala.feature.library.QueuePlayButtons
import com.gpo.pilipala.feature.library.RowGridMinCell
import com.gpo.pilipala.feature.library.SearchField
import com.gpo.pilipala.feature.library.librarySeam
import com.gpo.pilipala.feature.videomenu.VideoCardMenu
import com.gpo.pilipala.feature.videomenu.VideoMenuAction
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.yt.feature.library.LibItem
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.yoin.symbols.YoinSymbols

/** How a list page can order its videos: a key, its label, and the order itself. */
internal class ListOrder(val key: String, val label: String, val sort: (List<LibItem>) -> List<LibItem>)

/** A Mix: as added, newest additions first, longest first. */
internal val MixOrders = listOf(
    ListOrder("added", "加入顺序") { it },
    ListOrder("recent", "最近加入") { l -> l.sortedByDescending { it.at } },
    ListOrder("long", "时长最长") { l -> l.sortedByDescending { it.durationSec } },
)

/** A YouTube playlist: its own order, turned round, longest first. */
internal val PlaylistOrders = listOf(
    ListOrder("default", "默认顺序") { it },
    ListOrder("reverse", "倒序") { it.asReversed() },
    ListOrder("long", "时长最长") { l -> l.sortedByDescending { it.durationSec } },
)

/**
 * A list of videos laid out as a 收藏夹 is (FavDetailScreen): 搜索 and 排序 in the bar, the
 * page's own [head] then 播放全部 / 随机播放 with 仅听声音, and the videos as the folder's row
 * cards, in columns on a wide window. The list plays through as a Mix does (MixPlayback, B站 and
 * YouTube alike), from a tapped card on, in the order and search on screen. A long press opens
 * the video card menu with 单集播放 and the page's [extras]. [items] null: still loading.
 */
@Composable
internal fun VideoListPage(
    id: String,
    title: String,
    subtitle: String?,
    items: List<LibItem>?,
    orders: List<ListOrder>,
    error: String? = null,
    emptyLabel: String = "还没有视频",
    actions: @Composable RowScope.() -> Unit = {},
    head: @Composable () -> Unit = {},
    extras: (LibItem) -> List<VideoMenuAction> = { emptyList() },
) {
    val nav = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var orderKey by rememberSaveable { mutableStateOf(orders.first().key) }
    var sortOpen by remember { mutableStateOf(false) }
    var listen by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf<LibItem?>(null) }
    val order = orders.firstOrNull { it.key == orderKey } ?: orders.first()
    val q = query.trim()
    val shown = remember(items, order, q) {
        items?.let { l ->
            order.sort(l).filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.channelName.contains(q, ignoreCase = true) }
        }
    }
    val playFrom: (Int, Boolean) -> Unit = { start, shuffle ->
        val l = shown.orEmpty()
        // As the list stands (no order, no search): a Mix follows it live, adds and removals too.
        if (id.startsWith(MixIdPrefix) && order === orders.first() && q.isEmpty()) {
            val mixId = id.removePrefix(MixIdPrefix)
            if (shuffle) MixPlayback.shuffle(mixId, listen) else MixPlayback.start(mixId, start, listen)
        } else {
            MixPlayback.play(id.removePrefix(MixIdPrefix), title, l, start, listen, shuffle)
        }
    }
    val gridState = rememberLazyGridState()

    LibraryScaffold(
        title = title,
        subtitle = subtitle,
        actions = {
            IconButton(
                onClick = {
                    searching = !searching
                    if (!searching) query = ""
                },
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(if (searching) YoinSymbols.Close else YoinSymbols.Search, contentDescription = "搜索") }
            Box {
                IconButton(onClick = { sortOpen = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(YoinSymbols.Sort, contentDescription = "排序")
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    orders.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o.label) },
                            onClick = {
                                sortOpen = false
                                orderKey = o.key
                            },
                            trailingIcon = if (o === order) {
                                { Icon(YoinSymbols.Check, null) }
                            } else null,
                        )
                    }
                }
            }
            actions()
        },
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = searching,
                enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
                exit = shrinkVertically(YoinMotion.fastSpatialSpec()) + fadeOut(YoinMotion.fastEffectsSpec()),
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            ) {
                SearchField(query, onChange = { query = it }, placeholder = "搜索")
            }
            val inner = if (searching) PaddingValues(bottom = padding.calculateBottomPadding()) else padding
            when {
                error != null && items == null -> ErrorState(error, Modifier.padding(inner))
                shown == null -> LoadingState(Modifier.padding(inner))
                items.isNullOrEmpty() -> EmptyState(emptyLabel, Modifier.padding(inner), icon = YoinSymbols.Playlist)
                else -> {
                    val listPadding = PaddingValues(top = inner.calculateTopPadding() + 4.dp, bottom = inner.calculateBottomPadding() + 24.dp)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(RowGridMinCell),
                        state = gridState,
                        modifier = Modifier.librarySeam(gridState, listPadding),
                        contentPadding = listPadding,
                    ) {
                        item(key = "__head", span = { GridItemSpan(maxLineSpan) }) { head() }
                        item(key = "__play", span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                QueuePlayButtons(
                                    loading = false,
                                    listening = listen,
                                    onListeningChange = { listen = it },
                                    onPlay = { playFrom(0, false) },
                                    onShuffle = { playFrom(0, true) },
                                )
                            }
                        }
                        if (shown.isEmpty()) {
                            item(key = "__none", span = { GridItemSpan(maxLineSpan) }) {
                                EmptyState("无结果", icon = YoinSymbols.Search)
                            }
                        }
                        itemsIndexed(shown, key = { _, it -> it.key }) { i, item ->
                            val playing = MixPlayback.listId == id.removePrefix(MixIdPrefix) && MixPlayback.current?.key == item.key
                            VideoCardH(
                                item.toCard(),
                                modifier = Modifier.animateItem(),
                                onClick = { playFrom(i, false) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menu = item
                                },
                                trailing = if (playing) {
                                    {
                                        Icon(
                                            com.gpo.yoin.symbols.rememberEqualizerSymbolPainter(playing = true),
                                            contentDescription = "正在播放",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
    }

    menu?.let { item ->
        VideoCardMenu(
            item.toCard(),
            extras = buildList {
                add(
                    VideoMenuAction("单集播放", YoinSymbols.PlayFilled) {
                        if (MixItems.isBili(item)) {
                            nav.push(Route.Video(bvid = item.key.removePrefix("bili:"), cover = item.thumbnail, title = item.title, audioOnly = listen))
                        } else {
                            Player.play(item.url, item.toVideo())
                            Player.changeAudioOnly(listen)
                        }
                    },
                )
                addAll(extras(item))
            },
            onDismiss = { menu = null },
        )
    }
}

/** [VideoListPage]'s id for a Mix (its list id after it): it may then follow the list live. */
internal const val MixIdPrefix = "mix:"

/** A Mix or playlist video as PiliPaw's card: a B站 video opens its page, a YouTube one plays over it. */
internal fun LibItem.toCard(): VideoItem = if (MixItems.isBili(this)) {
    VideoItem(
        bvid = key.removePrefix("bili:"),
        title = title,
        cover = MixItems.secure(thumbnail),
        duration = durationSec,
        ownerName = channelName,
        ownerMid = channelUrl.substringAfterLast('/').toLongOrNull() ?: 0L,
    )
} else {
    VideoItem(
        title = title,
        cover = thumbnail,
        duration = durationSec,
        ownerName = channelName,
        youtubeUrl = url,
        youtubeChannelUrl = channelUrl.ifEmpty { null },
    )
}
