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
import com.gpo.pilipala.yt.feature.library.AddToPlaylistSheet
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
import com.gpo.pilipala.yt.ui.components.FameCount
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.feature.settings.ConnectedToggleRow
import com.gpo.pilipala.feature.settings.Choice
import com.gpo.pilipala.yt.feature.comments.rememberCommentsState
import com.gpo.pilipala.yt.feature.comments.CommentsList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.ModalBottomSheet
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
import com.gpo.pilipala.yt.ui.components.ValueLabel
import com.gpo.pilipala.yt.ui.components.VideoRow
import com.gpo.pilipala.ui.shell.floatingBarInset
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.CoverTheme
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
    val onShell = nav.backStack.lastOrNull() == Route.Main
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val expanded = Player.expanded
    val feedback = remember { GestureFeedback() }
    val haptics = LocalHapticFeedback.current
    val seek = rememberSeekState()
    var pictureRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val frames = remember(session.info) { pickFrameset(session.info?.previewFrames.orEmpty(), PreviewMinWidthPx) }

    LaunchedEffect(expanded) {
        if (!expanded) Player.fullscreen = false
        progress.animateTo(if (expanded) 1f else 0f, spatial)
    }

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

            // Rects (dp): the container, and the picture inside it, at both ends.
            val miniW = minOf(w - MiniMargin * 2, MiniMaxWidth)
            val miniX = (w - miniW) / 2
            val miniY = h - miniBottom - MiniHeight
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
            val miniVideoW = MiniHeight * 16f / 9f

            val p = progress.value
            val containerX = lerp(miniX, 0.dp, p)
            val containerY = lerp(miniY, 0.dp, p)
            val containerW = lerp(miniW, w, p)
            val containerH = lerp(MiniHeight, h, p)
            val videoX = lerp(0.dp, fullVideoX, p)
            val videoY = lerp(0.dp, fullVideoY, p)
            val videoW = lerp(miniVideoW, fullVideoW, p)
            val videoH = lerp(MiniHeight, fullVideoH, p)
            val corner = lerp(16.dp, 0.dp, p)
            val videoCorner = lerp(12.dp, if (twoPane) 16.dp else 0.dp, p)
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
                            .height(MiniHeight)
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
                        // Open: double tap skips, a hold speeds up (a tap does nothing, nothing to show).
                        .then(if (expanded) Modifier.pictureGestures(feedback, haptics) {} else Modifier),
                ) {
                    Picture(session)
                    if (!Player.fullscreen && !Player.audioOnly) SubtitleOverlay(big = false)
                    if (expanded) {
                        DoubleTapSeekLayer(feedback.skip, big = false)
                        HoldSpeedPill(feedback, big = false, modifier = Modifier.align(Alignment.Center))
                    }
                }
                // Over the picture: the scrub preview rides above the seek bar's thumb.
                if (p > 0.99f && !Player.fullscreen) SeekBubbleLayer(seek, frames, big = false)
                // 仅听声音: the listen page over the open player; the picture flies into its cover.
                if (Player.audioOnly && p > 0.99f) ListenScreen(from = { pictureRect })
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

@Composable
private fun FullContent(
    session: Player.Session,
    seek: SeekState,
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
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(leftWidth)
                    .fillMaxHeight()
                    .padding(top = topUnderVideo)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = navBottom + 24.dp),
            ) {
                Controls(seek, onCollapse)
                Details(session, info)
            }
            // The right column: 相关 or 评论.
            var showComments by rememberSaveable(session.url) { mutableStateOf(false) }
            Column(Modifier.fillMaxHeight().width(RelatedPaneWidth).padding(top = statusTop + 12.dp)) {
                ConnectedToggleRow(
                    choices = listOf(Choice(false, "相关"), Choice(true, "评论")),
                    selected = showComments,
                    onSelect = { showComments = it },
                    modifier = Modifier.padding(start = 8.dp, end = 24.dp, bottom = 8.dp),
                )
                if (showComments) {
                    CommentsList(comments, onSeek, Modifier.fillMaxSize(), PaddingValues(bottom = navBottom + 24.dp))
                } else {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = navBottom + 24.dp),
                    ) {
                        items(related, key = { it.url }) { VideoRow(it, thumbWidth = 144.dp) }
                    }
                }
            }
        }
    } else {
        var sheet by rememberSaveable(session.url) { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().padding(top = topUnderVideo)) {
            Controls(seek, onCollapse, Modifier.padding(horizontal = 16.dp))
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = navBottom + 24.dp),
            ) {
                item(key = "details") { Details(session, info, Modifier.padding(horizontal = 16.dp)) }
                item(key = "comments") { CommentsEntry(-1) { sheet = true } }
                items(related, key = { it.url }) { VideoRow(it) }
            }
        }
        if (sheet) {
            ModalBottomSheet(onDismissRequest = { sheet = false }, properties = rememberSheetProperties()) {
                CommentsList(comments, onSeek, Modifier.fillMaxWidth(), PaddingValues(bottom = sheetBottomPadding())) {
                    item(key = "title") {
                        Text("评论", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

/** 评论 (and how many) as a tonal row under the details; opens the sheet. */
@Composable
private fun CommentsEntry(count: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(ContinuousRoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("评论", style = MaterialTheme.typography.titleSmall)
        if (count >= 0) FameCount(Fmt.count(count), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Icon(YoinSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Under the picture, in the page's colours: collapse, the seek bar, the times, fullscreen and play. */
@Composable
private fun Controls(seek: SeekState, onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        PawSeekBar(seek, pageTrackInk(), modifier = Modifier.padding(horizontal = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCollapse, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.ChevronDown, "收起")
            }
            Spacer(Modifier.weight(1f))
            TimeLabels(seek, MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            var sheet by remember { mutableStateOf(false) }
            IconButton(onClick = { sheet = true }, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.Settings, "播放设置")
            }
            if (sheet) PlaybackSheet(onDismiss = { sheet = false })
            IconButton(onClick = { Player.fullscreen = true }, shapes = IconButtonDefaults.shapes()) {
                Icon(PiliSymbols.Fullscreen, "全屏")
            }
            FilledTonalIconButton(onClick = Player::toggle, shapes = IconButtonDefaults.shapes()) {
                Icon(if (Player.isPlaying) YoinSymbols.PauseFilled else YoinSymbols.PlayFilled, if (Player.isPlaying) "暂停" else "播放")
            }
        }
    }
}

@Composable
private fun Details(session: Player.Session, info: StreamInfo?, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Column(modifier.padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(session.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (info != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (info.viewCount >= 0) ValueLabel(Fmt.count(info.viewCount), "次观看")
                if (info.likeCount >= 0) ValueLabel(Fmt.count(info.likeCount), "赞")
                // Return YouTube Dislike's estimate (YouTube hides the real count).
                if (info.dislikeCount >= 0) ValueLabel(Fmt.count(info.dislikeCount), "踩")
                Fmt.ago(info.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val channel = ChannelItem(
                url = info.uploaderUrl.orEmpty(),
                name = info.uploaderName.orEmpty(),
                avatar = info.uploaderAvatarUrl.orEmpty(),
                subscribers = info.uploaderSubscriberCount,
                videos = -1,
                verified = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(ContinuousRoundedCornerShape(12.dp))
                        .clickable(enabled = channel.url.isNotEmpty()) {
                            Player.expanded = false
                            nav.push(Route.Channel(channel.url, channel.name, channel.avatar))
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(channel.avatar, size = 40.dp)
                    Column {
                        Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (channel.subscribers >= 0) ValueLabel(Fmt.count(channel.subscribers), "订阅者")
                    }
                }
                SubscribeButton(channel)
            }
            VideoActions(info)
            if (Player.chapters.isNotEmpty()) ChapterRow(Player.chapters)
            Description(info)
        }
    }
}

/**
 * 稍后再看 and 加入播放列表 as tonal buttons. Signed out both are this device's; signed in,
 * 稍后再看 is the account's (a write: a dry run in debug builds).
 */
@Composable
private fun VideoActions(info: StreamInfo) {
    val profile by Accounts.youtube.collectAsState()
    val later by Library.watchLater.collectAsState()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val video = remember(info) {
        VideoItem(
            url = info.url, title = info.name.orEmpty(), thumbnail = info.thumbnailUrl.orEmpty(),
            channelName = info.uploaderName.orEmpty(), channelUrl = info.uploaderUrl.orEmpty(),
            channelAvatar = info.uploaderAvatarUrl.orEmpty(), durationSec = info.duration, views = info.viewCount,
            uploaded = "", uploadedAt = 0, isLive = false, isShort = false, channelVerified = false,
        )
    }
    var addSheet by remember { mutableStateOf(false) }
    val signedIn = profile is Profile.SignedIn
    val inLater = !signedIn && later.any { it.key == Library.keyOf(info.url) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            if (signedIn) {
                scope.launch {
                    toast(runCatching { AccountWrites.run("加入稍后再看") { YtAccount.watchLater(info.id, add = true) } }.getOrElse { it.friendlyMessage() })
                }
            } else {
                toast(if (Library.toggleWatchLater(video)) "已加入稍后再看" else "已移出稍后再看")
            }
        }) {
            Icon(if (inLater) YoinSymbols.Check else PiliSymbols.WatchLater, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("稍后再看")
        }
        FilledTonalButton(onClick = { addSheet = true }) {
            Icon(YoinSymbols.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("加到 Mix")
        }
        FilledTonalButton(onClick = { Player.changeAudioOnly(true) }) {
            Icon(YoinSymbols.Headphones, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("仅听声音")
        }
    }
    if (addSheet) AddToPlaylistSheet(video, onDismiss = { addSheet = false })
}

/**
 * 章节: the chapters as a row of small cards (still, time, title), the one playing in the
 * primary container; a tap seeks there.
 */
@Composable
private fun ChapterRow(chapters: List<Chapter>) {
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(chapters) {
        while (true) {
            position = Player.exo.currentPosition
            delay(500)
        }
    }
    val current = chapters.at(position)
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        val i = chapters.indexOf(current)
        if (i >= 0) listState.animateScrollToItem(i)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("章节", style = MaterialTheme.typography.titleSmall)
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chapters, key = { it.startMs }) { c ->
                val on = c === current
                Column(
                    Modifier
                        .width(148.dp)
                        .clip(ContinuousRoundedCornerShape(12.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { Player.exo.seekTo(c.startMs) }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (c.preview.isNotEmpty()) {
                        NetImage(c.preview, Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = ContinuousRoundedCornerShape(8.dp))
                    }
                    Text(
                        Fmt.duration(c.startMs / 1000).ifEmpty { "0:00" },
                        style = MaterialTheme.typography.labelMedium.withTabularFigures(),
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        c.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * 订阅 / 已订阅. Signed out it is the local list; signed in it is the account's (a write, so a
 * dry run in debug builds, which says so and changes nothing).
 */
@Composable
fun SubscribeButton(channel: ChannelItem) {
    val profile by Accounts.youtube.collectAsState()
    val subs by Subscriptions.channels.collectAsState()
    val accountIds by AccountSubscriptions.ids.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val signedIn = profile is Profile.SignedIn
    val subscribed = if (signedIn) AccountSubscriptions.channelId(channel.url) in accountIds else subs.any { it.url == channel.url }
    val onClick: () -> Unit = {
        if (signedIn) {
            scope.launch {
                toast(runCatching { AccountSubscriptions.toggle(channel.url, channel.name) }.getOrElse { it.friendlyMessage() })
            }
        } else {
            Subscriptions.toggle(channel)
        }
    }
    if (subscribed) {
        FilledTonalButton(onClick = onClick) { Text("已订阅") }
    } else {
        Button(onClick = onClick, enabled = channel.url.isNotEmpty()) { Text("订阅") }
    }
}

@Composable
private fun Description(info: StreamInfo) {
    val text = remember(info) {
        val d = info.description ?: return@remember ""
        when (d.type) {
            org.schabi.newpipe.extractor.stream.Description.HTML ->
                HtmlCompat.fromHtml(d.content, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            else -> d.content.orEmpty().trim()
        }
    }
    if (text.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (open) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { open = !open }
            .animateContentSize(YoinMotion.defaultSpatialSpec())
            .padding(16.dp),
    )
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
