package com.gpo.pilipala.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
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
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * One danmaku. Built from a protobuf `DanmakuElem` (seg.so), an XML `<d p="…">` entry
 * (list.so fallback) or a line the user just sent ([mine]).
 */
internal class DanmakuItem(
    val timeMs: Long,
    val mode: Int,
    val size: Int,
    val color: Int,
    val text: String,
    /** bilibili's 屏蔽等级 weight, 1..11 (XML danmaku carry none → 10). */
    val weight: Int = 10,
    val id: Long = 0L,
    val midHash: String = "",
    val pool: Int = 0,
    val mine: Boolean = false,
) {
    fun withText(newText: String) = DanmakuItem(timeMs, mode, size, color, newText, weight, id, midHash, pool, mine)
}

/** Danmaku modes as sent to /x/v2/dm/post. */
internal object DanmakuMode {
    const val SCROLL = 1
    const val BOTTOM = 4
    const val TOP = 5
}

internal object DanmakuApi {
    /** seg.so serves 6-minute segments, 1-based. */
    const val SEGMENT_MS = 6 * 60 * 1000L

    private val entry = Regex("""<d p="([^"]*)">([^<]*)</d>""")

    /**
     * Called by the controller on every load. Danmaku are now loaded lazily, segment by
     * segment, by [DanmakuLayer] from the playback position (so a resume at 40:00 never
     * downloads the first 40 minutes); this only primes the per-cid feed.
     */
    @Suppress("RedundantSuspendModifier")
    suspend fun fetch(cid: Long): List<DanmakuItem> {
        DanmakuFeeds.get(cid)
        return emptyList()
    }

    /** One protobuf segment (`index` is 0-based; the API's segment_index is 1-based). */
    suspend fun fetchSegment(cid: Long, index: Int): List<DanmakuItem> = withContext(Dispatchers.IO) {
        // seg.so answers -352 (risk control) without the buvid3 device cookie.
        BiliClient.ensureBuvid()
        val bytes = BiliClient.getBytes(
            "https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=$cid&segment_index=${index + 1}",
            BiliClient.mediaHeaders(),
        )
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            // A JSON envelope instead of protobuf: an error (-352, -404 …).
            val root = BiliClient.parseEnvelope(bytes.toString(Charsets.UTF_8))
            throw BiliException(root.long("code", -1).toInt(), "danmaku segment ${index + 1}")
        }
        if (bytes.isNotEmpty() && bytes[0] == '<'.code.toByte()) {
            // An HTML error page (412 risk control and friends).
            throw BiliException(-412, "danmaku segment ${index + 1}")
        }
        DanmakuProto.decodeSegment(bytes)
    }

    /** Whole-video XML list (list.so). Truncated on long videos; only a fallback now. */
    suspend fun fetchXml(cid: Long): List<DanmakuItem> = withContext(Dispatchers.IO) {
        val bytes = BiliClient.getBytes(
            "https://api.bilibili.com/x/v1/dm/list.so?oid=$cid",
            BiliClient.mediaHeaders(),
        )
        parse(inflate(bytes))
    }

    /**
     * POST /x/v2/dm/post. Returns the new dmid. `rnd` (µs timestamp) keeps the
     * cooldown at 5s instead of 90s.
     */
    suspend fun send(
        target: PlayTarget,
        text: String,
        progressMs: Long,
        color: Int,
        mode: Int,
    ): Long {
        val form = buildMap<String, Any?> {
            put("type", 1)
            put("oid", target.cid)
            put("msg", text)
            if (target.aid > 0) put("aid", target.aid)
            if (target.bvid.isNotBlank()) put("bvid", target.bvid)
            put("progress", progressMs.coerceAtLeast(0L))
            put("color", color and 0xFFFFFF)
            put("fontsize", 25)
            put("pool", 0)
            put("mode", mode)
            put("rnd", System.currentTimeMillis() * 1000)
        }
        val data = BiliClient.postData("/x/v2/dm/post", form).asObj
        return data.long("dmid")
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
                pool = p.getOrNull(5)?.toIntOrNull() ?: 0,
                midHash = p.getOrNull(6).orEmpty(),
                id = p.getOrNull(7)?.toLongOrNull() ?: 0L,
                weight = p.getOrNull(8)?.toIntOrNull() ?: 10,
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

/**
 * Minimal protobuf reader for `DmSegMobileReply { repeated DanmakuElem elems = 1; … }`
 * (lib/models/danmaku/dm.proto). Only varint and length-delimited fields are read;
 * everything else is skipped by wire type.
 */
internal object DanmakuProto {
    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= end) throw IllegalStateException("truncated varint")
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalStateException("malformed varint")
        }

        fun length(): Int {
            val n = varint().toInt()
            if (n < 0 || pos + n > end) throw IllegalStateException("bad length")
            return n
        }

        fun string(): String {
            val n = length()
            val s = String(buf, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> {
                    // Read the length first: `pos += length()` would add it to the pre-varint pos.
                    val n = length()
                    pos += n
                }
                5 -> pos += 4
                else -> throw IllegalStateException("wire type $wireType")
            }
        }
    }

    fun decodeSegment(bytes: ByteArray): List<DanmakuItem> {
        val r = Reader(bytes, 0, bytes.size)
        val out = ArrayList<DanmakuItem>(2048)
        while (r.more()) {
            val tag = r.varint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 1 && wire == 2) {
                val len = r.length()
                val start = r.pos
                runCatching { decodeElem(bytes, start, start + len) }.getOrNull()?.let(out::add)
                r.pos = start + len
            } else {
                r.skip(wire)
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    private fun decodeElem(bytes: ByteArray, from: Int, to: Int): DanmakuItem? {
        val r = Reader(bytes, from, to)
        var id = 0L
        var progress = 0L
        var mode = 1
        var fontSize = 25
        var color = 0xFFFFFF
        var midHash = ""
        var content = ""
        var weight = 0
        var pool = 0
        var idStr = ""
        while (r.more()) {
            val tag = r.varint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 0 -> id = r.varint()
                field == 2 && wire == 0 -> progress = r.varint().toInt().toLong()
                field == 3 && wire == 0 -> mode = r.varint().toInt()
                field == 4 && wire == 0 -> fontSize = r.varint().toInt()
                field == 5 && wire == 0 -> color = r.varint().toInt()
                field == 6 && wire == 2 -> midHash = r.string()
                field == 7 && wire == 2 -> content = r.string()
                field == 9 && wire == 0 -> weight = r.varint().toInt()
                field == 11 && wire == 0 -> pool = r.varint().toInt()
                field == 12 && wire == 2 -> idStr = r.string()
                // ctime(8), attr(13) and the rest are not needed for drawing.
                else -> r.skip(wire)
            }
        }
        // 7 positioned / 8 code / 9 BAS danmaku need a script engine.
        if (mode == 7 || mode == 8 || mode == 9) return null
        val text = content.trim()
        if (text.isEmpty()) return null
        return DanmakuItem(
            timeMs = progress.coerceAtLeast(0L),
            mode = mode,
            size = fontSize,
            color = color,
            text = text,
            weight = weight,
            id = if (id != 0L) id else idStr.toLongOrNull() ?: 0L,
            midHash = midHash,
            pool = pool,
        )
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
    /** Plate behind the viewer's own danmaku (a colour block, not an outline). */
    val mineColor: Color,
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
    private val pendingInject = ArrayList<DanmakuItem>(2)
    private var lastPos = -1L
    private var catchUpUntil = -1L
    private var generation = -1
    private var scrollLanes = arrayOfNulls<Active>(0)
    private var topLanes = arrayOfNulls<Active>(0)
    private var bottomLanes = arrayOfNulls<Active>(0)
    private var lastWidth = 0f
    private var lastHeight = 0f
    private var lastLineKey = 0f

    /**
     * A new list (a segment arrived, filters changed): keep what is on screen and
     * re-walk the last ~1.2s so items that landed just behind the clock still show.
     */
    fun setItems(list: List<DanmakuItem>) {
        if (list === items) return
        items = list
        if (lastPos < 0) {
            cursor = 0
            return
        }
        catchUpUntil = lastPos
        cursor = lowerBound(lastPos - 1200)
    }

    /** Show a just-sent danmaku now, wherever the clock is. */
    fun inject(item: DanmakuItem) {
        pendingInject += item
    }

    private fun reset(pos: Long) {
        active.clear()
        scrollLanes.fill(null)
        topLanes.fill(null)
        bottomLanes.fill(null)
        cursor = lowerBound(pos - 150)
        catchUpUntil = -1L
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

    private fun isOnScreen(item: DanmakuItem): Boolean {
        for (a in active) {
            if (a.item === item) return true
            if (item.id != 0L && a.item.id == item.id) return true
        }
        return false
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

        val bottomBase = h - with(density) { 8.dp.toPx() }

        /** A live top / bottom line sits across scroll lane [i]: nothing scrolls in under it. */
        fun fixedBlocks(i: Int): Boolean {
            val t = i * lineH
            val b = t + lineH
            for (j in topLanes.indices) {
                val f = topLanes[j] ?: continue
                if (pos - f.spawnMs > fixedDur) continue
                val ft = j * lineH
                if (ft < b && ft + lineH > t) return true
            }
            for (j in bottomLanes.indices) {
                val f = bottomLanes[j] ?: continue
                if (pos - f.spawnMs > fixedDur) continue
                val fb = bottomBase - j * lineH
                if (fb - lineH < b && fb > t) return true
            }
            return false
        }

        /**
         * Whether a scrolling line in the rows across [top, bottom] covers [left, right] at any
         * point in the next [fixedDur]: a still line there would sit on top of it.
         */
        fun scrollCrosses(top: Float, bottom: Float, left: Float, right: Float): Boolean {
            for (s in active) {
                if (s.kind != 0) continue
                val st = s.lane * lineH
                if (st >= bottom || st + lineH <= top) continue
                val xNow = w - (w + s.width) * (pos - s.spawnMs).toFloat() / scrollDur
                val xEnd = w - (w + s.width) * (pos + fixedDur - s.spawnMs).toFloat() / scrollDur
                if (xEnd < right && xNow + s.width > left) return true
            }
            return false
        }

        fun spawn(it: DanmakuItem, force: Boolean) {
            val kind = when (it.mode) {
                DanmakuMode.BOTTOM -> 2
                DanmakuMode.TOP -> 1
                else -> 0
            }
            if (!force) {
                if ((kind == 0 && cfg.hideScroll) || (kind == 1 && cfg.hideTop) || (kind == 2 && cfg.hideBottom)) return
                if (active.size > 220) return
            }
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
                        if (fixedBlocks(i)) continue
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
                    if (lane < 0 && force && scrollLanes.isNotEmpty()) lane = 0
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
                        if (last != null && pos - last.spawnMs <= fixedDur) continue
                        val t = if (kind == 1) i * lineH else bottomBase - (i + 1) * lineH
                        if (scrollCrosses(t, t + lineH, (w - tw) / 2f, (w + tw) / 2f)) continue
                        lane = i
                        break
                    }
                    if (lane < 0 && force && lanes.isNotEmpty()) lane = 0
                    if (lane >= 0) {
                        val a = Active(it, layout, tw, pos, lane, kind)
                        lanes[lane] = a
                        active += a
                    }
                }
            }
        }

        // Just-sent lines first, then the timeline.
        if (pendingInject.isNotEmpty()) {
            for (it in pendingInject) spawn(it, force = true)
            pendingInject.clear()
        }
        while (cursor < items.size && items[cursor].timeMs <= pos) {
            val it = items[cursor++]
            if (pos - it.timeMs > 1200) continue
            if (it.timeMs <= catchUpUntil && isOnScreen(it)) continue
            spawn(it, force = false)
        }

        // Expire + draw.
        // B站's 描边: a hairline edge (about 0.5dp outside each glyph; the opaque fill covers
        // the inner half), so pale danmaku read on bright frames. Dark on dark text, light on
        // dark text, so a green or blue line never turns into a dark block. No blur and no
        // thicker stroke: on dense Chinese glyphs either fills the counters.
        val outline = Stroke(width = with(density) { 1.dp.toPx() }, join = StrokeJoin.Round)
        val platePadX = with(density) { 6.dp.toPx() }
        val platePadY = with(density) { 1.dp.toPx() }
        // Scrolling lines first, fixed (top / bottom) ones over them: where the two meet, the
        // still line stays readable instead of the glyphs mixing.
        for (pass in 0..1) {
            val iter = active.iterator()
            while (iter.hasNext()) {
                val a = iter.next()
                if ((a.kind == 0) != (pass == 0)) continue
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
                if (a.item.mine) {
                    val ph = a.layout.size.height + platePadY * 2
                    drawRoundRect(
                        color = cfg.mineColor,
                        topLeft = Offset(x - platePadX, y - platePadY),
                        size = Size(a.width + platePadX * 2, ph),
                        cornerRadius = CornerRadius(ph / 2f),
                        alpha = 0.55f,
                    )
                }
                val dark = color.luminance() < 0.18f
                drawText(
                    textLayoutResult = a.layout,
                    color = if (dark) Color.White else Color.Black,
                    topLeft = topLeft,
                    alpha = if (dark) 0.7f else 0.6f,
                    drawStyle = outline,
                )
                // Opaque over its own outline; the layer applies the opacity to both at once.
                // Fill must be explicit: a null drawStyle keeps the stroke the outline left on the
                // layout's paint, and the glyphs come out hollow.
                drawText(
                    textLayoutResult = a.layout,
                    color = color,
                    topLeft = topLeft,
                    drawStyle = Fill,
                )
            }
        }
        lastPos = pos
    }
}

/**
 * Canvas overlay synced to the player clock. Only the draw phase runs per
 * frame (no recomposition); frames are requested only while playing.
 * Segments are fetched around the playback position (see [DanmakuFeed]) and
 * run through the block rules / level / merge filters off the main thread.
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
    val mineColor = MaterialTheme.colorScheme.primary
    val cfg = DanmakuConfig(opacity, fontScale, area, speed, hideTop, hideBottom, hideScroll, big, mineColor)

    val cid = ctrl.target?.cid ?: 0L
    val feed = remember(cid) { if (cid > 0) DanmakuFeeds.get(cid) else null }
    DanmakuSegmentLoader(ctrl, feed)

    val spec = rememberDanmakuFilterSpec()
    val raw = feed?.items ?: emptyList()
    val shown by produceState(initialValue = emptyList<DanmakuItem>(), raw, spec) {
        value = if (spec.isNoop) raw else withContext(Dispatchers.Default) { DanmakuFilter.apply(raw, spec) }
    }

    val engine = remember(cid) { DanmakuEngine() }
    LaunchedEffect(feed, engine) {
        feed?.sent?.collect { engine.inject(it) }
    }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val baseStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    val frame = remember { mutableLongStateOf(0L) }
    val playing = ctrl.isPlaying
    LaunchedEffect(playing) {
        if (playing) {
            while (isActive) withFrameNanos { frame.longValue = it }
        }
    }
    val items = shown
    // Opacity goes on the whole layer, not on each draw: a see-through fill lets its own
    // outline's inner half show through, and on thin Chinese strokes that greys the glyph out.
    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = cfg.opacity
                compositingStrategy = CompositingStrategy.Offscreen
            },
    ) {
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
