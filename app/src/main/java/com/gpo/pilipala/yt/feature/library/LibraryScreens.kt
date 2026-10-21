package com.gpo.pilipala.yt.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.feature.player.miniPlayerInset
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.yt.ui.components.PawTopBar
import com.gpo.pilipala.yt.ui.components.Thumbnail
import com.gpo.pilipala.yt.ui.components.VideoRow
import com.gpo.pilipala.yt.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.yoin.symbols.YoinSymbols

/** Which local list a [Route.LocalList] shows. */
enum class LocalList(val title: String) { History("历史记录"), WatchLater("稍后再看") }

/**
 * 历史记录 / 稍后再看 (本机). History rows carry how far each was watched; a long press takes a
 * row out. History has 清空 in its bar.
 */
@Composable
fun LocalListScreen(route: Route.LocalList) {
    val kind = LocalList.valueOf(route.kind)
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val list by (if (kind == LocalList.History) Library.history else Library.watchLater).collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PawTopBar(title = kind.title, onBack = { nav.pop() }, actions = {
            if (kind == LocalList.History && list.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("清空") }
            }
        })
        if (list.isEmpty()) {
            EmptyState(if (kind == LocalList.History) "还没有看过的视频" else "还没有稍后再看", icon = YoinSymbols.Library)
        } else {
            LazyColumn(
                Modifier.fillMaxSize().readingWidth(),
                contentPadding = PaddingValues(bottom = miniPlayerInset() + 24.dp),
            ) {
                items(list, key = { it.key }) { item ->
                    val progress = if (kind == LocalList.History && item.durationSec > 0) item.positionMs / (item.durationSec * 1000f) else null
                    VideoRow(item.toVideo(), progress = progress, onLongClick = {
                        if (kind == LocalList.History) Library.removeFromHistory(item.key) else Library.toggleWatchLater(item.toVideo())
                        toast("已移除")
                    })
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史记录") },
            confirmButton = {
                TextButton(onClick = {
                    Library.clearHistory()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 播放列表 (本机): every playlist with its first video's thumbnail and count; 新建 in the bar. */
@Composable
fun PlaylistsScreen() {
    val nav = LocalNavigator.current
    val lists by Library.playlists.collectAsState()
    var create by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PawTopBar(title = "播放列表", onBack = { nav.pop() }, actions = {
            IconButton(onClick = { create = true }) { Icon(YoinSymbols.Add, "新建播放列表") }
        })
        if (lists.isEmpty()) {
            EmptyState("还没有播放列表", icon = YoinSymbols.Library)
        } else {
            LazyColumn(Modifier.fillMaxSize().readingWidth(), contentPadding = PaddingValues(bottom = miniPlayerInset() + 24.dp)) {
                items(lists, key = { it.id }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.push(Route.LocalPlaylist(p.id)) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val first = p.items.firstOrNull()?.toVideo()
                        if (first != null) {
                            Thumbnail(first.copy(durationSec = 0), Modifier.width(140.dp))
                        } else {
                            Thumbnail(com.gpo.pilipala.yt.feature.library.emptyCover, Modifier.width(140.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${p.items.size} 个视频", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (create) NameDialog("新建播放列表", "", onDismiss = { create = false }) { Library.createPlaylist(it) }
}

/** One local playlist: its videos (long press takes one out), 重命名 and 删除 in the bar. */
@Composable
fun LocalPlaylistScreen(route: Route.LocalPlaylist) {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val lists by Library.playlists.collectAsState()
    val p = lists.firstOrNull { it.id == route.id }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PawTopBar(title = p?.name ?: "播放列表", onBack = { nav.pop() }, actions = {
            if (p != null) {
                IconButton(onClick = { rename = true }) { Icon(YoinSymbols.Edit, "重命名") }
                IconButton(onClick = { delete = true }) { Icon(YoinSymbols.Delete, "删除播放列表") }
            }
        })
        if (p == null || p.items.isEmpty()) {
            EmptyState("还没有视频", icon = YoinSymbols.Library)
        } else {
            LazyColumn(Modifier.fillMaxSize().readingWidth(), contentPadding = PaddingValues(bottom = miniPlayerInset() + 24.dp)) {
                items(p.items, key = { it.key }) { item ->
                    VideoRow(item.toVideo(), onLongClick = {
                        Library.removeFromPlaylist(p.id, item.key)
                        toast("已移除")
                    })
                }
            }
        }
    }
    if (rename && p != null) NameDialog("重命名", p.name, onDismiss = { rename = false }) { Library.renamePlaylist(p.id, it) }
    if (delete && p != null) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("删除「${p.name}」") },
            confirmButton = {
                TextButton(onClick = {
                    delete = false
                    nav.pop()
                    Library.deletePlaylist(p.id)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("名称") }) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onDone(name)
                onDismiss()
            }) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 加入播放列表: every local playlist with a tick where the video already is (a tap adds or takes
 * it out), and 新建 at the bottom.
 */
@Composable
fun AddToPlaylistSheet(video: com.gpo.pilipala.yt.core.extractor.VideoItem, onDismiss: () -> Unit) {
    val lists by Library.playlists.collectAsState()
    val toast = rememberToast()
    var create by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
        Column(Modifier.fillMaxWidth().padding(bottom = sheetBottomPadding())) {
            Text("加入播放列表", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            lists.forEach { p ->
                val inIt = p.items.any { it.key == Library.keyOf(video.url) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val added = Library.togglePlaylist(p.id, video)
                            toast(if (added) "已加入「${p.name}」" else "已从「${p.name}」移除")
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (inIt) Icon(YoinSymbols.Check, "已加入", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { create = true }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(YoinSymbols.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text("新建播放列表", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (create) {
        NameDialog("新建播放列表", "", onDismiss = { create = false }) {
            val p = Library.createPlaylist(it)
            Library.togglePlaylist(p.id, video)
            toast("已加入「${p.name}」")
        }
    }
}

/** An empty playlist's cover: a blank thumbnail. */
internal val emptyCover = com.gpo.pilipala.yt.core.extractor.VideoItem(
    url = "", title = "", thumbnail = "", channelName = "", channelUrl = "", channelAvatar = "",
    durationSec = 0, views = -1, uploaded = "", uploadedAt = 0, isLive = false, isShort = false, channelVerified = false,
)
