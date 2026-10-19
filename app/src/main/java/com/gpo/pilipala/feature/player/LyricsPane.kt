package com.gpo.pilipala.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.CompositionLocalProvider
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberPlayPauseSymbolPainter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.player.sheets.rememberAnimationsRemoved
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.GoogleSansFlexRounded
import com.gpo.pilipala.ui.theme.LocalPageBackdrop
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

/**
 * 仅听声音: the picture's place shows the subtitles as lyrics, the way Yoin shows a song's.
 * The current cue is large, bold and in primary, its neighbours step back with distance,
 * and the list springs to keep the current cue in the middle. Tap a cue's text to play from
 * it; drag to read ahead (following resumes a few seconds after the finger lets go). Without
 * subtitles the cover shows with a quiet label. Wide frames keep the cover beside the lines.
 * A compact transport (previous / play-pause / next, seek bar and times) stays docked at the
 * bottom, like a music app's, whether or not the player chrome shows.
 *
 * [onBackgroundTap]: a tap off the cue texts (the chrome toggles, as on the picture).
 * [bottomLift]: how far (px) the transport rises to clear the fullscreen bottom bar.
 */
@Composable
internal fun LyricsPane(
    ctrl: PiliPlayerController,
    cover: String,
    onBackgroundTap: () -> Unit,
    modifier: Modifier = Modifier,
    bottomLift: () -> Float = { 0f },
) {
    val latestBackgroundTap by rememberUpdatedState(onBackgroundTap)
    Column(
        modifier
            .fillMaxSize()
            .lyricsBackdrop()
            .pointerInput(Unit) { detectTapGestures(onTap = { latestBackgroundTap() }) },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            LyricsContent(ctrl = ctrl, cover = cover, frameW = maxWidth, frameH = maxHeight)
        }
        LyricsTransport(
            ctrl = ctrl,
            modifier = Modifier.graphicsLayer { translationY = -bottomLift() },
        )
    }
}

@Composable
private fun LyricsContent(ctrl: PiliPlayerController, cover: String, frameW: Dp, frameH: Dp) {
    val wide = frameW >= 560.dp && frameW > frameH * 1.3f
    val lines = ctrl.subtitleLines
    val waiting = !ctrl.subtitlesReady || ctrl.subtitleLoading
    val state = when {
        lines.isNotEmpty() -> LyricsState.Lines
        waiting -> LyricsState.Waiting
        else -> LyricsState.Empty
    }
    val emptyLabel = if (ctrl.subtitles.isNotEmpty() && ctrl.subtitleIndex < 0) "字幕已关闭" else "暂无字幕"
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            fadeIn(enter) togetherWith fadeOut(exit)
        },
        label = "lyricsState",
        modifier = Modifier.fillMaxSize(),
    ) { s ->
        when {
            s == LyricsState.Lines && wide -> Row(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    NetImage(
                        url = cover,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        thumbWidth = 480.dp,
                    )
                }
                Spacer(Modifier.width(24.dp))
                LyricsList(
                    lines = lines,
                    positionMs = { ctrl.positionMs },
                    onSeek = { ctrl.seekTo(it) },
                    frameHeight = frameH,
                    horizontalPadding = 0.dp,
                    modifier = Modifier.weight(0.58f).fillMaxHeight(),
                )
            }
            s == LyricsState.Lines -> LyricsList(
                lines = lines,
                positionMs = { ctrl.positionMs },
                onSeek = { ctrl.seekTo(it) },
                frameHeight = frameH,
                horizontalPadding = if (frameW >= 600.dp) 40.dp else 24.dp,
                modifier = Modifier.fillMaxSize(),
            )
            else -> CoverWithLabel(
                cover = cover,
                label = if (s == LyricsState.Empty) emptyLabel else null,
                frameWidth = frameW,
                frameHeight = frameH,
            )
        }
    }
}

/**
 * The docked transport: 上一个, a filled play / pause in the cover's primary, 下一个 (it steps
 * back while there is nothing to go to), then the playhead, a slim seek bar and the duration.
 * Its own [SeekScrub], so the chrome's seek bubble keeps following the chrome's bar.
 */
@Composable
private fun LyricsTransport(ctrl: PiliPlayerController, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ink = remember(scheme) {
        PlayerInk(
            content = scheme.onSurface,
            contentVariant = scheme.onSurfaceVariant,
            pill = scheme.secondaryContainer,
            pillContent = scheme.onSecondaryContainer,
            trackRest = scheme.onSurface.copy(alpha = 0.14f),
            trackBuffered = scheme.onSurface.copy(alpha = 0.26f),
            trackPlayed = scheme.primary,
        )
    }
    val scrub = remember { SeekScrub() }
    val playing = ctrl.isPlaying || (ctrl.playWhenReady && !ctrl.ended)
    val nextAlpha by animateFloatAsState(
        targetValue = if (ctrl.hasNext) 1f else 0.38f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "transportNext",
    )
    val timeStyle = MaterialTheme.typography.labelMedium.withTabularFigures()
    val d = ctrl.durationMs
    val shownMs = scrub.fraction?.let { (it * d).toLong() } ?: ctrl.positionMs
    CompositionLocalProvider(LocalPlayerInk provides ink) {
        Row(
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .height(TransportHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { ctrl.skipPrevious() },
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurface),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(YoinSymbols.SkipPreviousFilled, contentDescription = "上一个")
            }
            FilledIconButton(
                onClick = { ctrl.togglePlay() },
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary,
                ),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(rememberPlayPauseSymbolPainter(playing), contentDescription = if (playing) "暂停" else "播放")
            }
            IconButton(
                onClick = { ctrl.skipNext() },
                enabled = ctrl.hasNext,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = scheme.onSurface,
                    disabledContentColor = scheme.onSurface,
                ),
                modifier = Modifier.size(44.dp).graphicsLayer { alpha = nextAlpha },
            ) {
                Icon(YoinSymbols.SkipNextFilled, contentDescription = "下一个")
            }
            Spacer(Modifier.width(8.dp))
            Text(clock(shownMs), style = timeStyle, color = scheme.onSurface, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            PlayerSeekBar(
                ctrl = ctrl,
                scrub = scrub,
                onPoke = {},
                frame = { null },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(clock(d), style = timeStyle, color = scheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
    }
}

private val TransportHeight = 60.dp

private enum class LyricsState { Waiting, Lines, Empty }

/** The cover, centred, with a quiet label under it (none while the subtitles are on their way). */
@Composable
private fun CoverWithLabel(cover: String, label: String?, frameWidth: Dp, frameHeight: Dp) {
    // Leaves room for the label and the chrome's corners above and below.
    val coverW = min((frameWidth * 0.5f).value, ((frameHeight - 88.dp) * (16f / 9f)).value).coerceAtLeast(96f).dp
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (cover.isNotBlank()) {
            NetImage(
                url = cover,
                modifier = Modifier.width(coverW).aspectRatio(16f / 9f),
                thumbWidth = 480.dp,
            )
        }
        // Space is kept while waiting, so the cover does not move when the label arrives.
        Spacer(Modifier.height(12.dp))
        Text(
            text = label.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The cues, current one seated in the middle. One driver owns the scroll: the first seat of a
 * track is instant, each new cue glides on the spatial spring, a user drag pauses it.
 */
@Composable
private fun LyricsList(
    lines: List<SubtitleLine>,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    frameHeight: Dp,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val still = rememberAnimationsRemoved()
    val listState = rememberLazyListState()
    val latestPosition by rememberUpdatedState(positionMs)
    // derivedStateOf soaks up the position ticks: rows recompose only when the cue changes.
    val current by remember(lines) { derivedStateOf { currentCueIndex(lines, latestPosition()) } }

    // A drag hands the list to the finger; it comes back 3s after the last one ends.
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
                    // First seat, no animations, a resize, or a cue far off screen: land at once.
                    listState.scrollToItem(target, info.seatOffset(target))
                    val again = listState.layoutInfo.seatOffset(target)
                    listState.scrollToItem(target, again)
                    seated = true
                } else {
                    // One frame first, so the row is laid out in its current-cue style.
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

    val style = when {
        frameHeight >= 640.dp -> MaterialTheme.typography.headlineMedium
        frameHeight >= 360.dp -> MaterialTheme.typography.headlineSmall
        else -> MaterialTheme.typography.titleLarge
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
                style = style,
                onTap = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    held = false
                    onSeek(line.fromMs)
                },
            )
        }
    }
}

@Composable
private fun LyricRow(
    text: String,
    active: Boolean,
    distance: Int,
    style: androidx.compose.ui.text.TextStyle,
    onTap: () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "lyricColor",
    )
    // Distance falloff: the eye lands on the current cue and the page thins out around it.
    val alpha by animateFloatAsState(
        targetValue = when {
            active -> 1f
            distance <= 1 -> 0.62f
            distance == 2 -> 0.5f
            else -> 0.4f
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "lyricAlpha",
    )
    // The current cue springs up to full size from its left edge; neighbours sit smaller.
    val scale by animateFloatAsState(
        targetValue = if (active) 1f else 0.92f,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "lyricScale",
    )
    val lineStyle = if (active) {
        style.copy(fontFamily = GoogleSansFlexRounded, fontWeight = FontWeight.SemiBold)
    } else {
        style.copy(fontWeight = FontWeight.Medium)
    }
    // Only the text itself takes the tap (as drawn, scale included): the rest of the row,
    // the gaps and the margins belong to the pane, where a tap toggles the chrome.
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
private fun currentCueIndex(lines: List<SubtitleLine>, pos: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].fromMs <= pos) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/** Where a [rowPx]-tall row's top sits so its centre is the viewport's. */
private fun LazyListLayoutInfo.seatTop(rowPx: Int): Int = (viewportSize.height - rowPx) / 2

/** scrollToItem offset seating [target] in the middle (a row not laid out yet is sized like the rest). */
private fun LazyListLayoutInfo.seatOffset(target: Int): Int {
    val rowPx = visibleItemsInfo.firstOrNull { it.index == target }?.size
        ?: visibleItemsInfo.takeIf { it.isNotEmpty() }?.let { rows -> rows.sumOf { it.size } / rows.size }
        ?: 0
    return -viewportStartOffset - seatTop(rowPx)
}

/** Both edges dissolve, so cues fade in and out instead of being cut by the frame. */
private fun Modifier.lyricsEdgeFade(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val f = (min(48.dp.toPx(), size.height * 0.22f) / size.height).coerceIn(0f, 0.5f)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    f to Color.Black,
                    1f - f to Color.Black,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * Cover colours behind the lyrics (Yoin's now-playing wash): the page's cover base over the
 * dark chrome surface at the top, its accent at the bottom, and a soft accent bloom.
 */
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
        drawRect(
            Brush.verticalGradient(
                0f to lerp(surface, base, 0.34f),
                0.55f to lerp(surface, base, 0.16f),
                1f to lerp(surface, accent, 0.22f),
            ),
        )
        val r = maxOf(size.width, size.height) * 0.7f
        drawRect(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0f)),
                center = Offset(size.width * 0.12f, size.height * 0.1f),
                radius = r,
            ),
        )
    }
}

private const val LyricsResumeMs = 3_000L
