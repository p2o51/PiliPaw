package com.gpo.pilipala.feature.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
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
import com.gpo.pilipala.core.net.asBool
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LinkRouter
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.CoverBadge
import com.gpo.pilipala.ui.components.DurationChip
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import java.util.Calendar

@Immutable
internal data class HistoryEntry(
    val kid: Long,
    val business: String,
    val oid: Long,
    val epid: Long,
    val bvid: String,
    val cid: Long,
    val part: String,
    val title: String,
    val showTitle: String,
    val cover: String,
    val duration: Long,
    /** Seconds; -1 = finished. */
    val progress: Long,
    val authorName: String,
    val authorFace: String,
    val authorMid: Long,
    val viewAt: Long,
    val badge: String,
    val liveStatus: Int,
    val uri: String,
    val videos: Int,
    val newDesc: String,
) {
    val key: String get() = "${business}_$kid"
}

private fun JsonObject.toHistory(): HistoryEntry {
    val h = obj("history")
    return HistoryEntry(
        kid = long("kid"),
        business = h.str("business"),
        oid = h.long("oid"),
        epid = h.long("epid"),
        bvid = h.str("bvid"),
        cid = h.long("cid"),
        part = h.str("part"),
        title = str("title"),
        showTitle = str("show_title"),
        cover = str("cover").ifEmpty { arr("covers").firstOrNull().asString() },
        duration = long("duration"),
        progress = long("progress"),
        authorName = str("author_name"),
        authorFace = str("author_face"),
        authorMid = long("author_mid"),
        viewAt = long("view_at"),
        badge = str("badge"),
        liveStatus = int("live_status"),
        uri = str("uri"),
        videos = int("videos"),
        newDesc = str("new_desc"),
    )
}

internal class HistoryViewModel : ViewModel() {
    val pager = Pager(viewModelScope, dedupKey = { it.key }) { cursor ->
        val parts = cursor?.split('|')
        val data = BiliClient.getData(
            "/x/web-interface/history/cursor",
            mapOf(
                "type" to "all",
                "ps" to 20,
                "max" to (parts?.getOrNull(0) ?: "0"),
                "view_at" to (parts?.getOrNull(1) ?: "0"),
                "business" to (parts?.getOrNull(2) ?: ""),
            ),
        ).asObj
        val list = data.arr("list").objects().map { it.toHistory() }
        val c = data.obj("cursor")
        val max = c.long("max")
        Page(list, hasMore = list.isNotEmpty() && max != 0L, nextCursor = "$max|${c.long("view_at")}|${c.str("business")}")
    }
    val paused = AppSettings.pauseHistory

    init {
        pager.refresh()
        viewModelScope.launch {
            runCatching { BiliClient.getData("/x/v2/history/shadow").asBool() }
                .onSuccess { AppSettings.set(AppSettings.pauseHistory, it) }
        }
    }

    suspend fun setPaused(pause: Boolean) {
        BiliClient.postData("/x/v2/history/shadow/set", mapOf("switch" to pause, "jsonp" to "jsonp"))
        AppSettings.set(AppSettings.pauseHistory, pause)
    }

    suspend fun clearAll() {
        BiliClient.postData("/x/v2/history/clear", mapOf("jsonp" to "jsonp"))
        pager.update { emptyList() }
    }

    suspend fun delete(e: HistoryEntry) {
        BiliClient.postData("/x/v2/history/delete", mapOf("kid" to e.key, "jsonp" to "jsonp"))
        pager.update { l -> l.filterNot { it.key == e.key } }
    }
}

/** 今天 / 昨天 / 3月12日 / 2023年3月12日 */
private fun dayLabel(unix: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = unix * 1000 }
    val now = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(cal, now) -> "今天"
        same(cal, yesterday) -> "昨天"
        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> Fmt.date(unix, "M月d日")
        else -> Fmt.date(unix, "yyyy年M月d日")
    }
}

@Composable
fun HistoryScreen() {
    val vm = viewModel { HistoryViewModel() }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val paused by vm.paused.state.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<HistoryEntry?>(null) }
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { vm.pager.loadMore() }

    fun open(e: HistoryEntry) {
        when {
            e.business == "pgc" && e.epid > 0 -> nav.push(Route.Bangumi(epId = e.epid, cover = e.cover))
            e.business == "live" -> if (e.liveStatus == 1) nav.push(Route.LiveRoom(e.oid, e.cover, e.title)) else toast("未开播")
            e.business.startsWith("article") -> nav.push(Route.Read(if (e.cid > 0) e.cid else e.oid, e.title))
            e.bvid.isNotEmpty() -> nav.push(
                Route.Video(bvid = e.bvid, cid = e.cid, aid = e.oid, cover = e.cover, title = e.title, progress = e.progress.coerceAtLeast(0)),
            )
            e.uri.isNotEmpty() -> nav.openUrl(e.uri, e.title)
        }
    }

    LibraryScaffold(
        title = "历史记录",
        subtitle = if (paused) "已暂停记录" else null,
        actions = {
            if (BiliClient.isLoggedIn) {
                Box {
                    IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("暂停记录") },
                            leadingIcon = { Icon(YoinSymbols.Pause, null) },
                            trailingIcon = { Switch(checked = paused, onCheckedChange = null) },
                            onClick = {
                                menuOpen = false
                                val target = !paused
                                haptics.performHapticFeedback(if (target) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                scope.launch {
                                    runCatching { vm.setPaused(target) }
                                        .onSuccess { toast(if (target) "已暂停记录" else "已恢复记录") }
                                        .onFailure { toast(it.friendlyMessage()) }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清空记录") },
                            leadingIcon = { Icon(YoinSymbols.Delete, null) },
                            onClick = {
                                menuOpen = false
                                confirmClear = true
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        PagerBody(vm.pager, padding, emptyLabel = "暂无历史记录", emptyIcon = PiliSymbols.History) { listPadding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(RowGridMinCell),
                state = gridState,
                contentPadding = listPadding,
            ) {
                var lastDay: String? = null
                vm.pager.items.forEach { e ->
                    val day = dayLabel(e.viewAt)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "day_$day", span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                            Text(
                                day,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.animateItem().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp),
                            )
                        }
                    }
                    item(key = e.key, contentType = "entry") {
                        HistoryCard(
                            e,
                            modifier = Modifier.animateItem(),
                            onClick = { open(e) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sheet = e
                            },
                        )
                    }
                }
                pagerFooter(vm.pager)
            }
        }
    }

    sheet?.let { e ->
        ItemActionSheet(
            title = e.title,
            cover = e.cover,
            actions = buildList {
                if (e.business == "archive" && e.oid > 0) {
                    add(
                        SheetAction("稍后再看", PiliSymbols.WatchLater) {
                            scope.launch {
                                runCatching { addToLater(e.oid) }
                                    .onSuccess { toast("已添加") }
                                    .onFailure { toast(it.friendlyMessage()) }
                            }
                        },
                    )
                }
                if (e.authorMid > 0) {
                    add(SheetAction("访问 ${e.authorName}", YoinSymbols.Person) { nav.push(Route.Member(e.authorMid, e.authorFace, e.authorName)) })
                }
                add(
                    SheetAction("删除记录", YoinSymbols.Delete, destructive = true) {
                        scope.launch {
                            runCatching { vm.delete(e) }
                                .onSuccess { toast("已删除") }
                                .onFailure { toast(it.friendlyMessage()) }
                        }
                    },
                )
            },
            onDismiss = { sheet = null },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "清空历史记录",
            confirmLabel = "清空",
            onConfirm = {
                scope.launch {
                    runCatching { vm.clearAll() }
                        .onSuccess { toast("已清空") }
                        .onFailure { toast(it.friendlyMessage()) }
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun HistoryCard(
    e: HistoryEntry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
            NetImage(e.cover, Modifier.matchParentSize(), shape = LibCoverShape, thumbWidth = coverWidth)
            val badge = when {
                e.business == "live" -> if (e.liveStatus == 1) "直播中" else "直播"
                e.business.startsWith("article") -> "专栏"
                e.badge.isNotEmpty() -> e.badge
                e.videos > 1 -> "${e.videos}P"
                else -> ""
            }
            if (badge.isNotEmpty()) CoverBadge(badge, Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (e.duration > 0 && e.business != "live") {
                val label = when {
                    e.progress < 0 -> "已看完"
                    e.progress > 0 -> "${Fmt.duration(e.progress)}/${Fmt.duration(e.duration)}"
                    else -> Fmt.duration(e.duration)
                }
                DurationChip(label, Modifier.align(Alignment.BottomEnd).padding(4.dp))
                if (e.progress != 0L) {
                    val frac = if (e.progress < 0) 1f else (e.progress.toFloat() / e.duration).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 6.dp)
                            .fillMaxWidth(frac)
                            .height(3.dp)
                            .clip(YoinContainerShapes.ListRow)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Column(Modifier.weight(1f).height(coverWidth * 10f / 16f), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    e.business == "pgc" -> e.showTitle.ifEmpty { e.newDesc }
                    e.videos > 1 -> e.part
                    else -> ""
                }
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (e.authorName.isNotEmpty()) {
                    Text(
                        e.authorName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    Fmt.date(e.viewAt, "HH:mm"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
