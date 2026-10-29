package com.gpo.pilipala.navigation.transition

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.theme.BackMotionTokens
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/*
 * Page transitions for the single NavDisplay (predictive-back doctrine,
 * Pattern B replicated in-window for EVERY pushed page). Feature screens need
 * nothing: the host (AppNavHost) wraps every entry in PageEntryFrame and owns
 * back; cards opt into the container transform with Modifier.pageSharedSource.
 *
 * Forward (played by the entering PageEntryFrame in real time, over the page
 * beneath as captured at the tap — NavDisplay's own scene transition is a
 * no-op, its seekable clock made a heavy first frame eat the motion)
 *  - From a card (VideoCardV/H, live cards …): container transform. The
 *    container's bounds lerp from the card to the window on the spatial
 *    spring and it reads as a rounded card the whole way: the card's 12dp
 *    corner opens to the 28dp window corner early in the grow, holds while
 *    it moves and eases to 0 only over the last ~10% as it lands (one
 *    smoothstep curve of the growth); it starts as the card's own pixels,
 *    which fade off the full-size page on the effects spring; the page
 *    beneath dims under the M3 scrim and stays put.
 *  - Anything else (settings, search, replace …): Yoin's forward mirror —
 *    the page enters from 96dp on EMPHASIZED with a fade, clipped to the
 *    28dp window corner while it slides (easing to 0 as it settles), the
 *    page beneath stays put.
 *  - LocalPageSettled turns true only once the forward motion has landed.
 *
 * Back (the AOSP cross-activity replica, tokens in [BackMotionTokens])
 *  - The host consumes the gesture ([PageBackHandler], registered BEFORE the
 *    NavDisplay so every in-page BackHandler — fullscreen player, WebView
 *    history, the shell's tab → home — still wins while enabled).
 *  - The finger owns the pixels 1:1 through [YoinMotion.backGestureEasing]:
 *    the closing page scales to 0.9 with 28dp corners, a left-edge swipe parks
 *    its right edge 8dp from the screen edge, decelerated vertical follow.
 *  - The page beneath is the real previous page as it was last drawn: its
 *    pixels are captured at push time ([captureTop]) and posed at −96dp,
 *    scaling in sync. A snapshot instead of a second live composition keeps
 *    the gesture's first frame free (no page composes under the finger) and
 *    keeps a previous video page from starting a second player.
 *  - Commit: 450ms EMPHASIZED to completion, closing alpha max(1 − 5t, 0);
 *    THEN the back stack pops with no motion of its own and the live page
 *    replaces its snapshot under a short effects fade. Cancel: the predictive
 *    back settle spring. 3-button back and Navigator.pop() (top-bar back)
 *    play the same commit from rest.
 */

internal enum class BackPhase { Idle, Gesture, Committing, Revealing }

/** The previous page's pixels, valid only for the window it was taken in. */
internal class PageSnapshot(val image: ImageBitmap, val size: IntSize, val stamp: Int)

/**
 * A push's forward motion: grow out of the card at [source] (container) or
 * the 96dp mirror, over [backdrop] — the page beneath as it was at the tap.
 * A source with a [solid] colour (a FAB: one glyph on a colour block) starts
 * the container as that colour instead of its own pixels, and its corner
 * opens from [sourceCornerPx] (NaN: the card token).
 */
internal class PageEnter(
    val key: Any,
    val container: Boolean,
    val backdrop: PageSnapshot,
    val source: Rect,
    val solid: Color = Color.Unspecified,
    val sourceCornerPx: Float = Float.NaN,
)

/**
 * Debug builds: a back's timeline under the tag PiliBack, from the commit (release) on: the
 * ride, the pop (the page beneath live), the snapshot giving way, the reveal, every finger and the
 * first drag after the release, and every frame that took long, so "it does not answer at once"
 * can be read in milliseconds.
 */
internal object BackLog {
    private var t0 = 0L
    private var downs = 0
    private var moved = false

    // Release builds too, once asked for: adb shell setprop log.tag.PiliBack D
    private val on: Boolean get() = com.gpo.pilipala.BuildConfig.DEBUG || android.util.Log.isLoggable("PiliBack", android.util.Log.DEBUG)

    fun start(page: String, fromRest: Boolean, scope: CoroutineScope, phase: () -> BackPhase) {
        if (!on) return
        t0 = android.os.SystemClock.uptimeMillis()
        downs = 0
        moved = false
        android.util.Log.d("PiliBack", "commit $page ${if (fromRest) "(button / swipe)" else "(gesture)"}")
        // Long frames from the release until the back is over.
        scope.launch {
            var last = 0L
            while (phase() != BackPhase.Idle) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val ms = now / 1_000_000
                    if (last > 0L && ms - last > 24) mark("long frame ${ms - last} ms")
                    last = ms
                }
            }
        }
    }

    fun mark(what: String) {
        if (!on || t0 == 0L) return
        val dt = android.os.SystemClock.uptimeMillis() - t0
        if (dt > 4_000) return
        android.util.Log.d("PiliBack", "+$dt ms $what")
    }

    /** A finger came down (phase as it was), or the first one after the release moved. */
    fun down(phase: BackPhase) {
        if (!on) return
        downs++
        moved = false
        mark("finger down #$downs during $phase")
    }

    fun move() {
        if (!on || moved) return
        moved = true
        mark("finger moves #$downs")
    }
}

@Stable
class PageTransitions internal constructor(
    private val backStack: NavBackStack<NavKey>,
    private val scope: CoroutineScope,
) {
    // ── Back ────────────────────────────────────────────────────────────────

    internal var phase by mutableStateOf(BackPhase.Idle)
        private set

    /** No back is running: layout positions read now are the page's own, not a posed copy. */
    val backIdle: Boolean get() = phase == BackPhase.Idle

    /** Closing page pose, 0..1: the eased gesture progress, then the commit ride. */
    internal val closing = Animatable(0f)

    /** Post-commit linear time t (0..1); the closing page's alpha is max(1 − 5t, 0). */
    internal val exit = Animatable(0f)

    /** The revealed page's offset: 1 = parked at −96dp, 0 = in place. */
    internal val behind = Animatable(1f)

    /** NavDisplay alpha while the live page takes over from its snapshot. */
    internal val reveal = Animatable(1f)

    internal var touchYDelta by mutableFloatStateOf(0f)
    internal var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)

    /**
     * A finger came down while a back was finishing: the ride ends at once (on a quick spring)
     * and the page beneath stops waiting for its covers, so it is live under that finger.
     */
    private var hurry by mutableStateOf(false)

    /** A touch anywhere in the window (seen, never taken): see [hurry]. */
    internal fun touched() {
        BackLog.down(phase)
        if (phase == BackPhase.Committing || phase == BackPhase.Revealing) hurry = true
    }

    /** Gesture progress at release: the revealed page keeps that scale while it rides home. */
    internal var seedProgress by mutableFloatStateOf(0f)

    /** What is drawn beneath the closing page (null: bare background). */
    internal var beneath by mutableStateOf<PageSnapshot?>(null)
        private set

    private var pendingPops = 0
    private var generation = 0L

    /**
     * After the pop, while the live page draws hidden: the pixels beneath are drawn over
     * everything too (BeneathPage over), so the page that left — a video page's SurfaceView
     * hole above all, still composed for a frame — cannot show through.
     */
    internal var holding by mutableStateOf(false)
        private set

    /** The page a commit removed, until its frame has left composition. */
    private var leaving by mutableStateOf<String?>(null)

    // ── Hero (PageHero.kt) ──────────────────────────────────────────────────

    /** The picture flying home during a commit; null for every other back. */
    internal var hero by mutableStateOf<HeroFlight?>(null)
        private set

    /** The hero's travel, 0 (its place on the closing page) → 1 (the cover beneath). */
    internal val heroProgress = Animatable(0f)

    private val covers = HashMap<String, HeroCoverNode>()

    /** The cover each pushed entry was opened from (host px, as in the snapshot beneath it). */
    private val pushedCovers = HashMap<String, Rect>()

    /**
     * Pages whose back may dock their picture somewhere else than the card they came from (the
     * video page into 继续播放's mini bar), by entry key: when [HeroDock.enabled] at the commit,
     * the hero flies there instead, whichever back it is (gesture, button, the picture's swipe).
     */
    private val heroDocks = HashMap<String, HeroDock>()

    internal fun registerHeroDock(dock: HeroDock) {
        heroDocks[dock.key] = dock
    }

    internal fun unregisterHeroDock(dock: HeroDock) {
        if (heroDocks[dock.key] === dock) heroDocks.remove(dock.key)
    }
    private val heroes = HashMap<String, PageHeroCapture>()

    internal fun registerCover(key: String, node: HeroCoverNode) {
        covers[key] = node
    }

    internal fun unregisterCover(key: String, node: HeroCoverNode) {
        if (covers[key] === node) covers.remove(key)
    }

    /** The cover placed last wins its key (the one on screen, as with card sources). */
    internal fun noteCover(key: String, node: HeroCoverNode) {
        covers[key] = node
    }

    internal fun registerHero(key: String, capture: PageHeroCapture) {
        heroes[key] = capture
    }

    internal fun unregisterHero(key: String, capture: PageHeroCapture) {
        if (heroes[key] === capture) heroes.remove(key)
    }

    private fun coverBounds(key: String): Rect? {
        val c = covers[key]?.coordinates ?: return null
        if (!c.isAttached) return null
        return Rect(c.positionInRoot(), c.size.toSize())
    }

    /**
     * Finger travel that counts as a whole gesture (px), so a wide window needs a
     * proportionate swipe: the system's progress saturates after a phone's width
     * (~412dp), which on a tablet is a third of the screen. Set by the host.
     */
    internal var gestureSpanPx by mutableFloatStateOf(0f)

    // ── Snapshots ───────────────────────────────────────────────────────────

    private val layers = HashMap<Any, GraphicsLayer>()
    private val snapshots = LinkedHashMap<Any, PageSnapshot>()

    /** Host (window content) size, px. */
    internal var hostSize = IntSize.Zero

    /** uiMode etc.: a snapshot from another theme is stale. */
    internal var configStamp = 0

    // ── Forward ─────────────────────────────────────────────────────────────

    /** How the page being pushed enters; consumed by its frame's first composition. */
    private var pendingEnter: PageEnter? = null

    /**
     * True when the latest push has no backdrop (nothing captured): the
     * scene transition itself then plays the forward mirror over the live
     * page beneath. Otherwise the entering frame owns the motion.
     */
    internal var sceneMirror by mutableStateOf(true)
        private set

    private val sources = HashMap<String, SourceInfo>()

    /** A card key's live sources, the layout of the one placed last and its look (solid sources only). */
    private class SourceInfo(
        var count: Int,
        var coordinates: LayoutCoordinates?,
        var solid: Color = Color.Unspecified,
        var cornerPx: Float = Float.NaN,
    )

    /** The container's growth, 0 (the card) → 1 (the window): the spatial spring, run to sub-pixel. */
    internal val containerGrowthSpec: FiniteAnimationSpec<Float> = run {
        val spatial = YoinMotion.slowSpatialSpec<Float>(
            role = YoinMotionRole.Standard,
            expressiveScheme = MotionScheme.expressive(),
        ) as? SpringSpec<Float>
        spring(
            dampingRatio = spatial?.dampingRatio ?: Spring.DampingRatioNoBouncy,
            stiffness = spatial?.stiffness ?: Spring.StiffnessMediumLow,
            visibilityThreshold = ContainerProgressThreshold,
        )
    }

    /** The page's content fade-in on either forward motion. */
    internal val enterFadeSpec: FiniteAnimationSpec<Float> = YoinMotion.defaultEffectsSpec(
        role = YoinMotionRole.Standard,
        expressiveScheme = MotionScheme.expressive(),
    )

    /** The container's bounds (host px) at growth [s], from [source]. */
    internal fun containerRect(source: Rect, s: Float): Rect {
        val full = Rect(0f, 0f, hostSize.width.toFloat(), hostSize.height.toFloat())
        return lerp(source, full, s.coerceIn(0f, 1f))
    }

    internal fun takeEnter(key: Any): PageEnter? {
        val enter = pendingEnter ?: return null
        if (enter.key != key) return null
        pendingEnter = null
        return enter
    }

    internal fun registerSource(key: String) {
        val info = sources.getOrPut(key) { SourceInfo(0, null) }
        info.count++
    }

    internal fun unregisterSource(key: String, coordinates: LayoutCoordinates?) {
        val info = sources[key] ?: return
        if (--info.count <= 0) {
            sources.remove(key)
        } else if (info.coordinates === coordinates) {
            // Another card with this key is still around; it notes itself on its next placement.
            info.coordinates = null
        }
    }

    /** Called on every placement of a source: a reference store only. */
    internal fun noteSourceCoordinates(key: String, coordinates: LayoutCoordinates) {
        sources[key]?.coordinates = coordinates
    }

    /** A solid source's colour and corner (px), noted with its placement. */
    internal fun noteSourceLook(key: String, solid: Color, cornerPx: Float) {
        val info = sources[key] ?: return
        info.solid = solid
        info.cornerPx = cornerPx
    }

    /** The card's bounds in the window (unclipped, like the snapshot), or null when it is gone. */
    private fun sourceBounds(key: String): Rect? {
        val coordinates = sources[key]?.coordinates ?: return null
        if (!coordinates.isAttached) return null
        return Rect(coordinates.positionInRoot(), coordinates.size.toSize())
    }

    internal fun attachLayer(key: Any, layer: GraphicsLayer) {
        layers[key] = layer
    }

    internal fun detachLayer(key: Any, layer: GraphicsLayer) {
        if (layers[key] === layer) layers.remove(key)
        if (leaving == key.toString()) {
            BackLog.mark("closing page left")
            leaving = null
        }
    }

    /** The entry left the back stack: its pixels can go. */
    internal fun onEntryPopped(key: Any) {
        snapshots.remove(key)
        pushedCovers.remove(key.toString())
    }

    /**
     * Memory pressure (the app went to the background LRU): keep only the
     * newest snapshot — the page right beneath the top, which the next back
     * reveals — and let deeper backs take the no-snapshot dissolve.
     */
    internal fun trimSnapshots() {
        while (snapshots.size > 1) snapshots.remove(snapshots.keys.first())
    }

    // ── Navigator hooks ─────────────────────────────────────────────────────

    /**
     * Before [route] is pushed: freeze the page about to be covered (its
     * pixels are what a later back reveals) and pick the forward motion.
     */
    internal fun beforePush(route: androidx.navigation3.runtime.NavKey) {
        val backdrop = captureTop()
        val key = (route as? Route)?.sharedKey()
        val source = key?.let { sourceBounds(it) }
        val look = key?.let { sources[it] }
        val container = source != null && !source.isEmpty &&
            backdrop != null && phase == BackPhase.Idle
        pendingEnter = backdrop?.let {
            PageEnter(
                route.toString(),
                container,
                it,
                source ?: Rect.Zero,
                solid = look?.solid ?: Color.Unspecified,
                sourceCornerPx = look?.cornerPx ?: Float.NaN,
            )
        }
        sceneMirror = backdrop == null
        val cover = key?.let { coverBounds(it) }
        if (container && cover != null && !cover.isEmpty) pushedCovers[route.toString()] = cover
    }

    /** A replace keeps the page beneath; the new top fades in on the forward mirror over the old one. */
    internal fun beforeReplace(route: androidx.navigation3.runtime.NavKey) {
        val backdrop = captureTop()
        pendingEnter = backdrop?.let { PageEnter(route.toString(), false, it, Rect.Zero) }
        sceneMirror = backdrop == null
    }

    /**
     * A back the page drives itself: a swipe down on the video page's picture. The page follows
     * [SwipeBack.drag] 1:1 as it does a back gesture (no sideways park), then commits or settles.
     * Null while another back runs, or with nothing to go back to.
     */
    internal fun startSwipeBack(): SwipeBack? {
        if (phase != BackPhase.Idle || backStack.size <= 1) return null
        val events = kotlinx.coroutines.channels.Channel<BackEventCompat>(kotlinx.coroutines.channels.Channel.CONFLATED)
        scope.launch {
            runCatching {
                handleBack(kotlinx.coroutines.flow.flow { for (e in events) emit(e) })
            }
        }
        return SwipeBack(events)
    }

    /** Navigator.pop(): the same commit a 3-button back plays, from rest. */
    internal fun requestPop() {
        when (phase) {
            BackPhase.Idle -> launchCommit()
            BackPhase.Committing, BackPhase.Revealing -> pendingPops++
            // A gesture owns the page right now; its own release decides.
            BackPhase.Gesture -> Unit
        }
    }

    /** Freezes the top page's pixels; returns them when the capture completed synchronously. */
    private fun captureTop(): PageSnapshot? {
        val key = backStack.lastOrNull()?.toString() ?: return null
        val layer = layers[key] ?: return null
        val size = layer.size
        if (size.width <= 0 || size.height <= 0) return null
        val stamp = configStamp
        var captured: PageSnapshot? = null
        // LayerSnapshotV28 renders synchronously, so with UNDISPATCHED the
        // pixels are taken before the push recomposes anything.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val image = runCatching { layer.toImageBitmap() }.getOrNull() ?: return@launch
            val snapshot = PageSnapshot(image, size, stamp)
            snapshots.remove(key)
            snapshots[key] = snapshot
            while (snapshots.size > MaxSnapshots) {
                snapshots.remove(snapshots.keys.first())
            }
            captured = snapshot
        }
        return captured
    }

    private fun snapshotBeneath(): PageSnapshot? {
        if (backStack.size < 2) return null
        val key = backStack[backStack.lastIndex - 1].toString()
        val snap = snapshots[key] ?: return null
        return snap.takeIf { it.size == hostSize && it.stamp == configStamp }
    }

    // ── Back ────────────────────────────────────────────────────────────────

    /** Body of the host's PredictiveBackHandler. */
    internal suspend fun handleBack(events: Flow<BackEventCompat>) {
        if (phase == BackPhase.Committing || phase == BackPhase.Revealing) {
            // One commit at a time; this back runs right after it.
            events.collect { }
            pendingPops++
            return
        }
        val owner = ++generation
        var sawGesture = false
        var initialTouchY = Float.NaN
        var initialTouchX = Float.NaN
        try {
            events.collect { event ->
                if (!sawGesture) {
                    sawGesture = true
                    prepareReveal()
                    phase = BackPhase.Gesture
                }
                if (initialTouchY.isNaN()) initialTouchY = event.touchY
                if (initialTouchX.isNaN()) initialTouchX = event.touchX
                swipeEdge = event.swipeEdge
                touchYDelta = event.touchY - initialTouchY
                // The finger's own travel against the window's span (see gestureSpanPx); the
                // system's progress where the touch position is unknown.
                val span = gestureSpanPx
                val travel = abs(event.touchX - initialTouchX)
                val progress = if (span > 0f && !event.touchX.isNaN() && !initialTouchX.isNaN()) {
                    (travel / span).coerceIn(0f, 1f)
                } else {
                    event.progress
                }
                // Direct 1:1 tracking: the eased progress IS the pose.
                closing.snapTo(YoinMotion.backGestureEasing.transform(progress))
            }
            launchCommit()
        } catch (e: CancellationException) {
            if (sawGesture && generation == owner) {
                // The handler coroutine is dying; settle on the host scope.
                scope.launch {
                    closing.animateTo(0f, YoinMotion.predictiveBackSettleSpring())
                    if (generation == owner && phase == BackPhase.Gesture) {
                        touchYDelta = 0f
                        beneath = null
                        phase = BackPhase.Idle
                    }
                }
            }
            throw e
        }
    }

    private suspend fun prepareReveal() {
        beneath = snapshotBeneath()
        behind.snapTo(1f)
        exit.snapTo(0f)
        reveal.snapTo(1f)
    }

    private fun launchCommit() {
        if (backStack.size <= 1) {
            if (phase == BackPhase.Gesture) phase = BackPhase.Idle
            return
        }
        val owner = ++generation
        // The page this commit closes. A push or replace during the ride must not make the
        // landing remove the page that arrived instead (or index past a shrunken stack).
        val closingEntry = backStack.last()
        val fromRest = phase != BackPhase.Gesture
        pendingEnter = null
        hurry = false
        // Synchronously, so a second pop in the same frame queues behind this one.
        phase = BackPhase.Committing
        BackLog.start(closingEntry.toString().substringBefore('('), fromRest, scope) { phase }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (fromRest) {
                prepareReveal()
                // No gesture parked the page beneath: it waits where it rests (sliding it in from
                // −96dp on the emphasized curve put nearly all of that move in the first frames,
                // read as the page beneath jumping sideways).
                behind.snapTo(0f)
                closing.snapTo(0f)
                touchYDelta = 0f
            }
            seedProgress = closing.value
            // The ride home, on the host scope: it carries on past the pop below.
            var ride: kotlinx.coroutines.Deferred<Unit>? = null
            if (beneath != null) {
                prepareHero()
                val rideSpec = tween<Float>(BackMotionTokens.PostCommitDurationMs, easing = BackMotionTokens.EmphasizedEasing)
                ride = scope.async {
                    val main = launch {
                        coroutineScope {
                            launch { behind.animateTo(0f, rideSpec) }
                            launch { closing.animateTo(1f, rideSpec) }
                            launch { exit.animateTo(1f, tween(BackMotionTokens.PostCommitDurationMs, easing = LinearEasing)) }
                        }
                    }
                    val watch = launch {
                        snapshotFlow { hurry }.first { it }
                        main.cancel()
                    }
                    main.join()
                    watch.cancel()
                    BackLog.mark(if (main.isCancelled) "ride cut short by a touch" else "ride done")
                    if (main.isCancelled && generation == owner) {
                        // A touch: the rest of the ride on a quick spring (no jump).
                        val quick = YoinMotion.fastEffectsSpec<Float>(role = YoinMotionRole.Standard, expressiveScheme = MotionScheme.expressive())
                        coroutineScope {
                            launch { behind.animateTo(0f, quick) }
                            launch { closing.animateTo(1f, quick) }
                            launch { exit.animateTo(1f, quick) }
                        }
                    }
                }
                if (hero != null) {
                    // The flight goes on by itself; the mini player shows once it has landed
                    // (never two pictures at once), not after the slow spring's last settling.
                    val docked = heroDocked
                    val onLanded = dockLanded
                    scope.launch { heroProgress.animateTo(1f, heroSpec) }
                    scope.launch {
                        snapshotFlow { heroProgress.value >= HeroLandedAt }.first { it }
                        BackLog.mark("picture landed (${if (docked) "mini player" else "cover"})")
                        if (docked) onLanded?.invoke()
                    }
                }
                // Pop before the ride ends: the page beneath builds (Home: a ~90 ms frame) while the
                // pixels ride their last, nearly still stretch, and the picture has landed, so the
                // build's long frame does not stop anything in mid-flight. The closing page faded
                // out under the beneath's cover long before.
                val r = ride
                val flying = hero != null
                snapshotFlow { (exit.value >= PopAtExit && (!flying || heroProgress.value >= HeroLandedAt)) || !r.isActive }.first { it }
            } else {
                // Nothing to ride home (no valid pixels of the page beneath,
                // e.g. taken before a rotation): end the moment the closing
                // page has dissolved — the first fifth of the ride — and let
                // the live page come up instead of holding a bare background.
                val dissolveMs = BackMotionTokens.PostCommitDurationMs / 5
                exit.animateTo(0.2f, tween(dissolveMs, easing = LinearEasing))
            }
            if (generation != owner) return@launch

            // Pop with no motion of its own: the closing page is invisible,
            // its predecessor's pixels sit in place beneath. The live page
            // comes up hidden and fades over its own snapshot once drawn.
            reveal.snapTo(0f)
            phase = BackPhase.Revealing
            val closingIndex = backStack.indexOfLast { it === closingEntry }
            if (closingIndex < 1) {
                // Already gone (replaced, or dropped by popToMain): nothing left to remove.
                reveal.snapTo(1f)
                closing.snapTo(0f)
                exit.snapTo(0f)
                touchYDelta = 0f
                holding = false
                leaving = null
                beneath = null
                hero = null
                heroProgress.snapTo(0f)
                behind.snapTo(1f)
                phase = BackPhase.Idle
                pendingPops = 0
                return@launch
            }
            val revealedKey = backStack[closingIndex - 1].toString()
            val rideEnd = android.os.SystemClock.uptimeMillis()
            com.gpo.pilipala.feature.sources.MiniLog.landed(closingEntry.toString(), hero != null)
            holding = true
            leaving = closingEntry.toString()
            backStack.removeAt(closingIndex)
            BackLog.mark("popped: page beneath is live")
            if (ride == null) {
                closing.snapTo(0f)
                exit.snapTo(0f)
                touchYDelta = 0f
            }
            // The live page draws hidden under the pixels it replaces until it is really there:
            // a few frames, the page that left gone from composition, and its covers in (one
            // fading in from an empty tile over the snapshot read as a flash). A slow line
            // goes after a moment.
            repeat(RevealHoldFrames) { withFrameNanos { } }
            withTimeoutOrNull(RevealWaitMs) {
                snapshotFlow { leaving == null }.first { it }
                // A finger on the page beneath: it shows now, covers loading or not.
                kotlinx.coroutines.flow.combine(snapshotFlow { hurry }, com.gpo.pilipala.ui.components.ImageLoads.inFlight) { h, n -> h || n == 0 }
                    .first { it }
            }
            // The pixels beneath stay over the live page until they have ridden home and the
            // picture has landed on them.
            ride?.await()
            if (ride != null) {
                closing.snapTo(0f)
                exit.snapTo(0f)
                touchYDelta = 0f
            }
            if (hero != null) snapshotFlow { heroProgress.value >= HeroLandedAt }.first { it }
            leaving = null
            holding = false
            BackLog.mark("snapshot gives way (hurried=$hurry)")
            com.gpo.pilipala.feature.sources.MiniLog.event(
                "page beneath shows",
                "after ${android.os.SystemClock.uptimeMillis() - rideEnd} ms (images still loading ${com.gpo.pilipala.ui.components.ImageLoads.inFlight.value})",
            )
            if (generation != owner) return@launch
            reveal.animateTo(1f, revealSpec)
            BackLog.mark("revealed")
            if (generation != owner) return@launch

            beneath = null
            hero = null
            heroProgress.snapTo(0f)
            behind.snapTo(1f)
            snapshots.remove(revealedKey)
            phase = BackPhase.Idle
            BackLog.mark("idle")
            if (pendingPops > 0) {
                pendingPops--
                if (backStack.size > 1) launchCommit() else pendingPops = 0
            }
        }
    }

    /**
     * The closing page's picture, if its route came from a cover and the page can give a
     * frame of it: briefly waited for (a PixelCopy is a frame or two), else no hero.
     */
    /** The hero flying now lands in the mini player (a [HeroDock]), not on a cover. */
    private var heroDocked = false

    /** That dock's word that the picture is there. */
    private var dockLanded: (() -> Unit)? = null

    private suspend fun prepareHero() {
        hero = null
        heroDocked = false
        dockLanded = null
        heroProgress.snapTo(0f)
        val top = backStack.lastOrNull() as? Route ?: return
        val dock = heroDocks[top.toString()]?.takeIf { it.enabled() }
        if (dock != null) {
            val to = dock.target() ?: return
            val capture = top.sharedKey()?.let { heroes[it] } ?: return
            val shot = withTimeoutOrNull(HeroCaptureTimeoutMs) { runCatching { capture() }.getOrNull() } ?: return
            if (shot.second.isEmpty) return
            dock.landing(shot.first)
            com.gpo.pilipala.feature.sources.MiniLog.event("hero flies to bar", top.toString())
            hero = HeroFlight(shot.first, shot.second, to, top.sharedKey().orEmpty(), dock.corner())
            heroDocked = true
            dockLanded = dock.landed
            return
        }
        val cover = pushedCovers[top.toString()] ?: return
        val capture = top.sharedKey()?.let { heroes[it] } ?: return
        val shot = withTimeoutOrNull(HeroCaptureTimeoutMs) { runCatching { capture() }.getOrNull() } ?: return
        if (shot.second.isEmpty) return
        hero = HeroFlight(shot.first, shot.second, cover, top.sharedKey().orEmpty())
    }

    /** The picture's flight home: the slow spatial spring, landing with the page beneath. */
    private val heroSpec: FiniteAnimationSpec<Float> = YoinMotion.slowSpatialSpec(
        role = YoinMotionRole.Standard,
        expressiveScheme = MotionScheme.expressive(),
    )

    private val revealSpec = YoinMotion.defaultEffectsSpec<Float>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MotionScheme.expressive(),
    )

    private companion object {
        /**
         * Full-window snapshots kept (least recently covered goes first). Each
         * is a window-sized bitmap (~16MB on the tablet); a back past these
         * takes the no-snapshot dissolve.
         */
        const val MaxSnapshots = 3

        /** Frames the live page draws, hidden, before it fades over its snapshot. */
        const val RevealHoldFrames = 3

        /**
         * How far into the ride the pop comes: the closing page has long faded out (by 0.2) and the
         * emphasized ride has nearly arrived, so a long frame for the page beneath barely shows.
         */
        const val PopAtExit = 0.6f

        /** How far the picture's flight has gone when it counts as landed (the rest is the spring settling). */
        const val HeroLandedAt = 0.97f

        /** The most the live page waits (past those frames) for its covers and the old page to go. */
        const val RevealWaitMs = 450L

        /** How long a commit waits for the hero's frame before it goes without one. */
        const val HeroCaptureTimeoutMs = 60L

        /** ≈ 0.2px of a full-window container's travel. */
        const val ContainerProgressThreshold = 1e-4f
    }
}

@Composable
internal fun rememberPageTransitions(backStack: NavBackStack<NavKey>): PageTransitions {
    val scope = rememberCoroutineScope()
    val transitions = remember(backStack) { PageTransitions(backStack, scope) }
    val app = LocalContext.current.applicationContext
    DisposableEffect(transitions, app) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) transitions.trimSnapshots()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = transitions.trimSnapshots()
        }
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }
    return transitions
}

/** The host's transitions (null outside the NavDisplay, e.g. previews). */
val LocalPageTransitions = compositionLocalOf<PageTransitions?> { null }

/** The key that pairs a card with the page it opens (container transform); null: no card source. */
fun Route.sharedKey(): String? = when (this) {
    is Route.Video -> "video:$bvid"
    is Route.LiveRoom -> "live:$roomId"
    is Route.Bangumi -> "bangumi:${if (epId != 0L) "ep$epId" else "ss$seasonId"}"
    is Route.Member -> "member:$mid"
    is Route.FavDetail -> "fav:$mediaId"
    is Route.MixList -> "mix:$id"
    is Route.ReplyDetail -> "reply:$rpid"
    Route.DynamicCompose -> "dynamic:compose"
    else -> null
}

/**
 * Where a docking hero lands (host px) when [enabled] at the commit, and the frame it carries
 * there, for whoever takes over.
 */
internal class HeroDock(
    val key: String,
    val enabled: () -> Boolean,
    val target: () -> Rect?,
    val corner: () -> Float,
    /** The flight sets off toward it, carrying this frame. */
    val landing: (ImageBitmap) -> Unit,
    /** The picture is there (the rest is the spring settling): what docks it may show now. */
    val landed: () -> Unit = {},
)

/** The app's page transitions, for what draws outside the page host (the mini bar). */
object PageTransitionsHost {
    var current: PageTransitions? = null
        internal set
}

/** A page-driven back ([PageTransitions.startSwipeBack]). */
internal class SwipeBack(private val events: kotlinx.coroutines.channels.Channel<BackEventCompat>) {
    /** [progress] 0..1 of the back; [dy] the finger's travel down (px), for the page's vertical follow. */
    fun drag(progress: Float, dy: Float) {
        // No touch x: the progress is taken as given; the right edge keeps the page centred.
        events.trySend(BackEventCompat(Float.NaN, dy, progress.coerceIn(0f, 1f), BackEventCompat.EDGE_RIGHT))
    }

    fun commit() {
        events.close()
    }

    fun cancel() {
        events.close(CancellationException("swipe back cancelled"))
    }
}
