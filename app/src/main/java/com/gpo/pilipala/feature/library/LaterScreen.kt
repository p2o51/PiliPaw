package com.gpo.pilipala.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import com.gpo.pilipala.ui.theme.YoinMotion
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import com.gpo.pilipala.feature.player.PlayQueues
import com.gpo.pilipala.feature.player.QueueItem
import com.gpo.pilipala.feature.widget.WidgetStore
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
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

    /** aid = comma-joined avids. */
    suspend fun remove(items: List<VideoItem>) {
        val aids = items.map { it.aid }.distinct()
        if (aids.isEmpty()) return
        BiliClient.postData("/x/v2/history/toview/del", mapOf("aid" to aids.joinToString(","), "jsonp" to "jsonp"))
        WidgetStore.laterChanged()
        val gone = aids.toSet()
        pager.update { l -> l.filterNot { it.aid in gone } }
        count = (count - aids.size).coerceAtLeast(0)
    }

    suspend fun clearWatched() {
        BiliClient.postData("/x/v2/history/toview/del", mapOf("viewed" to true, "jsonp" to "jsonp"))
        WidgetStore.laterChanged()
        pager.refresh()
    }

    suspend fun clearAll() {
        BiliClient.postData("/x/v2/history/toview/clear", mapOf("jsonp" to "jsonp"))
        WidgetStore.laterChanged()
        pager.update { emptyList() }
        count = 0
    }

    /** 稍后再看 as a playlist (the whole list is already loaded); null when nothing is playable. */
    fun queueRoute(shuffle: Boolean, from: String? = null): Route.Video? {
        val items = pager.items
            .filter { it.target == null && it.bvid.isNotEmpty() }
            .map { QueueItem(it.bvid, it.aid, it.cid, it.title, it.cover, it.ownerName, it.duration) }
            .let { if (shuffle) it.shuffled() else it }
        val start = from?.let { b -> items.indexOfFirst { it.bvid == b } }?.coerceAtLeast(0) ?: 0
        return PlayQueues.create("稍后再看", items, start)?.let { PlayQueues.route(it) }
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
    val selection = remember { Selection<Long>() }
    var confirmDelete by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val nav = LocalNavigator.current
    LaunchedEffect(vm.pager.items) { selection.retain(vm.pager.items.map { it.aid }) }
    BackHandler(enabled = selection.active) { selection.exit() }
    val allSelected = vm.pager.items.isNotEmpty() && vm.pager.items.all { it.aid in selection.keys }
    val toolbarRoom by animateDpAsState(
        if (selection.active) SelectionToolbarRoom else 0.dp,
        YoinMotion.defaultSpatialSpec(),
        label = "toolbarRoom",
    )
    val startQueue: (Boolean, String?) -> Unit = { shuffle, from ->
        val route = vm.queueRoute(shuffle, from)
        if (route != null) nav.push(route) else toast("没有可播放的视频")
    }

    LibraryScaffold(
        title = "稍后再看",
        subtitle = when {
            selection.active -> "已选${selection.keys.size}项"
            vm.count > 0 -> "${vm.count}个视频"
            else -> null
        },
        actions = {
            if (BiliClient.isLoggedIn) {
                SelectModeButton(selection)
                AnimatedVisibility(
                    visible = !selection.active,
                    enter = expandHorizontally(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
                    exit = shrinkHorizontally(YoinMotion.fastSpatialSpec()) + fadeOut(YoinMotion.fastEffectsSpec()),
                ) {
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
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            val bodyPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + toolbarRoom,
            )
            PagerBody(vm.pager, bodyPadding, emptyLabel = "稍后再看为空", emptyIcon = PiliSymbols.WatchLater) { listPadding ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(RowGridMinCell),
                    state = gridState,
                    modifier = Modifier.librarySeam(gridState, listPadding),
                    contentPadding = listPadding,
                ) {
                    item(key = "__play", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            QueuePlayButtons(
                                loading = false,
                                onPlay = { startQueue(false, null) },
                                onShuffle = { startQueue(true, null) },
                            )
                        }
                    }
                    items(vm.pager.items, key = { it.aid }) { item ->
                        val selected = selection.active && item.aid in selection.keys
                        Box(Modifier.animateItem().selectionBlock(selected)) {
                            VideoCardH(
                                item,
                                onClick = if (selection.active) {
                                    {
                                        haptics.performHapticFeedback(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                                        selection.toggle(item.aid)
                                    }
                                } else {
                                    null
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    sheet = item
                                },
                            )
                            SelectionCover(selected, RowCoverSlot)
                        }
                    }
                }
            }
            SelectionToolbar(
                visible = selection.active,
                count = selection.keys.size,
                allSelected = allSelected,
                deleteLabel = "移除",
                onSelectAll = { on -> selection.setAll(vm.pager.items.map { it.aid }, on) },
                onDelete = { confirmDelete = true },
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "移除${selection.keys.size}个视频",
            confirmLabel = "移除",
            onConfirm = {
                val targets = vm.pager.items.filter { it.aid in selection.keys }
                scope.launch {
                    runCatching { vm.remove(targets) }
                        .onSuccess {
                            selection.exit()
                            toast("已移除")
                        }
                        .onFailure { toast(it.friendlyMessage()) }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }

    sheet?.let { item ->
        ItemActionSheet(
            title = item.title,
            cover = item.cover,
            actions = listOfNotNull(
                if (item.target == null && item.bvid.isNotEmpty()) {
                    SheetAction("从这里播放", YoinSymbols.Playlist) { startQueue(false, item.bvid) }
                } else {
                    null
                },
                SheetAction("移除", YoinSymbols.Delete, destructive = true) {
                    scope.launch {
                        runCatching { vm.remove(listOf(item)) }
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
