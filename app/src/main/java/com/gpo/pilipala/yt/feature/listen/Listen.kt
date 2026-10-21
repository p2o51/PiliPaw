package com.gpo.pilipala.yt.feature.listen

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.yt.feature.comments.CommentsList
import com.gpo.pilipala.yt.feature.comments.rememberCommentsState
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.player.Cue
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.pilipala.feature.settings.Choice
import com.gpo.pilipala.feature.settings.ConnectedToggleRow
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.theme.GoogleSansFlexRounded
import com.gpo.pilipala.ui.theme.LocalPageBackdrop
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.SymbolPlayMode
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberPlayModeSymbolPainter
import com.gpo.yoin.symbols.rememberPlayPauseSymbolPainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// 听 YouTube (design §4 C): PiliPaw 0.4.35's listen page (feature/video/ListenScreen.kt,
// LyricsPane, ListenTransport), on PipePaw's player. Its look and behaviour match PiliPaw's,
// so the merged app has one listen page.

/**
 * 仅听声音's page over the open player: the cover, the title, the subtitles as lyrics (or the
 * lines picked from 网易云 / QQ 音乐), Yoin's transport, and 评论 / 显示画面 on the bottom
 * edge. Coming in, the picture flies into the cover's place ([from]: the picture's window rect).
 * Wide windows put the cover and transport left and the lyrics / comments right.
 */
@Composable
fun ListenScreen(from: () -> Rect?) {
    val session = Player.session ?: return
    var sheet by remember { mutableStateOf<ListenSheet?>(null) }
    val key = remember(session.url) { Library.keyOf(session.url) }
    LaunchedEffect(key) { LyricsStore.load(key) }
    val lines = LyricsStore.lines(key) ?: Player.cues
    val title = session.title
    val subtitle = session.channelName
    val cover = session.thumbnail

    // The flight in: the picture's rect → the cover's, on the slow spatial spring.
    val progress = remember { Animatable(0f) }
    val spec = YoinMotion.slowSpatialSpec<Float>(YoinMotionRole.Standard)
    LaunchedEffect(Unit) { progress.animateTo(1f, spec) }
    val flight = remember { ListenFlight({ progress.value }, from) }

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInWindow() }
            .clickable(interactionSource = null, indication = null) {},
    ) {
        val scheme = MaterialTheme.colorScheme
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = (progress.value / 0.6f).coerceIn(0f, 1f) }
                .drawBehind { drawRect(scheme.surfaceContainerLowest) }
                .lyricsBackdrop(),
        )
        val wide = maxWidth >= 840.dp && maxWidth > maxHeight
        val bar: @Composable () -> Unit = {
            ListenTopBar(
                onCollapse = { Player.expanded = false },
                onSearch = { sheet = ListenSheet.Search },
                onShowVideo = { Player.changeAudioOnly(false) },
            )
        }
        if (wide) {
            WideListen(flight, bar, cover, title, subtitle, lines, onSearch = { sheet = ListenSheet.Search })
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 8.dp, bottom = 2.dp),
            ) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Box(Modifier.listenReveal(flight, 0.3f)) { bar() }
                    Spacer(Modifier.height(16.dp))
                    ListenCover(flight, cover, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.listenReveal(flight, 0.4f)) { TitleRow(title, subtitle) }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).listenReveal(flight, 0.5f)) {
                    ImmersiveLyrics(lines, maxHeight, 24.dp, MaterialTheme.typography.headlineMedium) { sheet = ListenSheet.Search }
                }
                Column(Modifier.padding(horizontal = 24.dp).listenReveal(flight, 0.58f)) {
                    ListenTransport(controlSize = 56.dp, horizontalPadding = 0.dp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        QuietAction(PiliSymbols.Comment, "评论") { sheet = ListenSheet.Comments }
                        QuietAction(YoinSymbols.Tv, "显示画面") { Player.changeAudioOnly(false) }
                    }
                }
            }
        }
        FlyingCover(flight, cover) { rootOrigin }
    }

    when (sheet) {
        ListenSheet.Comments -> CommentsSheet(session.url, onDismiss = { sheet = null })
        ListenSheet.Search -> LyricsSearchSheet(
            key = key,
            initialQuery = lyricQueryFrom(title),
            durationMs = Player.durationMs,
            hasPicked = LyricsStore.lines(key) != null,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

private enum class ListenSheet { Comments, Search }

/** The move into 仅听声音: [progress] 0 the picture, 1 the cover; [from] the picture's rect. */
private class ListenFlight(val progress: () -> Float, val from: () -> Rect?) {
    var coverRect by mutableStateOf<Rect?>(null)
    val flying: Boolean get() = abs(progress() - 1f) > 0.002f
}

/** A piece of the listen page that rises in once the flight has carried [start] of the way. */
private fun Modifier.listenReveal(flight: ListenFlight, start: Float): Modifier = graphicsLayer {
    val t = ((flight.progress() - start) / (1f - start)).coerceIn(0f, 1f)
    alpha = t
    translationY = (1f - t) * 28.dp.toPx()
}

/** The card in flight between the picture's slot and the cover's. */
@Composable
private fun FlyingCover(flight: ListenFlight, cover: String, origin: () -> Offset) {
    val to = flight.coverRect ?: return
    if (!flight.flying) return
    Box(
        Modifier
            .layout { m, c ->
                val p = flight.progress()
                val from = flight.from() ?: to.deflate(to.width * 0.05f)
                val r = androidx.compose.ui.geometry.lerp(from, to, p)
                val placeable = m.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)))
                val o = origin()
                layout(c.maxWidth, c.maxHeight) { placeable.place((r.left - o.x).roundToInt(), (r.top - o.y).roundToInt()) }
            }
            .graphicsLayer {
                val k = flight.progress().coerceIn(0f, 1f)
                shape = RoundedCornerShape(28.dp.toPx() * k)
                clip = true
                shadowElevation = 24.dp.toPx() * k
            },
    ) {
        NetImage(cover, Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp), thumbWidth = 720.dp)
    }
}

@Composable
private fun ListenTopBar(onCollapse: () -> Unit, onSearch: () -> Unit, onShowVideo: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onCollapse, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
            Icon(YoinSymbols.ChevronDown, contentDescription = "收起")
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在听", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("仅听声音", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        Box {
            FilledTonalIconButton(onClick = { menu = true }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
                Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("搜索歌词") }, leadingIcon = { Icon(YoinSymbols.Search, null) }, onClick = {
                    menu = false
                    onSearch()
                })
                DropdownMenuItem(text = { Text("显示画面") }, leadingIcon = { Icon(YoinSymbols.Tv, null) }, onClick = {
                    menu = false
                    onShowVideo()
                })
            }
        }
    }
}

@Composable
private fun ListenCover(flight: ListenFlight, cover: String, modifier: Modifier) {
    NetImage(
        url = cover,
        modifier = modifier
            .aspectRatio(16f / 9f)
            .onGloballyPositioned { flight.coverRect = it.boundsInWindow() }
            .graphicsLayer { alpha = if (flight.flying) 0f else 1f }
            .shadow(24.dp, RoundedCornerShape(28.dp), clip = false)
            .clip(RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        thumbWidth = 720.dp,
    )
}

@Composable
private fun TitleRow(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The lyrics with no frame, the current line seated mid-height; without any: 搜索歌词. */
@Composable
private fun ImmersiveLyrics(lines: List<Cue>, frameHeight: Dp, horizontalPadding: Dp, style: TextStyle, onSearch: () -> Unit) {
    if (lines.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (Player.subtitleTracks.isEmpty()) "这个视频没有字幕" else "字幕加载中",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSearch) {
                Icon(YoinSymbols.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("在网易云 / QQ 音乐搜索歌词")
            }
        }
    } else {
        val live = rememberLivePosition()
        LyricsList(
            lines = lines,
            positionMs = { live.value },
            onSeek = { Player.exo.seekTo(it) },
            frameHeight = frameHeight,
            horizontalPadding = horizontalPadding,
            modifier = Modifier.fillMaxSize(),
            textStyle = style,
        )
    }
}

@Composable
private fun QuietAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(48.dp)) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Tablet landscape: cover, title and transport left; 歌词 / 评论 right. */
@Composable
private fun WideListen(
    flight: ListenFlight,
    bar: @Composable () -> Unit,
    cover: String,
    title: String,
    subtitle: String,
    lines: List<Cue>,
    onSearch: () -> Unit,
) {
    var right by rememberSaveable { mutableStateOf(0) }
    val url = Player.session?.url.orEmpty()
    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 32.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Box(Modifier.listenReveal(flight, 0.3f)) { bar() }
            Spacer(Modifier.height(16.dp))
            ListenCover(flight, cover, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Box(Modifier.listenReveal(flight, 0.4f)) { TitleRow(title, subtitle) }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.listenReveal(flight, 0.58f)) { ListenTransport(controlSize = 56.dp, horizontalPadding = 0.dp) }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f).fillMaxHeight().listenReveal(flight, 0.5f)) {
            ConnectedToggleRow(listOf(Choice(0, "歌词"), Choice(1, "评论")), right, { right = it }, Modifier.padding(bottom = 8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                if (right == 0) {
                    ImmersiveLyrics(lines, maxHeight, 0.dp, MaterialTheme.typography.headlineMedium, onSearch)
                } else {
                    CommentsList(rememberCommentsState(url), { Player.exo.seekTo(it * 1000) }, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun CommentsSheet(url: String, onDismiss: () -> Unit) {
    val state = rememberCommentsState(url)
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
        CommentsList(state, { Player.exo.seekTo(it * 1000) }, Modifier.fillMaxWidth(), PaddingValues(bottom = sheetBottomPadding())) {
            item(key = "title") { Text("评论", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
    }
}

/**
 * 搜索歌词: the query starts from the title and can be retyped; a result is fetched, kept for
 * this video and replaces the subtitles.
 */
@Composable
private fun LyricsSearchSheet(key: String, initialQuery: String, durationMs: Long, hasPicked: Boolean, onDismiss: () -> Unit) {
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
                .sortedBy { c -> if (durationMs > 0 && c.durationMs > 0 && abs(c.durationMs - durationMs) <= 3_000) 0 else 1 }
            searching = false
        }
    }
    LaunchedEffect(Unit) { if (query.isNotBlank()) run() }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("搜索歌词", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasPicked) {
                TextButton(onClick = {
                    scope.launch {
                        LyricsStore.clear(key)
                        toast("已换回视频字幕")
                        onDismiss()
                    }
                }) { Text("换回视频自己的字幕") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = maxHeight + sheetBottomPadding())) {
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
                list != null -> LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = sheetBottomPadding())) {
                    itemsIndexed(list, key = { _, c -> "${c.source}_${c.id}" }) { _, c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = fetching == null) {
                                    fetching = c
                                    scope.launch {
                                        val picked = LyricsSearch.fetch(c)
                                        fetching = null
                                        if (picked == null) {
                                            toast("这首没有可同步的歌词")
                                        } else {
                                            LyricsStore.save(key, picked)
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
                                Text(
                                    listOf(c.artist, c.album).filter { it.isNotBlank() }.joinToString("  "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            if (fetching == c) {
                                LoadingIndicator(Modifier.size(24.dp))
                            } else {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(c.source.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    if (c.durationMs > 0) Text(Fmt.duration(c.durationMs / 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Lyrics list (PiliPaw's LyricsPane) ──────────────────────────────────────

/** The playhead read every frame while playing, so the lyrics and the wave glide. */
@Composable
private fun rememberLivePosition(): State<Long> {
    val live: MutableLongState = remember { mutableLongStateOf(Player.exo.currentPosition) }
    val playing = Player.isPlaying
    LaunchedEffect(playing) {
        if (playing) {
            while (isActive) withFrameMillis { live.longValue = Player.exo.currentPosition }
        } else {
            while (isActive) {
                live.longValue = Player.exo.currentPosition
                delay(250)
            }
        }
    }
    return live
}

/**
 * The cues, current one seated in the middle. One driver owns the scroll: the first seat of a
 * track is instant, each new cue glides on the spatial spring, a user drag pauses it.
 */
@Composable
private fun LyricsList(
    lines: List<Cue>,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    frameHeight: Dp,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle,
) {
    val haptics = LocalHapticFeedback.current
    val still = rememberAnimationsRemoved()
    val listState = rememberLazyListState()
    val latestPosition by rememberUpdatedState(positionMs)
    val current by remember(lines) { derivedStateOf { currentCueIndex(lines, latestPosition()) } }

    var held by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { i ->
            when (i) {
                is DragInteraction.Start -> held = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    delay(LyricsResumeMs)
                    held = false
                }
            }
        }
    }

    var seated by remember(lines) { mutableStateOf(false) }
    val glide = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(lines, current, held) {
        if (held || lines.isEmpty()) return@LaunchedEffect
        val target = current.coerceIn(0, lines.lastIndex)
        var first = true
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .filter { it > 0 }
            .collect {
                val info = listState.layoutInfo
                val row = info.visibleItemsInfo.firstOrNull { it.index == target }
                if (!seated || still || !first || row == null) {
                    listState.scrollToItem(target, info.seatOffset(target))
                    listState.scrollToItem(target, listState.layoutInfo.seatOffset(target))
                    seated = true
                } else {
                    withFrameNanos { }
                    val now = listState.layoutInfo
                    val r = now.visibleItemsInfo.firstOrNull { it.index == target }
                    if (r != null) {
                        val delta = (r.offset - now.viewportStartOffset) - now.seatTop(r.size)
                        if (delta != 0) listState.animateScrollBy(delta.toFloat(), glide)
                    } else {
                        listState.scrollToItem(target, now.seatOffset(target))
                    }
                }
                first = false
            }
    }

    val pad = (frameHeight / 2 - 24.dp).coerceAtLeast(12.dp)
    LazyColumn(
        state = listState,
        modifier = modifier.lyricsEdgeFade(),
        contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = pad, bottom = pad),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
            LyricRow(
                text = line.text.trim(),
                active = i == current,
                distance = if (current >= 0) abs(i - current) else 3,
                style = textStyle,
                onTap = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    held = false
                    onSeek(line.startMs)
                },
            )
        }
    }
}

@Composable
private fun LyricRow(text: String, active: Boolean, distance: Int, style: TextStyle, onTap: () -> Unit) {
    val color by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        YoinMotion.defaultEffectsSpec(),
        label = "lyricColor",
    )
    val alpha by animateFloatAsState(
        when {
            active -> 1f
            distance <= 1 -> 0.62f
            distance == 2 -> 0.5f
            else -> 0.4f
        },
        YoinMotion.defaultEffectsSpec(),
        label = "lyricAlpha",
    )
    val scale by animateFloatAsState(if (active) 1f else 0.92f, YoinMotion.defaultSpatialSpec(), label = "lyricScale")
    val lineStyle = if (active) style.copy(fontFamily = GoogleSansFlexRounded, fontWeight = FontWeight.SemiBold) else style.copy(fontWeight = FontWeight.Medium)
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = text,
            style = lineStyle,
            color = color,
            modifier = Modifier
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        )
    }
}

/** The last cue that has started (a gap keeps the previous one lit); -1 before the first. */
private fun currentCueIndex(lines: List<Cue>, pos: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].startMs <= pos) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

private fun LazyListLayoutInfo.seatTop(rowPx: Int): Int = (viewportSize.height - rowPx) / 2

private fun LazyListLayoutInfo.seatOffset(target: Int): Int {
    val rowPx = visibleItemsInfo.firstOrNull { it.index == target }?.size
        ?: visibleItemsInfo.takeIf { it.isNotEmpty() }?.let { rows -> rows.sumOf { it.size } / rows.size }
        ?: 0
    return -viewportStartOffset - seatTop(rowPx)
}

private fun Modifier.lyricsEdgeFade(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val f = (min(48.dp.toPx(), size.height * 0.22f) / size.height).coerceIn(0f, 0.5f)
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Transparent, f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }

/** Cover colours behind the lyrics (Yoin's now-playing wash). */
@Composable
private fun Modifier.lyricsBackdrop(): Modifier {
    val scheme = MaterialTheme.colorScheme
    val backdrop = LocalPageBackdrop.current
    val fallbackBase = scheme.primary
    val fallbackAccent = scheme.tertiary
    val surface = scheme.surfaceContainerLowest
    return drawBehind {
        val base = backdrop?.base ?: fallbackBase
        val accent = backdrop?.accent ?: fallbackAccent
        drawRect(Brush.verticalGradient(0f to lerp(surface, base, 0.34f), 0.55f to lerp(surface, base, 0.16f), 1f to lerp(surface, accent, 0.22f)))
        val r = maxOf(size.width, size.height) * 0.7f
        drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0f)), Offset(size.width * 0.12f, size.height * 0.1f), r))
    }
}

@Composable
private fun rememberAnimationsRemoved(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

private const val LyricsResumeMs = 3_000L

// ── Transport (PiliPaw's ListenTransport) ───────────────────────────────────

/**
 * Yoin's transport, a size down: the play / pause pill and 下一个 (YouTube's up next) in primary
 * with the play-mode key at the far end (单曲循环 on / off), then 回到开头 (secondary), the
 * playhead, Yoin's wave scrubber and the time left.
 */
@Composable
private fun ListenTransport(controlSize: Dp, modifier: Modifier = Modifier, horizontalPadding: Dp = 16.dp) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val playing = Player.isPlaying
    val iconSize = controlSize * 0.5f
    val playPadding by animateDpAsState(if (playing) 22.dp else 14.dp, YoinMotion.fastSpatialSpec(), label = "listenPlayPadding")
    val hasNext = Player.hasNext
    val nextAlpha by animateFloatAsState(if (hasNext) 1f else 0.38f, YoinMotion.defaultEffectsSpec(), label = "listenNext")
    val d = Player.durationMs
    val live = rememberLivePosition()
    val timeStyle = MaterialTheme.typography.labelLarge.withTabularFigures()
    Column(modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    Player.toggle()
                },
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(horizontal = playPadding),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
                modifier = Modifier.height(controlSize),
            ) {
                Icon(rememberPlayPauseSymbolPainter(playing), contentDescription = if (playing) "暂停" else "播放", modifier = Modifier.size(iconSize))
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    Player.playNext()
                },
                enabled = hasNext,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                    disabledContainerColor = scheme.primary,
                    disabledContentColor = scheme.onPrimary,
                ),
                modifier = Modifier.size(controlSize).graphicsLayer { alpha = nextAlpha },
            ) {
                Icon(YoinSymbols.SkipNextFilled, contentDescription = "下一个", modifier = Modifier.size(iconSize))
            }
            Spacer(Modifier.weight(1f))
            PlayModeButton(controlSize, iconSize)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    Player.exo.seekTo(0)
                },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.secondary, contentColor = scheme.onSecondary),
                modifier = Modifier.size(controlSize),
            ) {
                Icon(YoinSymbols.SkipPreviousFilled, contentDescription = "回到开头", modifier = Modifier.size(iconSize))
            }
            Text(Fmt.duration(live.value / 1000).ifEmpty { "0:00" }, style = timeStyle, color = scheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.defaultMinSize(minWidth = 40.dp))
            WaveBar(
                progress = { if (d > 0) live.value.toFloat() / d else 0f },
                durationMs = d,
                playing = playing,
                onSeek = { f -> Player.exo.seekTo((f * d).toLong()) },
                modifier = Modifier.weight(1f).height(controlSize),
            )
            Text(
                "-" + Fmt.duration(((d - live.value).coerceAtLeast(0)) / 1000).ifEmpty { "0:00" },
                style = timeStyle,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.defaultMinSize(minWidth = 46.dp),
            )
        }
    }
}

/** Yoin's play-mode key: 单曲循环 lit (primary), off resting on tertiaryContainer. */
@Composable
private fun PlayModeButton(size: Dp, iconSize: Dp) {
    val haptics = LocalHapticFeedback.current
    val loop = Player.loop
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (loop) scheme.primary else scheme.tertiaryContainer, YoinMotion.defaultEffectsSpec(), label = "modeContainer")
    val content by animateColorAsState(if (loop) scheme.onPrimary else scheme.onTertiaryContainer, YoinMotion.defaultEffectsSpec(), label = "modeContent")
    FilledIconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            Player.changeLoop(!loop)
        },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        modifier = Modifier.size(size),
    ) {
        Icon(rememberPlayModeSymbolPainter(if (loop) SymbolPlayMode.RepeatOne else SymbolPlayMode.RepeatAll), contentDescription = if (loop) "单曲循环" else "不循环", modifier = Modifier.size(iconSize))
    }
}

/** Yoin's wave scrubber: M3E's wavy indicator flowing while it plays; a tap seeks, a drag scrubs. */
@Composable
private fun WaveBar(progress: () -> Float, durationMs: Long, playing: Boolean, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var trackWidthPx by remember { mutableIntStateOf(1) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    var bubbleHeightPx by remember { mutableIntStateOf(0) }
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestDrag = rememberUpdatedState(dragFraction)
    val latestProgress = rememberUpdatedState(progress)
    val indicatorProgress: () -> Float = remember { { (latestDrag.value ?: latestProgress.value()).coerceIn(0f, 1f) } }
    val base = WavyProgressIndicatorDefaults.linearIndicatorStroke
    val stroke = remember(base) { Stroke(width = base.width * 0.92f, cap = base.cap) }
    val trackStroke = remember(stroke) { Stroke(width = stroke.width, cap = StrokeCap.Butt) }
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Box(
        modifier
            .pointerInput(durationMs) {
                detectTapGestures(onPress = { o ->
                    val f = (o.x / size.width).coerceIn(0f, 1f)
                    if (tryAwaitRelease()) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        latestOnSeek(f)
                    }
                })
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            latestOnSeek(it)
                        }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                    onHorizontalDrag = { _, dx -> dragFraction = ((dragFraction ?: 0f) + dx / size.width).coerceIn(0f, 1f) },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        LinearWavyProgressIndicator(
            progress = indicatorProgress,
            modifier = Modifier.fillMaxWidth().height(18.dp).onSizeChanged { trackWidthPx = it.width },
            color = MaterialTheme.colorScheme.primary,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = trackStroke,
            gapSize = 1.dp,
            stopSize = 0.dp,
            amplitude = { if (playing) 0.28f else 0f },
            wavelength = 24.dp,
            waveSpeed = if (playing) 24.dp else 0.dp,
        )
        dragFraction?.let { f ->
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged {
                        bubbleWidthPx = it.width
                        bubbleHeightPx = it.height
                    }
                    .offset {
                        val x = (trackWidthPx * f - bubbleWidthPx / 2f).roundToInt().coerceIn(0, (trackWidthPx - bubbleWidthPx).coerceAtLeast(0))
                        IntOffset(x, -bubbleHeightPx)
                    },
            ) {
                Text(
                    Fmt.duration((durationMs * f).toLong() / 1000).ifEmpty { "0:00" },
                    style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
