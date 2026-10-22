package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import androidx.activity.compose.PredictiveBackHandler
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.core.text.HtmlCompat
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.core.extractor.toVideo
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.yt.core.util.Fmt
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import com.gpo.pilipala.yt.feature.listen.ListenScreen
import com.gpo.pilipala.yt.feature.mute.Mutes
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.extractor.YtAccount
import com.gpo.pilipala.yt.core.account.AccountWrites
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.pilipala.ui.components.LocalSnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import com.gpo.pilipala.yt.feature.comments.rememberCommentsState
import com.gpo.pilipala.yt.feature.comments.CommentsList
import androidx.compose.runtime.saveable.rememberSaveable
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.yt.ui.components.friendlyMessage
import com.gpo.pilipala.yt.core.account.AccountSubscriptions
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.feature.subscriptions.Subscriptions
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.shell.floatingBarInset
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.CoverTheme
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.gpo.pilipala.ui.components.ExpressivePageBackground
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

private val MiniHeight = 64.dp
private val MiniMargin = 12.dp
private val MiniMaxWidth = 560.dp
private val RelatedPaneWidth = 400.dp

/** How far a predictive-back gesture previews the collapse before the finger lets go. */
private const val BackPreview = 0.3f

/** Bottom space pages leave for the mini bar while a video is open. */
@Composable
fun miniPlayerInset(): Dp = if (Player.session != null) MiniHeight + MiniMargin else 0.dp

/**
 * The player as a shell overlay (Yoin Now Playing, predictive-back class ShellOverlayDown):
 * one progress p runs mini bar (0) ⇄ full player (1). The tap, the drag, the collapse
 * button and system back all drive that one value; the picture is the same view the whole
 * way, so it flies between the bar's corner and the top of the page with no swap.
 *
 * Content stays pure: no control ever sits on the picture. Play, the seek bar and times live
 * under it in the page's colours (PiliPaw 0.3.3).
 *
 * Wide windows (≥ 840dp wide, ≥ 480dp tall): the picture and the details on the left, related
 * videos in a 400dp column on the right. The picture never shrinks to make room.
 */
@Composable
fun PlayerOverlayHost() {
    val session = Player.session ?: return
    val activity = LocalActivity.current as? ComponentActivity
    val inPip = rememberIsInPip(activity)
    // 自动画中画: leaving the app with the player open and playing floats the picture (Android 12+).
    val autoPip by AppSettings.autoPip.state.collectAsState()
    val pipTitle = session.title
    LaunchedEffect(activity, autoPip, Player.isPlaying, Player.expanded, Player.videoAspect, pipTitle) {
        if (activity == null || !pipSupported(activity) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        runCatching {
            activity.setPictureInPictureParams(
                pipParams(Player.videoAspect, autoEnter = autoPip && Player.isPlaying && Player.expanded, title = pipTitle),
            )
        }
    }
    // In the floating window: the picture alone, nothing else of the app.
    if (inPip) {
        PipPicture(session)
        return
    }
    val nav = LocalNavigator.current
    val onShell = nav.backStack.lastOrNull().let { it == Route.Main || it == com.gpo.pilipala.navigation.Route.Main }
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val expanded = Player.expanded
    val feedback = remember { GestureFeedback() }
    val haptics = LocalHapticFeedback.current
    val seek = rememberSeekState()
    // The portrait controls (PiliPaw's look): a tap on the picture brings them, they fold away.
    // One for the overlay's life: the picture's gesture detector keeps its first onTap, so a
    // new object per video would leave taps toggling a stale one. A new video starts hidden.
    val chrome = remember { YtChrome() }
    LaunchedEffect(session.url) {
        chrome.visible = false
        chrome.sheet = false
    }
    YtChromeAutoHide(chrome, seek, session)
    val reveal = rememberChromeReveal(chrome)
    var pictureRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val frames = remember(session.info) { pickFrameset(session.info?.previewFrames.orEmpty(), PreviewMinWidthPx) }

    LaunchedEffect(expanded) {
        if (!expanded) Player.fullscreen = false
        progress.animateTo(if (expanded) 1f else 0f, spatial)
        // Landed back on the bar or window: where it opened from is spent.
        if (!expanded) com.gpo.pilipala.feature.sources.KeepPlaying.openFrom = null
    }
    // The mini bar takes the picture only once this has landed on its tile (one surface at a time).
    LaunchedEffect(progress) {
        androidx.compose.runtime.snapshotFlow { progress.value > 0.001f || Player.expanded }.collect { Player.overlayUp = it }
    }
    DisposableEffect(Unit) { onDispose { Player.overlayUp = false } }

    // Open at all (expanded, or left part-way by a drag): a collapsed bar never blocks the
    // page's own back.
    val open by remember { derivedStateOf { progress.value > 0.001f } }
    PredictiveBackHandler(enabled = expanded || open) { events ->
        val from = progress.value
        try {
            events.collect { e -> progress.snapTo(from - BackPreview * e.progress) }
            // Commit (also the 3-button / no-gesture path: an empty flow lands here at once).
            if (Player.expanded) Player.expanded = false
            else scope.launch { progress.animateTo(0f, spatial) }
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(from, spatial) }
            throw e
        }
    }

    // 后台播放 off: leaving the app pauses, coming back resumes what was playing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var resumeOnReturn = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!AppSettings.backgroundPlay.value && Player.isPlaying) {
                    Player.exo.pause()
                    resumeOnReturn = true
                }
                Lifecycle.Event.ON_START -> if (resumeOnReturn) {
                    resumeOnReturn = false
                    Player.exo.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // SponsorBlock skipped a segment: one quiet line with 撤销 (the bar closes the segment up).
    val snackbar = LocalSnackbarHost.current
    val lastSkip = Player.lastSkip
    LaunchedEffect(lastSkip) {
        val s = lastSkip ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar("已跳过${s.category.label}", actionLabel = "撤销", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) Player.undoSkip()
    }

    val view = LocalView.current
    DisposableEffect(Player.isPlaying) {
        view.keepScreenOn = Player.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    CoverTheme(session.thumbnail) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val w = maxWidth
            val h = maxHeight
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val miniBottom = if (onShell) navBottom + floatingBarInset() else navBottom + MiniMargin
            val twoPane = w >= 840.dp && h >= 480.dp

            // Rects (dp): the container, and the picture inside it, at both ends. Collapsed, the
            // picture lands exactly on the mini bar's tile (feature/sources/KeepPlaying.kt), which
            // takes over from there: the container is just the picture.
            // Opened from the bar, its circle or a floating window: it grows from where the picture
            // was, and goes back there (the window at its size and corner) when it collapses.
            val keep = com.gpo.pilipala.feature.sources.KeepPlaying
            val dock = (if (expanded) keep.openFrom else keep.landing ?: keep.openFrom) ?: with(density) {
                com.gpo.pilipala.feature.sources.KeepPlaying.dockTile(
                    onShell, w.toPx(), (h - navBottom).toPx(),
                    com.gpo.pilipala.ui.components.widthClass() != com.gpo.pilipala.ui.components.WidthClass.Compact, density,
                )
            }
            val miniW = with(density) { dock.width.toDp() }
            val miniH = with(density) { dock.height.toDp() }
            val miniX = with(density) { dock.left.toDp() }
            val miniY = with(density) { dock.top.toDp() }
            val leftW = if (twoPane) w - RelatedPaneWidth else w
            var fullVideoW = if (twoPane) leftW - 48.dp else w
            var fullVideoH = fullVideoW * 9f / 16f
            val maxVideoH = (h - statusTop) * 0.62f
            if (fullVideoH > maxVideoH) {
                fullVideoH = maxVideoH
                fullVideoW = fullVideoH * 16f / 9f
            }
            val fullVideoX = if (twoPane) 24.dp else (w - fullVideoW) / 2
            val fullVideoY = statusTop + if (twoPane) 16.dp else 0.dp
            val miniVideoW = miniW

            val p = progress.value
            val containerX = lerp(miniX, 0.dp, p)
            val containerY = lerp(miniY, 0.dp, p)
            val containerW = lerp(miniW, w, p)
            val containerH = lerp(miniH, h, p)
            val videoX = lerp(0.dp, fullVideoX, p)
            val videoY = lerp(0.dp, fullVideoY, p)
            val videoW = lerp(miniVideoW, fullVideoW, p)
            val videoH = lerp(miniH, fullVideoH, p)
            val corner = lerp(6.dp, 0.dp, p)
            val videoCorner = lerp(6.dp, if (twoPane) 16.dp else 0.dp, p)
            val rangePx = with(density) { (miniY).toPx() }.coerceAtLeast(1f)

            val drag = rememberDraggableState { delta ->
                scope.launch { progress.snapTo((progress.value - delta / rangePx).coerceIn(0f, 1f)) }
            }
            // The mini content fades out by p = 0.35; it stays composed while a drag that began
            // on it runs, or its draggable would be disposed mid-drag and never settle.
            var dragging by remember { mutableStateOf(false) }
            val settle: (Float) -> Unit = { velocity ->
                dragging = false
                val target = when {
                    velocity > FlingVelocity -> false
                    velocity < -FlingVelocity -> true
                    else -> progress.value > 0.5f
                }
                if (target == Player.expanded) {
                    scope.launch { progress.animateTo(if (target) 1f else 0f, spatial, initialVelocity = -velocity / rangePx) }
                } else {
                    Player.expanded = target
                }
            }

            // Collapsed, the app's one mini bar (feature/sources/KeepPlaying.kt) stands for the player;
            // this overlay only shows while it opens, is open, or is being dragged.
            if (p <= 0.001f && !expanded && !dragging) return@BoxWithConstraints

            // Behind the full player: the page dims a little as it rises.
            if (p > 0.001f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Color.Black.copy(alpha = 0.32f)))
            }

            val scheme = MaterialTheme.colorScheme
            Box(
                Modifier
                    .offset { IntOffset(containerX.roundToPx(), containerY.roundToPx()) }
                    .size(containerW, containerH)
                    .clip(ContinuousRoundedCornerShape(corner))
                    .background(lerp(scheme.surfaceContainerHigh, scheme.surface, p)),
            ) {
                // Full content first (under the picture in z-order).
                if (p > 0.01f) {
                    val alpha = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                this.alpha = alpha
                                translationY = (1f - p) * 48.dp.toPx()
                            },
                    ) {
                        FullContent(
                            session = session,
                            seek = seek,
                            chrome = chrome,
                            twoPane = twoPane,
                            topUnderVideo = fullVideoY + fullVideoH,
                            leftWidth = leftW,
                            statusTop = statusTop,
                            navBottom = navBottom,
                            onCollapse = { Player.expanded = false },
                        )
                    }
                }

                // Mini bar content, right of the picture.
                val miniAlpha = (1f - p / 0.35f).coerceIn(0f, 1f)
                if (miniAlpha > 0f || dragging) {
                    MiniContent(
                        session = session,
                        modifier = Modifier
                            .padding(start = miniVideoW)
                            .fillMaxWidth()
                            .height(miniH)
                            .graphicsLayer { alpha = miniAlpha }
                            .clickable { Player.expanded = true }
                            .draggable(drag, Orientation.Vertical, onDragStarted = { dragging = true }, onDragStopped = { settle(it) }),
                    )
                }

                // The picture: one view, mini corner ⇄ page top.
                Box(
                    Modifier
                        .offset { IntOffset(videoX.roundToPx(), videoY.roundToPx()) }
                        .size(videoW, videoH)
                        .clip(ContinuousRoundedCornerShape(videoCorner))
                        .background(Color.Black)
                        .onGloballyPositioned { pictureRect = it.boundsInWindow() }
                        .clickable(enabled = p < 0.5f) { Player.expanded = true }
                        .draggable(drag, Orientation.Vertical, onDragStarted = { dragging = true }, onDragStopped = { settle(it) })
                        // Open: a tap brings the controls, double tap skips, a hold speeds up.
                        .then(if (expanded) Modifier.pictureGestures(feedback, haptics) { chrome.toggle() } else Modifier),
                ) {
                    Picture(session)
                    if (!Player.fullscreen && !Player.audioOnly) SubtitleOverlay(big = false)
                    if (expanded) {
                        DoubleTapSeekLayer(feedback.skip, big = false)
                        HoldSpeedPill(feedback, big = false, modifier = Modifier.align(Alignment.Center))
                    }
                    if (expanded && p > 0.99f && !Player.fullscreen && !Player.audioOnly && session.error == null) {
                        YtPictureChrome(chrome, seek, reveal, onCollapse = { Player.expanded = false })
                    }
                }
                // Over the picture: the scrub preview rides above the seek bar's thumb.
                if (p > 0.99f && !Player.fullscreen) SeekBubbleLayer(seek, frames, big = false)
                // 仅听声音: the listen page over the open player; the picture flies into its cover,
                // and the page folds back into the picture as the player docks.
                ListenScreen(overlay = { progress.value }, from = { pictureRect })
            }
        }
        // Over everything, the overlay included: it takes the video surface while it is up.
        if (Player.fullscreen) FullscreenPlayer(session)
    }
}

private const val FlingVelocity = 1200f

@Composable
private fun BoxScope.Picture(session: Player.Session) {
    // 仅听声音 draws no picture: the cover stands in (the mini bar, the flight's start).
    if (Player.audioOnly) {
        NetImage(session.thumbnail, Modifier.fillMaxSize(), shape = ContinuousRoundedCornerShape(0.dp), thumbWidth = null)
        return
    }
    // Fullscreen owns the one video surface while it is up.
    if (session.info != null && session.error == null && !Player.fullscreen) {
        ContentFrame(
            player = Player.exo,
            modifier = Modifier.fillMaxSize(),
            // A TextureView: it scales with its frame through the whole fly, a SurfaceView would lag.
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = ContentScale.Fit,
            shutter = {},
        )
    }
    val waiting = session.info == null || (Player.isBuffering && Player.durationMs == 0L)
    if (waiting && session.error == null) {
        NetImage(session.thumbnail, Modifier.fillMaxSize(), shape = ContinuousRoundedCornerShape(0.dp), thumbWidth = null)
        LoadingIndicator(modifier = Modifier.align(Alignment.Center))
    }
    session.error?.let { message ->
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, color = Color.White, style = MaterialTheme.typography.labelLarge)
            FilledTonalButton(onClick = Player::retry) { Text("重试") }
        }
    }
}

@Composable
private fun MiniContent(session: Player.Session, modifier: Modifier) {
    Row(modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                session.channelName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = Player::toggle, shapes = IconButtonDefaults.shapes()) {
            Icon(if (Player.isPlaying) YoinSymbols.PauseFilled else YoinSymbols.PlayFilled, if (Player.isPlaying) "暂停" else "播放")
        }
        IconButton(onClick = Player::close, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Close, "关闭")
        }
    }
}

/**
 * The page under the picture, as PiliPaw's video page: on phones and tablet portrait the
 * controls, then 简介 / 评论 tabs over a pager (the intro is a grid, so 相关推荐 runs two columns
 * from tablet portrait); wide, the intro under the picture and 评论 / 相关 in a side panel.
 */
@Composable
private fun FullContent(
    session: Player.Session,
    seek: SeekState,
    chrome: YtChrome,
    twoPane: Boolean,
    topUnderVideo: Dp,
    leftWidth: Dp,
    statusTop: Dp,
    navBottom: Dp,
    onCollapse: () -> Unit,
) {
    val info = session.info
    val mutes by Mutes.version.collectAsState()
    val related = remember(info, mutes) {
        info?.relatedItems.orEmpty().filterIsInstance<StreamInfoItem>().map { it.toVideo() }.filterNot(Mutes::hides)
    }
    val comments = rememberCommentsState(session.url)
    val onSeek: (Long) -> Unit = { Player.exo.seekTo(it * 1000) }
    val bottom = PaddingValues(bottom = navBottom + 24.dp)
    ExpressivePageBackground(Modifier.fillMaxSize(), belowPlayer = !twoPane) {
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    modifier = Modifier.width(leftWidth).fillMaxHeight().padding(top = topUnderVideo),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = navBottom + 24.dp),
                ) {
                    item(key = "controls") { RevealedStrip(seek, chrome, hasNext = related.isNotEmpty(), Modifier.padding(horizontal = 8.dp)) }
                    ytIntroItems(session, info, related, onSeek, includeRelated = false)
                }
                // The side panel: 评论 / 相关 (PiliPaw's side pane).
                val panel = MaterialTheme.colorScheme.surfaceContainerLow
                val tabs = rememberPagerState { 2 }
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(RelatedPaneWidth)
                        .padding(top = statusTop + 8.dp, end = 16.dp, bottom = navBottom + 16.dp)
                        .clip(YoinContainerShapes.Panel)
                        .background(panel),
                ) {
                    YtDetailTabRow(listOf("评论", "相关"), tabs, panel)
                    HorizontalPager(tabs, Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
                        if (page == 0) {
                            CommentsList(comments, onSeek, Modifier.fillMaxSize(), PaddingValues(bottom = 16.dp))
                        } else {
                            LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                                ytRelatedItems(related)
                            }
                        }
                    }
                }
            }
        } else {
            val tabs = rememberPagerState { 2 }
            Column(Modifier.fillMaxSize().padding(top = topUnderVideo)) {
                RevealedStrip(seek, chrome, hasNext = related.isNotEmpty())
                YtDetailTabRow(listOf("简介", "评论"), tabs, Color.Transparent)
                HorizontalPager(tabs, Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
                    if (page == 0) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(RelatedMinCell),
                            modifier = Modifier.fillMaxSize().readingWidth(),
                            contentPadding = bottom,
                        ) {
                            ytIntroItems(session, info, related, onSeek, includeRelated = true)
                        }
                    } else {
                        CommentsList(comments, onSeek, Modifier.fillMaxSize(), bottom)
                    }
                }
            }
        }
    }
}

/**
 * The control strip under the picture, sliding out (it takes real height, the page moves down)
 * while the controls are up and folding away with them, as PiliPaw's inline strip does.
 */
@Composable
private fun RevealedStrip(seek: SeekState, chrome: YtChrome, hasNext: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = chrome.visible,
        modifier = modifier,
        enter = androidx.compose.animation.expandVertically(YoinMotion.defaultSpatialSpec()) +
            androidx.compose.animation.fadeIn(YoinMotion.defaultEffectsSpec()),
        exit = androidx.compose.animation.shrinkVertically(YoinMotion.defaultSpatialSpec()) +
            androidx.compose.animation.fadeOut(YoinMotion.fastEffectsSpec()),
    ) {
        YtInlineStrip(seek, chrome, hasNext)
    }
}

/** The picture-in-picture window's content: the video on black, nothing else. */
@Composable
private fun PipPicture(session: Player.Session) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (session.info != null && session.error == null) {
            ContentFrame(
                player = Player.exo,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = ContentScale.Fit,
                shutter = {},
            )
        }
    }
}
