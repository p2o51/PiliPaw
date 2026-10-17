package com.gpo.pilipala.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

internal class LaterViewModel : ViewModel() {
    var count by mutableStateOf(0)
        private set

    val pager = Pager(viewModelScope, dedupKey = { it.aid }) { _ ->
        val data = BiliClient.getData("/x/v2/history/toview").asObj
        count = data.int("count")
        val list = data.arr("list").objects().map { o ->
            val owner = o.obj("owner")
            val stat = o.obj("stat")
            val bangumi = o.obj("bangumi")
            val epId = bangumi.long("ep_id")
            VideoItem(
                bvid = o.str("bvid"),
                aid = o.long("aid"),
                cid = o.long("cid"),
                title = o.str("title"),
                cover = o.str("pic"),
                duration = o.long("duration"),
                ownerMid = owner.long("mid"),
                ownerName = owner.str("name"),
                ownerFace = owner.str("face"),
                views = stat.long("view"),
                danmaku = stat.long("danmaku"),
                pubdate = o.long("pubdate"),
                progress = o.long("progress"),
                badge = if (o.int("videos") > 1) "${o.int("videos")}P" else "",
                target = if (epId > 0) com.gpo.pilipala.navigation.Route.Bangumi(epId = epId, cover = o.str("pic")) else null,
            )
        }
        Page(list, hasMore = false)
    }

    init {
        pager.refresh()
    }

    suspend fun remove(item: VideoItem) {
        BiliClient.postData("/x/v2/history/toview/del", mapOf("aid" to item.aid, "jsonp" to "jsonp"))
        pager.update { l -> l.filterNot { it.aid == item.aid } }
        count = (count - 1).coerceAtLeast(0)
    }

    suspend fun clearWatched() {
        BiliClient.postData("/x/v2/history/toview/del", mapOf("viewed" to true, "jsonp" to "jsonp"))
        pager.refresh()
    }

    suspend fun clearAll() {
        BiliClient.postData("/x/v2/history/toview/clear", mapOf("jsonp" to "jsonp"))
        pager.update { emptyList() }
        count = 0
    }
}

private enum class LaterConfirm { Watched, All }

@Composable
fun LaterScreen() {
    val vm = viewModel { LaterViewModel() }
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<LaterConfirm?>(null) }
    var sheet by remember { mutableStateOf<VideoItem?>(null) }
    val gridState = rememberLazyGridState()

    LibraryScaffold(
        title = "稍后再看",
        subtitle = if (vm.count > 0) "${vm.count}个视频" else null,
        actions = {
            if (BiliClient.isLoggedIn) {
                Box {
                    IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("移除已看") },
                            leadingIcon = { Icon(YoinSymbols.Check, null) },
                            onClick = {
                                menuOpen = false
                                confirm = LaterConfirm.Watched
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清空") },
                            leadingIcon = { Icon(YoinSymbols.Delete, null) },
                            onClick = {
                                menuOpen = false
                                confirm = LaterConfirm.All
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        PagerBody(vm.pager, padding, emptyLabel = "稍后再看为空", emptyIcon = PiliSymbols.WatchLater) { listPadding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(RowGridMinCell),
                state = gridState,
                contentPadding = listPadding,
            ) {
                items(vm.pager.items, key = { it.aid }) { item ->
                    VideoCardH(
                        item,
                        modifier = Modifier.animateItem(),
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            sheet = item
                        },
                    )
                }
            }
        }
    }

    sheet?.let { item ->
        ItemActionSheet(
            title = item.title,
            cover = item.cover,
            actions = listOf(
                SheetAction("移除", YoinSymbols.Delete, destructive = true) {
                    scope.launch {
                        runCatching { vm.remove(item) }
                            .onSuccess { toast("已移除") }
                            .onFailure { toast(it.friendlyMessage()) }
                    }
                },
            ),
            onDismiss = { sheet = null },
        )
    }
    confirm?.let { c ->
        ConfirmDialog(
            title = if (c == LaterConfirm.Watched) "移除已看视频" else "清空稍后再看",
            confirmLabel = if (c == LaterConfirm.Watched) "移除" else "清空",
            onConfirm = {
                scope.launch {
                    runCatching { if (c == LaterConfirm.Watched) vm.clearWatched() else vm.clearAll() }
                        .onSuccess { toast("已完成") }
                        .onFailure { toast(it.friendlyMessage()) }
                }
            },
            onDismiss = { confirm = null },
        )
    }
}
