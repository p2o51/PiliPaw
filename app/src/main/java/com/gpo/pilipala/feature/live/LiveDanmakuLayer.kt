package com.gpo.pilipala.feature.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

private class Flying(
    val id: Long,
    val text: String,
    val color: Color,
    val lane: Int,
    val start: Long,
    val widthPx: Float,
)

/**
 * Right-to-left scrolling danmaku over the live video. A single frame clock
 * drives every item's x (read only in the layout phase), lanes are reused
 * once the previous item's tail has cleared the right edge.
 */
@Composable
internal fun LiveDanmakuLayer(
    incoming: SharedFlow<List<LiveChat>>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = remember { mutableStateListOf<Flying>() }
    var now by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyLarge.copy(
        shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 3f),
    )
    val fontPx = with(density) { style.fontSize.toPx() }
    val laneHeightPx = with(density) { 28.dp.toPx() }
    val speedPx = with(density) { 110.dp.toPx() } // per second

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val lanes = ((with(density) { maxHeight.toPx() } * 0.5f) / laneHeightPx).toInt().coerceAtLeast(1)
        var counter by remember { mutableLongStateOf(0L) }

        LaunchedEffect(incoming, enabled, widthPx, lanes) {
            if (!enabled) {
                items.clear()
                return@LaunchedEffect
            }
            incoming.collect { batch ->
                val t = System.nanoTime()
                batch.take(12).forEach { chat ->
                    val w = chat.text.length * fontPx * 1.05f
                    // Pick the first lane whose last item has fully entered the screen.
                    val lane = (0 until lanes).firstOrNull { l ->
                        val last = items.lastOrNull { it.lane == l } ?: return@firstOrNull true
                        val travelled = (t - last.start) / 1e9f * speedPx
                        travelled > last.widthPx + fontPx * 2
                    } ?: return@forEach
                    counter++
                    items += Flying(counter, chat.text, Color(0xFF000000.toInt() or chat.color), lane, t, w)
                }
            }
        }
        LaunchedEffect(enabled) {
            while (enabled) {
                if (items.isEmpty()) {
                    delay(120)
                    continue
                }
                withFrameNanos { frame -> now = frame }
                val t = now
                items.removeAll { (t - it.start) / 1e9f * speedPx > widthPx + it.widthPx + 40f }
            }
        }

        if (enabled) {
            items.forEach { f ->
                Box(
                    Modifier.offset {
                        val t = now
                        val x = widthPx - (t - f.start) / 1e9f * speedPx
                        IntOffset(x.toInt(), (f.lane * laneHeightPx + laneHeightPx * 0.3f).toInt())
                    },
                ) {
                    Text(f.text, style = style, color = f.color, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
