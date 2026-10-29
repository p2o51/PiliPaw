package com.gpo.pilipala.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.search.PiliSearchPage
import com.gpo.pilipala.feature.search.SearchRequest
import com.gpo.pilipala.feature.search.openSearch
import com.gpo.pilipala.feature.search.rememberPiliSearchState
import com.gpo.pilipala.feature.sources.KeepPlaying
import com.gpo.pilipala.feature.sources.MiniSprings
import com.gpo.pilipala.feature.sources.NavRoom
import com.gpo.pilipala.feature.sources.NavSpot
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.BackPhase
import com.gpo.pilipala.navigation.transition.PageTransitions
import com.gpo.pilipala.ui.components.SideRailRight
import com.gpo.pilipala.ui.components.SideRailState
import com.gpo.pilipala.ui.components.SideRailWidthDp
import com.gpo.pilipala.ui.components.useSideRail
import com.gpo.pilipala.ui.launch.splashReveal
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * What the shell hands its side rail, published by [MainShell] each composition. The rail lives
 * outside the shell's page (above every page, [SideRailHost]) so it can slide away when a page
 * opens and follow a back gesture in again; while a page covers the shell it keeps drawing the
 * last model it had.
 */
internal class RailModel(
    val tabs: List<NavTab>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
    val hasLongPress: (Int) -> Boolean,
    val badge: (Int) -> Boolean,
    val onLongPress: (Int) -> Unit,
    /** Bilibili's suggested word, the search field's placeholder ("" without Bilibili). */
    val searchHint: String,
    /** 消息, only while Bilibili is ticked. */
    val onMessages: (() -> Unit)?,
)

/**
 * Whether the rail is in: by where the window is going, never by "a page is closing". A back
 * lands on the entry beneath the closing page until the pop and on the top one after it (the pop
 * comes mid-ride, while the back is still running): only when that is the shell does the rail
 * come in (a back to search results, an UP page, 设置… leaves it out).
 */
internal enum class RailVisibility {
    In, Out,

    /** A back gesture toward the shell: in as far as the gesture has gone (a cancel takes it back out). */
    FollowGesture;

    companion object {
        fun of(shellOnTop: Boolean, shellBeneath: Boolean, phase: BackPhase): RailVisibility = when {
            shellOnTop -> In
            // Revealing: the closing page is already popped; what is beneath the top now is not where this back lands.
            phase == BackPhase.Gesture && shellBeneath -> FollowGesture
            phase == BackPhase.Committing && shellBeneath -> In
            else -> Out
        }
    }
}

internal object SideRail {
    var model by mutableStateOf<RailModel?>(null)

    /** Long press on 首页 while the rail is up: its search opens. */
    val searchRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/** Whether a drag let go at [position] (0 left … 1 right) after starting on the right moves the rail across. */
internal object RailFlip {
    /** A throw toward the other edge faster than this (px/ms) flips it whatever the distance. */
    const val FlingPxPerMs = 0.6f

    fun landsRight(startRight: Boolean, position: Float, velocityPxPerMs: Float): Boolean = when {
        startRight && (position < 0.5f || velocityPxPerMs < -FlingPxPerMs) -> false
        !startRight && (position > 0.5f || velocityPxPerMs > FlingPxPerMs) -> true
        else -> startRight
    }

    /** The content column's left edge (px) for a rail [railWidth] wide at [position]: it gives way as the rail crosses. */
    fun contentLeft(position: Float, railWidth: Float): Float = railWidth * (1f - position.coerceIn(0f, 1f))
}

/**
 * The side rail on a large window (ui/components useSideRail): an 88dp column on one edge with the
 * account, search and messages at its top, the nav pill stood up in its middle and the mini
 * player's strip under it (drawn by MiniBarHost, which docks under the pill [NavSpot] reports).
 * A page opening slides it out toward its edge; a back gesture to the shell draws it back in with
 * the gesture. Dragging its blank space across moves it to the other edge.
 */
@Composable
internal fun SideRailHost(transitions: PageTransitions, navigator: Navigator) {
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { NavSpot.clear(token) } }
    val rail = useSideRail()
    val model = SideRail.model
    if (!rail || model == null) {
        LaunchedEffect(Unit) { NavSpot.clear(token) }
        return
    }
    val right by SideRailRight.state.collectAsState()
    val position = SideRailState.position
    LaunchedEffect(right) {
        val target = if (right) 1f else 0f
        if (position.targetValue != target || (!position.isRunning && position.value != target)) {
            position.animateTo(target, MiniSprings.liq())
        }
    }

    // In with the shell on top, out under any page; a back gesture to the shell brings it in with the finger.
    val stack = navigator.backStack
    fun isShell(key: Any?) = key == Route.Main || key == com.gpo.pilipala.yt.navigation.Route.Main
    val shellOnTop = isShell(stack.lastOrNull())
    val shellBeneath = isShell(stack.getOrNull(stack.size - 2))
    val phase = transitions.phase
    val slide = remember { Animatable(if (shellOnTop) 1f else 0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val target = RailVisibility.of(shellOnTop, shellBeneath, phase)
    LaunchedEffect(target) {
        when (target) {
            RailVisibility.In -> slide.animateTo(1f, spatial)
            RailVisibility.FollowGesture -> snapshotFlow { transitions.closing.value }.collect { slide.snapTo(it.coerceIn(0f, 1f)) }
            RailVisibility.Out -> slide.animateTo(0f, spatial)
        }
    }

    val search = rememberPiliSearchState(spatial = true)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SideRail.searchRequests.collect { search.bar.animateToExpanded() } }
    // A page opening over the shell takes the search with it.
    LaunchedEffect(shellOnTop) { if (!shellOnTop && search.isOpen) search.bar.animateToCollapsed() }
    var searchButton by remember { mutableStateOf(Rect.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val railW = with(density) { SideRailWidthDp.dp.toPx() }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val travel = (w - railW).coerceAtLeast(1f)
        val away = railW + with(density) { 16.dp.toPx() }
        val haptics = LocalHapticFeedback.current

        // The search page covers the content column only.
        val contentLeft = RailFlip.contentLeft(position.value, railW)
        PiliSearchPage(
            state = search,
            hint = model.searchHint,
            onSubmit = { request: SearchRequest -> navigator.openSearch(request) },
            from = searchButton,
            area = Rect(contentLeft, 0f, contentLeft + w - railW, h),
        )

        // What a drag on the rail must not start on: its buttons and the pill (root px).
        val solid = remember { mutableMapOf<String, Rect>() }
        fun Modifier.solid(key: String) = onGloballyPositioned { solid[key] = it.boundsInRoot() }
        var railOrigin by remember { mutableStateOf(Offset.Zero) }
        val onTop = androidx.compose.runtime.rememberUpdatedState(shellOnTop)
        val currentAway = { (1f - slide.value) * away * (if (position.value > 0.5f) 1f else -1f) }

        Box(
            Modifier
                .offset { IntOffset((position.value * travel + currentAway()).roundToInt(), 0) }
                .width(SideRailWidthDp.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .onGloballyPositioned { railOrigin = it.positionInRoot() }
                .pointerInput(travel) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        val at = down.position + railOrigin
                        if (solid.values.any { it.contains(at) } || slide.value < 0.99f) return@awaitEachGesture
                        val startRight = position.targetValue > 0.5f
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position + railOrigin)
                        var crossed = false
                        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        scope.launch { position.stop() }
                        SideRailState.dragging = true
                        var pos = position.value
                        fun move(dx: Float) {
                            pos = (pos + dx / travel).coerceIn(0f, 1f)
                            scope.launch { position.snapTo(pos) }
                            val over = if (startRight) pos < 0.5f else pos > 0.5f
                            if (over != crossed) {
                                crossed = over
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }
                        try {
                            move(start.positionChange().x)
                            horizontalDrag(start.id) { change ->
                                tracker.addPosition(change.uptimeMillis, change.position + railOrigin)
                                move(change.positionChange().x)
                                change.consume()
                            }
                        } finally {
                            SideRailState.dragging = false
                        }
                        val v = tracker.calculateVelocity().x
                        val landsRight = RailFlip.landsRight(startRight, pos, v / 1000f)
                        if (landsRight != startRight) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        scope.launch {
                            position.animateTo(if (landsRight) 1f else 0f, MiniSprings.liq(), initialVelocity = v / travel)
                        }
                        if (landsRight != right) AppSettings.set(SideRailRight, landsRight)
                    }
                },
        ) {
            val statusTop = WindowInsets.statusBars.getTop(density)
            Layout(
                content = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.solid("avatar")) {
                            CompositionLocalProvider(com.gpo.pilipala.feature.sources.LocalHoldFanEnd provides (position.targetValue > 0.5f)) {
                                com.gpo.pilipala.feature.home.HomeAvatarButton()
                            }
                        }
                        IconButton(
                            onClick = { scope.launch { search.bar.animateToExpanded() } },
                            shapes = IconButtonDefaults.shapes(),
                            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier
                                .size(48.dp)
                                .splashReveal()
                                .solid("search")
                                .onGloballyPositioned { searchButton = it.boundsInRoot() },
                        ) {
                            Icon(YoinSymbols.Search, contentDescription = "搜索")
                        }
                        val messages = model.onMessages
                        if (messages != null) {
                            IconButton(
                                onClick = messages,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier.size(48.dp).splashReveal().solid("messages"),
                            ) {
                                Icon(YoinSymbols.Mail, contentDescription = "消息")
                            }
                        } else {
                            solid.remove("messages")
                        }
                    }
                    FloatingNavBar(
                        tabs = model.tabs,
                        selected = model.selected,
                        onSelect = model.onSelect,
                        hasLongPress = model.hasLongPress,
                        badge = model.badge,
                        onLongPress = model.onLongPress,
                        vertical = true,
                        modifier = Modifier
                            .splashReveal(lift = 0.dp)
                            .solid("pill")
                            // The mini player docks under the pill: its rect where it rests (the
                            // slide and the circle's room left out), only while the shell is on top.
                            .onGloballyPositioned {
                                if (!onTop.value) return@onGloballyPositioned
                                val b = it.boundsInRoot()
                                val room = KeepPlaying.navRoom * with(density) { NavRoom.toPx() }
                                NavSpot.place(token, Rect(b.left - currentAway(), b.top, b.right - currentAway(), b.bottom - room))
                            },
                    )
                },
            ) { measurables, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val top = measurables[0].measure(loose)
                val pill = measurables[1].measure(loose.copy(maxHeight = Constraints.Infinity))
                val room = (KeepPlaying.navRoom * NavRoom.toPx()).roundToInt()
                val base = pill.height - room
                val topY = statusTop + 8.dp.roundToPx()
                // The pill centred on the window's height (by its size without the circle's room,
                // which it grows downward into), never up into the buttons above it.
                val pillY = maxOf((constraints.maxHeight - base) / 2, topY + top.height + 24.dp.roundToPx())
                layout(constraints.maxWidth, constraints.maxHeight) {
                    top.place((constraints.maxWidth - top.width) / 2, topY)
                    pill.place((constraints.maxWidth - pill.width) / 2, pillY)
                }
            }
        }

        LaunchedEffect(shellOnTop) { if (!shellOnTop) NavSpot.clear(token) }
    }
}
