package com.gpo.pilipala.yt.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.library.ConfirmDialog
import com.gpo.pilipala.feature.library.FolderCard
import com.gpo.pilipala.feature.library.ItemActionSheet
import com.gpo.pilipala.feature.library.RowGridMinCell
import com.gpo.pilipala.feature.library.SheetAction
import com.gpo.pilipala.feature.sources.MixListScreen
import com.gpo.pilipala.feature.sources.asFolder
import com.gpo.pilipala.feature.sources.toCard
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.PiliLargeTopBar
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.pilipala.yt.feature.player.miniPlayerInset
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

/** Which local list a [Route.LocalList] shows. */
enum class LocalList(val title: String) { History("历史记录"), WatchLater("稍后再看") }

/**
 * The YouTube library pages' frame, PiliPaw's LibraryScaffold: the large flexible bar with a
 * subtitle, collapsing as the list scrolls. [content] gets the padding for the bar, the
 * navigation bar and the mini player.
 */
@Composable
internal fun YtLibraryScaffold(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val nav = LocalNavigator.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            PiliLargeTopBar(title = title, subtitle = subtitle, scrollBehavior = scrollBehavior, onBack = { nav.pop() }, actions = actions)
        },
    ) { padding ->
        content(
            PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + miniPlayerInset() + 24.dp,
            ),
        )
    }
}

/**
 * 历史记录 / 稍后再看 (本机), as PiliPaw's: row cards in a grid (one column on phones, two from
 * tablet portrait), history cards showing how far each was watched. A long press opens the
 * item's sheet; 清空 asks first.
 */
@Composable
fun LocalListScreen(route: Route.LocalList) {
    val kind = LocalList.valueOf(route.kind)
    val toast = rememberToast()
    val list by (if (kind == LocalList.History) Library.history else Library.watchLater).collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<LibItem?>(null) }
    YtLibraryScaffold(
        title = kind.title,
        subtitle = if (list.isEmpty()) null else "${list.size}个视频",
        actions = {
            if (kind == LocalList.History && list.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(YoinSymbols.Delete, "清空")
                }
            }
        },
    ) { padding ->
        if (list.isEmpty()) {
            if (kind == LocalList.History) {
                EmptyState("暂无历史记录", Modifier.padding(padding), icon = PiliSymbols.History)
            } else {
                EmptyState("稍后再看为空", Modifier.padding(padding), icon = PiliSymbols.WatchLater)
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(RowGridMinCell), Modifier.fillMaxSize(), contentPadding = padding) {
                items(list, key = { it.key }) { item ->
                    val video = item.toVideo()
                    // History: seconds watched (-1 when finished), as PiliPaw's history cards.
                    val progress = when {
                        kind != LocalList.History || item.positionMs <= 0 -> 0L
                        item.durationSec > 0 && item.positionMs >= item.durationSec * 1000 - 1000 -> -1L
                        else -> item.positionMs / 1000
                    }
                    VideoCardH(
                        video.toCard().copy(progress = progress),
                        Modifier.animateItem(),
                        onLongClick = { sheetFor = item },
                        onClick = { Player.play(video.url, video) },
                    )
                }
            }
        }
    }
    sheetFor?.let { item ->
        ItemActionSheet(
            title = item.title,
            cover = item.thumbnail,
            actions = listOf(
                if (kind == LocalList.History) {
                    SheetAction("从历史记录移除", YoinSymbols.Delete, destructive = true) {
                        Library.removeFromHistory(item.key)
                        toast("已移除")
                    }
                } else {
                    SheetAction("移出稍后再看", YoinSymbols.Delete, destructive = true) {
                        Library.toggleWatchLater(item.toVideo())
                        toast("已移除")
                    }
                },
            ),
            onDismiss = { sheetFor = null },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "清空历史记录",
            confirmLabel = "清空",
            onConfirm = {
                Library.clearHistory()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** Mix: every Mix list as PiliPaw's folder cards (as in 收藏); one opens PiliPaw's Mix page. */
@Composable
fun PlaylistsScreen() {
    val biliNav = com.gpo.pilipala.navigation.LocalNavigator.current
    val lists by Library.playlists.collectAsState()
    var create by remember { mutableStateOf(false) }
    YtLibraryScaffold(
        title = "Mix",
        subtitle = if (lists.isEmpty()) null else "${lists.size}个",
        actions = {
            IconButton(onClick = { create = true }, shapes = IconButtonDefaults.shapes()) { Icon(YoinSymbols.Add, "新建 Mix") }
        },
    ) { padding ->
        if (lists.isEmpty()) {
            EmptyState("暂无 Mix", Modifier.padding(padding), icon = YoinSymbols.Library)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(168.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(lists, key = { it.id }) { p ->
                    val route = com.gpo.pilipala.navigation.Route.MixList(p.id, p.name)
                    FolderCard(p.asFolder(), Modifier.animateItem(), route = route, onClick = { biliNav.push(route) })
                }
            }
        }
    }
    if (create) {
        NameDialog("新建 Mix", "", onDismiss = { create = false }) {
            val p = Library.createPlaylist(it)
            biliNav.push(com.gpo.pilipala.navigation.Route.MixList(p.id, p.name))
        }
    }
}

/** One Mix list: PiliPaw's Mix page (cover theme, source counts, play split, rows). */
@Composable
fun LocalPlaylistScreen(route: Route.LocalPlaylist) {
    val name = Library.playlists.collectAsState().value.firstOrNull { it.id == route.id }?.name.orEmpty()
    MixListScreen(com.gpo.pilipala.navigation.Route.MixList(route.id, name))
}

/** A name, typed into a filled field (no outlines: colour blocks, not strokes). */
@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { FilledNameField(name, { name = it }) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onDone(name)
                onDismiss()
            }) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** A single-line filled field on a tonal block, PiliPaw's FeedTextField look. */
@Composable
internal fun FilledNameField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "名称") {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        shape = YoinContainerShapes.Card,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}
