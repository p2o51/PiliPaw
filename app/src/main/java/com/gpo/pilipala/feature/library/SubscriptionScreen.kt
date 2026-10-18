package com.gpo.pilipala.feature.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.draw.clip
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
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

/** Unsubscribe a season (type 21) or a collected fav folder (type 11). */
internal suspend fun unsubscribe(id: Long, type: Int) {
    if (type == 21) {
        BiliClient.postData("/x/v3/fav/season/unfav", mapOf("platform" to "web", "season_id" to id))
    } else {
        BiliClient.postData("/x/v3/fav/folder/unfav", mapOf("media_id" to id))
    }
}

internal class SubscriptionViewModel(val mid: Long) : ViewModel() {
    val pager = Pager(viewModelScope, dedupKey = { "${it.type}:${it.id}" }) { cursor ->
        val pn = cursor?.toIntOrNull() ?: 1
        val data = BiliClient.getData(
            "/x/v3/fav/folder/collected/list",
            mapOf("up_mid" to mid, "pn" to pn, "ps" to 20, "platform" to "web"),
        ).asObj
        val list = data.arr("list").objects().map { it.toFavFolder() }
        Page(list, data.bool("has_more"), (pn + 1).toString())
    }

    init {
        pager.refresh()
    }

    suspend fun cancel(f: FavFolder) {
        unsubscribe(f.id, f.type)
        pager.update { l -> l.filterNot { it.id == f.id && it.type == f.type } }
    }
}

@Composable
fun SubscriptionScreen(route: Route.Subscription) {
    val mid = if (route.mid > 0) route.mid else BiliClient.mid
    val vm = viewModel { SubscriptionViewModel(mid) }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val isOwner = mid == BiliClient.mid
    var sheet by remember { mutableStateOf<FavFolder?>(null) }
    var confirm by remember { mutableStateOf<FavFolder?>(null) }
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { vm.pager.loadMore() }

    LibraryScaffold(title = if (isOwner) "我的订阅" else "订阅") { padding ->
        PagerBody(vm.pager, padding, emptyLabel = "暂无订阅", emptyIcon = YoinSymbols.LibraryAdd) { listPadding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(RowGridMinCell),
                state = gridState,
                modifier = Modifier.librarySeam(gridState, listPadding),
                contentPadding = listPadding,
            ) {
                items(vm.pager.items, key = { "${it.type}:${it.id}" }) { f ->
                    SubFolderRow(
                        f,
                        modifier = Modifier.animateItem(),
                        onClick = { nav.push(Route.SubDetail(f.id, f.type, f.title, f.cover)) },
                        onLongClick = if (isOwner) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sheet = f
                            }
                        } else null,
                    )
                }
                pagerFooter(vm.pager)
            }
        }
    }

    sheet?.let { f ->
        ItemActionSheet(
            title = f.title,
            cover = f.cover,
            actions = listOf(SheetAction("取消订阅", YoinSymbols.Delete, destructive = true) { confirm = f }),
            onDismiss = { sheet = null },
        )
    }
    confirm?.let { f ->
        ConfirmDialog(
            title = "取消订阅",
            text = f.title,
            confirmLabel = "取消订阅",
            onConfirm = {
                scope.launch {
                    runCatching { vm.cancel(f) }
                        .onSuccess { toast("已取消订阅") }
                        .onFailure { toast(it.friendlyMessage()) }
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun SubFolderRow(
    f: FavFolder,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val coverWidth = 152.dp
    Row(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(coverWidth).aspectRatio(16f / 10f)) {
            NetImage(f.cover, Modifier.matchParentSize(), shape = LibCoverShape, thumbWidth = coverWidth)
        }
        Column(Modifier.weight(1f).height(coverWidth * 10f / 16f).seamText(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(f.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (f.upperName.isNotEmpty()) {
                    Text(
                        f.upperName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(if (f.type == 21) "合集" else "收藏夹")
                    Text(
                        "${f.mediaCount}个视频",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
