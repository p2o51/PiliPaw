package com.gpo.pilipala.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.videomenu.VideoCardMenu
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

internal data class SubInfo(
    val title: String = "",
    val cover: String = "",
    val intro: String = "",
    val upperName: String = "",
    val mediaCount: Int = 0,
    val plays: Long = -1,
    /** The owner's mid (名声模式 只隐藏自己的); 0 when unknown. */
    val upperMid: Long = 0,
)

internal class SubDetailViewModel(val id: Long, val type: Int) : ViewModel() {
    var info by mutableStateOf(SubInfo())
        private set

    val pager = Pager(viewModelScope, dedupKey = { it.bvid.ifEmpty { it.aid.toString() } }) { cursor ->
        val pn = cursor?.toIntOrNull() ?: 1
        val ps = 20
        val data = if (type == 21) {
            BiliClient.getData("/x/space/fav/season/list", mapOf("season_id" to id, "pn" to pn, "ps" to ps)).asObj
        } else {
            BiliClient.getData(
                "/x/v3/fav/resource/list",
                mapOf(
                    "media_id" to id, "pn" to pn, "ps" to ps, "keyword" to "", "order" to "mtime",
                    "type" to 0, "tid" to 0, "platform" to "web",
                ),
            ).asObj
        }
        data.obj("info")?.let { i ->
            info = SubInfo(
                title = i.str("title"),
                cover = i.str("cover"),
                intro = i.str("intro"),
                upperName = i.obj("upper").str("name"),
                mediaCount = i.int("media_count"),
                plays = i.obj("cnt_info")?.long("play") ?: -1,
                upperMid = i.obj("upper").long("mid"),
            )
        }
        val list = data.arr("medias").objects().map { it.favMediaToVideo() }
        val total = info.mediaCount
        Page(list, hasMore = list.isNotEmpty() && pn * ps < total, nextCursor = (pn + 1).toString())
    }

    init {
        pager.refresh()
    }
}

@Composable
fun SubDetailScreen(route: Route.SubDetail) {
    val vm = viewModel { SubDetailViewModel(route.seasonId, route.type) }
    CoverTheme(vm.info.cover.ifEmpty { route.cover }) {
        SubDetailContent(route, vm)
    }
}

@Composable
private fun SubDetailContent(route: Route.SubDetail, vm: SubDetailViewModel) {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<VideoItem?>(null) }
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { vm.pager.loadMore() }

    LibraryScaffold(
        title = vm.info.title.ifEmpty { route.title.ifEmpty { "订阅" } },
        subtitle = if (vm.info.mediaCount > 0) "${vm.info.mediaCount}个视频" else null,
    ) { padding ->
        PagerBody(vm.pager, padding, emptyLabel = "暂无视频", emptyIcon = YoinSymbols.Video, requireLogin = false) { listPadding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(RowGridMinCell),
                state = gridState,
                modifier = Modifier.librarySeam(gridState, listPadding),
                contentPadding = listPadding,
            ) {
                item(key = "__header", span = { GridItemSpan(maxLineSpan) }) {
                    SubHeader(
                        vm.info,
                        cover = vm.info.cover.ifEmpty { route.cover },
                        showUnsub = BiliClient.isLoggedIn,
                        onUnsub = { confirm = true },
                    )
                }
                items(vm.pager.items, key = { it.bvid.ifEmpty { it.aid.toString() } }) { item ->
                    VideoCardH(
                        item,
                        modifier = Modifier.animateItem(),
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            sheet = item
                        },
                    )
                }
                pagerFooter(vm.pager)
            }
        }
    }

    sheet?.let { item ->
        VideoCardMenu(item, onDismiss = { sheet = null })
    }
    if (confirm) {
        ConfirmDialog(
            title = "取消订阅",
            text = vm.info.title.ifEmpty { route.title },
            confirmLabel = "取消订阅",
            onConfirm = {
                scope.launch {
                    runCatching { unsubscribe(route.seasonId, route.type) }
                        .onSuccess {
                            toast("已取消订阅")
                            nav.pop()
                        }
                        .onFailure { toast(it.friendlyMessage()) }
                }
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun SubHeader(info: SubInfo, cover: String, showUnsub: Boolean, onUnsub: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 840.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 10f)) {
            NetImage(cover, Modifier.matchParentSize(), shape = ContinuousRoundedCornerShape(12.dp), thumbWidth = 160.dp)
        }
        Column(Modifier.weight(1f).seamText(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (info.upperName.isNotEmpty()) {
                Text(info.upperName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (info.plays >= 0) Stat(YoinSymbols.Views, Fmt.count(info.plays), self = FameMode.isSelf(info.upperMid))
                if (info.mediaCount > 0) Stat(YoinSymbols.Video, info.mediaCount.toString())
            }
            if (info.intro.isNotBlank()) {
                Text(
                    info.intro,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showUnsub) {
                FilledTonalButton(onClick = onUnsub) {
                    Icon(YoinSymbols.LibraryAdded, null)
                    Text("已订阅", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
