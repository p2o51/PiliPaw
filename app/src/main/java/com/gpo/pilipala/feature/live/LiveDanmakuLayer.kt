package com.gpo.pilipala.feature.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.pilipala.core.settings.AppSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive

/** The global 弹幕 settings, as the overlay reads them. */
private data class LiveDanmakuConfig(
    val opacity: Float,
    val fontScale: Float,
    val area: Float,
    val speed: Float,
    val hideTop: Boolean,
    val hideBottom: Boolean,
    val hideScroll: Boolean,
    val big: Boolean,
)

/**
 * Lane-allocating engine for live danmaku. Items queue as they arrive, spawn
 * on the next frame that has a free lane, and are dropped if they wait too
 * long (a busy room just shows fewer). Widths come from the real text layout.
 */
private class LiveDanmakuEngine {
    private class Active(
        val text: String,
        val color: Int,
        val layout: TextLayoutResult,
        val width: Float,
        val spawnMs: Long,
        val lane: Int,
        val kind: Int, // 0 scroll, 1 top, 2 bottom
    )

    private class Pending(val chat: LiveChat, val atMs: Long)

    private val pending = ArrayDeque<Pending>()
    private val active = ArrayList<Active>(96)
    private var scrollLanes = arrayOfNulls<Active>(0)
    private var topLanes = arrayOfNulls<Active>(0)
    private var bottomLanes = arrayOfNulls<Active>(0)
    private var lastW = 0f
    private var lastH = 0f
    private var lastLineH = 0f
    private var lastArea = 0f

    val busy: Boolean get() = active.isNotEmpty() || pending.isNotEmpty()

    fun offer(list: List<LiveChat>, nowMs: Long) {
        list.forEach { pending.addLast(Pending(it, nowMs)) }
        while (pending.size > 60) pending.removeFirst()
    }

    fun clear() {
        pending.clear()
        active.clear()
        scrollLanes.fill(null)
        topLanes.fill(null)
        bottomLanes.fill(null)
    }

    fun draw(scope: DrawScope, now: Long, cfg: LiveDanmakuConfig, measurer: TextMeasurer, base: TextStyle) = with(scope) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@with
        val fontSp = (if (cfg.big) 21f else 16f) * cfg.fontScale
        val lineH = (fontSp * 1.45f).sp.toPx() + 2.dp.toPx()
        if (w != lastW || h != lastH || lineH != lastLineH || cfg.area != lastArea) {
            lastW = w
            lastH = h
            lastLineH = lineH
            lastArea = cfg.area
            scrollLanes = arrayOfNulls(((h * cfg.area) / lineH).toInt().coerceAtLeast(1))
            val fixed = ((h * 0.5f) / lineH).toInt().coerceAtLeast(1)
            topLanes = arrayOfNulls(fixed)
            bottomLanes = arrayOfNulls(fixed)
            active.clear()
        }
        val scrollDur = (8000f / cfg.speed.coerceIn(0.3f, 3f)).toLong()
        val fixedDur = 4000L
        val gap = 24.dp.toPx()

        // Spawn what fits; keep the rest queued for a moment.
        val style = base.copy(fontSize = fontSp.sp)
        var tries = pending.size
        while (tries-- > 0) {
            val p = pending.removeFirst()
            if (now - p.atMs > 1500) continue
            val kind = when (p.chat.mode) {
                4 -> 2
                5 -> 1
                else -> 0
            }
            if ((kind == 0 && cfg.hideScroll) || (kind == 1 && cfg.hideTop) || (kind == 2 && cfg.hideBottom)) continue
            val layout = measurer.measure(p.chat.text, style = style, maxLines = 1, softWrap = false)
            val tw = layout.size.width.toFloat()
            val lanes = when (kind) {
                0 -> scrollLanes
                1 -> topLanes
                else -> bottomLanes
            }
            var lane = -1
            for (i in lanes.indices) {
                val last = lanes[i]
                val free = when {
                    last == null -> true
                    kind != 0 -> now - last.spawnMs > fixedDur
                    else -> {
                        val elapsed = (now - last.spawnMs).toFloat()
                        val entered = (w + last.width) * elapsed / scrollDur >= last.width + gap
                        // The new item must not catch the old one before it leaves.
                        val newHitsLeft = now + scrollDur * w / (w + tw)
                        entered && newHitsLeft >= last.spawnMs + scrollDur
                    }
                }
                if (free) {
                    lane = i
                    break
                }
            }
            if (lane < 0) {
                pending.addLast(p)
                continue
            }
            val a = Active(p.chat.text, p.chat.color, layout, tw, now, lane, kind)
            lanes[lane] = a
            active += a
        }

        val outline = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round)
        val iter = active.iterator()
        while (iter.hasNext()) {
            val a = iter.next()
            val elapsed = now - a.spawnMs
            val dur = if (a.kind == 0) scrollDur else fixedDur
            if (elapsed > dur) {
                iter.remove()
                continue
            }
            val x = if (a.kind == 0) w - (w + a.width) * elapsed.toFloat() / dur else (w - a.width) / 2f
            val y = when (a.kind) {
                2 -> h - (a.lane + 1) * lineH - 8.dp.toPx()
                else -> a.lane * lineH + (lineH - a.layout.size.height) / 2f
            }
            if (x > w || x + a.width < 0f) continue
            val topLeft = Offset(x, y)
            drawText(a.layout, color = Color.Black, topLeft = topLeft, alpha = cfg.opacity * 0.7f, drawStyle = outline)
            drawText(a.layout, color = Color(a.color or 0xFF000000.toInt()), topLeft = topLeft, alpha = cfg.opacity)
        }
    }
}

/**
 * Right-to-left danmaku over the live video, styled by the global 弹幕
 * settings (opacity, font size, area, speed, hidden modes). Only the draw
 * phase runs per frame; frames are requested only while something is on
 * screen or queued.
 */
@Composable
internal fun LiveDanmakuLayer(
    incoming: SharedFlow<List<LiveChat>>,
    enabled: Boolean,
    big: Boolean,
    modifier: Modifier = Modifier,
) {
    val opacity by AppSettings.danmakuOpacity.state.collectAsState()
    val fontScale by AppSettings.danmakuFontScale.state.collectAsState()
    val area by AppSettings.danmakuArea.state.collectAsState()
    val speed by AppSettings.danmakuSpeed.state.collectAsState()
    val hideTop by AppSettings.danmakuHideTop.state.collectAsState()
    val hideBottom by AppSettings.danmakuHideBottom.state.collectAsState()
    val hideScroll by AppSettings.danmakuHideScroll.state.collectAsState()
    val cfg by rememberUpdatedState(LiveDanmakuConfig(opacity, fontScale, area, speed, hideTop, hideBottom, hideScroll, big))

    val engine = remember { LiveDanmakuEngine() }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val base = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
    val frame = remember { mutableLongStateOf(0L) }

    val wake = remember { Channel<Unit>(Channel.CONFLATED) }

    LaunchedEffect(incoming, enabled) {
        engine.clear()
        if (!enabled) return@LaunchedEffect
        incoming.collect {
            // Frame time and System.nanoTime share the Choreographer clock.
            engine.offer(it, System.nanoTime() / 1_000_000)
            wake.trySend(Unit)
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (isActive) {
            if (!engine.busy) wake.receive()
            withFrameNanos { frame.longValue = it / 1_000_000 }
        }
    }
    if (!enabled) return
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        engine.draw(this, frame.longValue, cfg, measurer, base)
    }
}
