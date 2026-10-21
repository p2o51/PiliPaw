package com.gpo.pilipala.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import com.gpo.pilipala.feature.videomenu.VideoCardMenu
import com.gpo.pilipala.feature.videomenu.VideoMenuAction
import com.gpo.pilipala.feature.videomenu.isPlainVideo
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.widthClass
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
import com.gpo.pilipala.feature.player.PlayQueues
import com.gpo.pilipala.feature.player.QueueItem
import com.gpo.pilipala.feature.widget.WidgetStore
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.singleRoute
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
internal data class FavMedia(val video: VideoItem, val id: Long, val type: Int) {
    /** A plain UGC video, so it can be played as part of the folder's queue. */
    val queueable: Boolean get() = type == 2 && video.target == null && video.bvid.isNotEmpty()
}

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

    /** Building the play queue (every page of the folder is fetched first). */
    var queueLoading by mutableStateOf(false)
        private set

    private fun pageParams(pn: Int) = mapOf(
        "media_id" to mediaId, "pn" to pn, "ps" to QueuePageSize, "keyword" to keyword,
        "order" to order, "type" to 0, "tid" to 0, "platform" to "web",
    )

    private class QueuePage(val items: List<QueueItem>, val hasMore: Boolean, val total: Int)

    private suspend fun queuePage(pn: Int): QueuePage {
        val data = BiliClient.getData("/x/v3/fav/resource/list", pageParams(pn)).asObj
        return QueuePage(
            items = data.arr("medias").objects().mapNotNull { it.toQueueItem() },
            hasMore = data.bool("has_more"),
            total = data.obj("info").int("media_count"),
        )
    }

    /** Every playable video in the folder, in the current order (and search), pages fetched in parallel. */
    private suspend fun allQueueItems(): List<QueueItem> = coroutineScope {
        val first = queuePage(1)
        if (!first.hasMore) return@coroutineScope first.items
        if (keyword.isNotEmpty()) {
            // Search results: their count is unknown — walk the pages.
            val all = first.items.toMutableList()
            var pn = 2
            while (pn <= QueueMaxPages) {
                val page = queuePage(pn)
                all += page.items
                if (!page.hasMore) break
                pn++
            }
            return@coroutineScope all
        }
        val total = first.total.takeIf { it > 0 } ?: info.mediaCount
        val pages = ((total + QueuePageSize - 1) / QueuePageSize).coerceIn(2, QueueMaxPages)
        val gate = Semaphore(4)
        val rest = (2..pages).map { pn ->
            async { gate.withPermit { runCatching { queuePage(pn).items }.getOrDefault(emptyList()) } }
        }.awaitAll()
        first.items + rest.flatten()
    }

    /** Builds the folder's queue (shuffled list for 随机播放) and opens its first video (or [from]). */
    suspend fun buildQueue(shuffle: Boolean, from: String? = null): Route.Video? {
        if (queueLoading) return null
        queueLoading = true
        try {
            val items = allQueueItems().let { if (shuffle) it.shuffled() else it }
            val start = from?.let { b -> items.indexOfFirst { it.bvid == b } }?.also { i ->
                if (i < 0) throw IllegalStateException("没有可播放的视频")
            } ?: 0
            val title = info.title.ifEmpty { "收藏夹" }
            return PlayQueues.create(title, items, start)?.let { PlayQueues.route(it) }
                ?: throw IllegalStateException("没有可播放的视频")
        } finally {
            queueLoading = false
        }
    }
}

private const val QueuePageSize = 20
private const val QueueMaxPages = 100

/** A fav entry as a queue item; null for anything that is not a playable UGC video (失效, 番剧, 音频…). */
private fun JsonObject.toQueueItem(): QueueItem? {
    if (int("type", 2) != 2) return null
    if (int("attr") and 1 == 1) return null
    if (obj("ogv").long("season_id") > 0 || str("link").contains("/bangumi/")) return null
    val bvid = str("bvid").ifEmpty { str("bv_id") }
    if (bvid.isEmpty()) return null
    return QueueItem(
        bvid = bvid,
        aid = long("id"),
        cid = obj("ugc").long("first_cid"),
        title = str("title"),
        cover = str("cover"),
        owner = obj("upper").str("name"),
        duration = long("duration"),
    )
}

/**
 * 播放全部 + 随机播放 as one M3 split button (Yoin's Play split): the folder / list plays
 * as a queue. 随机播放 shuffles the list itself; the play mode stays 列表循环. Beside it the
 * 仅听声音 toggle ([listening]): the queue then starts audio-only and keeps it across items
 * (the player's queue panel can still turn it off). Compact widths show 随机播放 as its icon.
 */
@Composable
internal fun QueuePlayButtons(
    loading: Boolean,
    listening: Boolean,
    onListeningChange: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val compact = widthClass() == WidthClass.Compact
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        QueueSplitButton(loading, compact, onPlay, onShuffle)
        ListenToggle(listening) { on ->
            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onListeningChange(on)
        }
    }
}

@Composable
private fun ListenToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (checked) colors.primary else colors.surfaceContainerHighest,
        YoinMotion.defaultEffectsSpec(),
        label = "listenContainer",
    )
    val content by animateColorAsState(
        if (checked) colors.onPrimary else colors.onSurfaceVariant,
        YoinMotion.defaultEffectsSpec(),
        label = "listenContent",
    )
    ToggleButton(
        checked = checked,
        onCheckedChange = onChange,
        shapes = ToggleButtonDefaults.shapes(),
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = container,
            contentColor = content,
            checkedContainerColor = container,
            checkedContentColor = content,
        ),
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        modifier = Modifier.height(40.dp),
    ) {
        Icon(YoinSymbols.Headphones, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("仅听声音", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun QueueSplitButton(loading: Boolean, compact: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    SplitButtonLayout(
        leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onPlay()
                },
                enabled = !loading,
            ) {
                val effects = YoinMotion.defaultEffectsSpec<Float>()
                AnimatedContent(loading, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "playAll") { l ->
                    if (l) {
                        LoadingIndicator(Modifier.size(SplitButtonDefaults.LeadingIconSize))
                    } else {
                        Icon(YoinSymbols.PlayFilled, contentDescription = null, modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize))
                    }
                }
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("播放全部", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        },
        trailingButton = {
            SplitButtonDefaults.TrailingButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onShuffle()
                },
                enabled = !loading,
            ) {
                Icon(
                    YoinSymbols.Shuffle,
                    contentDescription = if (compact) "随机播放" else null,
                    modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                )
                if (!compact) {
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("随机播放", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        },
    )
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
    val nav = LocalNavigator.current
    // The folder as a playlist: 播放全部 / 随机播放 / 从这里播放, audio-only with 仅听声音 on.
    var listen by rememberSaveable { mutableStateOf(false) }
    val startQueue: (Boolean, String?) -> Unit = { shuffle, from ->
        scope.launch {
            runCatching { vm.buildQueue(shuffle, from) }
                .onSuccess { r -> r?.let { nav.push(it.copy(audioOnly = listen)) } }
                .onFailure {
                    // The folder could not be listed (or this one is not in it): play the video alone.
                    val single = from?.let { b -> vm.pager.items.firstOrNull { it.video.bvid == b } }
                    if (single != null) nav.push(single.video.singleRoute(listen)) else toast(it.friendlyMessage())
                }
        }
    }

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
                    modifier = Modifier.librarySeam(gridState, listPadding),
                    contentPadding = listPadding,
                ) {
                    if (vm.info.intro.isNotBlank() || vm.info.upperName.isNotEmpty()) {
                        item(key = "__info", span = { GridItemSpan(maxLineSpan) }) {
                            FavInfoHeader(vm.info)
                        }
                    }
                    item(key = "__play", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            QueuePlayButtons(
                                loading = vm.queueLoading,
                                listening = listen,
                                onListeningChange = { listen = it },
                                onPlay = { startQueue(false, null) },
                                onShuffle = { startQueue(true, null) },
                            )
                        }
                    }
                    items(vm.pager.items, key = { "${it.id}:${it.type}" }) { m ->
                        // A tap plays the folder from here (单集播放 in the menu opens it alone).
                        val queueable = m.queueable
                        VideoCardH(
                            m.video,
                            modifier = Modifier.animateItem(),
                            onClick = if (queueable) {
                                { startQueue(false, m.video.bvid) }
                            } else null,
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
        VideoCardMenu(
            m.video,
            isVideo = m.type == 2 && m.video.isPlainVideo,
            extras = buildList {
                if (m.queueable) {
                    add(VideoMenuAction("单集播放", YoinSymbols.PlayFilled) { nav.push(m.video.singleRoute(listen)) })
                }
                if (isOwner) {
                    add(
                        VideoMenuAction("取消收藏", YoinSymbols.Delete, destructive = true) {
                            scope.launch {
                                runCatching { vm.remove(m) }
                                    .onSuccess { toast("已取消收藏") }
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
    WidgetStore.laterChanged()
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
