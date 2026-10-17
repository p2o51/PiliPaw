package com.gpo.pilipala.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** One `<d p="time,mode,size,color,...">text</d>` entry. */
internal class DanmakuItem(
    val timeMs: Long,
    val mode: Int,
    val size: Int,
    val color: Int,
    val text: String,
)

internal object DanmakuApi {
    private val entry = Regex("""<d p="([^"]*)">([^<]*)</d>""")

    suspend fun fetch(cid: Long): List<DanmakuItem> = withContext(Dispatchers.IO) {
        val bytes = BiliClient.getBytes(
            "https://api.bilibili.com/x/v1/dm/list.so?oid=$cid",
            BiliClient.mediaHeaders(),
        )
        parse(inflate(bytes))
    }

    /** list.so answers raw deflate (`Content-Encoding: deflate`), which OkHttp leaves alone. */
    private fun inflate(bytes: ByteArray): String {
        if (bytes.isNotEmpty() && bytes[0] == '<'.code.toByte()) return bytes.toString(Charsets.UTF_8)
        return runCatching {
            val inflater = Inflater(true)
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toString("UTF-8")
        }.getOrElse { bytes.toString(Charsets.UTF_8) }
    }

    fun parse(xml: String): List<DanmakuItem> {
        val list = ArrayList<DanmakuItem>(1024)
        for (m in entry.findAll(xml)) {
            val p = m.groupValues[1].split(',')
            if (p.size < 4) continue
            val mode = p[1].toIntOrNull() ?: continue
            if (mode == 7 || mode == 8 || mode == 9) continue
            val text = unescape(m.groupValues[2]).trim()
            if (text.isEmpty()) continue
            list += DanmakuItem(
                timeMs = ((p[0].toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                mode = mode,
                size = p[2].toIntOrNull() ?: 25,
                color = (p[3].toLongOrNull() ?: 0xFFFFFF).toInt(),
                text = text,
            )
        }
        list.sortBy { it.timeMs }
        return list
    }

    private fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
    }
}

internal data class DanmakuConfig(
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
 * Track-allocating danmaku engine. Spawns items as playback passes them,
 * keeps scrolling items from overlapping or catching up within a lane, and
 * drops what does not fit (bilibili's own density behaviour).
 */
internal class DanmakuEngine {
    private class Active(
        val item: DanmakuItem,
        val layout: TextLayoutResult,
        val width: Float,
        val spawnMs: Long,
        val lane: Int,
        val kind: Int, // 0 scroll, 1 top, 2 bottom
    )

    private var items: List<DanmakuItem> = emptyList()
    private var cursor = 0
    private val active = ArrayList<Active>(128)
    private var lastPos = -1L
    private var generation = -1
    private var scrollLanes = arrayOfNulls<Active>(0)
    private var topLanes = arrayOfNulls<Active>(0)
    private var bottomLanes = arrayOfNulls<Active>(0)
    private var lastWidth = 0f
    private var lastHeight = 0f
    private var lastLineKey = 0f

    fun setItems(list: List<DanmakuItem>) {
        if (list !== items) {
            items = list
            reset(lastPos.coerceAtLeast(0))
        }
    }

    private fun reset(pos: Long) {
        active.clear()
        scrollLanes.fill(null)
        topLanes.fill(null)
        bottomLanes.fill(null)
        cursor = lowerBound(pos - 150)
        lastPos = pos
    }

    private fun lowerBound(ms: Long): Int {
        var lo = 0
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].timeMs < ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun drawFrame(
        scope: DrawScope,
        pos: Long,
        gen: Int,
        cfg: DanmakuConfig,
        measurer: TextMeasurer,
        baseStyle: TextStyle,
        density: Density,
    ) = with(scope) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@with
        val baseSp = (if (cfg.big) 21f else 16f) * cfg.fontScale
        val lineH = with(density) { (baseSp * 1.45f).sp.toPx() } + with(density) { 2.dp.toPx() }
        if (w != lastWidth || h != lastHeight || lineH != lastLineKey) {
            lastWidth = w
            lastHeight = h
            lastLineKey = lineH
            val scroll = ((h * cfg.area) / lineH).toInt().coerceAtLeast(1)
            val fixed = ((h * 0.5f) / lineH).toInt().coerceAtLeast(1)
            scrollLanes = arrayOfNulls(scroll)
            topLanes = arrayOfNulls(fixed)
            bottomLanes = arrayOfNulls(fixed)
            active.clear()
        }
        val wantedScroll = ((h * cfg.area) / lineH).toInt().coerceAtLeast(1)
        if (wantedScroll != scrollLanes.size) {
            scrollLanes = arrayOfNulls(wantedScroll)
            active.removeAll { it.kind == 0 }
        }

        if (gen != generation || lastPos < 0 || pos < lastPos - 400 || pos > lastPos + 2500) {
            generation = gen
            reset(pos)
        }

        val scrollDur = (8000f / cfg.speed.coerceIn(0.3f, 3f)).toLong()
        val fixedDur = 4000L
        val gap = with(density) { 24.dp.toPx() }

        // Spawn.
        while (cursor < items.size && items[cursor].timeMs <= pos) {
            val it = items[cursor++]
            if (pos - it.timeMs > 1200) continue
            val kind = when (it.mode) {
                4 -> 2
                5 -> 1
                else -> 0
            }
            if ((kind == 0 && cfg.hideScroll) || (kind == 1 && cfg.hideTop) || (kind == 2 && cfg.hideBottom)) continue
            if (active.size > 220) continue
            val scale = when {
                it.size <= 18 -> 0.82f
                it.size >= 36 -> 1.3f
                else -> 1f
            }
            val layout = measurer.measure(
                text = it.text,
                style = baseStyle.copy(fontSize = (baseSp * scale).sp),
                maxLines = 1,
                softWrap = false,
            )
            val tw = layout.size.width.toFloat()
            when (kind) {
                0 -> {
                    var lane = -1
                    for (i in scrollLanes.indices) {
                        val last = scrollLanes[i]
                        if (last == null) {
                            lane = i
                            break
                        }
                        val elapsed = (pos - last.spawnMs).toFloat()
                        val travelled = (w + last.width) * elapsed / scrollDur
                        val entered = travelled >= last.width + gap
                        val newHitsLeft = pos + scrollDur * w / (w + tw)
                        val lastLeaves = last.spawnMs + scrollDur
                        if (entered && newHitsLeft >= lastLeaves) {
                            lane = i
                            break
                        }
                    }
                    if (lane >= 0) {
                        val a = Active(it, layout, tw, pos, lane, 0)
                        scrollLanes[lane] = a
                        active += a
                    }
                }
                else -> {
                    val lanes = if (kind == 1) topLanes else bottomLanes
                    var lane = -1
                    for (i in lanes.indices) {
                        val last = lanes[i]
                        if (last == null || pos - last.spawnMs > fixedDur) {
                            lane = i
                            break
                        }
                    }
                    if (lane >= 0) {
                        val a = Active(it, layout, tw, pos, lane, kind)
                        lanes[lane] = a
                        active += a
                    }
                }
            }
        }

        // Expire + draw.
        val outline = Stroke(width = with(density) { 1.6.dp.toPx() }, join = StrokeJoin.Round)
        val iter = active.iterator()
        while (iter.hasNext()) {
            val a = iter.next()
            val elapsed = pos - a.spawnMs
            val dur = if (a.kind == 0) scrollDur else fixedDur
            if (elapsed > dur || elapsed < -200) {
                iter.remove()
                continue
            }
            val x: Float
            val y: Float
            when (a.kind) {
                0 -> {
                    x = w - (w + a.width) * elapsed.toFloat() / dur
                    y = a.lane * lineH + (lineH - a.layout.size.height) / 2f
                }
                1 -> {
                    x = (w - a.width) / 2f
                    y = a.lane * lineH + (lineH - a.layout.size.height) / 2f
                }
                else -> {
                    x = (w - a.width) / 2f
                    y = h - (a.lane + 1) * lineH - with(density) { 8.dp.toPx() }
                }
            }
            if (x > w || x + a.width < 0f) continue
            val color = Color(a.item.color or 0xFF000000.toInt())
            val topLeft = Offset(x, y)
            drawText(
                textLayoutResult = a.layout,
                color = Color.Black,
                topLeft = topLeft,
                alpha = cfg.opacity * 0.7f,
                drawStyle = outline,
            )
            drawText(
                textLayoutResult = a.layout,
                color = color,
                topLeft = topLeft,
                alpha = cfg.opacity,
            )
        }
        lastPos = pos
    }
}

/**
 * Canvas overlay synced to the player clock. Only the draw phase runs per
 * frame (no recomposition); frames are requested only while playing.
 */
@Composable
internal fun DanmakuLayer(
    ctrl: PiliPlayerController,
    big: Boolean,
    modifier: Modifier = Modifier,
) {
    val enabled by AppSettings.danmakuEnabled.state.collectAsState()
    if (!enabled) return
    val opacity by AppSettings.danmakuOpacity.state.collectAsState()
    val fontScale by AppSettings.danmakuFontScale.state.collectAsState()
    val area by AppSettings.danmakuArea.state.collectAsState()
    val speed by AppSettings.danmakuSpeed.state.collectAsState()
    val hideTop by AppSettings.danmakuHideTop.state.collectAsState()
    val hideBottom by AppSettings.danmakuHideBottom.state.collectAsState()
    val hideScroll by AppSettings.danmakuHideScroll.state.collectAsState()
    val cfg = DanmakuConfig(opacity, fontScale, area, speed, hideTop, hideBottom, hideScroll, big)

    val engine = remember { DanmakuEngine() }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val baseStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
    val frame = remember { mutableLongStateOf(0L) }
    val playing = ctrl.isPlaying
    LaunchedEffect(playing) {
        if (playing) {
            while (isActive) withFrameNanos { frame.longValue = it }
        }
    }
    val items = ctrl.danmaku
    Canvas(modifier.fillMaxSize()) {
        frame.longValue
        engine.setItems(items)
        engine.drawFrame(
            scope = this,
            pos = ctrl.exactPositionMs(),
            gen = ctrl.seekGeneration,
            cfg = cfg,
            measurer = measurer,
            baseStyle = baseStyle,
            density = this,
        )
    }
}
