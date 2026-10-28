package com.gpo.pilipala.feature.sources

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the fan offers: each account's page, then 设置. */
private sealed interface HoldItem {
    data class Home(val source: VideoSource) : HoldItem
    data object Settings : HoldItem
}

private val Items: List<HoldItem> = VideoSource.entries.map { HoldItem.Home(it) } + HoldItem.Settings

private val ItemSize = 52.dp
private val ItemStep = 68.dp
private val FirstGap = 20.dp

/** The hold gesture's state, its modifier for the avatar, and [HoldFan] to draw it from inside that avatar. */
internal class HoldMenu {
    var holding by mutableStateOf(false)
    var hover by mutableIntStateOf(-1)
    var anchorW by mutableIntStateOf(0)
    var anchorH by mutableIntStateOf(0)
    var modifier: Modifier = Modifier
}

/**
 * Press and hold the avatar: the accounts (and 设置) drop out of it in a column, one after another;
 * the finger slides down along them, each one it meets swells and ticks; letting go opens it, letting go
 * anywhere else does nothing. A short tap still opens the panel (the gesture only takes over once
 * the hold is long enough).
 */
@Composable
internal fun rememberHoldMenu(onHome: (VideoSource) -> Unit, onSettings: () -> Unit): HoldMenu {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val state = remember { HoldMenu() }
    val home by rememberUpdatedState(onHome)
    val settings by rememberUpdatedState(onSettings)

    fun itemCenterY(i: Int): Float = itemCenterY(state, density, i)

    state.modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.anchorW = size.width
            state.anchorH = size.height
            // Up, or a drag, before the hold time: not ours (the tap opens the panel).
            val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.first()
                    if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            if (early != null) return@awaitEachGesture
            state.holding = true
            state.hover = -1
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val step = with(density) { ItemStep.toPx() }
            val reachY = with(density) { 72.dp.toPx() }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                val c = event.changes.first()
                val p = c.position
                val near = Items.indices.minByOrNull { abs(itemCenterY(it) - p.y) } ?: -1
                val hit = near.takeIf { it >= 0 && abs(itemCenterY(it) - p.y) < step / 2 && abs(p.x - state.anchorW / 2f) < reachY * 2.5f } ?: -1
                if (hit != state.hover) {
                    state.hover = hit
                    if (hit >= 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                if (!c.pressed) break
            }
            val chosen = Items.getOrNull(state.hover)
            state.holding = false
            state.hover = -1
            when (chosen) {
                is HoldItem.Home -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    home(chosen.source)
                }
                HoldItem.Settings -> {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    settings()
                }
                null -> Unit
            }
        }
    }

    return state
}

private fun itemCenterY(state: HoldMenu, density: androidx.compose.ui.unit.Density, i: Int): Float =
    with(density) { state.anchorH + FirstGap.toPx() + ItemSize.toPx() / 2 + i * ItemStep.toPx() }

/**
 * The avatar sits at the window's right edge (the side rail on the right): the fan opens toward
 * the window, its labels on the items' left.
 */
internal val LocalHoldFanEnd = androidx.compose.runtime.compositionLocalOf { false }

/** The fan itself; call it inside the avatar's box, so its popup starts at the avatar. */
@Composable
internal fun HoldFan(state: HoldMenu) {
    val density = LocalDensity.current
    val holding = state.holding
    val hover = state.hover
    val anchorW = state.anchorW
    val anchorH = state.anchorH
    fun itemCenterY(i: Int): Float = itemCenterY(state, density, i)
    val end = LocalHoldFanEnd.current
    val fanW = with(density) { FanWidth.toPx() }
    val shown by animateFloatAsState(if (holding) 1f else 0f, YoinMotion.defaultSpatialSpec(), label = "holdShown")
    if (shown > 0.001f) {
        Popup(alignment = if (end) Alignment.TopEnd else Alignment.TopStart, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
            // The popup window is as big as this box: room for the whole column and its labels.
            Box(Modifier.size(FanWidth, with(density) { (anchorH + FirstGap.toPx() + Items.size * ItemStep.toPx() + 16.dp.toPx()).toDp() })) {
                Items.forEachIndexed { i, item ->
                    // One after another: each item starts a little later than the one before.
                    val local = ((shown - i * 0.12f) / (1f - (Items.size - 1) * 0.12f)).coerceIn(0f, 1f)
                    val lift by animateFloatAsState(if (hover == i) 1f else 0f, YoinMotion.fastSpatialSpec(), label = "holdLift")
                    val cy = itemCenterY(i)
                    val half = with(density) { ItemSize.toPx() / 2 }
                    val y = ((cy - half) * local + (anchorH / 2f - half) * (1f - local)).roundToInt()
                    // Mirrored at the right edge: the item under the avatar, its label before it.
                    androidx.compose.foundation.layout.Row(
                        Modifier.layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            val itemX = if (end) fanW - anchorW / 2f - half else anchorW / 2f - half
                            val x = if (end) itemX + 2 * half - p.width else itemX
                            layout(p.width, p.height) { p.place(x.roundToInt(), y) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (end) HoldLabel(item, lift, end = true)
                        Box(
                            Modifier
                                .size(ItemSize)
                                .graphicsLayer {
                                    val sc = (0.4f + 0.6f * local) * (1f + 0.25f * lift)
                                    scaleX = sc
                                    scaleY = sc
                                    alpha = local
                                }
                                .shadow(if (lift > 0.5f) 8.dp else 3.dp, CircleShape)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (item) {
                                is HoldItem.Home -> HoldAvatar(item.source)
                                HoldItem.Settings -> Icon(YoinSymbols.Settings, "设置", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        if (!end) HoldLabel(item, lift, end = false)
                    }
                }
            }
        }
    }
}

/** The label beside the item the finger is on (after it, or before it at the right edge). */
@Composable
private fun HoldLabel(item: HoldItem, lift: Float, end: Boolean) {
    Text(
        when (item) {
            is HoldItem.Home -> item.source.label
            HoldItem.Settings -> "设置"
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .graphicsLayer {
                alpha = lift
                val out = (1f - lift) * -8.dp.toPx() + 10.dp.toPx()
                translationX = if (end) -out else out
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private val FanWidth = 240.dp

@Composable
private fun HoldAvatar(source: VideoSource) {
    SourceDotForMenu(source, ItemSize)
}
