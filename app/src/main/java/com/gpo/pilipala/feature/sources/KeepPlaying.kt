package com.gpo.pilipala.feature.sources

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.feature.live.LiveRoomViewModel
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.pageSharedSource
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * 继续播放 (the "like YouTube" choice): leaving a video keeps it playing in a mini bar instead of
 * stopping it. Asked once, the first time a playing video is left; changeable in 设置 › 播放.
 */
object KeepPlaying {
    /** 0: not asked yet, 1: stop when the video is left (PiliPaw as it was), 2: keep playing. */
    val mode = AppSettings.register("keep_playing_mode", 0)

    /** The question is up (a video was just left for the first time); its player waits in [BiliKeeper]. */
    var asking by mutableStateOf(false)
        internal set

    val on: Boolean get() = mode.value == 2

    fun choose(keep: Boolean) {
        AppSettings.set(mode, if (keep) 2 else 1)
        asking = false
        if (!keep) {
            BiliKeeper.close()
            LiveKeeper.close()
        }
    }

    /**
     * Where the bar rests on its one size axis (MiniBarMotion.kt): merged into the nav bar as a
     * circle, the bar, or a floating window. Kept in memory only, and only while its video is
     * docked ([resetDock]): nothing of a window outlives the player it showed.
     */
    internal var rest by mutableStateOf<MiniRest>(MiniRest.Row)

    /** The floating window's centre (window px), where it was dragged; unspecified: where it detached. */
    var windowCenter by mutableStateOf(Offset.Unspecified)
        internal set

    /**
     * Where the picture was on screen (window px) when the bar or window opened its video:
     * YouTube's player grows from there instead of from the bar's place.
     */
    internal var openFrom by mutableStateOf<Rect?>(null)

    /** The docked video's shape (width / height) as last known: the bar's window keeps it while no player says. */
    internal var knownAspect: Float? = null

    /** Where a video's page that was opened from the bar's picture lands back (window px). */
    internal var landing by mutableStateOf<Rect?>(null)

    /** The page opened from the docked picture leaves: back in the form it was opened from. */
    internal fun restore(form: DockForm) {
        rest = form.rest
        windowCorner = form.corner
        windowCenter = form.center
        landing = form.rect
        mergeRaw = if (form.rest == MiniRest.Merged) 1f else 0f
        resetPending = false
    }

    /** The corner a resting window keeps through resizes (0 top left … 3 bottom right); null: none yet. */
    internal var windowCorner: Int? = null

    /** How far the bar has merged into the nav bar (0..1), live while it moves. */
    internal var mergeRaw by mutableFloatStateOf(0f)

    /** The room the nav bar makes at its start for the merged circle (0..1 of it). */
    val navRoom: Float
        get() = if (BiliKeeper.held != null || LiveKeeper.held != null || (Player.session != null && !Player.expanded)) mergeRaw else navRoomLeaving.value

    /** A circle swiped off the nav bar: the room it leaves behind closes on a spring (else 0 at once). */
    private val navRoomLeaving = Animatable(0f)

    // Outlives the bar: it leaves composition once faded, well before the room has closed.
    private val roomScope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)

    /**
     * Call on the main thread just before the player closes: the room the circle had springs shut
     * instead of vanishing, and the next bar starts with none.
     */
    internal fun closeNavRoom() {
        val from = navRoom
        // Undispatched: the room holds from this frame on, before the player is gone.
        roomScope.launch(start = CoroutineStart.UNDISPATCHED) {
            navRoomLeaving.snapTo(from)
            navRoomLeaving.animateTo(0f, MiniSprings.liq())
        }
        mergeRaw = 0f
    }

    /** Scrolling's ask (down: fold into the nav bar); the bar follows once it holds for a moment. */
    internal var scrollWantsMerge by mutableStateOf(false)

    /** The frame the video page's swipe down carried into the bar's tile, and when it landed. */
    // Snapshot state, and expired by the clock on the main thread: the bar reads both while it
    // composes, and a deadline only a later read would notice left it held or shown too long.
    private var arrival by mutableStateOf<Arrival?>(null)
    private var arrivalAt = 0L
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())

    /** A flight landed on the bar's tile, carrying [frame] (null: the picture itself comes on). */
    internal class Arrival(val frame: androidx.compose.ui.graphics.ImageBitmap?)

    internal fun arrive(frame: androidx.compose.ui.graphics.ImageBitmap?, shell: Boolean = false) {
        val a = Arrival(frame)
        arrival = a
        arrivalAt = android.os.SystemClock.uptimeMillis()
        clock.postDelayed({ if (arrival === a) arrival = null }, ArrivalMs)
        // Landing over the shell: hold the bar where the nav bar was until the shell reports it
        // again (or the bar would drop to the bottom for a frame and spring back up: a second move).
        if (shell) {
            navHeld = true
            clock.removeCallbacks(releaseNav)
            clock.postDelayed(releaseNav, 1_500)
        }
        MiniLog.event("arrive", if (frame != null) "frame" else "in place")
    }

    private var navHeld by mutableStateOf(false)
    private val releaseNav = Runnable { navHeld = false }

    /** Where the nav bar is, or was a moment ago while a flight lands over the shell. */
    internal fun navForBar(): Rect? = NavSpot.bounds ?: NavSpot.last?.takeIf { navHeld }

    /** The bar appearing now takes over from a flight that just landed (good for a moment only). */
    internal fun takeArrival(): Arrival? {
        val a = arrival?.takeIf { android.os.SystemClock.uptimeMillis() - arrivalAt < ArrivalMs }
        arrival = null
        return a
    }

    internal val arriving: Boolean get() = arrival != null
    internal val arrivalFrame: androidx.compose.ui.graphics.ImageBitmap? get() = arrival?.frame

    private const val ArrivalMs = 2_000L

    /**
     * Where the bar's picture will rest (window px) once the page over [shell] goes: for a flight
     * that lands in it. [w] × [h] is the window less the system's navigation bar.
     */
    internal fun dockTile(shell: Boolean, w: Float, h: Float, tablet: Boolean, density: androidx.compose.ui.unit.Density, live: Boolean = false): Rect {
        val bar = miniBarRect(if (shell) NavSpot.bounds ?: NavSpot.last else null, w, h, tablet, density, sides = !live)
        val barH = bar.height
        val gap = with(density) { BlockGap.toPx() }
        return miniGeo(barH * 16f / 9f, MiniDims(1f, barH * 16f / 9f, 1f), bar, null, 16f / 9f, Offset.Unspecified, Rect(0f, 0f, w, h), null, MiniPx(gap, 0f, 0f, 0f, 0f), sides = !live).tile
    }

    /**
     * The bar's picture was tapped open: its page grows out of it where it is, so the bar steps
     * aside at once (no fade over the growing page) and keeps its form until it is gone.
     */
    internal var openingFromDock = false

    // While the bar is on screen (shown or still fading), a reset waits until it is gone: done at
    // once it laid a window out as the plain bar at the bottom for the frames it was fading.
    internal var barOnScreen = false
    private var resetPending = false

    /** A video opened, or the docked one went: the next dock starts again as the plain bar. */
    internal fun resetDock() {
        if (barOnScreen) {
            resetPending = true
            MiniLog.event("reset waits", "the bar is still on screen")
            return
        }
        resetNow()
    }

    /** The bar is gone: a reset that waited for it happens now. */
    internal fun barGone() {
        barOnScreen = false
        if (resetPending) {
            resetPending = false
            resetNow()
        }
    }

    private fun resetNow() {
        rest = MiniRest.Row
        windowCenter = Offset.Unspecified
        windowCorner = null
        landing = null
        scrollWantsMerge = false
        floatReturn = null
    }

    /**
     * Pages where typing happens at the bottom (私信, 发动态) put the mini player up as a floating
     * window in a top corner while they are on screen ([miniPlayerFloatOnly]); counted, so a page
     * covering another keeps it so.
     */
    private val floatPages = androidx.compose.runtime.mutableStateMapOf<Any, Unit>()

    val floatOnly: Boolean get() = floatPages.isNotEmpty()

    internal fun floatOn(token: Any) {
        floatPages[token] = Unit
    }

    internal fun floatOff(token: Any) {
        floatPages.remove(token)
    }

    /** The form the float-only page found the player in (the bar or the circle); null: it was a window already. */
    internal var floatReturn: MiniRest? = null

    /** The nav bar went while the bar was the circle: what a float-only page gives back on leaving. */
    internal var mergedBeforePage = false

    /**
     * For the shell's content: scrolling down folds the bar into a circle in the nav bar,
     * scrolling up opens it again; pulled at the very top it is always the bar.
     */
    val scroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (abs(available.y) > 2f) scrollWantsMerge = available.y < 0
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y > 0.5f) atTop()
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (available.y > 0f) atTop()
            return Velocity.Zero
        }

        private fun atTop() {
            scrollWantsMerge = false
            if (rest == MiniRest.Merged) rest = MiniRest.Row
        }
    }
}

/**
 * For a page whose player 继续播放 keeps when it goes (a video, a live room): any back of it
 * (gesture, button, the picture's swipe) that keeps it, [enabled] at the commit, flies the
 * picture's last frame into the bar's tile, or the window or circle it was opened from, where the
 * bar takes over in place. The page gives the frame with a PageHeroEffect.
 */
@Composable
internal fun DockFlightEffect(live: Boolean = false, enabled: () -> Boolean) {
    val transitions = com.gpo.pilipala.navigation.transition.LocalPageTransitions.current ?: return
    val nav = com.gpo.pilipala.navigation.LocalNavigator.current
    val density = LocalDensity.current
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val tablet = widthClass() != WidthClass.Compact
    val metrics by rememberUpdatedState(Triple(window.width.toFloat(), (window.height - navBottom).toFloat(), tablet))
    val isEnabled by rememberUpdatedState(enabled)
    val key = remember { nav.backStack.lastOrNull()?.toString() } ?: return
    DisposableEffect(transitions, key) {
        val stack = nav.backStack
        fun shellBeneath(): Boolean {
            val i = stack.indexOfLast { it.toString() == key }
            val beneath = stack.getOrNull(i - 1)
            return beneath == Route.Main || beneath == com.gpo.pilipala.yt.navigation.Route.Main
        }
        val dock = com.gpo.pilipala.navigation.transition.HeroDock(
            key = key,
            enabled = { isEnabled() },
            target = {
                // Opened from the floating window (or circle): back to it, else the bar's tile.
                val form = PlayerHost.front?.let(PlayerHost::remembered) as? DockForm
                form?.rect ?: metrics.let { (w, h, tablet) -> KeepPlaying.dockTile(shellBeneath(), w, h, tablet, density, live) }
            },
            corner = {
                val form = PlayerHost.front?.let(PlayerHost::remembered) as? DockForm
                form?.tileCorner ?: with(density) { InnerCorner.toPx() }
            },
            landing = { KeepPlaying.arrive(it, shellBeneath()) },
        )
        transitions.registerHeroDock(dock)
        onDispose { transitions.unregisterHeroDock(dock) }
    }
}

/** The docked picture's form when it was tapped open: its rest, its corner and centre, its rect. */
internal class DockForm(val rest: MiniRest, val corner: Int?, val center: Offset, val rect: Rect, val tileCorner: Float)

/**
 * Where the shell's nav bar sits on screen (window px), reported by the shell on screen; null
 * on a pushed page. The mini bar sits above it, or beside it as a circle.
 */
object NavSpot {
    var bounds by mutableStateOf<Rect?>(null)
        private set
    private var owner: Any? = null

    /** A shell's nav pill moved (or appeared); the newest shell owns the spot. */
    fun place(token: Any, rect: Rect?) {
        if (rect == null && owner !== token) return
        owner = if (rect == null) null else token
        bounds = rect
        if (rect != null) last = rect
    }

    /** Where the nav bar was last seen: where it comes back when a page over the shell goes. */
    var last: Rect? = null
        private set

    /** A shell left: only its own spot goes (the next shell may already have placed its own). */
    fun clear(token: Any) {
        if (owner !== token) return
        owner = null
        bounds = null
    }
}

/**
 * This page types at its bottom (a chat's composer, 发动态's toolbar): while it is on screen the
 * keep-playing mini player shows only as a floating window, up in a top corner, out of the way.
 * Leaving gives back the bar or the circle it was ([KeepPlaying.floatOnly]).
 */
fun Modifier.miniPlayerFloatOnly(): Modifier = composed {
    val token = remember { Any() }
    DisposableEffect(token) {
        KeepPlaying.floatOn(token)
        onDispose { KeepPlaying.floatOff(token) }
    }
    this
}

/**
 * The one Bilibili player kept after its page went ([PiliPlayerController.onForgotten] offers
 * itself). The page that opens the same video next takes it back and carries on without
 * reloading; a newer kept player, or the bar's swipe, releases it.
 */
internal object BiliKeeper {
    internal var held by mutableStateOf<PiliPlayerController?>(null)
        private set

    internal fun offer(ctrl: PiliPlayerController): Boolean {
        val t = ctrl.target ?: return false
        // Plain videos only (an episode or an offline file reopens through other pages).
        if (!ctrl.keepable || t.isPgc || t.bvid.isEmpty()) return false
        if (KeepPlaying.mode.value == 1) return false
        // A newer player came to the front meanwhile (the next video's page): this one goes.
        if (!PlayerHost.dock(PlayerSlot.Bili(ctrl))) return false
        when (KeepPlaying.mode.value) {
            1 -> return false
            0 -> KeepPlaying.asking = true
        }
        held?.takeIf { it !== ctrl }?.dispose()
        // The picture carries on in the mini bar. The page's own lifecycle stopped as it left, which
        // rested the video track (BiliVideoPlayer): wake it, the app is still on screen. MiniBarHost
        // rests it again whenever the app itself goes away.
        ctrl.setBackground(false)
        held = ctrl
        return true
    }

    /** Whether [ctrl]'s page leaving now would keep it in the bar (what [offer] checks first). */
    internal fun wouldKeep(ctrl: PiliPlayerController): Boolean {
        val t = ctrl.target ?: return false
        return ctrl.keepable && !t.isPgc && t.bvid.isNotEmpty() && KeepPlaying.mode.value != 1
    }

    /**
     * A Mix's next B站 video while the app is away (MixPlayback): [ctrl] plays it from the bar's
     * place, with no page; the page opened when the app is back takes it over ([take]).
     */
    internal fun playAway(ctrl: PiliPlayerController) {
        PlayerHost.opened(PlayerSlot.Bili(ctrl))
        PlayerHost.dock(PlayerSlot.Bili(ctrl))
        held?.takeIf { it !== ctrl }?.dispose()
        held = ctrl
    }

    internal fun take(bvid: String): PiliPlayerController? {
        val c = held ?: return null
        if (bvid.isEmpty() || c.target?.bvid != bvid) return null
        held = null
        c.setBackground(false)
        return c
    }

    fun close() {
        val c = held ?: return
        held = null
        // Swiped away, or the window closed: the watch's way back goes with it.
        WatchTrail.clear()
        PlayerHost.released(PlayerSlot.Bili(c))
        c.dispose()
    }

    internal fun route(c: PiliPlayerController): Route? = c.target?.let { t ->
        Route.Video(bvid = t.bvid, aid = t.aid, cid = t.cid, cover = t.cover, title = t.title, playlistId = c.queue?.id.orEmpty(), audioOnly = c.audioOnly)
    }
}

/**
 * The live room kept after its page went (继续播放): the stream carries on in the mini bar, chat
 * and all. Reopening the room takes it back; any video opening, the bar's swipe or the window's
 * close stops it for good.
 */
internal object LiveKeeper {
    internal var held by mutableStateOf<LiveRoomViewModel?>(null)
        private set

    /** Whether [room]'s page leaving now would keep it in the bar (the stop-playing setting allowing). */
    internal fun wouldKeep(room: LiveRoomViewModel): Boolean =
        KeepPlaying.mode.value != 1 && room.keepable && PlayerHost.front == PlayerSlot.Live(room)

    /** [room]'s page went: kept in the bar (true), or the caller releases it. */
    internal fun offer(room: LiveRoomViewModel): Boolean {
        if (KeepPlaying.mode.value == 1 || !room.keepable) return false
        if (!PlayerHost.dock(PlayerSlot.Live(room))) return false
        if (KeepPlaying.mode.value == 0) KeepPlaying.asking = true
        held?.takeIf { it !== room }?.release()
        room.dock()
        held = room
        return true
    }

    /** The room's page opens again: it carries on with the kept stream. */
    internal fun take(roomId: Long): LiveRoomViewModel? {
        val r = held?.takeIf { it.roomId == roomId } ?: return null
        held = null
        return r
    }

    fun close() {
        val r = held ?: return
        held = null
        PlayerHost.released(PlayerSlot.Live(r))
        r.release()
    }

    internal fun route(r: LiveRoomViewModel): Route =
        Route.LiveRoom(r.roomId, r.info?.cover.orEmpty(), r.info?.title.orEmpty())
}

/** What the bar draws and does, whichever part is playing. */
private class Playing(
    val source: VideoSource,
    val title: String,
    val cover: String,
    val isPlaying: Boolean,
    val progress: Float,
    val toggle: () -> Unit,
    val seekBy: (Long) -> Unit,
    val open: () -> Unit,
    val close: () -> Unit,
    /** The live picture for the bar, when there is one (not in 仅听声音, not before it loads). */
    val player: androidx.media3.common.Player? = null,
    /** A Bilibili video's page: it grows out of the picture where it is (a shared source). */
    val route: Route? = null,
    /** A live stream: no ±15, no progress; a red dot on the picture says it is live. */
    val live: Boolean = false,
)

@Composable
private fun currentPlaying(): Playing? {
    // A live room in the bar (only ever alone there: opening it closed the rest).
    LiveKeeper.held?.let { room ->
        val route = LiveKeeper.route(room)
        return Playing(
            source = VideoSource.Bilibili,
            title = room.info?.title.orEmpty(),
            cover = MixItems.secure(room.info?.cover.orEmpty()),
            isPlaying = room.isPlaying,
            progress = 0f,
            toggle = { room.togglePlay() },
            seekBy = {},
            open = { BiliLinks.open(route) },
            close = { LiveKeeper.close() },
            player = room.player,
            route = route,
            live = true,
        )
    }
    // Both kept: the one playing (starting either pauses the other).
    val ytUp = Player.session != null && !Player.expanded
    val bili = BiliKeeper.held?.takeIf { it.isPlaying || !ytUp || !Player.isPlaying }
    if (bili != null) {
        val t = bili.target
        return Playing(
            source = VideoSource.Bilibili,
            title = t?.title.orEmpty(),
            cover = MixItems.secure(t?.cover.orEmpty()),
            isPlaying = bili.isPlaying,
            progress = if (bili.durationMs > 0) bili.positionMs.toFloat() / bili.durationMs else 0f,
            toggle = { bili.togglePlay() },
            seekBy = { d -> bili.seekTo((bili.positionMs + d).coerceAtLeast(0)) },
            open = { BiliKeeper.route(bili)?.let(BiliLinks::open) },
            close = { BiliKeeper.close() },
            player = bili.player.takeIf { !bili.audioOnly },
            route = BiliKeeper.route(bili),
        )
    }
    // The YouTube player collapsed: this bar is its mini player.
    // A video that failed (or a live stream that ended) leaves the bar instead of sitting there blank.
    val yt = Player.session?.takeIf { !Player.expanded && !Player.overlayUp && it.error == null }
    if (yt != null) {
        // The overlay's picture just landed on the tile: carry on from it without a fade.
        LaunchedEffect(yt.url) { KeepPlaying.arrive(null) }
        var position by remember { mutableStateOf(0L) }
        LaunchedEffect(yt) {
            while (true) {
                position = Player.exo.currentPosition
                kotlinx.coroutines.delay(500)
            }
        }
        return Playing(
            source = VideoSource.YouTube,
            title = yt.title,
            cover = yt.thumbnail,
            isPlaying = Player.isPlaying,
            progress = if (Player.durationMs > 0) position.toFloat() / Player.durationMs else 0f,
            toggle = { Player.toggle() },
            seekBy = { d -> Player.exo.seekTo((Player.exo.currentPosition + d).coerceAtLeast(0)) },
            open = { Player.expanded = true },
            close = { Player.close() },
            player = Player.exo.takeIf { yt.info != null && !Player.audioOnly },
        )
    }
    return null
}

private val BarHeightPhone = 56.dp
private val BarHeightTablet = 52.dp
private val CircleSize = 48.dp
private val BlockGap = 3.dp
private val NavGap = 8.dp
private val SideMargin = 16.dp
private val InnerCorner = 6.dp
private val WindowCorner = 16.dp
private val ProgressStroke = 3.dp

/**
 * The strip under the picture the progress line runs in, so it reads on any video; in the merged
 * circle it runs all round the picture, and the line is a ring.
 */
private val ProgressGutter = 5.dp

/** The progress line's track: clearly there on the tile, so the played part never reads as a stray line. */
private const val ProgressTrackAlpha = 0.3f

/** The nav bar's padding before its first tab: where the merged circle sits. */
internal val NavLead = 10.dp

/** What the nav bar opens at its start for the merged circle: the circle and the tabs' gap. */
internal val NavRoom = CircleSize + 4.dp

/**
 * Where the bar rests (window px, in a [w] × [h] area above the system's navigation bar): over
 * the nav bar (phone), left of it in its row (tablet, when there is room), or at the bottom on a
 * page without one. A live bar has no ±15 blocks ([sides] false), so it is narrower.
 */
internal fun miniBarRect(nav: Rect?, w: Float, h: Float, tablet: Boolean, density: androidx.compose.ui.unit.Density, sides: Boolean = true): Rect = with(density) {
    val barH = (if (tablet) BarHeightTablet else BarHeightPhone).toPx()
    val navGap = NavGap.toPx()
    val side = SideMargin.toPx()
    // It hugs its blocks: three square keys (as wide as the bar is tall), the 16:9 picture, 3dp gaps.
    val bw = minOf(miniBarWidth(barH, BlockGap.toPx(), sides), w - side * 2f)
    when {
        nav != null && tablet && nav.left - navGap - side >= bw -> {
            val top = nav.center.y - barH / 2f
            Rect(nav.left - navGap - bw, top, nav.left - navGap, top + barH)
        }
        else -> {
            val left = (w - bw) / 2f
            val bottom = nav?.top?.minus(navGap) ?: (h - side)
            Rect(left, bottom - barH, left + bw, bottom)
        }
    }
}

/** Extra bottom room a shell list needs while the bar sits above the nav bar (phones). */
@Composable
fun miniBarInset(): Dp {
    val showing = BiliKeeper.held != null || LiveKeeper.held != null || Player.session != null
    return if (showing) BarHeightPhone + NavGap else 0.dp
}


/**
 * The bar's live state: the size axis (an override while two fingers hold it), the bar's place
 * following the nav bar, the swipe, and the window's drag. Gestures and the layout read it in
 * their own phases, so a pinch or a drag moves things without recomposing the bar.
 */
private class MiniFrame(initialAxis: Float, initialBar: Rect) {
    val axis = Animatable(initialAxis)
    var pinchAxis by mutableStateOf<Float?>(null)
    val axisNow: Float get() = pinchAxis ?: axis.value

    val anchor = Animatable(initialBar, Rect.VectorConverter)
    val swipe = Animatable(0f)
    var swipeDrag by mutableStateOf<Float?>(null)
    val swipeNow: Float get() = swipeDrag ?: swipe.value

    /** The merged circle's sideways swipe (px under the finger; null when not dragged), as progress. */
    var dockDrag by mutableStateOf<Float?>(null)
    val dockSwipe = Animatable(0f)
    var dockRange = 1f
    val dockNow: Float get() = dockDrag?.let { MiniGestures.dockSwipeProgress(it, dockRange) } ?: dockSwipe.value

    /** Which way the circle is swiped (−1 left, 1 right): the scrim darkens from where it started. */
    var dockDir by mutableFloatStateOf(1f)

    /** Letting go now closes it: the scrim turns solid and the circle a cookie. */
    var dockArmed by mutableStateOf(false)

    /** The circle's own scale while it springs away (1 at rest). */
    val dockScale = Animatable(1f)

    /** Swiped off the nav bar: the bar sinks no lower while it fades, the circle shrinks instead. */
    var dockClosing by mutableStateOf(false)

    /** The window's centre under the finger (window px); null when not dragged. */
    var dragCenter by mutableStateOf<Offset?>(null)
    var flingJob: Job? = null
    var gesturing = false

    /** The side blocks growing in around a picture that flew in from its page (1: at rest). */
    var sidesIn: Animatable<Float, *>? = null

    /** That flight's last frame, shown in the tile until the video itself is up. */
    var arrived by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    var dims by mutableStateOf(MiniDims(1f, 2f, 1f))
    var circle by mutableStateOf<Rect?>(null)
    var nav by mutableStateOf<Rect?>(null)
    var aspect by mutableFloatStateOf(16f / 9f)
    var maxAxis by mutableFloatStateOf(1f)
    var bounds by mutableStateOf(Rect.Zero)

    /** Where a resting window's corners are: inside the insets, above the nav bar and the bar. */
    var restArea by mutableStateOf(Rect.Zero)

    /** A held window gives a little under the finger. */
    val press = Animatable(1f)

    /** When the window last moved (uptime ms): close ignores taps right after. */
    var movedAt = 0L

    /** Touches on the window, counted: each one brings the keys' backing back. */
    var touches by androidx.compose.runtime.mutableIntStateOf(0)

    /** The keys' backing (1) fading to bare icons (0) after a few idle seconds. */
    val keysBacking = Animatable(1f)

    /** The bar's picture is popping out under a finger: it sits under the finger as it grows. */
    var popping by mutableStateOf(false)

    var view: android.view.View? = null

    /** A light tick (into a corner, off the bar) or a firmer confirm (docked), as the system allows. */
    fun haptic(dock: Boolean) {
        val v = view ?: return
        val c = when {
            dock && android.os.Build.VERSION.SDK_INT >= 30 -> android.view.HapticFeedbackConstants.CONFIRM
            dock -> android.view.HapticFeedbackConstants.VIRTUAL_KEY
            else -> android.view.HapticFeedbackConstants.CLOCK_TICK
        }
        v.performHapticFeedback(c)
    }
    var px by mutableStateOf(MiniPx(0f, 0f, 0f, 0f, 0f))

    /** The ±15 blocks are there (not for a live stream). */
    var sides by mutableStateOf(true)

    val canMerge: Boolean get() = nav != null

    val geo by derivedStateOf {
        miniGeo(
            axis = axisNow,
            d = dims,
            bar = anchor.value,
            circle = circle,
            aspect = aspect,
            windowCenter = dragCenter ?: KeepPlaying.windowCenter,
            bounds = bounds,
            nav = nav,
            px = px,
            pinned = popping,
            sides = sides,
        )
    }

    fun restAxis(rest: MiniRest, density: Float): Float = restAxisOf(rest, dims, canMerge, maxAxis, density)

    /** Where the bar's picture sits at rest (a merging window travels there). */
    fun rowTile(): Rect = miniGeo(dims.row, dims, anchor.value, circle, aspect, Offset.Unspecified, bounds, nav, px, sides = sides).tile

    /** The smallest size it may take: the window's while a page keeps it floating, else the circle or the bar. */
    val minAxis: Float get() = when {
        KeepPlaying.floatOnly -> dims.detach
        canMerge -> dims.circle
        else -> dims.row
    }
}

/** The axis (px) a rest stands for; a window's size is kept in dp so it survives a rotation. */
private fun restAxisOf(rest: MiniRest, dims: MiniDims, canMerge: Boolean, maxAxis: Float, density: Float): Float = when (rest) {
    MiniRest.Merged -> if (canMerge) dims.circle else dims.row
    MiniRest.Row -> dims.row
    is MiniRest.Window -> (rest.axisDp * density).coerceIn(dims.detach, maxAxis.coerceAtLeast(dims.detach))
}

/** The picture's shape, as the player reports it (16:9 until it says, or with no picture). */
@Composable
private fun rememberVideoAspect(player: androidx.media3.common.Player?): Float {
    // The picture's own shape from the very first frame: read from the player as this composes
    // (a 16:9 start, corrected a frame later, sized a portrait window 16:9 and back), else the
    // last one known (the page that flew it here, a moment without a player).
    var aspect by remember(player) {
        mutableFloatStateOf(player?.videoSize?.let(::aspectOf) ?: KeepPlaying.knownAspect ?: (16f / 9f))
    }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        fun read(v: androidx.media3.common.VideoSize) {
            aspectOf(v)?.let {
                aspect = it
                KeepPlaying.knownAspect = it
            }
        }
        read(player.videoSize)
        val l = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = read(videoSize)
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return aspect
}

/**
 * The mini bar, over both parts of the app: [−15s][picture][pause][+15s], joined blocks with no
 * title and no ×; a sideways swipe closes it. Stacked 8dp above the nav bar on a phone, left of it
 * on a tablet. Scrolling down pours the picture into a 48dp circle at the nav bar's start, with a
 * progress ring; scrolling up brings it out again. Two fingers run one size axis: circle ← bar →
 * a floating window of the video's own shape (drag or fling it to a corner; pinch it small to dock)
 * (rubber-banding past its largest size). Tapping the picture is what opens the video; tapping
 * the circle opens it out into the bar.
 */
@OptIn(FlowPreview::class)
@Composable
fun MiniBarHost() {
    val playing = currentPlaying()
    // The app goes away: a kept Bilibili player drops its picture (sound only), and takes it back on return.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    BiliKeeper.held?.setBackground(true)
                    // A docked live stream: on in the background only as the room's page would be.
                    LiveKeeper.held?.onAppStopped()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    BiliKeeper.held?.setBackground(false)
                    LiveKeeper.held?.resumeFromBackground()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // A YouTube video that failed while collapsed: its player goes, not just its bar.
    val failed = Player.session?.takeIf { !Player.expanded && it.error != null }
    LaunchedEffect(failed) { if (failed != null) Player.close() }
    // 继续播放 switched off in 设置 while a Bilibili video is docked: it stops now, not at the next video.
    LaunchedEffect(Unit) {
        KeepPlaying.mode.state.collect {
            if (it == 1) {
                BiliKeeper.close()
                LiveKeeper.close()
            }
        }
    }
    if (KeepPlaying.asking) KeepAskDialog()
    // Coming and going fade; a picture that just flew in from its page is simply there (the
    // flight left it in the tile), and only the side blocks grow in around it.
    val shownA = remember { Animatable(0f) }
    // Hidden until the bar shows: an arriving bar grows them in once, never shows them first.
    val sidesIn = remember { Animatable(0f) }
    var arrived by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val up = playing != null
    val fadeSpec = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(up) {
        if (up) {
            KeepPlaying.barOnScreen = true
            val arrival = KeepPlaying.takeArrival()
            KeepPlaying.landing = null
            if (arrival != null && shownA.value < 0.01f) {
                MiniLog.event("bar takes over", "arrival")
                arrived = arrival.frame
                shownA.snapTo(1f)
                sidesIn.animateTo(1f, MiniSprings.def())
                return@LaunchedEffect
            }
            MiniLog.event("bar fades in", "")
            sidesIn.snapTo(1f)
            shownA.animateTo(1f, fadeSpec)
        } else {
            if (KeepPlaying.openingFromDock) {
                // Its page is growing out of the picture right where it is: gone this frame.
                KeepPlaying.openingFromDock = false
                MiniLog.event("bar steps aside", "opened from ${KeepPlaying.rest}")
                shownA.snapTo(0f)
            } else {
                MiniLog.event("bar fades out", "")
                shownA.animateTo(0f, fadeSpec)
            }
            sidesIn.snapTo(0f)
            KeepPlaying.barGone()
        }
    }
    val shown = if (up && KeepPlaying.arriving) 1f else shownA.value
    var last by remember { mutableStateOf<Playing?>(null) }
    if (playing != null) last = playing
    val p = playing ?: last ?: return
    if (shown <= 0.001f && playing == null) return
    // Playing is rebuilt with every tick of progress; gestures read the latest one instead of
    // restarting (a restart cut every pinch and swipe short).
    val latest by rememberUpdatedState(p)
    val aspect = rememberVideoAspect(p.player)

    BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding()) {
        val density = LocalDensity.current
        val tablet = widthClass() != WidthClass.Compact
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
        val barH = with(density) { (if (tablet) BarHeightTablet else BarHeightPhone).toPx() }
        val navGap = with(density) { NavGap.toPx() }
        val side = with(density) { SideMargin.toPx() }
        val dims = with(density) { MiniDims(circle = CircleSize.toPx(), row = barH * 16f / 9f, detachExtra = 40.dp.toPx()) }
        // Landing from the video page's swipe down: the shell's nav bar may not be back yet.
        val nav = KeepPlaying.navForBar()
        val barTarget = miniBarRect(nav, w, h, tablet, density, sides = !p.live)
        // The merged circle at the nav bar's start; kept after the nav bar goes so the picture
        // can fly back out of it.
        val circleTarget = nav?.let {
            val c = with(density) { CircleSize.toPx() }
            val lead = with(density) { NavLead.toPx() }
            Rect(it.left + lead, it.center.y - c / 2f, it.left + lead + c, it.center.y + c / 2f)
        }
        val widthCap = if (tablet) minOf(with(density) { 520.dp.toPx() }, w - with(density) { 24.dp.toPx() }) else w - with(density) { 24.dp.toPx() }
        val maxAxis = MiniAxis.maxAxis(widthCap, (h - statusTop) * 0.6f, aspect).coerceAtLeast(dims.detach)
        val margin = with(density) { 12.dp.toPx() }
        // Back on screen (rotation, a new window): straight to where it rests, no flight.
        // A newly docked player takes over at the flight's destination. Reusing the hidden
        // frame would spring its old row axis into the restored circle/window a second time.
        val retainedFrame = remember { arrayOfNulls<MiniFrame>(1) }
        val frame = remember(p.player, up) {
            if (!up && retainedFrame[0] != null) retainedFrame[0]!!
            else MiniFrame(restAxisOf(KeepPlaying.rest, dims, nav != null, maxAxis, density.density), barTarget)
                .also {
                    // Available in the first composition, before the arrival effect consumes it.
                    // Otherwise the bar paints its cover for one frame over the landing image.
                    it.arrived = KeepPlaying.arrivalFrame
                    retainedFrame[0] = it
                }
        }
        frame.dims = dims
        frame.sides = !p.live
        frame.view = androidx.compose.ui.platform.LocalView.current
        // A bar swiped away waits out there; the next one to show comes back in the middle.
        LaunchedEffect(frame, up) {
            if (up && frame.swipeDrag == null && !frame.swipe.isRunning && frame.swipe.value != 0f) frame.swipe.snapTo(0f)
        }
        frame.sidesIn = sidesIn
        arrived?.let {
            frame.arrived = it
            arrived = null
        }
        frame.nav = nav
        if (circleTarget != null) frame.circle = circleTarget
        frame.aspect = aspect
        frame.maxAxis = maxAxis
        frame.bounds = Rect(margin, statusTop + margin, w - margin, h - margin)
        // A window resting at a bottom corner rests above the nav bar and the bar.
        // Without a nav bar (a pushed page) the bottom corners sit just above the system's inset.
        val restBottom = if (nav != null) nav.top - with(density) { 32.dp.toPx() } else h - with(density) { 16.dp.toPx() }
        frame.restArea = Rect(margin, statusTop + margin, w - margin, restBottom)
        frame.dockRange = with(density) { DockSwipeAt.toPx() }
        frame.px = with(density) { MiniPx(BlockGap.toPx(), InnerCorner.toPx(), WindowCorner.toPx(), margin, 8.dp.toPx(), 32.dp.toPx(), 8.dp.toPx()) }

        // The bar follows the nav bar (a page opening hides it, coming back shows it) on a spring.
        LaunchedEffect(barTarget) {
            if (frame.anchor.targetValue != barTarget) frame.anchor.animateTo(barTarget, MiniSprings.def())
        }
        // Rest changes from outside a gesture (scrolling, a new video, the nav bar going): spring there.
        val restAxis = frame.restAxis(KeepPlaying.rest, density.density)
        LaunchedEffect(restAxis) {
            // A circle swiped away shrinks where it is (a scroll to the top must not open it out).
            if (frame.pinchAxis != null || frame.dockClosing) return@LaunchedEffect
            if (frame.axis.isRunning && frame.axis.targetValue == restAxis) return@LaunchedEffect
            frame.axis.animateTo(restAxis, MiniSprings.liq())
        }
        val scope = rememberCoroutineScope()
        // The window's keys lose their backing after ~3s untouched; any touch brings it back.
        LaunchedEffect(frame.touches) {
            frame.keysBacking.snapTo(1f)
            kotlinx.coroutines.delay(3_000)
            frame.keysBacking.animateTo(0f, androidx.compose.animation.core.tween(250))
        }
        // The circle lives in the nav bar: a page without one splits it back into the bar.
        val navGone = nav == null
        LaunchedEffect(navGone) {
            KeepPlaying.mergedBeforePage = navGone && KeepPlaying.rest == MiniRest.Merged
            if (navGone && KeepPlaying.rest == MiniRest.Merged) KeepPlaying.rest = MiniRest.Row
        }
        // A page that types at its bottom (私信, 发动态): up as a floating window in the top right,
        // the way a picture dragged up off the bar pops out; leaving gives back the bar or the
        // circle. A window already keeps its place, only lifted out of a bottom corner.
        val floatOnly = KeepPlaying.floatOnly
        LaunchedEffect(frame, floatOnly) {
            if (floatOnly) {
                val corner = KeepPlaying.windowCorner
                if (KeepPlaying.rest is MiniRest.Window) {
                    if (corner != null && corner and 2 != 0 && !frame.gesturing) {
                        snapWindow(frame, scope, frame.restAxis(KeepPlaying.rest, density.density), corner and 1, KeepPlaying.windowCenter, Offset.Zero)
                    }
                    return@LaunchedEffect
                }
                MiniLog.event("float only", "pops out of ${KeepPlaying.rest}")
                KeepPlaying.floatReturn = if (KeepPlaying.mergedBeforePage) MiniRest.Merged else KeepPlaying.rest
                popOut(frame, scope, density.density)
            } else {
                val back = KeepPlaying.floatReturn ?: return@LaunchedEffect
                KeepPlaying.floatReturn = null
                if (KeepPlaying.rest !is MiniRest.Window || frame.gesturing) return@LaunchedEffect
                MiniLog.event("float only ends", "back to $back")
                // The window shrinks back down into its place (as a pinch back into the bar does).
                KeepPlaying.windowCorner = null
                KeepPlaying.rest = back
            }
        }
        // A resting window keeps its corner when the screen around it changes (rotation, nav bar).
        LaunchedEffect(frame.restArea, maxAxis) {
            val corner = KeepPlaying.windowCorner ?: return@LaunchedEffect
            if (frame.gesturing || KeepPlaying.rest !is MiniRest.Window || !KeepPlaying.windowCenter.isSpecified) return@LaunchedEffect
            snapWindow(frame, scope, restAxis, corner, KeepPlaying.windowCenter, Offset.Zero)
        }
        // Scrolling: the direction must hold ~130ms, then the bar merges or splits (the spring
        // carries on from whatever it was doing).
        LaunchedEffect(frame) {
            snapshotFlow { KeepPlaying.scrollWantsMerge }.drop(1).debounce(130).collect { want ->
                if (frame.gesturing || frame.dockClosing || KeepPlaying.rest is MiniRest.Window) return@collect
                KeepPlaying.rest = if (want && frame.canMerge) MiniRest.Merged else MiniRest.Row
            }
        }
        // The nav bar opens room for the circle as the picture pours in; a window pinched back
        // into the bar forgets its place once it lands.
        LaunchedEffect(frame) {
            snapshotFlow { frame.geo.merge }.distinctUntilChanged().collect { KeepPlaying.mergeRaw = it }
        }
        LaunchedEffect(frame) {
            snapshotFlow { frame.pinchAxis == null && KeepPlaying.rest !is MiniRest.Window && frame.axisNow <= frame.dims.row + 0.5f }
                .distinctUntilChanged()
                .collect { if (it) KeepPlaying.windowCenter = Offset.Unspecified }
        }

        val navColor = MaterialTheme.colorScheme.surfaceContainerHigh
        // The goo, under the blocks.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val n = frame.geo.neck ?: return@drawBehind
                val span = n.bottom - n.top
                val path = Path().apply {
                    moveTo(n.cx - n.halfTop, n.top)
                    cubicTo(n.cx - n.pinch, n.top + span * 0.55f, n.cx - n.pinch, n.bottom - span * 0.3f, n.cx - n.flare, n.bottom)
                    lineTo(n.cx + n.flare, n.bottom)
                    cubicTo(n.cx + n.pinch, n.bottom - span * 0.3f, n.cx + n.pinch, n.top + span * 0.55f, n.cx + n.halfTop, n.top)
                    close()
                }
                drawPath(path, navColor, alpha = shown)
            },
        )
        Layout(
            content = {
                // The page transitions reach the bar too: a Bilibili page grows out of its picture.
                androidx.compose.runtime.CompositionLocalProvider(
                    com.gpo.pilipala.navigation.transition.LocalPageTransitions provides com.gpo.pilipala.navigation.transition.PageTransitionsHost.current,
                ) {
                    SideBlock(forward = false, p = p, frame = frame)
                    Tile(p = p, frame = frame)
                    PauseBlock(p = p, frame = frame)
                    SideBlock(forward = true, p = p, frame = frame)
                    WindowKey(close = false, p = p, frame = frame)
                    WindowKey(close = true, p = p, frame = frame)
                }
            },
            modifier = Modifier
                .offset { frame.geo.hit.let { IntOffset(it.left.roundToInt(), it.top.roundToInt()) } }
                // The system's back gesture must not take a swipe that starts on the bar near an edge.
                .systemGestureExclusion()
                .pointerInput(frame) { miniGestures(frame, scope, density.density) { latest } }
                .graphicsLayer {
                    translationX = frame.swipeNow
                    alpha = shown * (1f - (abs(frame.swipeNow) / (frame.anchor.value.width * 0.6f).coerceAtLeast(1f))).coerceIn(0f, 1f)
                    // Closing shrinks as it fades (the window where it is, the bar sinking a little).
                    val going = 0.85f + 0.15f * shown
                    if (frame.geo.windowed) {
                        scaleX = frame.press.value * going
                        scaleY = frame.press.value * going
                    } else if (frame.dockClosing) {
                        // Swiped off the nav bar: the circle shrinks about its own centre instead.
                    } else {
                        translationY = (1f - shown) * barH
                        scaleX = going
                        scaleY = going
                    }
                },
        ) { measurables, _ ->
            val g = frame.geo
            val rects = listOf(g.left, g.tile, g.pause, g.right, g.play, g.close)
            val placeables = measurables.mapIndexed { i, m ->
                val r = rects[i]
                m.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0)))
            }
            layout(g.hit.width.roundToInt().coerceAtLeast(0), g.hit.height.roundToInt().coerceAtLeast(0)) {
                placeables.forEachIndexed { i, pl ->
                    pl.place((rects[i].left - g.hit.left).roundToInt(), (rects[i].top - g.hit.top).roundToInt())
                }
            }
        }
    }
}

/**
 * Every touch on the bar, the circle and the window, in one place so they cannot fight: two
 * fingers run the size axis (read before the picture's own view sees them, and a second finger
 * anywhere on the screen counts); one finger swipes the bar away sideways or drags the window,
 * with momentum. A tap is decided here too, only once every finger is up: exactly one finger,
 * still and quick. A pinch stays a pinch until the last finger lifts.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.miniGestures(
    frame: MiniFrame,
    scope: CoroutineScope,
    density: Float,
    latest: () -> Playing,
) {
    val minSpan = 48f * density
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Swiped away and going: a tap must not open it again on its way out.
        if (frame.dockClosing) return@awaitEachGesture
        frame.flingJob?.cancel()
        val g0 = frame.geo
        val windowAtDown = MiniGestures.isWindow(KeepPlaying.rest is MiniRest.Window, g0.windowed)
        val mergedAtDown = g0.merge > 0.5f
        // Window px: the node moves with the window, the finger's place must not.
        val downAt = down.position + g0.hit.topLeft
        // The window's keys: their press is theirs alone (no drag, no open, no pinch).
        // A key under the finger fires only if this whole gesture turns out to be a clean tap.
        val keyAtDown = if (windowAtDown && g0.buttonsAlpha > 0.5f) MiniGestures.keyAt(downAt, g0.play, g0.close, 8f * density) else null
        // Any touch brings the keys' backing back and restarts their idle timer.
        if (windowAtDown) frame.touches++
        if (windowAtDown) scope.launch { frame.press.animateTo(PressScale, MiniSprings.fast()) }
        var pinching = false
        var dragging = false
        var giveUp = false
        // Dragged up off the bar's picture: lifting off as a window under the finger (back down undoes it).
        var popped = false
        var popProgress = 0f
        val popRange = 48f * density
        // Swiped sideways off the merged circle: closing it once past its range.
        var docking = false
        // Dragged down from a bottom corner: merging back into the bar as the finger goes.
        var merging = false
        var mergeFromAxis = 0f
        var mergeFrom = Offset.Zero
        var mergeTo = Offset.Zero
        var mergeRange = 1f
        var mergeProgress = 0f
        var maxPointers = 1
        var startSpan = 0f
        var startAxis = 0f
        var lastAxis = 0f
        var raw = 0f
        val axisVelocity = VelocityTracker1D(isDataDifferential = false)
        val velocity = VelocityTracker()
        var travel = Offset.Zero
        var finger = Offset.Zero
        var upTime = down.uptimeMillis
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            maxPointers = maxOf(maxPointers, pressed.size, MiniTouch.maxCount)
            upTime = event.changes.firstOrNull()?.uptimeMillis ?: upTime
            if (pressed.isEmpty()) break
            if (pressed.size >= 2 || MiniTouch.count >= 2) {
                // Both fingers on the bar, or one of them elsewhere on the screen (the activity saw it).
                val hitAt = frame.geo.hit.topLeft
                val span: Float
                val centroid: Offset
                if (pressed.size >= 2) {
                    span = (pressed[0].position - pressed[1].position).getDistance()
                    centroid = (pressed[0].position + pressed[1].position) / 2f + hitAt
                } else {
                    span = MiniTouch.span()
                    centroid = MiniTouch.centroid()
                }
                if (!pinching) {
                    pinching = true
                    frame.gesturing = true
                    // A drag that became a pinch: it stays where it got to.
                    frame.dragCenter?.let { KeepPlaying.windowCenter = it }
                    frame.dragCenter = null
                    frame.swipeDrag?.let { x -> scope.launch { frame.swipe.snapTo(x); frame.swipe.animateTo(0f, MiniSprings.def()) } }
                    frame.swipeDrag = null
                    if (docking) {
                        cancelDockSwipe(frame, scope)
                        docking = false
                    }
                    dragging = false
                    startSpan = span.coerceAtLeast(minSpan)
                    startAxis = frame.axisNow
                    lastAxis = startAxis
                    frame.pinchAxis = startAxis
                    scope.launch { frame.axis.stop() }
                }
                // The size follows the fingers 1:1 (the picture's side scales with their span).
                raw = startAxis * span.coerceAtLeast(minSpan) / startSpan
                val now = MiniAxis.rubber(raw, frame.minAxis, frame.maxAxis)
                // A tick where letting go would change what it becomes: the circle past halfway,
                // a window ~70% of the way to the detach point (as the release decides).
                val d = frame.dims
                val wasAt = frame.pinchAxis ?: startAxis
                if (frame.canMerge && MiniGestures.crosses(MiniAxis.merge(wasAt, d), MiniAxis.merge(now, d), 0.5f) ||
                    MiniGestures.crosses(wasAt - d.row, now - d.row, d.detachExtra * 0.7f)
                ) frame.haptic(dock = false)
                frame.pinchAxis = now
                axisVelocity.addDataPoint(upTime, raw)
                if (now >= frame.dims.detach) {
                    // A window: it starts where the picture detached and grows about the fingers' middle.
                    val c = if (KeepPlaying.windowCenter.isSpecified) KeepPlaying.windowCenter else frame.geo.tile.center
                    val k = if (lastAxis >= frame.dims.detach) now / lastAxis else 1f
                    val pan = if (pressed.size >= 2) event.calculatePan() else Offset.Zero
                    KeepPlaying.windowCenter = MiniGestures.scaleAbout(c, centroid, k) + pan
                }
                lastAxis = now
                event.changes.forEach { it.consume() }
                continue
            }
            // One finger left after a pinch: nothing more (no drag, no tap) until it lifts.
            if (pinching) {
                event.changes.forEach { it.consume() }
                continue
            }
            val c = pressed[0]
            val delta = c.positionChange()
            travel += delta
            finger += delta
            if (!dragging && !giveUp && travel.getDistance() > viewConfiguration.touchSlop &&
                windowAtDown && !KeepPlaying.floatOnly && MiniGestures.startsMerge(KeepPlaying.windowCorner, travel)
            ) {
                dragging = true
                merging = true
                frame.gesturing = true
                frame.flingJob?.cancel()
                mergeFromAxis = frame.axisNow
                mergeFrom = frame.geo.tile.center
                mergeTo = frame.rowTile().center
                // The window's way down to the nav bar (or the bar's place) is the whole merge.
                val navY = frame.nav?.center?.y ?: frame.anchor.value.center.y
                mergeRange = (navY - frame.geo.tile.bottom).coerceIn(80f * density, 120f * density)
                frame.pinchAxis = mergeFromAxis
            }
            if (!dragging && !giveUp && travel.getDistance() > viewConfiguration.touchSlop) {
                when (MiniGestures.dragIntent(travel, windowAtDown, mergedAtDown, g0.tile.contains(downAt))) {
                    MiniDragIntent.MoveWindow, MiniDragIntent.SwipeAway -> dragging = true
                    MiniDragIntent.DockSwipe -> {
                        dragging = true
                        docking = true
                        scope.launch { frame.dockSwipe.stop() }
                    }
                    MiniDragIntent.PopOut -> {
                        dragging = true
                        popped = true
                        // The tile lifts off under the finger: its centre is the finger's from the
                        // first frame, its size and the side blocks follow how far up it has gone.
                        val start = frame.geo.tile.center + travel
                        frame.dragCenter = start
                        KeepPlaying.windowCenter = start
                        KeepPlaying.windowCorner = null
                        frame.popping = true
                        frame.flingJob?.cancel()
                        scope.launch { frame.axis.stop() }
                        frame.pinchAxis = frame.dims.row
                    }
                    MiniDragIntent.None -> giveUp = true
                }
                if (dragging) frame.gesturing = true
            }
            if (dragging && merging) {
                velocity.addPosition(c.uptimeMillis, finger)
                // 1:1 with the finger: it shrinks and travels to the bar's picture, the side
                // blocks unfold around it; back up undoes it.
                val was = mergeProgress
                mergeProgress = MiniGestures.mergeProgress(travel.y, mergeRange)
                // A tick where letting go would dock it, and again coming back over it.
                if (MiniGestures.crosses(was, mergeProgress, MiniGestures.MergeAt)) frame.haptic(dock = false)
                frame.pinchAxis = lerp(mergeFromAxis, frame.dims.row, mergeProgress)
                frame.dragCenter = lerpOffset(mergeFrom, mergeTo, mergeProgress)
                c.consume()
            } else if (dragging && docking) {
                // The circle stays put; only what is drawn over it follows the finger.
                frame.dockDrag = travel.x
                if (travel.x != 0f) frame.dockDir = sign(travel.x)
                val armed = MiniGestures.dockSwipeProgress(travel.x, frame.dockRange) >= 1f
                if (armed != frame.dockArmed) {
                    frame.dockArmed = armed
                    frame.haptic(dock = armed)
                }
                c.consume()
            } else if (dragging) {
                velocity.addPosition(c.uptimeMillis, finger)
                if (popped) {
                    val was = popProgress
                    popProgress = MiniGestures.popProgress(-travel.y, popRange)
                    frame.pinchAxis = lerp(frame.dims.row, frame.dims.detach, popProgress)
                    // A tick the moment it comes off the bar.
                    if (was < 1f && popProgress >= 1f) frame.haptic(dock = false)
                }
                if (windowAtDown || popped) {
                    frame.movedAt = c.uptimeMillis
                    frame.dragCenter = (frame.dragCenter ?: frame.geo.tile.center) + delta
                } else {
                    val was = frame.swipeDrag ?: frame.swipe.value
                    val now = was + delta.x
                    frame.swipeDrag = now
                    // A tick where letting go would swipe it away, and again coming back over it.
                    val away = frame.anchor.value.width * MiniGestures.SwipeAwayAt
                    if (MiniGestures.crosses(abs(was), abs(now), away)) frame.haptic(dock = false)
                }
                c.consume()
            }
        }
        if (windowAtDown) scope.launch { frame.press.animateTo(1f, MiniSprings.liq()) }
        val kind = MiniGestures.classify(
            maxPointers = maxPointers,
            travel = travel.getDistance(),
            durationMs = upTime - down.uptimeMillis,
            dragged = dragging,
            slop = viewConfiguration.touchSlop,
            tapTimeoutMs = viewConfiguration.longPressTimeoutMillis,
        )
        when {
            docking && !pinching -> releaseDockSwipe(frame, scope, latest)
            merging && !pinching -> releaseMerge(frame, scope, mergeProgress, velocity.calculateVelocity().y, mergeFromAxis, mergeRange, density)
            pinching -> releasePinch(frame, scope, raw, axisVelocity.calculateVelocity().takeIf { it.isFinite() } ?: 0f, density, latest)
            popped -> releasePop(frame, scope, popProgress, velocity.calculateVelocity(), density, latest)
            kind == MiniGestureKind.Drag && windowAtDown -> releaseWindow(frame, scope, velocity.calculateVelocity(), density, latest)
            kind == MiniGestureKind.Drag -> releaseSwipe(frame, scope, velocity.calculateVelocity().x, density, latest)
            else -> {
                frame.gesturing = false
                when {
                    keyAtDown != null -> {
                        val settling = frame.flingJob?.isActive == true
                        val since = android.os.SystemClock.uptimeMillis() - frame.movedAt
                        if (MiniGestures.keyFires(kind, keyAtDown, since, settling)) {
                            if (keyAtDown == MiniKey.Close) latest().close() else latest().toggle()
                        }
                    }
                    kind == MiniGestureKind.Tap -> tapAt(frame, downAt, latest())
                }
            }
        }
    }
}

/** The corner a new window rests in (MiniGestures.cornerCenter: 3 = bottom right). */
private const val BottomRight = 3

/** How far a held window gives under the finger. */
private const val PressScale = 0.97f

/** How far the merged circle must be swiped sideways for letting go to close it. */
private val DockSwipeAt = 56.dp

/** The floating window's shadow at rest and under a finger. */
private val WindowShadow = 6.dp
private val WindowShadowHeld = 14.dp

/** The ring glides over one player tick; paused, each half of its breath. */
private const val RingTickMs = 520
private const val RingBreathMs = 1100

/**
 * A tap, by where it landed: the picture opens the video (the merged circle opens out into the
 * bar first), the side blocks seek, pause pauses.
 */
private fun tapAt(frame: MiniFrame, at: Offset, p: Playing) {
    val g = frame.geo
    val sides = g.sideAlpha > 0.5f && !g.windowed
    when {
        g.tile.contains(at) && g.merge > 0.5f -> expandCircle()
        g.tile.contains(at) -> {
            openDock(frame, p)
        }
        sides && g.left.contains(at) -> p.seekBy(-15_000)
        sides && g.pause.contains(at) -> p.toggle()
        sides && g.right.contains(at) -> p.seekBy(15_000)
    }
}

/** The merged circle splits back out into the bar; the next scroll down folds it in again. */
private fun expandCircle() {
    MiniLog.event("circle opens out", "tap")
    KeepPlaying.scrollWantsMerge = false
    KeepPlaying.rest = MiniRest.Row
}

/** Touch and accessibility opens must remember the same return geometry. */
private fun openDock(frame: MiniFrame, p: Playing) {
    val tile = frame.geo.tile
    PlayerHost.openingFromDock(DockForm(KeepPlaying.rest, KeepPlaying.windowCorner, KeepPlaying.windowCenter, tile, frame.geo.tileCorner))
    if (p.source == VideoSource.YouTube) KeepPlaying.openFrom = tile
    KeepPlaying.openingFromDock = true
    MiniLog.event("open from dock", "${KeepPlaying.rest} at $tile")
    p.open()
}

/** The corner a page that keeps the player floating puts it in (1 = top right). */
private const val TopRight = 1

/**
 * The bar's picture pops out as a floating window into the top right, on the springs a drag up
 * off the bar lets go on (the size from the bar's, the place from the picture's).
 */
private fun popOut(frame: MiniFrame, scope: CoroutineScope, density: Float) {
    val from = frame.geo.tile.center
    KeepPlaying.rest = MiniRest.Window(frame.dims.detach / density)
    snapWindow(frame, scope, frame.dims.detach, TopRight, from, Offset.Zero)
}

/** Springs the window into [corner] (or the nearest one to where it is) at the long side [axis]. */
private fun snapWindow(frame: MiniFrame, scope: CoroutineScope, axis: Float, corner: Int?, from: Offset, velocity: Offset) {
    val (w, h) = MiniAxis.pictureSize(axis, frame.aspect)
    val area = frame.restArea
    // A page that keeps it floating keeps it up top: only the two top corners.
    val c = (corner ?: MiniGestures.nearestCorner(from, w, h, area)).let { if (KeepPlaying.floatOnly) it and 1 else it }
    MiniLog.event("window to corner", "$c")
    KeepPlaying.windowCorner = c
    val to = MiniGestures.cornerCenter(c, w, h, area)
    KeepPlaying.windowCenter = from
    frame.flingJob?.cancel()
    frame.movedAt = android.os.SystemClock.uptimeMillis()
    frame.flingJob = scope.launch {
        try {
            animate(Offset.VectorConverter, from, to, initialVelocity = velocity, animationSpec = MiniSprings.liq()) { value, _ ->
                KeepPlaying.windowCenter = value
            }
        } finally {
            frame.movedAt = android.os.SystemClock.uptimeMillis()
        }
    }
}

private fun releasePinch(frame: MiniFrame, scope: CoroutineScope, raw: Float, v: Float, density: Float, latest: () -> Playing) {
    val d = frame.dims
    val target: Float
    var window = false
    val wasWindow = KeepPlaying.rest is MiniRest.Window
    // A page that keeps it floating: it stays a window, at least the smallest one.
    val release = if (KeepPlaying.floatOnly) MiniRelease.Rest(MiniRest.Window(raw.coerceIn(d.detach, frame.maxAxis.coerceAtLeast(d.detach))))
    else MiniAxis.release(raw, v, d, frame.maxAxis, frame.canMerge)
    when (val r = release) {
        is MiniRelease.Rest -> {
            val rest = r.rest
            if (rest is MiniRest.Window) {
                // The release works in px; the rest keeps dp.
                KeepPlaying.rest = MiniRest.Window(rest.axisDp / density)
                window = true
            } else {
                KeepPlaying.rest = rest
            }
            target = frame.restAxis(KeepPlaying.rest, density)
        }
    }
    // A resized window goes back to its corner; a new one starts bottom right, above the nav bar
    // (never left mid-screen over a page).
    if (window) {
        frame.haptic(dock = false)
        snapWindow(frame, scope, target, KeepPlaying.windowCorner ?: BottomRight, frame.geo.tile.center, Offset.Zero)
    } else if (wasWindow) {
        // Pinched back into the bar.
        frame.haptic(dock = true)
    }
    scope.launch {
        frame.axis.snapTo(frame.pinchAxis ?: raw)
        frame.pinchAxis = null
        frame.gesturing = false
        // Past an end the fingers moved only a third of their speed into the size.
        val inside = raw in frame.minAxis..frame.maxAxis
        frame.axis.animateTo(target, MiniSprings.liq(), initialVelocity = if (inside) v else v * MiniAxis.RubberBand)
    }
}

/** A pop-out let go: a window (to a corner, carrying the throw), or back into the bar. */
private fun releasePop(frame: MiniFrame, scope: CoroutineScope, progress: Float, v: Velocity, density: Float, latest: () -> Playing) {
    val now = frame.pinchAxis ?: frame.axisNow
    val at = frame.dragCenter ?: frame.geo.tile.center
    if (MiniGestures.popKeeps(progress)) {
        KeepPlaying.rest = MiniRest.Window(frame.dims.detach / density)
        scope.launch {
            frame.axis.snapTo(now)
            frame.pinchAxis = null
            frame.axis.animateTo(frame.dims.detach, MiniSprings.liq())
            frame.popping = false
        }
        releaseWindow(frame, scope, v, density, latest)
        return
    }
    // Back into the bar: the picture flies to its place as it shrinks.
    KeepPlaying.rest = MiniRest.Row
    KeepPlaying.windowCenter = at
    frame.dragCenter = null
    frame.gesturing = false
    val home = frame.rowTile().center
    frame.flingJob?.cancel()
    frame.flingJob = scope.launch {
        animate(Offset.VectorConverter, at, home, animationSpec = MiniSprings.liq()) { value, _ -> KeepPlaying.windowCenter = value }
    }
    scope.launch {
        frame.axis.snapTo(now)
        frame.pinchAxis = null
        frame.axis.animateTo(frame.dims.row, MiniSprings.liq())
        frame.flingJob?.join()
        frame.popping = false
        KeepPlaying.windowCenter = Offset.Unspecified
    }
}

/** A merge from a bottom corner let go: into the bar, or back to its corner. */
private fun releaseMerge(frame: MiniFrame, scope: CoroutineScope, progress: Float, vy: Float, fromAxis: Float, range: Float, density: Float) {
    val at = frame.dragCenter ?: frame.geo.tile.center
    KeepPlaying.windowCenter = at
    val now = frame.pinchAxis ?: frame.axisNow
    if (MiniGestures.mergeCompletes(progress, vy, 500f * density)) {
        MiniLog.event("window merges into bar", "")
        KeepPlaying.windowCorner = null
        KeepPlaying.rest = MiniRest.Row
        frame.haptic(dock = true)
        // The finger's speed carries on into the size.
        val axisSpeed = vy / range * (frame.dims.row - fromAxis)
        scope.launch {
            frame.axis.snapTo(now)
            frame.pinchAxis = null
            frame.dragCenter = null
            frame.gesturing = false
            frame.axis.animateTo(frame.dims.row, MiniSprings.liq(), initialVelocity = axisSpeed)
        }
    } else {
        val corner = KeepPlaying.windowCorner
        scope.launch {
            frame.axis.snapTo(now)
            frame.pinchAxis = null
            frame.dragCenter = null
            frame.gesturing = false
            frame.axis.animateTo(fromAxis, MiniSprings.liq())
        }
        snapWindow(frame, scope, fromAxis, corner, at, Offset.Zero)
    }
}

private fun releaseWindow(frame: MiniFrame, scope: CoroutineScope, v: Velocity, density: Float, latest: () -> Playing) {
    val g = frame.geo
    val center = g.tile.center
    frame.dragCenter = null
    frame.gesturing = false
    val vel = Offset(v.x, v.y)
    // A corner (the bottom ones rest above the nav bar), however it is dragged or flung, however
    // hard: a window never closes by a throw or a swipe, only by its close key; only a drag down
    // from a bottom corner, or a pinch, takes it back into the bar.
    frame.haptic(dock = false)
    val corner = MiniGestures.flungCorner(center, vel, g.tile.width, g.tile.height, frame.restArea, 250f * density)
    // At the size it rests at (a popped-out window may still be growing into its smallest).
    snapWindow(frame, scope, frame.restAxis(KeepPlaying.rest, density), corner, center, vel)
}

private fun releaseSwipe(frame: MiniFrame, scope: CoroutineScope, vx: Float, density: Float, latest: () -> Playing) {
    val x = frame.swipeDrag ?: 0f
    val width = frame.anchor.value.width
    // Swiping away is the docked bar's alone: a window (resting as one, or drawn as one) never goes.
    val bar = !MiniGestures.isWindow(KeepPlaying.rest is MiniRest.Window, frame.geo.windowed)
    val away = bar && (abs(x) > width * MiniGestures.SwipeAwayAt || (abs(vx) > 1000f * density && sign(vx) == sign(x)))
    scope.launch {
        frame.swipe.snapTo(x)
        frame.swipeDrag = null
        frame.gesturing = false
        if (away) {
            val dir = if (x != 0f) sign(x) else sign(vx)
            frame.swipe.animateTo(dir * (width + 200f * density), MiniSprings.fast(), initialVelocity = vx)
            // It stays out there, faded, while it closes; it comes back to the middle only when
            // a bar shows again (snapping home here flashed it back in at full strength).
            latest().close()
        } else {
            frame.swipe.animateTo(0f, MiniSprings.def(), initialVelocity = vx)
        }
    }
}

/**
 * The merged circle let go: armed, it pops a little and springs down to nothing while the nav
 * bar closes the room it leaves, and the video stops; else what was drawn over it lets go.
 */
private fun releaseDockSwipe(frame: MiniFrame, scope: CoroutineScope, latest: () -> Playing) {
    frame.gesturing = false
    if (!frame.dockArmed) {
        cancelDockSwipe(frame, scope)
        return
    }
    val at = frame.dockNow
    MiniLog.event("circle swiped off the nav bar", "")
    frame.dockClosing = true
    scope.launch {
        frame.dockSwipe.snapTo(at)
        frame.dockDrag = null
        frame.dockScale.animateTo(1.12f, MiniSprings.fast())
        KeepPlaying.closeNavRoom()
        latest().close()
        frame.dockScale.animateTo(0f, MiniSprings.slow())
    }
}

private fun cancelDockSwipe(frame: MiniFrame, scope: CoroutineScope) {
    val at = frame.dockNow
    frame.dockArmed = false
    scope.launch {
        frame.dockSwipe.snapTo(at)
        frame.dockDrag = null
        frame.dockSwipe.animateTo(0f, MiniSprings.def())
    }
}

/**
 * The floating window's keys: play / pause bottom left, close bottom right. Small circles on a
 * scrim so they read on any frame; they grow and shrink with the window (the detector owns
 * their presses).
 */
@Composable
private fun WindowKey(close: Boolean, p: Playing, frame: MiniFrame) {
    Box(
        Modifier
            .graphicsLayer { alpha = frame.geo.buttonsAlpha }
            // The scrim fades away when idle; the icon keeps a soft shadow to stay legible.
            .drawBehind { drawCircle(Color.Black.copy(alpha = 0.45f * frame.keysBacking.value)) }
            .a11yClick(if (close) "关闭" else if (p.isPlaying) "暂停" else "播放") { if (close) p.close() else p.toggle() },
        contentAlignment = Alignment.Center,
    ) {
        val m = Modifier.fillMaxSize(0.6f)
        val shadow = Modifier.fillMaxSize(0.6f).offset { IntOffset(0, 1.dp.roundToPx()) }
        if (close) {
            Icon(com.gpo.yoin.symbols.YoinSymbols.Close, contentDescription = null, tint = Color.Black.copy(alpha = 0.45f), modifier = shadow)
            Icon(com.gpo.yoin.symbols.YoinSymbols.Close, contentDescription = null, tint = Color.White, modifier = m)
        } else {
            val painter = com.gpo.yoin.symbols.rememberPlayPauseSymbolPainter(p.isPlaying)
            Icon(painter, contentDescription = null, tint = Color.Black.copy(alpha = 0.45f), modifier = shadow)
            Icon(painter, contentDescription = null, tint = Color.White, modifier = m)
        }
    }
}

/** −15 s / +15 s: the outer side fully round, the inner one a small corner. */
@Composable
private fun SideBlock(forward: Boolean, p: Playing, frame: MiniFrame) {
    // A live stream has no ±15: an empty place keeps the bar's layout the same.
    if (p.live) {
        Box(Modifier)
        return
    }
    val inner = InnerCorner
    val small = androidx.compose.foundation.shape.CornerSize(inner)
    val round = androidx.compose.foundation.shape.CornerSize(50)
    val shape = if (forward) {
        RoundedCornerShape(topStart = small, topEnd = round, bottomEnd = round, bottomStart = small)
    } else {
        RoundedCornerShape(topStart = round, topEnd = small, bottomEnd = small, bottomStart = round)
    }
    Box(
        Modifier
            .graphicsLayer { alpha = frame.geo.sideAlpha * (frame.sidesIn?.value ?: 1f) }
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            // Taps are the bar's detector's (a pinch must never end as one); this is for accessibility.
            .a11yClick(if (forward) "前进 15 秒" else "后退 15 秒") { p.seekBy(if (forward) 15_000 else -15_000) },
        contentAlignment = Alignment.Center,
    ) { SeekGlyph(forward = forward, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
}

/** Pause / play: the bare symbol on its block (a live bar's last block: its outer side fully round, like +15). */
@Composable
private fun PauseBlock(p: Playing, frame: MiniFrame) {
    val small = androidx.compose.foundation.shape.CornerSize(InnerCorner)
    val round = androidx.compose.foundation.shape.CornerSize(50)
    val shape = if (p.live) {
        RoundedCornerShape(topStart = small, topEnd = round, bottomEnd = round, bottomStart = small)
    } else {
        RoundedCornerShape(InnerCorner)
    }
    Box(
        Modifier
            .graphicsLayer { alpha = frame.geo.sideAlpha * (frame.sidesIn?.value ?: 1f) }
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .a11yClick(if (p.isPlaying) "暂停" else "播放", p.toggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            com.gpo.yoin.symbols.rememberPlayPauseSymbolPainter(p.isPlaying),
            contentDescription = if (p.isPlaying) "暂停" else "播放",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * The picture: the bar's tile, the circle and the floating window are this one view (one
 * surface), reshaped. Its progress is a straight line in a narrow strip under the picture, left to
 * right; in the circle the strip runs all round and the line is a ring from the top centre,
 * clockwise. The strip and the line turn into the ring with the shape itself (the merge), so
 * nothing jumps. Off the picture, it reads on any video (a line drawn over it vanished into
 * frames of its own colour).
 */
@Composable
private fun Tile(p: Playing, frame: MiniFrame) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // The progress glides between the player's ticks (YouTube's come every half second) instead
    // of stepping; a jump (a seek, ±15) springs there; another video starts where it is.
    val shown = remember(p.player, p.title) { Animatable(p.progress.coerceIn(0f, 1f)) }
    LaunchedEffect(p.progress, p.isPlaying) {
        val to = p.progress.coerceIn(0f, 1f)
        val step = to - shown.targetValue
        if (p.isPlaying && step in 0f..0.02f) {
            shown.animateTo(to, androidx.compose.animation.core.tween(RingTickMs, easing = androidx.compose.animation.core.LinearEasing))
        } else {
            shown.animateTo(to, MiniSprings.def())
        }
    }
    // Paused, the line breathes (never so faint it reads as gone); playing, it holds full strength.
    val breath = remember { Animatable(1f) }
    LaunchedEffect(p.isPlaying) {
        if (p.isPlaying) {
            breath.animateTo(1f, MiniSprings.def())
        } else {
            while (true) {
                breath.animateTo(0.6f, androidx.compose.animation.core.tween(RingBreathMs, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                breath.animateTo(1f, androidx.compose.animation.core.tween(RingBreathMs, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
        }
    }
    // A floating window sits above the page: a soft shadow, deeper while a finger holds it.
    val lift by animateFloatAsState(if (frame.geo.windowed) 1f else 0f, MiniSprings.def(), label = "windowLift")
    // The merged circle swiped far enough to close: it turns into a cookie (and back).
    val armed = remember(frame) { Animatable(0f) }
    LaunchedEffect(frame, frame.dockArmed) { armed.animateTo(if (frame.dockArmed) 1f else 0f, MiniSprings.def()) }
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val cookie = remember(morph) { CookieMorphShape(morph, 1f) }
    Box(
        Modifier
            .graphicsLayer {
                val a = armed.value
                shape = when {
                    a <= 0f -> RoundedCornerShape(frame.geo.tileCorner)
                    a >= 1f -> cookie
                    else -> CookieMorphShape(morph, a)
                }
                clip = true
                val s = frame.dockScale.value.coerceAtLeast(0f)
                scaleX = s
                scaleY = s
                val held = ((1f - frame.press.value) / (1f - PressScale)).coerceIn(0f, 1f)
                shadowElevation = lift * (WindowShadow + (WindowShadowHeld - WindowShadow) * held).toPx()
            }
            .background(track)
            .then(p.route?.let { Modifier.pageSharedSource(it) } ?: Modifier)
            .then(
                if (KeepPlaying.rest == MiniRest.Merged) Modifier.a11yClick("展开迷你播放器") { expandCircle() }
                else Modifier.a11yClick(p.title.ifEmpty { if (p.live) "打开直播间" else "打开视频" }) { openDock(frame, p) },
            )
            .drawWithContent {
                drawContent()
                if (p.live) {
                    drawLiveDot(frame.geo.tileCorner, frame.geo.merge, ring = track)
                    return@drawWithContent
                }
                val stroke = ProgressStroke.toPx()
                val gutter = ProgressGutter.toPx()
                val corner = frame.geo.tileCorner
                // The bar and the window draw the line, the circle the ring; between them both, crossfaded.
                val m = frame.geo.merge
                // Swiping the circle away, the ring gives way to the scrim.
                val gone = 1f - frame.dockNow.coerceIn(0f, 1f)
                val strength = breath.value * gone
                if (m < 1f) {
                    // Straight, in the strip's middle, stopping short of the rounded corners (cap included).
                    val y = size.height - gutter / 2f
                    val dy = gutter / 2f
                    val edge = if (corner > dy) corner - kotlin.math.sqrt(corner * corner - (corner - dy) * (corner - dy)) else 0f
                    val x0 = maxOf(gutter, edge + stroke)
                    val x1 = (size.width - x0).coerceAtLeast(x0)
                    val line = 1f - m
                    drawLine(primary.copy(alpha = ProgressTrackAlpha * line * gone), Offset(x0, y), Offset(x1, y), stroke, StrokeCap.Round)
                    if (shown.value > 0f) {
                        drawLine(primary.copy(alpha = strength * line), Offset(x0, y), Offset(x0 + (x1 - x0) * shown.value, y), stroke, StrokeCap.Round)
                    }
                }
                if (m > 0f) {
                    val path = edgePath(size, corner, gutter / 2f)
                    drawPath(path, primary.copy(alpha = ProgressTrackAlpha * m * gone), style = Stroke(stroke))
                    val measure = PathMeasure().apply { setPath(path, false) }
                    val seg = Path()
                    measure.getSegment(0f, measure.length * shown.value, seg, true)
                    drawPath(seg, primary.copy(alpha = strength * m), style = Stroke(stroke, cap = StrokeCap.Round))
                }
            },
    ) {
        Box(
            Modifier
                // The picture fills the tile but for the strip under it; merging, the strip grows
                // round its other sides too (read while laying out: the morph does not recompose).
                .layout { measurable, constraints ->
                    val gutter = if (p.live) 0f else ProgressGutter.toPx()
                    val side = (gutter * frame.geo.merge).roundToInt()
                    val bottom = gutter.roundToInt()
                    val w = (constraints.maxWidth - side * 2).coerceAtLeast(0)
                    val h = (constraints.maxHeight - side - bottom).coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(side, side) }
                }
                .graphicsLayer {
                    val gutter = if (p.live) 0f else ProgressGutter.toPx()
                    val corner = frame.geo.tileCorner
                    val top = (corner - gutter * frame.geo.merge).coerceAtLeast(0f)
                    val bottom = (corner - gutter).coerceAtLeast(0f)
                    shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
                    clip = true
                },
        ) {
            // The page has left composition before its controller is offered to the dock.
            // Media3 clears a surface by identity, so there is no need to freeze the landing
            // frame for another 400 ms before attaching this view.
            // Until the video has drawn a frame into this tile, what stood for it stays on top: the
            // frame the page's flight landed with, else the cover. Swapped in one frame once the
            // picture is really there (a fixed delay showed the cover, or an empty view, first).
            var drawn by remember(p.player, frame) { mutableStateOf(false) }
            val player = p.player
            if (player != null) {
                androidx.compose.runtime.DisposableEffect(player, frame) {
                    val l = object : androidx.media3.common.Player.Listener {
                        override fun onRenderedFirstFrame() {
                            drawn = true
                            MiniLog.tileDrawn()
                        }
                    }
                    player.addListener(l)
                    onDispose { player.removeListener(l) }
                }
            }
            val stillAlpha = remember(p.player, frame) { Animatable(1f) }
            LaunchedEffect(drawn, frame) {
                if (drawn) {
                    // Media3's renderer callback can precede the TextureView's composited frame.
                    // Keep the landing image until that frame has had a chance to reach the display.
                    repeat(2) { androidx.compose.runtime.withFrameNanos { } }
                    stillAlpha.animateTo(0f, androidx.compose.animation.core.tween(120))
                    frame.arrived = null
                }
            }
            player?.let { player ->
                // Cropped only while the bar's 16:9 tile turns into the video's own shape; in the
                // window the shapes match and nothing is cut or stretched.
                androidx.media3.ui.compose.ContentFrame(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                    surfaceType = androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    shutter = {},
                )
            }
            if (stillAlpha.value > 0f) {
                val stillModifier = Modifier.fillMaxSize().graphicsLayer { alpha = stillAlpha.value }
                val still = frame.arrived
                if (still != null) {
                    androidx.compose.foundation.Image(still, null, stillModifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                } else {
                    NetImage(p.cover, stillModifier, contentDescription = p.title, shape = androidx.compose.ui.graphics.RectangleShape, thumbWidth = 640.dp)
                }
            }
        }
        DockSwipeScrim(frame, armed)
    }
}

/**
 * A live stream's mark: a small red dot in the picture's top-left corner (just inside the
 * rounding), on a thin [ring] of the tile's own colour so it reads on any frame. In the circle
 * ([merge] 1) it sits on the same diagonal, inside the round.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLiveDot(corner: Float, merge: Float, ring: Color) {
    // A little smaller in the circle.
    val r = lerp(LiveDot.toPx(), LiveDotRound.toPx(), merge.coerceIn(0f, 1f)) / 2f
    val edge = LiveDotRing.toPx()
    // The corner's arc (radius [corner]) passes the diagonal at corner·(1 − 1/√2) in from each
    // side; the dot sits a little further in than that, never closer than 4dp to the sides.
    val square = maxOf(4.dp.toPx() + r, corner * 0.293f + r + edge * 2f)
    val radius = size.minDimension / 2f
    val round = radius - (radius - r - edge - 3.dp.toPx()) * 0.7071f
    val at = lerp(square, round, merge.coerceIn(0f, 1f))
    val c = Offset(at, at)
    drawCircle(ring, radius = r + edge, center = c)
    drawCircle(LiveRed, radius = r, center = c)
}

/** The live dot: the same red in any theme. */
private val LiveRed = Color(0xFFFF3B30)
private val LiveDot = 6.dp
private val LiveDotRound = 5.dp
private val LiveDotRing = 1.5.dp

/**
 * Over the merged circle while it is swiped sideways: a scrim darkening from where the swipe
 * started, with a back arrow growing in; armed, both turn solid (primaryContainer).
 */
@Composable
private fun DockSwipeScrim(frame: MiniFrame, armed: Animatable<Float, *>) {
    val solid = MaterialTheme.colorScheme.primaryContainer
    val onSolid = MaterialTheme.colorScheme.onPrimaryContainer
    val back = rememberVectorPainter(YoinSymbols.Back)
    Box(
        Modifier.fillMaxSize().drawBehind {
            val p = frame.dockNow.coerceIn(0f, 1f)
            val a = armed.value.coerceIn(0f, 1f)
            if (p <= 0f && a <= 0f) return@drawBehind
            val deep = Color.Black.copy(alpha = 0.7f * p)
            val light = Color.Black.copy(alpha = 0.25f * p)
            drawRect(Brush.horizontalGradient(if (frame.dockDir > 0f) listOf(deep, light) else listOf(light, deep)))
            drawRect(solid, alpha = a)
            val s = DockIcon.toPx() * (0.6f + 0.4f * p + 0.1f * a)
            val tint = androidx.compose.ui.graphics.lerp(Color.White, onSolid, a)
            translate((size.width - s) / 2f, (size.height - s) / 2f) {
                with(back) { draw(Size(s, s), alpha = maxOf(p, a), colorFilter = ColorFilter.tint(tint)) }
            }
        },
    )
}

private val DockIcon = 24.dp

/** The circle → cookie [Morph] at [progress], scaled from the unit square to the tile. */
private class CookieMorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress).asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height, 1f) })
        return Outline.Generic(path)
    }
}

/** Clickable for accessibility services only: touches go through the bar's own detector. */
private fun Modifier.a11yClick(label: String, action: () -> Unit): Modifier = semantics {
    role = Role.Button
    onClick(label) {
        action()
        true
    }
}

/** A rounded rectangle's edge, [inset] in, starting at the top centre and running clockwise. */
private fun edgePath(size: Size, corner: Float, inset: Float): Path {
    val l = inset
    val t = inset
    val r = size.width - inset
    val b = size.height - inset
    val rad = (corner - inset).coerceIn(0f, minOf(r - l, b - t) / 2f)
    return Path().apply {
        moveTo((l + r) / 2f, t)
        lineTo(r - rad, t)
        arcTo(Rect(r - 2 * rad, t, r, t + 2 * rad), -90f, 90f, false)
        lineTo(r, b - rad)
        arcTo(Rect(r - 2 * rad, b - 2 * rad, r, b), 0f, 90f, false)
        lineTo(l + rad, b)
        arcTo(Rect(l, b - 2 * rad, l + 2 * rad, b), 90f, 90f, false)
        lineTo(l, t + rad)
        arcTo(Rect(l, t, l + 2 * rad, t + 2 * rad), 180f, 90f, false)
        close()
    }
}

/** ±15 s: the circular arrow (mirrored for forward) with the 15 inside it. */
@Composable
private fun SeekGlyph(forward: Boolean, tint: androidx.compose.ui.graphics.Color) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            com.gpo.yoin.symbols.PiliSymbols.Replay,
            contentDescription = if (forward) "前进 15 秒" else "后退 15 秒",
            tint = tint,
            modifier = Modifier.size(30.dp).graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            "15",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = tint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Asked once: the first time a playing video is left. */
@Composable
private fun KeepAskDialog() {
    AlertDialog(
        onDismissRequest = { KeepPlaying.choose(keep = true) },
        title = { Text("回到首页时停止播放？") },
        text = { Text("继续播放时，视频收进底部的迷你条。可在 设置 › 播放 中更改。") },
        confirmButton = { TextButton(onClick = { KeepPlaying.choose(keep = true) }) { Text("继续播放") } },
        dismissButton = { TextButton(onClick = { KeepPlaying.choose(keep = false) }) { Text("停止播放") } },
    )
}

/**
 * Debug builds only: every mini bar / window transition's start, with a running id and its
 * caller, in logcat (tag PiliMini), so a double start shows up as two lines.
 */
internal object MiniLog {
    private var id = 0
    private var flight = 0
    private var landedAt = 0L

    /** A page's back ride ended (the hero, if any, on its target): the gap to the tile's first frame is timed from here. */
    fun landed(page: String, hero: Boolean) {
        if (!com.gpo.pilipala.BuildConfig.DEBUG) return
        flight++
        landedAt = android.os.SystemClock.uptimeMillis()
        event("flight #$flight lands", "${page.substringBefore('(')} hero=$hero")
    }

    /** The bar's tile drew its first video frame. */
    fun tileDrawn() {
        if (!com.gpo.pilipala.BuildConfig.DEBUG) return
        val gap = if (landedAt > 0L) "${android.os.SystemClock.uptimeMillis() - landedAt} ms after flight #$flight landed" else "no flight"
        event("tile first frame", gap)
        landedAt = 0L
    }

    fun event(what: String, detail: String) {
        if (!com.gpo.pilipala.BuildConfig.DEBUG) return
        id++
        val caller = Throwable().stackTrace.getOrNull(2)?.let { "${it.fileName}:${it.lineNumber}" }.orEmpty()
        android.util.Log.d("PiliMini", "#$id $what $detail ($caller)")
    }
}

/** A video size's width / height, held to what the window can show; null while unknown. */
private fun aspectOf(v: androidx.media3.common.VideoSize): Float? =
    if (v.width > 0 && v.height > 0) (v.width * v.pixelWidthHeightRatio / v.height).coerceIn(0.4f, 2.5f) else null
