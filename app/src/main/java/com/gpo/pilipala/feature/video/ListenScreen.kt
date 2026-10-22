package com.gpo.pilipala.feature.video

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.listen.ChargeLikeButton
import com.gpo.pilipala.feature.listen.ListenFlight
import com.gpo.pilipala.feature.listen.ListenPage
import com.gpo.pilipala.feature.listen.ListenPlayback
import com.gpo.pilipala.feature.listen.ListenSource
import com.gpo.pilipala.feature.player.BiliListenPlayback
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayMode
import com.gpo.pilipala.feature.player.PlayQueue
import com.gpo.pilipala.feature.player.QueueItem
import com.gpo.pilipala.feature.sources.MixItems
import com.gpo.pilipala.feature.sources.MixPlayback
import com.gpo.pilipala.feature.player.SubtitleLine
import com.gpo.pilipala.feature.player.lyricLines
import com.gpo.pilipala.feature.player.lyrics.LyricsStore
import com.gpo.pilipala.feature.player.lyrics.lyricQueryFrom
import com.gpo.pilipala.feature.reply.ReplyList
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.sheetNavBottom
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.rememberPlayModeSymbolPainter

/**
 * Bilibili's 仅听声音, over the video page (which stays composed beneath, player and all): the
 * shared listen page ([ListenPage]) on this page's player, detail and queue. 收起 leaves the
 * page; 显示画面 brings the picture back; holding 点赞 asks for a 三连 ([onTriple]).
 */
@Composable
internal fun ListenScreen(
    vm: VideoDetailViewModel,
    ctrl: PiliPlayerController,
    queue: PlayQueue?,
    gate: (() -> Unit) -> Unit,
    flight: ListenFlight? = null,
    onTriple: () -> Unit = {},
) {
    val nav = LocalNavigator.current
    val lines = ctrl.lyricLines()
    val index by (queue?.index ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0) }).collectAsState()
    val source = remember(vm, ctrl, queue) { BiliListenSource(vm, ctrl, queue, gate, nav::pop, onTriple) }
    source.lines = lines
    source.index = index
    source.gate = gate
    source.onTriple = onTriple
    ListenPage(source, flight)
}

@Stable
private class BiliListenSource(
    val vm: VideoDetailViewModel,
    val ctrl: PiliPlayerController,
    val queue: PlayQueue?,
    var gate: (() -> Unit) -> Unit,
    val pop: () -> Unit,
    var onTriple: () -> Unit,
) : ListenSource {
    override var lines: List<SubtitleLine> by mutableStateOf(emptyList())
    var index by mutableIntStateOf(0)

    override val title: String get() = vm.detail?.title ?: ctrl.target?.title.orEmpty()
    override val subtitle: String
        get() {
            val d = vm.detail
            val part = d?.pages?.takeIf { it.size > 1 }?.firstOrNull { it.cid == vm.cid }?.part
            return listOfNotNull(d?.ownerName?.takeIf { it.isNotEmpty() }, part).joinToString("  ")
        }
    override val cover: String get() = vm.detail?.pic ?: vm.cover
    override val header: String get() = if (queue != null) "${queue.title}  ${index + 1} / ${queue.size}" else mixHeader(mixKey) ?: "仅听声音"
    override val playback: ListenPlayback = BiliListenPlayback(ctrl)
    private val mixKey: String? get() = ctrl.target?.bvid?.let { "bili:$it" }
    override val hasQueue: Boolean get() = queue != null || MixPlayback.runs(mixKey)
    override val commentsLabel: String get() = vm.detail?.reply?.takeIf { it > 0 }?.let { "评论 ${Fmt.count(it)}" } ?: "评论"
    override val hasComments: Boolean get() = (vm.detail?.aid ?: 0L) > 0

    private val cid: Long get() = ctrl.target?.cid ?: 0L
    override val lyricsQuery: String get() = lyricQueryFrom(title)
    override val hasPickedLyrics: Boolean get() = LyricsStore.lines(cid) != null
    override suspend fun pickLyrics(lines: List<SubtitleLine>) = LyricsStore.save(cid, lines)
    override suspend fun clearLyrics() = LyricsStore.clear(cid)

    override fun collapse() = pop()
    override fun showVideo() = ctrl.toggleAudioOnly()

    /** 点赞; held, 三连 (its sheet asks before any coin goes out). */
    @Composable
    override fun Like() {
        ChargeLikeButton(
            checked = vm.actions.liked,
            icon = PiliSymbols.ThumbUp,
            checkedIcon = PiliSymbols.ThumbUpFilled,
            label = "点赞",
            checkedLabel = "取消点赞",
            onClick = { gate { vm.actions.toggleLike() } },
            onCharged = { gate(onTriple) },
            celebration = vm.actions.tripleCelebration,
        )
    }

    @Composable
    override fun QueueSheet(onDismiss: () -> Unit) {
        if (queue != null) ListenQueueSheet(queue, ctrl, onDismiss)
        else if (MixPlayback.runs(mixKey)) MixQueueSheet(ctrl.isPlaying, onDismiss)
    }

    @Composable
    override fun QueuePane(modifier: Modifier) {
        if (queue != null) QueueList(queue, ctrl, modifier)
        else if (MixPlayback.runs(mixKey)) MixQueueList(ctrl.isPlaying, modifier)
    }

    @Composable
    override fun CommentsSheet(onDismiss: () -> Unit) {
        val aid = vm.detail?.aid?.takeIf { it > 0 } ?: return
        BiliCommentsSheet(aid, onSeek = { ctrl.seekTo(it * 1000) }, onDismiss = onDismiss)
    }

    @Composable
    override fun CommentsPane(modifier: Modifier) {
        val aid = vm.detail?.aid?.takeIf { it > 0 } ?: return
        ReplyList(oid = aid, type = 1, modifier = modifier, onTimestampClick = { ctrl.seekTo(it * 1000) }, threadsInPlace = true)
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
        PlayModeSegments(mode) { queue.setMode(it) }
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
private fun BiliCommentsSheet(aid: Long, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
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

/** The play mode as three segments (Yoin's modes, spelled out). */
@Composable
internal fun PlayModeSegments(mode: PlayMode, onPick: (PlayMode) -> Unit) {
    val haptics = LocalHapticFeedback.current
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
                            onPick(m)
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
}

/**
 * A Mix playing through (MixPlayback): its videos, B站 and YouTube alike, with the play mode
 * on top; a tap plays that one in its own source's player.
 */
@Composable
internal fun MixQueueList(playing: Boolean, modifier: Modifier = Modifier, bottom: Dp = 16.dp) {
    val haptics = LocalHapticFeedback.current
    val items = MixPlayback.items
    val index = MixPlayback.index
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (index > 2) listState.scrollToItem(index - 2) }
    Column(modifier) {
        PlayModeSegments(MixPlayback.mode, MixPlayback::changeMode)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom),
        ) {
            itemsIndexed(items, key = { i, it -> "mq_${i}_${it.key}" }) { i, item ->
                QueueRow(
                    item = QueueItem(
                        bvid = item.key,
                        title = item.title,
                        cover = MixItems.secure(item.thumbnail),
                        owner = listOf(MixItems.source(item).label, item.channelName).filter { it.isNotEmpty() }.joinToString(" · "),
                        duration = item.durationSec,
                    ),
                    position = i,
                    current = i == index,
                    playing = playing,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        MixPlayback.playAt(i)
                    },
                )
            }
        }
    }
}

@Composable
internal fun MixQueueSheet(playing: Boolean, onDismiss: () -> Unit) {
    val navBottom = sheetNavBottom()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, contentWindowInsets = SheetInsets, properties = rememberSheetProperties()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(MixPlayback.title.ifEmpty { "Mix" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${MixPlayback.items.size} 个视频", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MixQueueList(playing, Modifier.fillMaxWidth().heightIn(max = maxHeight + navBottom), bottom = 24.dp + navBottom)
    }
}

/** The listen page's header while a Mix plays through: its name and where in it. */
internal fun mixHeader(key: String?): String? =
    MixPlayback.listId?.takeIf { MixPlayback.runs(key) }?.let { "${MixPlayback.title.ifEmpty { "Mix" }}  ${MixPlayback.index + 1} / ${MixPlayback.items.size}" }
