package com.gpo.pilipala.feature.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.player.ListenTransport
import com.gpo.pilipala.feature.player.LyricsList
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayMode
import com.gpo.pilipala.feature.player.PlayQueue
import com.gpo.pilipala.feature.player.SubtitleLine
import com.gpo.pilipala.feature.player.lyricLines
import com.gpo.pilipala.feature.player.lyrics.LyricCandidate
import com.gpo.pilipala.feature.player.lyrics.LyricsSearch
import com.gpo.pilipala.feature.player.lyrics.LyricsStore
import com.gpo.pilipala.feature.player.lyrics.lyricQueryFrom
import com.gpo.pilipala.feature.player.lyricsBackdrop
import com.gpo.pilipala.feature.reply.ReplyList
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetNavBottom
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberPlayModeSymbolPainter
import kotlinx.coroutines.launch

/**
 * 仅听声音's own page, over the video page (which stays composed beneath, player and all):
 * the cover, the title, three lines of lyrics, Yoin's transport, and three ways on —
 * 播放列表 and 评论 as sheets, 显示画面 back to the video. Tapping the lyrics opens them
 * full height, where 搜索歌词 finds a song's lyrics on 网易云 / QQ 音乐. Wide windows put
 * the cover and transport left and the lyrics / list / comments right.
 */
@Composable
internal fun ListenScreen(
    vm: VideoDetailViewModel,
    ctrl: PiliPlayerController,
    queue: PlayQueue?,
    gate: (() -> Unit) -> Unit,
) {
    val nav = LocalNavigator.current
    var sheet by remember { mutableStateOf<ListenSheet?>(null) }
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    val lines = ctrl.lyricLines()
    val d = vm.detail
    val title = d?.title ?: ctrl.target?.title.orEmpty()
    val part = d?.pages?.takeIf { it.size > 1 }?.firstOrNull { it.cid == vm.cid }?.part
    val subtitle = listOfNotNull(d?.ownerName?.takeIf { it.isNotEmpty() }, part?.let { "P · $it" }).joinToString(" · ")
    val cover = d?.pic ?: vm.cover
    val index by (queue?.index ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0) }).collectAsState()
    val header = if (queue != null) "${queue.title} · ${index + 1} / ${queue.size}" else "仅听声音"

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .lyricsBackdrop()
            // Swallow touches so nothing reaches the video page beneath.
            .clickable(interactionSource = null, indication = null) {},
    ) {
        val wide = maxWidth >= 840.dp && maxWidth > maxHeight
        val bar: @Composable () -> Unit = {
            ListenTopBar(
                header = header,
                onCollapse = { nav.pop() },
                onSearch = { sheet = ListenSheet.Search },
                onShowVideo = { ctrl.toggleAudioOnly() },
            )
        }
        if (wide) {
            WideListen(
                ctrl, queue, vm, bar, cover, title, subtitle, lines,
                onSearch = { sheet = ListenSheet.Search },
            )
        } else {
            val enter = YoinMotion.defaultEffectsSpec<Float>()
            val exit = YoinMotion.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = lyricsOpen,
                transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                label = "listenLyrics",
            ) { open ->
                if (open) {
                    FullLyrics(
                        ctrl = ctrl,
                        lines = lines,
                        cover = cover,
                        title = title,
                        subtitle = subtitle,
                        onClose = { lyricsOpen = false },
                        onSearch = { sheet = ListenSheet.Search },
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 24.dp)
                            .padding(top = 8.dp, bottom = 12.dp),
                    ) {
                        bar()
                        Spacer(Modifier.height(16.dp))
                        ListenCover(cover, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(22.dp))
                        TitleRow(vm, title, subtitle, gate)
                        Spacer(Modifier.height(18.dp))
                        LyricsCard(
                            ctrl = ctrl,
                            lines = lines,
                            onOpen = { lyricsOpen = true },
                            onSearch = { sheet = ListenSheet.Search },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Transport and actions sit at the bottom, under the thumb.
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.height(16.dp))
                        ListenTransport(ctrl, controlSize = 56.dp, horizontalPadding = 0.dp)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (queue != null) {
                                QuietAction(YoinSymbols.Playlist, "播放列表") { sheet = ListenSheet.Queue }
                            }
                            QuietAction(PiliSymbols.Comment, d?.reply?.takeIf { it > 0 }?.let { "评论 ${Fmt.count(it)}" } ?: "评论") {
                                sheet = ListenSheet.Comments
                            }
                            QuietAction(YoinSymbols.Tv, "显示画面") { ctrl.toggleAudioOnly() }
                        }
                    }
                }
            }
        }
    }

    when (sheet) {
        ListenSheet.Queue -> if (queue != null) ListenQueueSheet(queue, ctrl, onDismiss = { sheet = null })
        ListenSheet.Comments -> d?.aid?.takeIf { it > 0 }?.let { aid ->
            CommentsSheet(aid, onSeek = { ctrl.seekTo(it * 1000) }, onDismiss = { sheet = null })
        }
        ListenSheet.Search -> LyricsSearchSheet(
            cid = ctrl.target?.cid ?: 0L,
            initialQuery = lyricQueryFrom(title),
            durationMs = ctrl.durationMs,
            hasPicked = LyricsStore.lines(ctrl.target?.cid ?: 0L) != null,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

private enum class ListenSheet { Queue, Comments, Search }

@Composable
private fun ListenTopBar(header: String, onCollapse: () -> Unit, onSearch: () -> Unit, onShowVideo: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onCollapse, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
            Icon(YoinSymbols.ChevronDown, contentDescription = "收起")
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在听", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text(header, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            FilledTonalIconButton(onClick = { menu = true }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
                Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("搜索歌词") },
                    leadingIcon = { Icon(YoinSymbols.Search, null) },
                    onClick = {
                        menu = false
                        onSearch()
                    },
                )
                DropdownMenuItem(
                    text = { Text("显示画面") },
                    leadingIcon = { Icon(YoinSymbols.Tv, null) },
                    onClick = {
                        menu = false
                        onShowVideo()
                    },
                )
            }
        }
    }
}

@Composable
private fun ListenCover(cover: String, modifier: Modifier) {
    NetImage(
        url = cover,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .shadow(24.dp, RoundedCornerShape(28.dp), clip = false)
            .clip(RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        thumbWidth = 720.dp,
    )
}

@Composable
private fun TitleRow(vm: VideoDetailViewModel, title: String, subtitle: String, gate: (() -> Unit) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val liked = vm.actions.liked
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        val scheme = MaterialTheme.colorScheme
        val container by animateColorAsState(if (liked) scheme.primary else scheme.secondaryContainer, YoinMotion.defaultEffectsSpec(), label = "listenLike")
        val content by animateColorAsState(if (liked) scheme.onPrimary else scheme.onSecondaryContainer, YoinMotion.defaultEffectsSpec(), label = "listenLikeContent")
        FilledTonalIconButton(
            onClick = {
                haptics.performHapticFeedback(if (liked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                gate { vm.actions.toggleLike() }
            },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = container, contentColor = content),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(if (liked) PiliSymbols.ThumbUpFilled else PiliSymbols.ThumbUp, contentDescription = if (liked) "取消点赞" else "点赞")
        }
    }
}

/** Three lines around the current one; a tap opens them all. Without lyrics: 搜索歌词. */
@Composable
private fun LyricsCard(
    ctrl: PiliPlayerController,
    lines: List<SubtitleLine>,
    onOpen: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .clickable(onClick = if (lines.isEmpty()) onSearch else onOpen)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (lines.isEmpty()) {
            Text("这个视频没有字幕", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(YoinSymbols.Search, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("在网易云 / QQ 音乐搜索歌词", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
            }
        } else {
            val current by remember(lines) { derivedStateOf { cueAt(lines, ctrl.positionMs) } }
            // The current line in the middle, except at the start, where the first three show.
            val first = (current - 1).coerceIn(0, (lines.size - 3).coerceAtLeast(0))
            val rows = listOf(first, first + 1, first + 2)
            rows.forEach { i ->
                val text = lines.getOrNull(i)?.text.orEmpty()
                val now = i == current
                Text(
                    text.ifEmpty { " " },
                    style = if (now) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (now) FontWeight.Bold else null,
                    color = if (now) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun cueAt(lines: List<SubtitleLine>, pos: Long): Int {
    val i = lines.indexOfLast { it.fromMs <= pos }
    return i.coerceAtLeast(0)
}

@Composable
private fun QuietAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(48.dp)) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** The lyrics full height, under a small header; the transport stays below. */
@Composable
private fun FullLyrics(
    ctrl: PiliPlayerController,
    lines: List<SubtitleLine>,
    cover: String,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    onSearch: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            NetImage(cover, Modifier.width(64.dp).aspectRatio(16f / 9f), shape = RoundedCornerShape(10.dp), thumbWidth = 128.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onSearch, shapes = IconButtonDefaults.shapes()) { Icon(YoinSymbols.Search, contentDescription = "搜索歌词") }
            FilledTonalIconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) { Icon(YoinSymbols.UnfoldLess, contentDescription = "收起歌词") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (lines.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无歌词", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onSearch) { Text("在网易云 / QQ 音乐搜索") }
                }
            } else {
                LyricsList(
                    lines = lines,
                    positionMs = { ctrl.positionMs },
                    onSeek = { ctrl.seekTo(it) },
                    frameHeight = maxHeight,
                    horizontalPadding = 24.dp,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        ListenTransport(ctrl, controlSize = 48.dp, horizontalPadding = 24.dp)
    }
}

/** Tablet landscape: cover, title and transport left; 歌词 / 播放列表 / 评论 right. */
@Composable
private fun WideListen(
    ctrl: PiliPlayerController,
    queue: PlayQueue?,
    vm: VideoDetailViewModel,
    bar: @Composable () -> Unit,
    cover: String,
    title: String,
    subtitle: String,
    lines: List<SubtitleLine>,
    onSearch: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = buildList {
        add("歌词")
        if (queue != null) add("播放列表")
        add("评论")
    }
    Row(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 40.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.weight(0.46f).fillMaxHeight()) {
            bar()
            Spacer(Modifier.height(20.dp))
            ListenCover(cover, Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            ListenTransport(ctrl, controlSize = 60.dp, horizontalPadding = 0.dp)
        }
        Column(
            Modifier
                .weight(0.54f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
                .padding(top = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { i, label ->
                    val on = tab.coerceIn(0, tabs.lastIndex) == i
                    val scheme = MaterialTheme.colorScheme
                    TextButton(
                        onClick = { tab = i },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            containerColor = if (on) scheme.primary else Color.Transparent,
                            contentColor = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.height(44.dp),
                    ) { Text(label, style = MaterialTheme.typography.titleSmall) }
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSearch, shapes = IconButtonDefaults.shapes()) { Icon(YoinSymbols.Search, contentDescription = "搜索歌词") }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tabs.getOrNull(tab.coerceIn(0, tabs.lastIndex))) {
                    "播放列表" -> if (queue != null) QueueList(queue, ctrl, Modifier.fillMaxSize())
                    "评论" -> vm.detail?.aid?.takeIf { it > 0 }?.let { aid ->
                        ReplyList(oid = aid, type = 1, modifier = Modifier.fillMaxSize(), onTimestampClick = { ctrl.seekTo(it * 1000) }, threadsInPlace = true)
                    }
                    else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        if (lines.isEmpty()) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("暂无歌词", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = onSearch) { Text("在网易云 / QQ 音乐搜索") }
                            }
                        } else {
                            LyricsList(
                                lines = lines,
                                positionMs = { ctrl.positionMs },
                                onSeek = { ctrl.seekTo(it) },
                                frameHeight = maxHeight,
                                horizontalPadding = 32.dp,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The play mode as three segments (Yoin's modes, spelled out), then the list. */
@Composable
private fun QueueList(queue: PlayQueue, ctrl: PiliPlayerController, modifier: Modifier = Modifier, bottom: Dp = 16.dp) {
    val index by queue.index.collectAsState()
    val mode by queue.mode.collectAsState()
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (index > 2) listState.scrollToItem(index - 2) }
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlayMode.entries.forEach { m ->
                val on = m == mode
                val scheme = MaterialTheme.colorScheme
                val container by animateColorAsState(if (on) scheme.primary else Color.Transparent, YoinMotion.defaultEffectsSpec(), label = "modeSeg")
                val content by animateColorAsState(if (on) scheme.onPrimary else scheme.onSurfaceVariant, YoinMotion.defaultEffectsSpec(), label = "modeSegContent")
                Row(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(container)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            queue.setMode(m)
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(rememberPlayModeSymbolPainter(m.symbol), null, tint = content, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(m.label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom),
        ) {
            itemsIndexed(queue.items, key = { _, it -> "lq_${it.bvid}" }) { i, item ->
                QueueRow(
                    item = item,
                    position = i,
                    current = i == index,
                    playing = ctrl.isPlaying,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        if (i != index) ctrl.playQueueIndex(i)
                    },
                )
            }
        }
    }
}

@Composable
private fun ListenQueueSheet(queue: PlayQueue, ctrl: PiliPlayerController, onDismiss: () -> Unit) {
    val navBottom = sheetNavBottom()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, contentWindowInsets = SheetInsets, properties = rememberSheetProperties()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(queue.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${queue.size} 个视频", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        QueueList(queue, ctrl, Modifier.fillMaxWidth().heightIn(max = maxHeight + navBottom), bottom = 24.dp + navBottom)
    }
}

@Composable
private fun CommentsSheet(aid: Long, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    val height = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, contentWindowInsets = SheetInsets, properties = rememberSheetProperties()) {
        ReplyList(
            oid = aid,
            type = 1,
            modifier = Modifier.fillMaxWidth().height(height),
            contentPadding = PaddingValues(bottom = sheetNavBottom() + 16.dp),
            onTimestampClick = onSeek,
            threadsInPlace = true,
        )
    }
}

/**
 * 搜索歌词: the query starts from the title (《歌名》, or the title without its tags) and can
 * be retyped; a result is fetched, kept for this part and replaces the subtitles.
 */
@Composable
private fun LyricsSearchSheet(cid: Long, initialQuery: String, durationMs: Long, hasPicked: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<LyricCandidate>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf<LyricCandidate?>(null) }
    val run: () -> Unit = {
        searching = true
        scope.launch {
            // Songs as long as this video (within 3s) are most likely the one playing: first.
            results = runCatching { LyricsSearch.search(query) }.getOrDefault(emptyList())
                .sortedBy { c -> if (durationMs > 0 && c.durationMs > 0 && kotlin.math.abs(c.durationMs - durationMs) <= 3_000) 0 else 1 }
            searching = false
        }
    }
    LaunchedEffect(Unit) { if (query.isNotBlank()) run() }
    val navBottom = sheetNavBottom()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, contentWindowInsets = SheetInsets, properties = rememberSheetProperties()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("搜索歌词", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("在网易云和 QQ 音乐里找这首歌，选中后记在这个分 P 上", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("歌名 歌手") },
                leadingIcon = { Icon(YoinSymbols.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run() }),
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasPicked) {
                TextButton(onClick = {
                    scope.launch {
                        LyricsStore.clear(cid)
                        toast("已换回视频字幕")
                        onDismiss()
                    }
                }) { Text("换回视频自己的字幕") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = maxHeight + navBottom)) {
            val list = results
            when {
                searching -> LoadingIndicator(Modifier.align(Alignment.Center))
                list != null && list.isEmpty() -> Text(
                    "没有找到，换个关键词试试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
                list != null -> LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp + navBottom)) {
                    itemsIndexed(list, key = { _, c -> "${c.source}_${c.id}" }) { _, c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = fetching == null) {
                                    fetching = c
                                    scope.launch {
                                        val lines = LyricsSearch.fetch(c)
                                        fetching = null
                                        if (lines == null) {
                                            toast("这首没有可同步的歌词")
                                        } else {
                                            LyricsStore.save(cid, lines)
                                            toast("已使用 ${c.source.label} 的歌词")
                                            onDismiss()
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val meta = listOf(c.artist, c.album).filter { it.isNotBlank() }.joinToString(" · ")
                                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(12.dp))
                            if (fetching == c) {
                                LoadingIndicator(Modifier.size(24.dp))
                            } else {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(c.source.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    if (c.durationMs > 0) {
                                        Text(Fmt.duration(c.durationMs / 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
