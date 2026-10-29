package com.gpo.pilipala.feature.sources

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.theme.CoverTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.source.Sources
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.feature.library.FavFolder
import com.gpo.pilipala.feature.library.FolderCard
import com.gpo.pilipala.feature.library.ItemActionSheet
import com.gpo.pilipala.feature.library.LibraryScaffold
import com.gpo.pilipala.feature.library.SheetAction
import com.gpo.pilipala.feature.library.toFavFolder
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.objects
import androidx.compose.runtime.LaunchedEffect
import com.gpo.pilipala.feature.library.sectionHeader
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.yt.YtLinks
import com.gpo.pilipala.yt.feature.library.LibItem
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.library.LocalPlaylist
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.channels.Channel

/** Bilibili pages asked for from outside the Bilibili part (a Mix list's row in the YouTube part). */
object BiliLinks {
    val pending = Channel<Route>(Channel.UNLIMITED)

    fun open(route: Route) {
        pending.trySend(route)
    }
}

/**
 * Mix lists are PipePaw's local playlists ([Library.playlists]), which already key every video by
 * source ("yt:…"); a Bilibili video joins them as "bili:<bvid>". Nothing leaves the device.
 */
object MixItems {
    fun bili(bvid: String, title: String, cover: String, ownerName: String, ownerMid: Long, durationSec: Long): LibItem = LibItem(
        key = "bili:$bvid",
        url = "https://www.bilibili.com/video/$bvid",
        title = title,
        // Bilibili hands out http:// and //host covers; the app only loads https.
        thumbnail = secure(cover),
        channelName = ownerName,
        channelUrl = if (ownerMid > 0) "https://space.bilibili.com/$ownerMid" else "",
        durationSec = durationSec,
        at = System.currentTimeMillis(),
    )

    fun secure(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    fun isBili(item: LibItem): Boolean = item.key.startsWith("bili:")

    fun source(item: LibItem): VideoSource = if (isBili(item)) VideoSource.Bilibili else VideoSource.YouTube

    private fun biliRoute(item: LibItem) = Route.Video(bvid = item.key.removePrefix("bili:"), cover = item.thumbnail, title = item.title)

    /** For the YouTube part's list: a Bilibili row opens Bilibili's video page; null plays it in the YouTube player. */
    fun biliOpener(item: LibItem): (() -> Unit)? = if (isBili(item)) ({ BiliLinks.open(biliRoute(item)) }) else null

    /** From the Bilibili part: its own video page, or the YouTube part for a YouTube row. */
    fun open(item: LibItem, push: (Route) -> Unit) {
        if (isBili(item)) {
            push(biliRoute(item))
        } else {
            com.gpo.pilipala.yt.feature.player.Player.play(item.url, item.toVideo())
        }
    }
}

internal fun LocalPlaylist.asFolder() = FavFolder(
    id = id.hashCode().toLong(),
    title = name,
    cover = MixItems.secure(items.firstOrNull()?.thumbnail.orEmpty()),
    mediaCount = items.size,
    // Neither the default folder nor private: no lock, and it can be deleted.
    attr = 2,
)

/**
 * The Mix lists at the top of 收藏: one card each, like the folders below them, Bilibili and
 * YouTube videos together, kept on the device. Shown once a list exists or YouTube is ticked.
 */
internal fun LazyGridScope.mixSection(lists: List<LocalPlaylist>, onOpen: (LocalPlaylist) -> Unit, onNew: () -> Unit) {
    sectionHeader("h_mix", "Mix") { TextButton(onClick = onNew) { Text("新建") } }
    items(lists, key = { "m${it.id}" }) { list ->
        FolderCard(list.asFolder(), route = Route.MixList(list.id, list.name), onClick = { onOpen(list) })
    }
}

/**
 * 加到 Mix: every Mix list with a round tick, and a new one at the bottom. Ticks apply at once
 * (it is all on the device), so there is no 完成 to press.
 */
@Composable
fun MixPickerSheet(item: LibItem, onDismiss: () -> Unit) {
    val lists by Library.playlists.collectAsState()
    val haptics = LocalHapticFeedback.current
    val toast = rememberToast()
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, properties = rememberSheetProperties()) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = sheetBottomPadding(8.dp)),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("加到 Mix", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(lists, key = { it.id }) { list ->
                    val on = list.items.any { it.key == item.key }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .combinedClickable {
                                val now = Library.togglePlaylistItem(list.id, item)
                                haptics.performHapticFeedback(if (now) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        MixCover(list, Modifier.width(72.dp))
                        Column(Modifier.weight(1f)) {
                            Text(list.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${list.items.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TickMark(on, MaterialTheme.colorScheme.primary) {
                            val now = Library.togglePlaylistItem(list.id, item)
                            haptics.performHapticFeedback(if (now) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        }
                    }
                }
            }
            if (naming) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text("名称") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val list = Library.createPlaylist(name)
                            Library.togglePlaylistItem(list.id, item)
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            toast("已加到「${list.name}」")
                            close()
                        },
                    ) { Text("新建") }
                }
            } else {
                TextButton(onClick = { naming = true }, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Icon(YoinSymbols.Add, null, Modifier.size(18.dp))
                    Text("  新建 Mix")
                }
            }
        }
    }
}

@Composable
private fun MixCover(list: LocalPlaylist, modifier: Modifier = Modifier) {
    val cover = list.items.firstOrNull()?.thumbnail?.let(MixItems::secure)
    Box(
        modifier
            .aspectRatio(16f / 10f)
            .clip(CoverShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            NetImage(cover, Modifier.fillMaxSize(), shape = CoverShape, thumbWidth = 144.dp)
        } else {
            Icon(YoinSymbols.Library, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

private val CoverShape = ContinuousRoundedCornerShape(10.dp)

/**
 * One Mix list, laid out as a 收藏夹 is (VideoListPage): 搜索, 排序 and 关联 in the bar, 改名 and
 * 删除 under ⋯; how it splits between the sources and what it follows over the play buttons.
 * A long press offers 从 Mix 移除 with the card menu.
 */
@Composable
fun MixListScreen(route: Route.MixList) {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val lists by Library.playlists.collectAsState()
    val list = lists.firstOrNull { it.id == route.id }
    var deleting by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    // Opening a Mix that follows something brings it up to date.
    LaunchedEffect(route.id) { if (list?.links?.isNotEmpty() == true) MixSync.sync(route.id) }
    // The page takes its colours from the first video's cover, as a 收藏夹 does.
    CoverTheme(list?.items?.firstOrNull()?.thumbnail?.let(MixItems::secure)) {
        VideoListPage(
            id = MixIdPrefix + route.id,
            title = list?.name ?: route.title.ifEmpty { "Mix" },
            subtitle = list?.let { "${it.items.size} 个视频" },
            items = list?.items ?: emptyList(),
            orders = MixOrders,
            actions = {
                if (list != null) {
                    IconButton(onClick = { linking = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(YoinSymbols.Link, contentDescription = "关联")
                    }
                    Box {
                        IconButton(onClick = { more = true }, shapes = IconButtonDefaults.shapes()) {
                            Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
                        }
                        androidx.compose.material3.DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("改名") },
                                leadingIcon = { Icon(YoinSymbols.Edit, null) },
                                onClick = {
                                    more = false
                                    renaming = true
                                },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("删除这个 Mix") },
                                leadingIcon = { Icon(YoinSymbols.Delete, null) },
                                onClick = {
                                    more = false
                                    deleting = true
                                },
                            )
                        }
                    }
                }
            },
            head = { if (list != null) MixHead(list, onLinks = { linking = true }) },
            extras = { item ->
                listOf(
                    com.gpo.pilipala.feature.videomenu.VideoMenuAction("从 Mix 移除", YoinSymbols.Delete, destructive = true) {
                        Library.removeFromPlaylist(route.id, item.key)
                        toast("已移除")
                    },
                )
            },
        )
    }
    if (linking && list != null) MixLinkSheet(list, onDismiss = { linking = false })
    if (renaming && list != null) {
        var name by remember { mutableStateOf(list.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("改名") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("名字") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    Library.renamePlaylist(list.id, name)
                    renaming = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }
    if (deleting && list != null) {
        ItemActionSheet(
            title = "删除「${list.name}」",
            actions = listOf(
                SheetAction("删除这个 Mix", YoinSymbols.Delete, destructive = true) {
                    Library.deletePlaylist(list.id)
                    nav.pop()
                },
            ),
            onDismiss = { deleting = false },
        )
    }
}

/**
 * 关联: follow a Bilibili 收藏夹 (the signed-in account's own) or a YouTube playlist (the
 * signed-in account's own, or any by its link).
 * What is linked shows with 取消关联; a new link pulls its videos in at once.
 */
@Composable
private fun MixLinkSheet(list: LocalPlaylist, onDismiss: () -> Unit) {
    val toast = rememberToast()
    var folders by remember { mutableStateOf<List<FavFolder>?>(null) }
    var url by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val mid = com.gpo.pilipala.core.net.BiliClient.mid
        if (mid <= 0) {
            folders = emptyList()
            return@LaunchedEffect
        }
        folders = runCatching {
            com.gpo.pilipala.core.net.BiliClient.getData(
                "/x/v3/fav/folder/created/list",
                mapOf("up_mid" to mid, "pn" to 1, "ps" to 50),
            ).asObj.arr("list").objects().map { it.toFavFolder() }
        }.getOrDefault(emptyList())
    }
    // The signed-in YouTube account's playlists (none signed out).
    val ytProfile by com.gpo.pilipala.yt.core.account.Accounts.youtube.collectAsState()
    val ytSignedIn = ytProfile is com.gpo.pilipala.yt.core.account.Profile.SignedIn
    val ytCached by YtLibraryCache.lists.collectAsState()
    val ytAccountLists = if (ytSignedIn) ytCached else null
    LaunchedEffect(ytSignedIn) { if (ytSignedIn) YtLibraryCache.refresh() }
    val done: (Result<Int>) -> Unit = { r ->
        r.onSuccess { n -> toast(if (n > 0) "加入 $n 个视频" else "已关联") }
            .onFailure { toast(it.friendlyMessage()) }
    }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, properties = rememberSheetProperties()) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = sheetBottomPadding(8.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("关联", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
            list.links.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (l.kind == "bili_fav") YoinSymbols.Folder else YoinSymbols.Playlist, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(l.title.ifEmpty { l.ref }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (l.kind == "bili_fav") "哔哩哔哩收藏夹" else "YouTube 播放列表", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { MixSync.unlink(list.id, l.id) }) { Text("取消关联") }
                }
            }
            Text("哔哩哔哩收藏夹", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            val fs = folders
            when {
                fs == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.LoadingIndicator() }
                fs.isEmpty() -> Text("未登录哔哩哔哩", modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(fs, key = { it.id }) { f ->
                        val linked = list.links.any { it.id == "bili_fav:${f.id}" }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .combinedClickable(enabled = !linked) {
                                    MixSync.link(list.id, MixSync.biliFolderLink(f.id, f.title), done)
                                    close()
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            NetImage(MixItems.secure(f.cover), Modifier.width(72.dp).aspectRatio(16f / 10f), shape = CoverShape, thumbWidth = 144.dp)
                            Column(Modifier.weight(1f)) {
                                Text(f.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${f.mediaCount} 个内容", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (linked) Icon(YoinSymbols.Check, contentDescription = "已关联", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Text("YouTube 播放列表", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            // The signed-in account's own playlists to pick, as the 收藏夹 above; any other by its link.
            val ytLists = ytAccountLists
            if (!ytLists.isNullOrEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(ytLists, key = { it.id }) { pl ->
                        val link = MixSync.youtubePlaylistLink(pl.url, pl.title)
                        val linked = list.links.any { it.id == link.id }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .combinedClickable(enabled = !linked) {
                                    MixSync.link(list.id, link, done)
                                    close()
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            NetImage(MixItems.secure(pl.thumbnail), Modifier.width(72.dp).aspectRatio(16f / 10f), shape = CoverShape, thumbWidth = 144.dp)
                            Column(Modifier.weight(1f)) {
                                Text(pl.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (pl.count.isNotEmpty()) {
                                    Text(pl.count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (linked) Icon(YoinSymbols.Check, contentDescription = "已关联", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("播放列表链接") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = url.contains("list="),
                    onClick = {
                        MixSync.link(list.id, MixSync.youtubePlaylistLink(url.trim(), "YouTube 播放列表"), done)
                        close()
                    },
                ) { Text("关联") }
            }
        }
    }
}

/**
 * Over the play buttons: how the Mix splits between the two sources (each count with its own
 * weight, no "·" between them), and the lists it follows as chips.
 */
@Composable
private fun MixHead(list: LocalPlaylist, onLinks: () -> Unit) {
    val bili = list.items.count(MixItems::isBili)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
            SourceCount(bili, "哔哩哔哩")
            SourceCount(list.items.size - bili, "YouTube")
        }
        if (list.links.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.links.forEach { l ->
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickable(onClick = onLinks)
                            .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            if (l.kind == "bili_fav") YoinSymbols.Folder else YoinSymbols.Playlist,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            l.title.ifEmpty { if (l.kind == "bili_fav") "收藏夹" else "播放列表" },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCount(n: Int, label: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$n", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
    }
}
