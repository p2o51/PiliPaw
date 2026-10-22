package com.gpo.pilipala.feature.listen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.player.ListenTransport
import com.gpo.pilipala.feature.player.LyricsList
import com.gpo.pilipala.feature.player.SubtitleLine
import com.gpo.pilipala.feature.player.lyrics.LyricCandidate
import com.gpo.pilipala.feature.player.lyrics.LyricsSearch
import com.gpo.pilipala.feature.player.lyricsBackdrop
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetNavBottom
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * What the listen page shows and does, whichever part is playing: Bilibili's video page
 * (feature/video/ListenScreen.kt) and the YouTube player (yt/feature/listen/Listen.kt) each
 * hand one over, and [ListenPage] draws them the same way.
 */
@Stable
internal interface ListenSource {
    val title: String
    val subtitle: String
    val cover: String

    /** The bar's second line: the list and where in it, or 仅听声音. */
    val header: String
    val playback: ListenPlayback

    /** Picked lyrics, else the video's subtitles. */
    val lines: List<SubtitleLine>

    /** Shown where the lyrics go when there are none. */
    val noLyricsLabel: String get() = "这个视频没有字幕"
    val hasQueue: Boolean get() = false
    val commentsLabel: String get() = "评论"
    val hasComments: Boolean get() = true

    /** 搜索歌词: the query it starts from, and the pick kept for this video. */
    val lyricsQuery: String
    val hasPickedLyrics: Boolean
    suspend fun pickLyrics(lines: List<SubtitleLine>)
    suspend fun clearLyrics()

    /** 收起: back to where the page came from. */
    fun collapse()

    /** 显示画面. */
    fun showVideo()

    /** 点赞 (or the part's stand-in) at the title's end. */
    @Composable
    fun Like()

    @Composable
    fun QueueSheet(onDismiss: () -> Unit) = Unit

    @Composable
    fun QueuePane(modifier: Modifier) = Unit

    @Composable
    fun CommentsSheet(onDismiss: () -> Unit)

    @Composable
    fun CommentsPane(modifier: Modifier)
}

/**
 * The move between the video and 仅听声音, the core of listening: the picture lifts off its
 * slot as a card and flies, on the slow spatial spring, into the cover's place, its corners
 * rounding and a shadow growing under it, while the last video frame melts into the cover
 * art; the page's wash comes up beneath, and the bar, title, lyrics and transport follow in
 * turn. 显示画面 runs it back: the cover flies up into the picture's slot as the page fades.
 * [progress]: 0 the video page, 1 the listen page (springs may overshoot either end).
 */
@Stable
internal class ListenFlight(
    val progress: () -> Float,
    val from: () -> Rect?,
    val shot: () -> ImageBitmap? = { null },
) {
    /** The cover's slot on the listen page, in window px. */
    var coverRect by mutableStateOf<Rect?>(null)

    /** The cover as last drawn (the back's hero flies it home). */
    var coverLayer: GraphicsLayer? = null

    /** Two fingers spread on the cover: back to the picture (null: not offered). */
    var pinchOut: PicturePinchIn? = null

    val flying: Boolean get() = abs(progress() - 1f) > 0.002f
}

private val LocalListenFlight = staticCompositionLocalOf<ListenFlight?> { null }

/**
 * 仅听声音's own page, over the video (which stays composed beneath, player and all): the
 * cover, the title, the lyrics, Yoin's transport, and the ways on — 播放列表 and 评论 as
 * sheets, 显示画面 back to the video. 展开歌词 gives the lyrics the page, with 搜索歌词 beside
 * 点赞 at the top. Wide windows put the cover and transport left and the lyrics / list /
 * comments right.
 */
@Composable
internal fun ListenPage(source: ListenSource, flight: ListenFlight? = null) {
    var sheet by remember { mutableStateOf<ListenSheet?>(null) }
    val title = source.title
    val subtitle = source.subtitle
    val cover = source.cover
    val lines = source.lines
    val search = { sheet = ListenSheet.Search }

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalListenFlight provides flight) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOrigin = it.positionInWindow() }
            // Swallow touches so nothing reaches the video beneath.
            .clickable(interactionSource = null, indication = null) {},
    ) {
        // The page's wash comes up under the flying cover (it reaches full strength early).
        val scheme = MaterialTheme.colorScheme
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = flight?.let { (it.progress() / 0.6f).coerceIn(0f, 1f) } ?: 1f }
                .background(scheme.surfaceContainerLowest)
                .lyricsBackdrop(),
        )
        val wide = maxWidth >= 840.dp && maxWidth > maxHeight
        val bar: @Composable () -> Unit = {
            ListenTopBar(header = source.header, onCollapse = source::collapse, onShowVideo = source::showVideo)
        }
        if (wide) {
            WideListen(source, bar, cover, title, subtitle, lines, onSearch = search)
        } else {
            val pageH = maxHeight
            val pageW = maxWidth
            // Portrait: the lyrics are the page, full width between the title and the
            // transport, large, with no card; the ways on sit on the bottom edge.
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 8.dp, bottom = 2.dp),
            ) {
                val fl = flight
                // 展开歌词 folds the cover and title away and gives the lyrics the page.
                var expanded by rememberSaveable { mutableStateOf(false) }
                androidx.activity.compose.BackHandler(enabled = expanded) { expanded = false }
                // The cover is held to about a fifth of the height: a tall phone shows it full
                // width, a tablet in portrait a smaller card, centred, with the title under it.
                val coverMaxH = pageH * 0.22f
                val contentW = pageW - 48.dp
                val coverW = minOf(contentW, coverMaxH * (16f / 9f))
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Box(Modifier.listenReveal(fl, 0.3f)) { bar() }
                    // Expanded (Apple Music's lyrics): the cover steps down into one row with the
                    // title beside it, and the lyrics, larger, take the page under it.
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
                        exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(YoinMotion.fastEffectsSpec()),
                    ) {
                        CompactHeader(source, cover, title, subtitle, coverWidth = if (pageW >= 600.dp) 176.dp else 120.dp, onSearch = search)
                    }
                    AnimatedVisibility(
                        visible = !expanded,
                        enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
                        exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(YoinMotion.fastEffectsSpec()),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(16.dp))
                            // The cover centred at its held-down size; the title row under it, on one
                            // line, keeps to the cover's width.
                            Column(Modifier.width(coverW).align(Alignment.CenterHorizontally)) {
                                ListenCover(cover, Modifier.fillMaxWidth())
                                Spacer(Modifier.height(20.dp))
                                Box(Modifier.listenReveal(fl, 0.4f)) { TitleRow(source, title, subtitle) }
                            }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).listenReveal(fl, 0.5f)) {
                    val wideText = pageW >= 600.dp
                    ImmersiveLyrics(
                        source = source,
                        lines = lines,
                        frameHeight = maxHeight,
                        horizontalPadding = 24.dp,
                        style = when {
                            expanded && wideText -> MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 50.sp)
                            expanded || wideText -> MaterialTheme.typography.headlineLarge
                            else -> MaterialTheme.typography.headlineMedium
                        },
                        // The current line a third of the way down, so what comes next has the room.
                        seat = 0.32f,
                        onSearch = search,
                    )
                }
                Column(Modifier.padding(horizontal = 24.dp).listenReveal(fl, 0.58f)) {
                    ListenTransport(
                        source.playback,
                        controlSize = 56.dp,
                        horizontalPadding = 0.dp,
                        lyricsExpanded = expanded,
                        onExpandLyrics = { expanded = !expanded },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (source.hasQueue) {
                            QuietAction(YoinSymbols.Playlist, "播放列表") { sheet = ListenSheet.Queue }
                        }
                        if (source.hasComments) {
                            QuietAction(PiliSymbols.Comment, source.commentsLabel) { sheet = ListenSheet.Comments }
                        }
                    }
                }
            }
        }
        if (flight != null) FlyingCover(flight, cover) { rootOrigin }
    }
    }

    when (sheet) {
        ListenSheet.Queue -> source.QueueSheet(onDismiss = { sheet = null })
        ListenSheet.Comments -> source.CommentsSheet(onDismiss = { sheet = null })
        ListenSheet.Search -> LyricsSearchSheet(source, durationMs = source.playback.durationMs, onDismiss = { sheet = null })
        null -> Unit
    }
}

private enum class ListenSheet { Queue, Comments, Search }

/** A piece of the listen page that rises in once the flight has carried [start] of the way. */
private fun Modifier.listenReveal(flight: ListenFlight?, start: Float): Modifier =
    if (flight == null) this else graphicsLayer {
        val t = ((flight.progress() - start) / (1f - start)).coerceIn(0f, 1f)
        alpha = t
        translationY = (1f - t) * 28.dp.toPx()
    }

/** The card in flight between the picture's slot and the cover's (see [ListenFlight]). */
@Composable
private fun FlyingCover(flight: ListenFlight, cover: String, origin: () -> Offset) {
    val to = flight.coverRect ?: return
    val corner = with(LocalDensity.current) { 28.dp.toPx() }
    val shadow = with(LocalDensity.current) { 24.dp.toPx() }
    val shot = flight.shot()
    Box(
        Modifier
            .layout { m, c ->
                val p = flight.progress()
                val from = flight.from() ?: Rect(to.center, 0f).inflate(to.width * 0.45f).let {
                    Rect(it.left, to.top + to.height * 0.05f, it.right, to.bottom - to.height * 0.05f)
                }
                val r = lerp(from, to, p)
                val placeable = m.measure(
                    Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                )
                val o = origin()
                layout(c.maxWidth, c.maxHeight) { placeable.place((r.left - o.x).roundToInt(), (r.top - o.y).roundToInt()) }
            }
            .graphicsLayer {
                val p = flight.progress()
                alpha = if (flight.flying && p > 0.001f) 1f else 0f
                val k = p.coerceIn(0f, 1f)
                shape = RoundedCornerShape(corner * k)
                clip = true
                shadowElevation = shadow * k
            },
    ) {
        if (shot != null) {
            Image(shot, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        NetImage(
            url = cover,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // With the video's last frame under it, the art fades in during the flight.
                    alpha = if (shot == null) 1f else ((flight.progress() - 0.15f) / 0.6f).coerceIn(0f, 1f)
                },
            shape = RectangleShape,
            thumbWidth = 720.dp,
        )
    }
}

@Composable
private fun ListenTopBar(header: String, onCollapse: () -> Unit, onShowVideo: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onCollapse, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
            Icon(YoinSymbols.ChevronDown, contentDescription = "收起")
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在听", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text(header, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 显示画面 up here, out of the thumb's way (a cast button would sit beside it).
        FilledTonalIconButton(onClick = onShowVideo, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
            Icon(YoinSymbols.Tv, contentDescription = "显示画面")
        }
    }
}

@Composable
private fun ListenCover(cover: String, modifier: Modifier) {
    val flight = LocalListenFlight.current
    val layer = rememberGraphicsLayer()
    flight?.coverLayer = layer
    NetImage(
        url = cover,
        modifier = modifier
            .aspectRatio(16f / 9f)
            // Where the flying cover lands; hidden while it flies (FlyingCover draws it then).
            .onGloballyPositioned { c -> flight?.coverRect = c.boundsInWindow() }
            // Spread two fingers on it: the cover flies back into the picture with them.
            .pinchOutToVideo(flight?.pinchOut)
            .graphicsLayer { alpha = if (flight?.flying == true) 0f else 1f }
            .shadow(24.dp, RoundedCornerShape(28.dp), clip = false)
            .clip(RoundedCornerShape(28.dp))
            // Kept for the back's hero: the cover as drawn, corners and all.
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
        shape = RoundedCornerShape(28.dp),
        thumbWidth = 720.dp,
    )
}

@Composable
private fun TitleRow(source: ListenSource, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000),
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        source.Like()
    }
}

/** Expanded lyrics' header: a small cover, the title and UP beside it, 搜索歌词 and 点赞 at the end. */
@Composable
private fun CompactHeader(
    source: ListenSource,
    cover: String,
    title: String,
    subtitle: String,
    coverWidth: Dp,
    onSearch: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        NetImage(cover, Modifier.width(coverWidth).aspectRatio(16f / 9f), shape = RoundedCornerShape(14.dp), thumbWidth = 360.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000),
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        SearchLyricsButton(onSearch)
        Spacer(Modifier.width(8.dp))
        source.Like()
    }
}

@Composable
private fun SearchLyricsButton(onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
        Icon(YoinSymbols.Search, contentDescription = "搜索歌词")
    }
}

/** The lyrics with no frame, the current line seated mid-height; without any: 搜索歌词. */
@Composable
private fun ImmersiveLyrics(
    source: ListenSource,
    lines: List<SubtitleLine>,
    frameHeight: Dp,
    horizontalPadding: Dp,
    style: TextStyle,
    onSearch: () -> Unit,
    seat: Float = 0.5f,
) {
    if (lines.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(source.noLyricsLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSearch) {
                Icon(YoinSymbols.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("在网易云 / QQ 音乐搜索歌词")
            }
        }
    } else {
        val live = rememberListenPosition(source.playback)
        LyricsList(
            lines = lines,
            positionMs = { live.value },
            onSeek = { source.playback.seekTo(it) },
            frameHeight = frameHeight,
            horizontalPadding = horizontalPadding,
            modifier = Modifier.fillMaxSize(),
            textStyle = style,
            seat = seat,
        )
    }
}

@Composable
private fun QuietAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(48.dp)) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Tablet landscape: cover, title and transport left; 歌词 / 播放列表 / 评论 right. */
@Composable
private fun WideListen(
    source: ListenSource,
    bar: @Composable () -> Unit,
    cover: String,
    title: String,
    subtitle: String,
    lines: List<SubtitleLine>,
    onSearch: () -> Unit,
) {
    val flight = LocalListenFlight.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = buildList {
        add("歌词")
        if (source.hasQueue) add("播放列表")
        if (source.hasComments) add("评论")
    }
    Row(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 40.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.weight(0.46f).fillMaxHeight()) {
            Box(Modifier.listenReveal(flight, 0.3f)) { bar() }
            Spacer(Modifier.height(20.dp))
            ListenCover(cover, Modifier.fillMaxWidth())
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth().listenReveal(flight, 0.4f), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(12.dp))
                SearchLyricsButton(onSearch)
                Spacer(Modifier.width(8.dp))
                source.Like()
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.listenReveal(flight, 0.58f)) { ListenTransport(source.playback, controlSize = 60.dp, horizontalPadding = 0.dp) }
        }
        Column(
            Modifier
                .weight(0.54f)
                .fillMaxHeight()
                .listenReveal(flight, 0.5f)
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
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (on) scheme.primary else Color.Transparent,
                            contentColor = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.height(44.dp),
                    ) { Text(label, style = MaterialTheme.typography.titleSmall) }
                    Spacer(Modifier.width(4.dp))
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tabs.getOrNull(tab.coerceIn(0, tabs.lastIndex))) {
                    "播放列表" -> source.QueuePane(Modifier.fillMaxSize())
                    "评论" -> source.CommentsPane(Modifier.fillMaxSize())
                    else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        if (lines.isEmpty()) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(source.noLyricsLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = onSearch) { Text("在网易云 / QQ 音乐搜索歌词") }
                            }
                        } else {
                            val live = rememberListenPosition(source.playback)
                            LyricsList(
                                lines = lines,
                                positionMs = { live.value },
                                onSeek = { source.playback.seekTo(it) },
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

/**
 * 搜索歌词: the query starts from the title (《歌名》, or the title without its tags) and can
 * be retyped; a result is fetched, kept for this video and replaces the subtitles.
 */
@Composable
private fun LyricsSearchSheet(source: ListenSource, durationMs: Long, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var query by rememberSaveable { mutableStateOf(source.lyricsQuery) }
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
    val navBottom = sheetNavBottom()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    val sheetState = rememberModalBottomSheetState()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, contentWindowInsets = SheetInsets, properties = rememberSheetProperties()) {
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
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (source.hasPickedLyrics) {
                TextButton(onClick = {
                    scope.launch {
                        source.clearLyrics()
                        toast("已换回视频字幕")
                        close()
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
                                        val picked = LyricsSearch.fetch(c)
                                        fetching = null
                                        if (picked == null) {
                                            toast("这首没有可同步的歌词")
                                        } else {
                                            source.pickLyrics(picked)
                                            toast("已使用 ${c.source.label} 的歌词")
                                            close()
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val meta = listOf(c.artist, c.album).filter { it.isNotBlank() }.joinToString("  ")
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
