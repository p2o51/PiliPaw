package com.gpo.pilipala.feature.listen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.feature.player.ListenTransport
import com.gpo.pilipala.feature.player.LyricsList
import com.gpo.pilipala.feature.player.SubtitleLine
import com.gpo.pilipala.feature.player.bleed
import com.gpo.pilipala.feature.player.lyrics.LyricCandidate
import com.gpo.pilipala.feature.player.lyrics.LyricsSearch
import com.gpo.pilipala.feature.player.lyricsBackdrop
import com.gpo.pilipala.feature.player.sheets.rememberAnimationsRemoved
import com.gpo.pilipala.feature.player.sideFade
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetNavBottom
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.ExpressiveColorSchemeFactory
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.loadCoverBackdrop
import com.gpo.pilipala.ui.theme.piliIsDark
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Where in the list or Mix this plays (-1: none), so a switch knows next from previous. */
    val position: Int get() = -1
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
    val lines = source.lines
    val search = { sheet = ListenSheet.Search }
    val switch = rememberListenSwitch(source)
    val cover = switch.cover
    val hasQueue = rememberHeldQueue(source.hasQueue)

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    // The page wears the cover on screen: its colours change as that cover is revealed.
    CoverTheme(switch.themeCover) {
    CompositionLocalProvider(
        LocalListenFlight provides flight,
        LocalListenDirection provides switch.direction,
        LocalListenSwitch provides switch,
    ) {
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
            WideListen(source, bar, cover, title, subtitle, lines, hasQueue, onSearch = search)
        } else {
            val pageH = maxHeight
            val pageW = maxWidth
            // 展开歌词 folds the cover and title away and gives the lyrics the page.
            var expanded by rememberSaveable { mutableStateOf(false) }
            androidx.activity.compose.BackHandler(enabled = expanded) { expanded = false }
            // One cover for both headers: it moves and scales between their slots.
            val morph = remember { CoverMorph() }
            val toward = animateFloatAsState(if (expanded) 1f else 0f, YoinMotion.slowSpatialSpec(), label = "coverMorph")
            // Portrait: the lyrics are the page, full width between the title and the
            // transport, large, with no card; the ways on sit on the bottom edge.
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 8.dp, bottom = 2.dp),
            ) {
                val fl = flight
                // A tall phone holds the cover to about a fifth of the height (full width);
                // anything squarer (a tablet in portrait, split screen, a foldable) a bigger,
                // centred card, which gives way on a short window so the lyrics keep a few lines.
                val tall = pageW < 600.dp && pageH / pageW >= 1.9f
                val coverMaxH = if (tall) pageH * 0.22f else minOf(pageH * 0.28f, pageH - 560.dp).coerceAtLeast(pageH * 0.16f)
                val contentW = pageW - 48.dp
                val fullW = minOf(contentW, coverMaxH * (16f / 9f))
                // 点赞 moves beside the centred card when making room for it (a slot each side,
                // so the card stays centred) costs the card at most a fifth of its width; the
                // title then takes the whole width under it. A full-width card (a tall phone)
                // keeps 点赞 at the title row's end.
                val besideW = contentW - LikeSlot * 2
                val likeBeside = besideW >= fullW * 0.8f
                val coverW = if (likeBeside) minOf(fullW, besideW) else fullW
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Box(Modifier.listenReveal(fl, 0.3f)) { bar() }
                    // Expanded (Apple Music's lyrics): the cover steps down into one row with the
                    // title beside it, and the lyrics, larger, take the page under it.
                    // The headers' own clips are off: the title travels between them as they swap.
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(YoinMotion.slowSpatialSpec(), clip = false) + fadeIn(YoinMotion.defaultEffectsSpec()),
                        exit = shrinkVertically(YoinMotion.slowSpatialSpec(), clip = false) + fadeOut(YoinMotion.fastEffectsSpec()),
                    ) {
                        CompactHeader(
                            source,
                            title,
                            subtitle,
                            coverWidth = if (pageW >= 600.dp) 176.dp else 120.dp,
                            morph = morph,
                            away = { 1f - toward.value },
                            onSearch = search,
                        )
                    }
                    AnimatedVisibility(
                        visible = !expanded,
                        enter = expandVertically(YoinMotion.slowSpatialSpec(), clip = false) + fadeIn(YoinMotion.defaultEffectsSpec()),
                        exit = shrinkVertically(YoinMotion.slowSpatialSpec(), clip = false) + fadeOut(YoinMotion.fastEffectsSpec()),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                                // As wide again on the left, so the card stays centred.
                                if (likeBeside) Spacer(Modifier.width(LikeSlot))
                                // The cover's place; MorphingCover draws it.
                                Box(Modifier.width(coverW).aspectRatio(16f / 9f).onGloballyPositioned { morph.big = it.boundsInWindow() })
                                if (likeBeside) {
                                    Spacer(Modifier.width(LikeGap))
                                    Box(Modifier.listenReveal(fl, 0.4f)) { source.Like() }
                                }
                            }
                            Spacer(Modifier.height(if (likeBeside) 16.dp else 20.dp))
                            Box(
                                Modifier
                                    .listenReveal(fl, 0.4f)
                                    .travel({ morph.bigTitle = it }, from = { morph.bigTitle }, to = { morph.smallTitle }, amount = { toward.value }),
                            ) {
                                if (likeBeside) {
                                    WideTitle(title, subtitle, large = pageW >= 600.dp)
                                } else {
                                    // The title row on one line (a marquee), 点赞 at its end.
                                    TitleRow(source, title, subtitle)
                                }
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
                        if (hasQueue) {
                            QuietAction(YoinSymbols.Playlist, "播放列表") { sheet = ListenSheet.Queue }
                        }
                        if (source.hasComments) {
                            QuietAction(PiliSymbols.Comment, source.commentsLabel) { sheet = ListenSheet.Comments }
                        }
                    }
                }
            }
            MorphingCover(cover, morph, toward = { toward.value }) { rootOrigin }
        }
        if (flight != null) FlyingCover(flight, cover) { rootOrigin }
    }
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

/**
 * Where the portrait page's two headers keep the cover and the title (window px): the full
 * one ([big], [bigTitle]) and 展开歌词's row ([small], [smallTitle]). A slot that is not laid
 * out keeps where it last was.
 */
@Stable
private class CoverMorph {
    var big by mutableStateOf<Rect?>(null)
    var small by mutableStateOf<Rect?>(null)
    var bigTitle by mutableStateOf<Rect?>(null)
    var smallTitle by mutableStateOf<Rect?>(null)
}

/**
 * The portrait page's one cover, over both headers: 展开歌词 moves and scales it from the
 * full card into the row's small one on the spatial spring ([toward] 0 → 1), its corners and
 * shadow following, and 收起 brings it back.
 */
@Composable
private fun MorphingCover(cover: String, morph: CoverMorph, toward: () -> Float, origin: () -> Offset) {
    Box(
        Modifier.layout { m, c ->
            val a = morph.big
            val b = morph.small
            val r = when {
                a != null && b != null -> lerp(a, b, toward())
                else -> a ?: b
            }
            if (r == null || r.width < 1f) return@layout layout(0, 0) {}
            val p = m.measure(Constraints.fixed(r.width.roundToInt(), r.height.roundToInt()))
            val o = origin()
            layout(c.maxWidth, c.maxHeight) { p.place((r.left - o.x).roundToInt(), (r.top - o.y).roundToInt()) }
        },
    ) {
        ListenCover(cover, Modifier.fillMaxSize(), compact = toward)
    }
}

/**
 * Carries a header's title toward the other header's (reports its own place with [report]):
 * [amount] 0 where it is, 1 at the other's place.
 */
private fun Modifier.travel(report: (Rect) -> Unit, from: () -> Rect?, to: () -> Rect?, amount: () -> Float): Modifier =
    onGloballyPositioned { report(it.boundsInWindow()) }
        .graphicsLayer {
            val a = from()
            val b = to()
            if (a != null && b != null) {
                val k = amount()
                translationX = (b.left - a.left) * k
                translationY = (b.top - a.top) * k
            }
        }

/** 点赞 beside the card: its 48dp and a 16dp gap. */
private val LikeGap = 16.dp
private val LikeSlot = 48.dp + LikeGap

/**
 * The listen page's side of a track switch: [cover] is the cover on screen, which moves on to
 * the source's only once the new image has decoded and its colours are read, so the old one
 * stays up meanwhile. [themeCover], the one the page's colours come from, follows it as the
 * new cover starts to develop (so picture and colours change together), or a moment later
 * when no large cover is showing. [direction] is 1 for a next (and anything that is not a
 * step back in the list), -1 for a previous.
 */
@Stable
private class ListenSwitch(cover: String, var position: Int) {
    var cover by mutableStateOf(cover)
    var themeCover by mutableStateOf(cover)
    var direction = 1
}

private val LocalListenSwitch = staticCompositionLocalOf<ListenSwitch?> { null }

@Composable
private fun rememberListenSwitch(source: ListenSource): ListenSwitch {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { ListenCoverThumb.roundToPx() }
    val target = source.cover
    val position = source.position
    val dark = piliIsDark()
    val switch = remember { ListenSwitch(target, position) }
    // The list's place moves first (the item is picked before it loads): it sets the way.
    if (position != switch.position) {
        switch.direction = if (position >= 0 && switch.position >= 0 && position < switch.position) -1 else 1
        switch.position = position
    }
    LaunchedEffect(target) {
        // A blank cover (the next item still loading) keeps the one on screen.
        if (target.isBlank() || target == switch.cover) return@LaunchedEffect
        preloadListenCover(context, target, px)
        if (AppSettings.coverColor.state.value) {
            // The colours and their scheme are worked out here, off the main thread, so the
            // frame that swaps the cover has nothing heavy left to do.
            loadCoverBackdrop(context, Images.normalize(target))?.let { c ->
                withContext(Dispatchers.Default) { ExpressiveColorSchemeFactory.fromSeeds(c.base.toArgb(), c.accent.toArgb(), dark) }
            }
        }
        switch.cover = target
        delay(ThemeFallbackMs)
        switch.themeCover = target
    }
    return switch
}

/** Which way the current switch runs (see [ListenSwitch.direction]). */
private val LocalListenDirection = compositionLocalOf { 1 }

/**
 * [hasQueue], held through a switch: the list or Mix moves to the next item before the player
 * has it, and in between the source briefly reports no queue (the 播放列表 tab would drop
 * out and come back). It is let go only once it has stayed off a while.
 */
@Composable
private fun rememberHeldQueue(hasQueue: Boolean): Boolean {
    var held by remember { mutableStateOf(hasQueue) }
    LaunchedEffect(hasQueue) {
        if (hasQueue) {
            held = true
        } else {
            delay(QueueHoldMs)
            held = false
        }
    }
    return hasQueue || held
}

private const val QueueHoldMs = 4_000L

/** How long the colours wait for a large cover to start developing before they change anyway. */
private const val ThemeFallbackMs = 600L

/** The large cover's request width (the flight's and the page's). */
private val ListenCoverThumb = 720.dp

/**
 * Text that changes with the track: the old crossfades out sliding a little the switch's way,
 * the new comes in after it from the other side (no motion with 移除动画).
 */
@Composable
private fun SwapText(
    text: String,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    content: @Composable (String) -> Unit,
) {
    val direction = LocalListenDirection.current
    val still = rememberAnimationsRemoved()
    val shift = with(LocalDensity.current) { 16.dp.roundToPx() }
    val fadeIn = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOut = YoinMotion.fastEffectsSpec<Float>()
    val slide = YoinMotion.defaultSpatialSpec<IntOffset>()
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        contentAlignment = alignment,
        transitionSpec = {
            if (still) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(fadeIn) + slideInHorizontally(slide) { shift * direction }) togetherWith
                    (fadeOut(fadeOut) + slideOutHorizontally(slide) { -shift * direction }) using
                    SizeTransform(clip = false)
            }
        },
        label = "listenText",
    ) { content(it) }
}

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
private fun ListenCover(cover: String, modifier: Modifier, compact: () -> Float = { 0f }) {
    val flight = LocalListenFlight.current
    val switch = LocalListenSwitch.current
    val layer = rememberGraphicsLayer()
    flight?.coverLayer = layer
    ListenCoverImage(
        url = cover,
        thumbWidth = ListenCoverThumb,
        reveal = CoverReveal.Dissolve,
        direction = LocalListenDirection.current,
        onReveal = { switch?.themeCover = it },
        modifier = modifier
            .aspectRatio(16f / 9f)
            // Where the flying cover lands; hidden while it flies (FlyingCover draws it then).
            .onGloballyPositioned { c -> flight?.coverRect = c.boundsInWindow() }
            // Spread two fingers on it: the cover flies back into the picture with them.
            .pinchOutToVideo(flight?.pinchOut)
            .graphicsLayer { alpha = if (flight?.flying == true) 0f else 1f }
            // 28dp corners and a 24dp shadow at full size, 14dp and none in 展开歌词's header.
            .graphicsLayer {
                val k = compact().coerceIn(0f, 1f)
                shape = RoundedCornerShape((28f - 14f * k).dp)
                clip = true
                shadowElevation = 24.dp.toPx() * (1f - k)
            }
            // Kept for the back's hero: the cover as drawn, corners and all.
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
private fun TitleRow(source: ListenSource, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            // The marquee runs out into the page gutter and the gap before 点赞, fading there.
            SwapText(title) {
                MarqueeTitle(it, MaterialTheme.typography.titleLarge, start = 24.dp, end = 16.dp)
            }
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SwapText(subtitle) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        source.Like()
    }
}

/** The title under a card with 点赞 beside it: the whole width, on up to two lines, the artist under it. */
@Composable
private fun WideTitle(title: String, subtitle: String, large: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        SwapText(title, Modifier.fillMaxWidth()) {
            Text(
                it,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SwapText(subtitle) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One line that runs sideways when it is too long: it reaches [start] / [end] out past its slot
 * (the gutter, the gap beside it) and fades out there instead of being cut at the slot's edge.
 */
@Composable
private fun MarqueeTitle(text: String, style: TextStyle, start: Dp, end: Dp) {
    Text(
        text,
        style = style,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .bleed(start, end)
            .sideFade(start, end)
            .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_000)
            .padding(start = start, end = end),
    )
}

/** Expanded lyrics' header: a small cover, the title and UP beside it, 搜索歌词 and 点赞 at the end. */
@Composable
private fun CompactHeader(
    source: ListenSource,
    title: String,
    subtitle: String,
    coverWidth: Dp,
    morph: CoverMorph,
    away: () -> Float,
    onSearch: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // The cover's place; MorphingCover draws it.
        Box(Modifier.width(coverWidth).aspectRatio(16f / 9f).onGloballyPositioned { morph.small = it.boundsInWindow() })
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).travel({ morph.smallTitle = it }, from = { morph.smallTitle }, to = { morph.bigTitle }, amount = away)) {
            SwapText(title) {
                MarqueeTitle(it, MaterialTheme.typography.titleMedium, start = 16.dp, end = 12.dp)
            }
            if (subtitle.isNotEmpty()) {
                SwapText(subtitle) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
    hasQueue: Boolean,
    onSearch: () -> Unit,
) {
    val flight = LocalListenFlight.current
    // Kept by name: a tab coming or going never moves the pick onto its neighbour.
    var tab by rememberSaveable { mutableStateOf("歌词") }
    val tabs = buildList {
        add("歌词")
        if (hasQueue) add("播放列表")
        if (source.hasComments) add("评论")
    }
    val shownTab = tab.takeIf { it in tabs } ?: "歌词"
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
                    SwapText(title) {
                        Text(it, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        SwapText(subtitle) {
                            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
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
                tabs.forEach { label ->
                    val on = shownTab == label
                    val scheme = MaterialTheme.colorScheme
                    TextButton(
                        onClick = { tab = label },
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
                when (shownTab) {
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
                                // The phone's own size and weights (the frame-height default
                                // set it a size or two down, and the lines read differently).
                                textStyle = MaterialTheme.typography.headlineLarge,
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
