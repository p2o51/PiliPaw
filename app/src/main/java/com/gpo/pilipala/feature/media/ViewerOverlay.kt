package com.gpo.pilipala.feature.media

import android.view.Window
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The viewer in a full-screen window of its own, added above every window the
 * app has at that moment (a comment sheet, the 楼中楼 sheet, any dialog), so
 * a picture opened inside a sheet grows over the sheet too. The window is
 * clear, undimmed and edge to edge; it exists only while a session does (one
 * window carries a session opened while the last one still flies home).
 */
@Composable
internal fun ImageViewerWindow(state: ViewerState) {
    val session = state.session ?: return
    // The app's own window: the bar icons the viewer's window starts from.
    val base = LocalView.current.hostWindow()
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            // Back and taps are the viewer's own (predictive back drives the dismiss).
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        // The dialog's own window (never the app's, should the lookup ever miss).
        val window = remember(view, base) { view.hostWindow()?.takeIf { it !== base } }
        // Runs with the first composition, before the window's first frame.
        DisposableEffect(window) {
            window?.prepareClearLayer(base)
            onDispose { }
        }
        key(session.id) { ViewerContent(state, session, window, base) }
    }
}

private enum class GestureMode { Undecided, Pan, Transform, Dismiss, Yield }

/** A page decodes at most this many pixels (three pages are alive at once). */
private const val MaxDecodePixels = 8_000_000f

/** How long the open waits for the viewer's window to be on screen before it goes anyway. */
private const val WindowShownTimeoutMs = 320L

private typealias ToneMap = SnapshotStateMap<Int, androidx.compose.animation.core.Animatable<Color, AnimationVector4D>>

@Composable
private fun ViewerContent(state: ViewerState, session: ViewerSession, window: Window?, base: Window?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val windowInfo = LocalWindowInfo.current
    val reduced = rememberReducedMotion()
    session.density = density
    session.reduced = reduced
    session.haptics = haptics

    val specs = ViewerSpecs(
        open = YoinMotion.defaultSpatialSpec(),
        settle = YoinMotion.defaultSpatialSpec(),
        // The landing must not overshoot the cell: the standard (near-critical) spring.
        flight = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard),
        fade = YoinMotion.defaultEffectsSpec(),
    )
    val zoomSpec = YoinMotion.defaultSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Color>()

    val scheme = MaterialTheme.colorScheme
    val fallback = remember(scheme.primary) { deepTone(scheme.primary.toArgb(), tone = 8.0, maxChroma = 12.0) }
    val card = scheme.surfaceContainerLow
    val tones: ToneMap = remember { mutableStateMapOf() }

    val pager = rememberPagerState(initialPage = session.initial) { session.images.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p ->
            if (session.phase == ViewerPhase.Closing) return@collect
            session.page = p
            if (session.phase != ViewerPhase.Opening) state.hide(session, session.keys.getOrNull(p))
        }
    }
    LaunchedEffect(session) {
        snapshotFlow { session.size != IntSize.Zero && (session.fits[session.initial]?.width ?: 0f) >= 1f }.first { it }
        // The cell's own bitmap is normally in memory already; a large size on its way gets a moment.
        withTimeoutOrNull(150L) { snapshotFlow { session.ready[session.initial] == true }.first { it } }
        session.runOpen(scope, specs.open) {
            // A window is focused once it is shown: from then on the picture over the cell is on screen.
            withTimeoutOrNull(WindowShownTimeoutMs) { snapshotFlow { windowInfo.isWindowFocused }.first { it } }
        }
    }

    // This window's bars: they follow the focused window, and this one is on top.
    SystemBarsHidden(session.barsHidden)
    // Bar icons: the page's own until the wash is deep, then light on it.
    val deepWash by remember(session) { derivedStateOf { session.backgroundAlpha > 0.5f } }
    LaunchedEffect(window, deepWash) {
        val w = window ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(w, w.decorView)
        val under = base?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars.isAppearanceLightStatusBars = !deepWash && under?.isAppearanceLightStatusBars == true
        bars.isAppearanceLightNavigationBars = !deepWash && under?.isAppearanceLightNavigationBars == true
    }

    val backEnabled = session.phase == ViewerPhase.Opening || session.phase == ViewerPhase.Open || session.phase == ViewerPhase.Back
    PredictiveBackHandler(enabled = backEnabled) { events ->
        if (!session.started) {
            events.collect { }
            session.abort()
            return@PredictiveBackHandler
        }
        var startY = Float.NaN
        try {
            events.collect { e ->
                if (session.phase != ViewerPhase.Back) session.startBack()
                if (startY.isNaN()) startY = e.touchY
                session.backProgress(YoinMotion.backGestureEasing.transform(e.progress), e.touchY - startY)
            }
            session.close(scope, specs)
        } catch (c: CancellationException) {
            if (session.phase == ViewerPhase.Back) session.settleOpen(scope, specs.settle)
            throw c
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val toast: (String) -> Unit = { text ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    var sheetPage by remember { mutableStateOf<Int?>(null) }

    val navBottom = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density).toFloat()
    // The dots (or the counter) need their band below the picture.
    val hasChrome = session.images.size > 1
    val band = if (hasChrome) navBottom + with(density) { 64.dp.toPx() } else 0f

    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { session.overlay = it }
            .onSizeChanged { session.size = it }
            .drawBehind {
                val a = session.backgroundAlpha
                if (a <= 0f) return@drawBehind
                val current = pager.currentPage
                val fraction = pager.currentPageOffsetFraction
                val other = if (fraction >= 0f) current + 1 else current - 1
                val c1 = tones[current]?.value ?: fallback
                val c2 = tones[other]?.value ?: c1
                val tone = lerp(c1, c2, abs(fraction))
                // Washes from the card's colour into the picture's own deep tone.
                drawRect(lerp(card, tone, a), alpha = a)
            },
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            userScrollEnabled = session.phase == ViewerPhase.Open,
            key = { it },
        ) { page ->
            ViewerPage(
                session = session,
                page = page,
                bandPx = band,
                tones = tones,
                fallback = fallback,
                effects = effects,
                zoomSpec = zoomSpec,
                onTap = { session.close(scope, specs) },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    sheetPage = page
                },
                onDrag = { mode, at, delta, velocityY ->
                    when (mode) {
                        0 -> session.startDrag(at)
                        1 -> session.dragBy(delta)
                        else -> session.endDrag(velocityY, scope, specs)
                    }
                },
            )
        }
        ViewerChrome(
            session = session,
            pager = pager,
            tones = tones,
            fallback = fallback,
            navBottom = navBottom,
        )
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp),
        )
    }

    sheetPage?.let { p ->
        val image = session.images[p]
        PictureSheet(
            // Opened over the immersive viewer: the bars stay away while it is up.
            immersive = session.barsHidden,
            onAction = { share ->
                if (!PictureSaver.supported) {
                    if (share) PictureSaver.shareLink(context, image.url)
                    else toast("无法保存")
                    return@PictureSheet
                }
                val result = runCatching { PictureSaver.save(context, image.url) }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                result.onSuccess { uri ->
                    if (share) {
                        PictureSaver.shareUri(context, uri)
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        toast("已保存")
                    }
                }.onFailure {
                    toast(if (share) "分享失败" else "保存失败")
                }
            },
            onDismiss = { sheetPage = null },
        )
    }
}


/**
 * One picture. It is laid out at its fitted rect; the zoom layer scales and
 * moves it; inside that the hero layer draws the session's [Pose] (the flight
 * from / to the cell, the dismiss). The cell's own bitmap is drawn at once,
 * the large size fades in over it when it arrives.
 */
@Composable
private fun ViewerPage(
    session: ViewerSession,
    page: Int,
    bandPx: Float,
    tones: ToneMap,
    fallback: Color,
    effects: androidx.compose.animation.core.FiniteAnimationSpec<Color>,
    zoomSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float>,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (mode: Int, at: Offset, delta: Offset, velocityY: Float) -> Unit,
) {
    val image = session.images[page]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cached = remember(image) { ViewerImages.cachedBitmap(context, image) }
    val cachedPainter = remember(cached) { cached?.let { BitmapPainter(it.asImageBitmap()) } }
    // The API's size when it gave one; else a guess off the cell's bitmap (a `_1c` crop can be
    // the cell's shape, not the picture's) until the large size tells the truth.
    var aspect by remember(image) {
        mutableFloatStateOf(
            when {
                image.aspect > 0f -> image.aspect
                cached != null && cached.height > 0 -> cached.width.toFloat() / cached.height
                else -> 0f
            },
        )
    }
    var aspectKnown by remember(image) { mutableStateOf(image.aspect > 0f) }
    val size = session.size
    val viewport = Size(size.width.toFloat(), size.height.toFloat())
    val area = if (bandPx > 0f && viewport.height > bandPx * 3f) {
        Rect(0f, bandPx, viewport.width, viewport.height - bandPx)
    } else {
        Rect(0f, 0f, viewport.width, viewport.height)
    }
    // A long picture is read, not fitted: a column from its top, panned down (as the feed shows its top).
    val long = image.isLong(aspect)
    val known = if (aspect > 0f) aspect else 1f
    val fit = (if (long) readingRect(known, viewport) else null) ?: fitRect(known, area)
    SideEffect { if (session.fits[page] != fit) session.fits[page] = fit }
    val fitState = rememberUpdatedState(fit)
    val viewportState = rememberUpdatedState(viewport)
    val zoom = remember { ZoomState() }
    DisposableEffect(zoom, page) {
        session.zooms[page] = zoom
        onDispose { if (session.zooms[page] === zoom) session.zooms.remove(page) }
    }
    // The tablet turned (or the aspect came late): the zoom and a long picture's reading place follow the new rect.
    val lastFit = remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(fit, viewport) {
        lastFit.value?.let { zoom.refit(it, fit, viewport) }
        lastFit.value = fit
    }

    LaunchedEffect(image) {
        ViewerImages.cachedTone(image)?.let { known ->
            tones.getOrPut(page) { Animatable(known) }.snapTo(known)
            return@LaunchedEffect
        }
        val tone = ViewerImages.tone(context, image, cached) ?: return@LaunchedEffect
        tones.getOrPut(page) { Animatable(fallback) }.animateTo(tone, effects)
    }
    val current = session.page == page
    LaunchedEffect(current) { if (!current) zoom.snapHome() }
    if (current) {
        LaunchedEffect(zoom) { snapshotFlow { zoom.scale }.collect { session.zoomScale = it } }
    }
    // (Closing folds the zoom into the flight's first pose and lets it go: see ViewerSession.close.)

    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val drag by rememberUpdatedState(onDrag)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(session, page) {
                detectTapGestures(
                    onTap = { if (session.interactive) tap() },
                    onDoubleTap = { if (session.interactive) zoom.toggle(it, fitState.value, viewportState.value, scope, zoomSpec) },
                    onLongPress = { if (session.interactive && !zoom.zoomed) longPress() },
                )
            }
            .pointerInput(session, page) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!session.interactive) return@awaitEachGesture
                    zoom.stop()
                    var mode = GestureMode.Undecided
                    var total = Offset.Zero
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                            if (mode == GestureMode.Yield) continue
                            if (pressed >= 2 && mode != GestureMode.Dismiss) mode = GestureMode.Transform
                            val fitNow = fitState.value
                            val viewport = viewportState.value
                            when (mode) {
                                GestureMode.Undecided -> {
                                    total += event.calculatePan()
                                    if (total.getDistance() > slop) {
                                        val vertical = abs(total.y) > abs(total.x)
                                        mode = when {
                                            // Zoomed, or a long picture being read: the picture moves while it can.
                                            zoom.canPan(total, fitNow, viewport) -> GestureMode.Pan
                                            zoom.zoomed -> GestureMode.Yield
                                            // At 1× (a long picture at its top): a pull down dismisses.
                                            vertical && total.y > 0f -> GestureMode.Dismiss
                                            else -> GestureMode.Yield
                                        }
                                        if (mode == GestureMode.Dismiss) {
                                            drag(0, down.position, Offset.Zero, 0f)
                                            drag(1, down.position, total, 0f)
                                        }
                                        if (mode == GestureMode.Pan || mode == GestureMode.Dismiss) event.changes.forEach { it.consume() }
                                    }
                                }
                                GestureMode.Pan -> {
                                    zoom.panBy(event.calculatePan(), fitNow, viewport)
                                    event.changes.forEach { it.consume() }
                                }
                                GestureMode.Transform -> {
                                    zoom.transform(event.calculateCentroid(useCurrent = true), event.calculateZoom(), event.calculatePan(), fitNow, viewport)
                                    event.changes.forEach { it.consume() }
                                }
                                GestureMode.Dismiss -> {
                                    drag(1, down.position, event.calculatePan(), 0f)
                                    event.changes.forEach { it.consume() }
                                }
                                GestureMode.Yield -> Unit
                            }
                        }
                    } finally {
                        val viewport = viewportState.value
                        when (mode) {
                            GestureMode.Pan -> zoom.fling(tracker.calculateVelocity(), fitState.value, viewport, scope)
                            GestureMode.Transform -> zoom.settle(fitState.value, viewport, scope, zoomSpec)
                            GestureMode.Dismiss -> drag(2, down.position, Offset.Zero, runCatching { tracker.calculateVelocity().y }.getOrDefault(0f))
                            else -> Unit
                        }
                    }
                }
            },
    ) {
        if (size == IntSize.Zero || fit.width < 1f || fit.height < 1f) return@Box
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val r = fitState.value
                    val placeable = measurable.measure(
                        Constraints.fixed(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(IntOffset(r.left.roundToInt(), r.top.roundToInt()))
                    }
                }
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                }
                .graphicsLayer {
                    val pose = session.pose(page, fit)
                    val s = (pose.image.width / fit.width).takeIf { it.isFinite() && it > 0.0001f } ?: 0.0001f
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = s
                    scaleY = s
                    translationX = pose.image.left - fit.left
                    translationY = pose.image.top - fit.top
                    alpha = pose.alpha
                    val local = Rect(
                        (pose.clip.left - pose.image.left) / s,
                        (pose.clip.top - pose.image.top) / s,
                        (pose.clip.right - pose.image.left) / s,
                        (pose.clip.bottom - pose.image.top) / s,
                    )
                    // The cell's own outline (its continuous corners) easing into a rectangle.
                    shape = ContourAt(local, pose.contour.scale(1f / s), session.innerPx / s)
                    clip = true
                },
        ) {
            PageImages(
                image = image,
                long = long,
                onReady = { if (session.ready[page] != true) session.ready[page] = true },
                cachedPainter = cachedPainter,
                fit = fit,
                viewport = viewport,
                zoom = zoom,
                interactive = session.interactive,
                onAspect = {
                    if (!aspectKnown && it > 0f) {
                        aspect = it
                        aspectKnown = true
                    }
                },
            )
        }
    }
}

/**
 * The page's pixels: the cell's cached bitmap at once, the large size fading
 * in over it. The cached copy is drawn Fit and centred: a cell's `_1c` crop
 * is the picture's centre at the cell's ratio, which is exactly where Fit
 * lays it inside the picture's full rect (an uncropped copy simply fills it).
 */
@Composable
private fun PageImages(
    image: ViewerImage,
    long: Boolean,
    onReady: () -> Unit,
    cachedPainter: BitmapPainter?,
    fit: Rect,
    viewport: Size,
    zoom: ZoomState,
    interactive: Boolean,
    onAspect: (Float) -> Unit,
) {
    val context = LocalContext.current
    var largeLoaded by remember(image) { mutableStateOf(false) }
    val largeAlpha by animateFloatAsState(if (largeLoaded) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "large")
    if (cachedPainter != null || largeLoaded) SideEffect { onReady() }
    Box(Modifier.fillMaxSize()) {
        if (cachedPainter != null) {
            Image(
                cachedPainter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else if (!largeLoaded) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
        // Enough pixels for the double-tap zoom, capped so a long picture never decodes whole.
        val target = zoom.doubleTapScale(fit, viewport)
        var w = fit.width * target
        var h = fit.height * target
        if (image.width > 0 && image.height > 0 && w > image.width) {
            w = image.width.toFloat()
            h = image.height.toFloat()
        }
        val k = min(1f, min(8192f / max(w, h), sqrt(MaxDecodePixels / (w * h).coerceAtLeast(1f))))
        val reqW = (w * k).roundToInt().coerceAtLeast(1)
        val reqH = (h * k).roundToInt().coerceAtLeast(1)
        // The original, unless it is wider than the CDN's largest webp (a long one is never asked
        // for a width: the CDN could scale it past what webp holds). The decode is capped by the size.
        val source = remember(image.url, image.width, long) {
            if (image.width > 1920 && !long) Images.thumb(image.url, 1920) else Images.normalize(image.url)
        }
        val request = remember(source, reqW / 64, reqH / 64) {
            ImageRequest.Builder(context)
                .data(source)
                .size(reqW, reqH)
                .crossfade(false)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = largeAlpha },
            onSuccess = {
                largeLoaded = true
                val img = it.result.image
                if (img.height > 0) onAspect(img.width.toFloat() / img.height)
            },
        )
        if (interactive && !largeLoaded) {
            // A long picture's box runs far below the screen: the indicator sits in the middle of what is seen.
            val density = LocalDensity.current
            LoadingIndicator(
                if (long) {
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = with(density) { (viewport.height / 2f).toDp() - 24.dp }.coerceAtLeast(0.dp))
                } else {
                    Modifier.align(Alignment.Center)
                },
            )
        }
    }
}

/**
 * Below the picture, in the empty band: the page dots (or a counter past 9).
 * Follows the bottom of the picture as pages slide; never drawn over a picture.
 */
@Composable
private fun ViewerChrome(
    session: ViewerSession,
    pager: PagerState,
    tones: ToneMap,
    fallback: Color,
    navBottom: Float,
) {
    val count = session.images.size
    if (count <= 1) return
    val current = pager.currentPage
    val density = LocalDensity.current
    val tone = tones[current]?.value ?: fallback
    val ink = remember(tone) { lightTone(tone) }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .height(48.dp)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        val fraction = pager.currentPageOffsetFraction
                        val other = if (fraction >= 0f) pager.currentPage + 1 else pager.currentPage - 1
                        val b1 = session.fits[pager.currentPage]?.bottom ?: 0f
                        val b2 = session.fits[other]?.bottom ?: b1
                        val bottom = lerp(b1, b2, abs(fraction))
                        val gap = with(density) { 8.dp.toPx() }
                        val max = session.size.height - navBottom - placeable.height - gap
                        placeable.place(0, (bottom + gap).coerceAtMost(max).roundToInt())
                    }
                }
                .graphicsLayer { alpha = session.chromeAlpha * (1f - scrolling(session, pager)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (count <= 9) {
                Dots(count, current, ink)
            } else {
                Text("${current + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink)
                Text("/$count", style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = 0.6f))
            }
        }
    }
}

/**
 * How much of the band below the picture a long picture being read takes
 * (0..1, following the swipe between pages): it runs under where the dots
 * would sit, so they give way rather than sit on its pixels.
 */
private fun scrolling(session: ViewerSession, pager: PagerState): Float {
    val fraction = pager.currentPageOffsetFraction
    val current = pager.currentPage
    val other = if (fraction >= 0f) current + 1 else current - 1
    val a = if (session.scrolls(current)) 1f else 0f
    val b = when {
        other !in session.images.indices -> a
        session.scrolls(other) -> 1f
        else -> 0f
    }
    return lerp(a, b, abs(fraction))
}

@Composable
private fun Dots(count: Int, current: Int, ink: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val on = i == current
            val width by animateDpAsState(if (on) 18.dp else 6.dp, YoinMotion.fastSpatialSpec(), label = "dot")
            val alpha by animateFloatAsState(if (on) 1f else 0.4f, YoinMotion.fastEffectsSpec(), label = "dotAlpha")
            Box(
                Modifier
                    .size(width = width, height = 6.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(ink),
            )
        }
    }
}
