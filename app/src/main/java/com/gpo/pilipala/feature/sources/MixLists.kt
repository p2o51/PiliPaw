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

private fun LocalPlaylist.asFolder() = FavFolder(
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
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
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
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
            )
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
                                "${list.items.size} 个视频",
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
                        placeholder = { Text("Mix 的名字") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            val list = Library.createPlaylist(name)
                            Library.togglePlaylistItem(list.id, item)
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            toast("已加到「${list.name}」")
                            onDismiss()
                        },
                    ) { Text("新建并加入") }
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

/** One Mix list, newest additions last. A row opens in its own part; long press removes it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MixListScreen(route: Route.MixList) {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val lists by Library.playlists.collectAsState()
    val list = lists.firstOrNull { it.id == route.id }
    var menu by remember { mutableStateOf<LibItem?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    // Opening a Mix that follows something brings it up to date.
    LaunchedEffect(route.id) { if (list?.links?.isNotEmpty() == true) MixSync.sync(route.id) }
    LibraryScaffold(
        title = list?.name ?: route.title.ifEmpty { "Mix" },
        subtitle = list?.let { l ->
            val bili = l.items.count(MixItems::isBili)
            "${l.items.size} 个视频 · 哔哩哔哩 $bili · YouTube ${l.items.size - bili}"
        },
        actions = {
            if (list != null && list.items.isNotEmpty()) {
                // Plays the whole Mix, Bilibili and YouTube one after another (MixPlayback).
                androidx.compose.material3.FilledTonalButton(onClick = { MixPlayback.start(list.id, 0) }) {
                    Icon(YoinSymbols.PlayFilled, null, Modifier.size(18.dp))
                    Text(" 全部播放")
                }
            }
            if (list != null) TextButton(onClick = { linking = true }) { Text("关联") }
            if (list != null) TextButton(onClick = { deleting = true }) { Text("删除") }
        },
    ) { padding ->
        if (list == null || list.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "长按视频卡片，选“加到 Mix”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
                if (list.links.isNotEmpty()) {
                    item(key = "links") {
                        Text(
                            "跟随：" + list.links.joinToString("、") { it.title.ifEmpty { if (it.kind == "bili_fav") "收藏夹" else "播放列表" } } + "。新增的会加到最后，原列表删掉的这里也会删掉。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                itemsIndexed(list.items, key = { _, it -> it.key }) { i, item ->
                    MixRow(
                        item,
                        Modifier.animateItem(),
                        playing = MixPlayback.listId == list.id && MixPlayback.index == i,
                        // From here on through the rest of the Mix.
                        onClick = { MixPlayback.start(list.id, i) },
                        onLongClick = { menu = item },
                    )
                }
            }
        }
    }
    if (linking && list != null) MixLinkSheet(list, onDismiss = { linking = false })
    menu?.let { item ->
        ItemActionSheet(
            title = item.title,
            cover = MixItems.secure(item.thumbnail),
            actions = listOf(
                SheetAction("从 Mix 移除", YoinSymbols.Delete, destructive = true) {
                    Library.removeFromPlaylist(route.id, item.key)
                    toast("已移除")
                },
            ),
            onDismiss = { menu = null },
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
 * 关联: follow a Bilibili 收藏夹 (the signed-in account's own) or a YouTube playlist (its link).
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
    val done: (Result<Int>) -> Unit = { r ->
        r.onSuccess { n -> toast(if (n > 0) "已加入 $n 个视频" else "已关联，没有新视频") }
            .onFailure { toast("同步失败：" + (it.message ?: "网络错误")) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
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
            Text(
                "跟着一个收藏夹或播放列表走：新增的视频加到 Mix 最后，删掉的也从 Mix 里拿掉；你自己加的不受影响。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            list.links.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.title.ifEmpty { l.ref }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (l.kind == "bili_fav") "哔哩哔哩收藏夹" else "YouTube 播放列表", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { MixSync.unlink(list.id, l.id) }) { Text("取消关联") }
                }
            }
            Text("哔哩哔哩收藏夹", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            val fs = folders
            when {
                fs == null -> Text("加载中…", modifier = Modifier.padding(8.dp))
                fs.isEmpty() -> Text("登录哔哩哔哩后可以关联自己的收藏夹", modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(fs, key = { it.id }) { f ->
                        val linked = list.links.any { it.id == "bili_fav:${f.id}" }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .combinedClickable(enabled = !linked) {
                                    MixSync.link(list.id, MixSync.biliFolderLink(f.id, f.title), done)
                                    onDismiss()
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
                            if (linked) Text("已关联", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Text("YouTube 播放列表", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("粘贴播放列表链接") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = url.contains("list="),
                    onClick = {
                        MixSync.link(list.id, MixSync.youtubePlaylistLink(url.trim(), "YouTube 播放列表"), done)
                        onDismiss()
                    },
                ) { Text("关联") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixRow(item: LibItem, modifier: Modifier, playing: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit) {
    val source = MixItems.source(item)
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 10f)) {
            NetImage(MixItems.secure(item.thumbnail), Modifier.fillMaxSize(), shape = CoverShape, thumbWidth = 320.dp)
            // Which part it opens in: the source's own colour, as on the account dots.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (source == VideoSource.Bilibili) Color(0xFFFB7299) else Color(0xFFFF0033)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.channelName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (playing) "${source.label} · 正在播放" else source.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}
