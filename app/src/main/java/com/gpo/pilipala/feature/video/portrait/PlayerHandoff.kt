package com.gpo.pilipala.feature.video.portrait

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.toSize
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The picture's trip between the classic page and the immersive pager (one page visit).
 *
 * Both layouts report where their picture sits ([classicRect] / [immersiveRect], window
 * coordinates). Switching starts a [Flight]: while it runs the player is drawn by
 * [PlayerFlightLayer] over both layouts, springing from the old picture's rect to the new one,
 * and the layouts show an empty slot; when it lands the player moves into the new slot. The
 * player itself (one `movableContentOf` over the page's single controller) never reloads.
 */
@Stable
internal class PlayerHandoff {
    var classicRect by mutableStateOf<Rect?>(null)
    var immersiveRect by mutableStateOf<Rect?>(null)
    var flight by mutableStateOf<Flight?>(null)

    /** The immersive pager was opened from the classic page (back returns there). */
    var fromClassic by mutableStateOf(false)

    /** Starts the picture's trip into the immersive pager (from the classic slot, if known). */
    fun toImmersive() {
        val from = classicRect
        immersiveRect = null
        flight = from?.let { Flight(it, toImmersive = true) }
    }

    /** Starts the picture's trip into the classic page's slot (from the immersive picture, if known). */
    fun toClassic() {
        val from = immersiveRect
        classicRect = null
        flight = from?.let { Flight(it, toImmersive = false) }
    }
}

@Stable
internal class Flight(val from: Rect, val toImmersive: Boolean) {
    val progress = Animatable(0f)
}

/** Reports this node's unclipped rect in the window (a cropped picture keeps its full size). */
internal fun Modifier.reportWindowRect(onRect: (Rect) -> Unit): Modifier = onGloballyPositioned {
    onRect(Rect(it.positionInWindow(), it.size.toSize()))
}

/**
 * Draws [player] at the flying rect: from [Flight.from] to [target] (read live, so a layout
 * still settling is followed), on the spatial spring. [onLanded] ends the flight.
 */
@Composable
internal fun PlayerFlightLayer(
    flight: Flight,
    target: Rect?,
    onLanded: () -> Unit,
    player: @Composable (Modifier) -> Unit,
) {
    val latestTarget by rememberUpdatedState(target)
    val latestLanded by rememberUpdatedState(onLanded)
    val spec = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(flight) {
        // The new layout reports its slot on its first layout pass; a slot that never shows
        // (the layout bailed out) just ends the flight.
        val ready = withTimeoutOrNull(600) { snapshotFlow { latestTarget }.first { it != null } }
        if (ready != null) flight.progress.animateTo(1f, spec)
        latestLanded()
    }
    val origin = remember { arrayOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin[0] = it.positionInWindow() },
    ) {
        player(
            Modifier.layout { measurable, constraints ->
                val to = latestTarget ?: flight.from
                val p = flight.progress.value
                val topLeft = lerp(flight.from.topLeft, to.topLeft, p) - origin[0]
                val size = lerp(flight.from.size, to.size, p)
                val w = size.width.roundToInt().coerceAtLeast(1)
                val h = size.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(topLeft.x.roundToInt(), topLeft.y.roundToInt())
                }
            },
        )
    }
}
