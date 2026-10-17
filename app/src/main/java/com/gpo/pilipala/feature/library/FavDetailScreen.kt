package com.gpo.pilipala.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class FavInfo(
    val title: String = "",
    val cover: String = "",
    val intro: String = "",
    val mediaCount: Int = 0,
    val upperName: String = "",
    val upperFace: String = "",
    val upperMid: Long = 0,
    val attr: Int = 0,
)

/** One fav entry: the card model plus what batch-del needs. */
internal data class FavMedia(val video: VideoItem, val id: Long, val type: Int)

internal class FavDetailViewModel(val mediaId: Long) : ViewModel() {
    var keyword by mutableStateOf("")
        private set
    var order by mutableStateOf("mtime")
        private set
    var info by mutableStateOf(FavInfo())
        private set

    val pager = Pager(viewModelScope, dedupKey = { "${it.id}:${it.type}" }) { cursor ->
        val pn = cursor?.toIntOrNull() ?: 1
        val data = BiliClient.getData(
            "/x/v3/fav/resource/list",
            mapOf(
                "media_id" to mediaId, "pn" to pn, "ps" to 20, "keyword" to keyword,
                "order" to order, "type" to 0, "tid" to 0, "platform" to "web",
            ),
        ).asObj
        data.obj("info")?.let { i ->
            info = FavInfo(
                title = i.str("title"),
                cover = i.str("cover"),
                intro = i.str("intro"),
                mediaCount = i.int("media_count"),
                upperName = i.obj("upper").str("name"),
                upperFace = i.obj("upper").str("face"),
                upperMid = i.obj("upper").long("mid"),
                attr = i.int("attr"),
            )
        }
        val list = data.arr("medias").objects().map { FavMedia(it.favMediaToVideo(), it.long("id"), it.int("type", 2)) }
        Page(list, data.bool("has_more"), (pn + 1).toString())
    }

    init {
        pager.refresh()
    }

    fun search(kw: String) {
        if (kw == keyword) return
        keyword = kw
        pager.refresh()
    }

    fun sort(o: String) {
        if (o == order) return
        order = o
        pager.refresh()
    }

    suspend fun remove(m: FavMedia) {
        BiliClient.postData(
            "/x/v3/fav/resource/batch-del",
            mapOf("resources" to "${m.id}:${m.type}", "media_id" to mediaId, "platform" to "web"),
        )
        pager.update { l -> l.filterNot { it.id == m.id && it.type == m.type } }
        info = info.copy(mediaCount = (info.mediaCount - 1).coerceAtLeast(0))
    }
}

private val FavOrders = listOf("mtime" to "最近收藏", "view" to "最多播放", "pubtime" to "最新投稿")

@Composable
fun FavDetailScreen(route: Route.FavDetail) {
    val vm = viewModel { FavDetailViewModel(route.mediaId) }
    val cover = vm.info.cover.ifEmpty { route.cover }
    CoverTheme(cover) {
        FavDetailContent(route, vm)
    }
}

@Composable
private fun FavDetailContent(route: Route.FavDetail, vm: FavDetailViewModel) {
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(vm.keyword.isNotEmpty()) }
    var query by remember { mutableStateOf(vm.keyword) }
    var sortOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<FavMedia?>(null) }
    val isOwner = vm.info.upperMid != 0L && vm.info.upperMid == BiliClient.mid
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { vm.pager.loadMore() }

    LaunchedEffect(query) {
        delay(400)
        vm.search(query.trim())
    }

    LibraryScaffold(
        title = vm.info.title.ifEmpty { route.title.ifEmpty { "收藏夹" } },
        subtitle = if (vm.info.mediaCount > 0) "${vm.info.mediaCount}个内容" else null,
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
                    FavOrders.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                sortOpen = false
                                vm.sort(key)
                            },
                            trailingIcon = if (vm.order == key) {
                                { Icon(YoinSymbols.Check, null) }
                            } else null,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = searching,
                enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
                exit = shrinkVertically(YoinMotion.fastSpatialSpec()) + fadeOut(YoinMotion.fastEffectsSpec()),
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            ) {
                SearchField(query, onChange = { query = it }, placeholder = "搜索收藏")
            }
            val innerPadding = if (searching) {
                PaddingValues(bottom = padding.calculateBottomPadding())
            } else padding
            PagerBody(vm.pager, innerPadding, emptyLabel = if (vm.keyword.isNotEmpty()) "无结果" else "收藏夹为空", emptyIcon = YoinSymbols.Folder) { listPadding ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(RowGridMinCell),
                    state = gridState,
                    contentPadding = listPadding,
                ) {
                    if (vm.info.intro.isNotBlank() || vm.info.upperName.isNotEmpty()) {
                        item(key = "__info", span = { GridItemSpan(maxLineSpan) }) {
                            FavInfoHeader(vm.info)
                        }
                    }
                    items(vm.pager.items, key = { "${it.id}:${it.type}" }) { m ->
                        VideoCardH(
                            m.video,
                            modifier = Modifier.animateItem(),
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sheet = m
                            },
                        )
                    }
                    pagerFooter(vm.pager)
                }
            }
        }
    }

    sheet?.let { m ->
        ItemActionSheet(
            title = m.video.title,
            cover = m.video.cover,
            actions = buildList {
                if (isOwner) {
                    add(
                        SheetAction("取消收藏", YoinSymbols.Delete, destructive = true) {
                            scope.launch {
                                runCatching { vm.remove(m) }
                                    .onSuccess { toast("已取消收藏") }
                                    .onFailure { toast(it.friendlyMessage()) }
                            }
                        },
                    )
                }
                if (m.video.aid > 0 && m.video.target == null) {
                    add(
                        SheetAction("稍后再看", PiliSymbols.WatchLater) {
                            scope.launch {
                                runCatching { addToLater(m.video.aid) }
                                    .onSuccess { toast("已添加") }
                                    .onFailure { toast(it.friendlyMessage()) }
                            }
                        },
                    )
                }
            },
            onDismiss = { sheet = null },
        )
    }
}

internal suspend fun addToLater(aid: Long) {
    BiliClient.postData("/x/v2/history/toview/add", mapOf("aid" to aid))
}

@Composable
private fun FavInfoHeader(info: FavInfo) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 840.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (info.upperName.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(info.upperFace, 24.dp)
                Text(
                    info.upperName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (info.intro.isNotBlank()) {
            Text(info.intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Filled pill search field (no indicator line, no outline). */
@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(YoinSymbols.Search, null) },
        trailingIcon = if (value.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(YoinSymbols.Close, contentDescription = "清除") } }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focus),
    )
}
