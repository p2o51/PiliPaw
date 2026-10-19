package com.gpo.pilipala.feature.player

import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CancellationException

/**
 * 高能进度条: how much the audience leans in at each moment, [values] normalised to 0..1,
 * one per [stepMs] from the start.
 */
@Immutable
internal class HeatCurve(val stepMs: Long, val values: FloatArray)

internal object HeatApi {
    private val cache = LruCache<Long, HeatCurve>(24)
    private val none = HashSet<Long>()

    /** Buckets across the video; a few seconds each on a typical upload. */
    private const val Buckets = 120

    /** Fewer danmaku than this say nothing about where the audience leans in. */
    private const val MinDanmaku = 30

    /**
     * The curve for [target], cached per cid; null when the video has too few danmaku.
     * The old pbp endpoint (bvc.bilibili.com) no longer resolves, so the heat is the
     * danmaku density of the whole-video list.so sample, the way the web's 弹幕密度 is drawn.
     */
    suspend fun get(target: PlayTarget): HeatCurve? {
        val cid = target.cid
        if (cid <= 0) return null
        cache.get(cid)?.let { return it }
        synchronized(none) { if (cid in none) return null }
        val curve = try {
            density(DanmakuApi.fetchXml(cid).map { it.timeMs })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Unreachable or unreadable: try again next time the video opens.
            return null
        }
        if (curve == null) synchronized(none) { none += cid } else cache.put(cid, curve)
        return curve
    }

    private fun density(times: List<Long>): HeatCurve? {
        if (times.size < MinDanmaku) return null
        val last = times.max().coerceAtLeast(1L)
        val step = (last + Buckets) / Buckets
        val raw = FloatArray(Buckets)
        for (t in times) raw[(t / step).toInt().coerceIn(0, Buckets - 1)] += 1f
        val max = raw.max()
        if (!(max > 0f)) return null
        // A light three-tap blur, so the area reads as a soft hill rather than a bar chart.
        val n = raw.size
        val values = FloatArray(n) { i ->
            val a = raw[(i - 1).coerceAtLeast(0)]
            val b = raw[i]
            val c = raw[(i + 1).coerceAtMost(n - 1)]
            ((a + 2f * b + c) / 4f / max).coerceIn(0f, 1f)
        }
        return HeatCurve(step, values)
    }
}

/**
 * Draws [heat] as a soft filled area standing on the seek track's top edge (no outline).
 * The outline is built once per size / curve / duration ([drawWithCache]) as a unit-high
 * shape; each frame only places and stretches it: it stands up to [height] tall over a
 * [baseline] (px from the top, given the box height) that follows the thickening track, and
 * [alpha] fades it.
 */
internal fun Modifier.heatArea(
    heat: HeatCurve?,
    durationMs: Long,
    color: Color,
    height: Dp,
    baseline: Density.(height: Float) -> Float,
    alpha: () -> Float,
): Modifier = if (heat == null || durationMs <= 0L) {
    this
} else {
    drawWithCache {
        val path = heatPath(heat, durationMs, size.width)
        val h = height.toPx()
        onDrawBehind {
            val a = alpha()
            if (a <= 0.01f) return@onDrawBehind
            val base = baseline(size.height)
            translate(top = base) {
                scale(scaleX = 1f, scaleY = minOf(h, base), pivot = Offset.Zero) {
                    drawPath(path, color.copy(alpha = color.alpha * a))
                }
            }
        }
    }
}

/** The area as a path in x = 0..[width], y = 0 (base) .. -1 (the hottest moment). */
private fun heatPath(heat: HeatCurve, durationMs: Long, width: Float): Path {
    val path = Path()
    val v = heat.values
    if (width <= 0f || v.isEmpty()) return path
    fun x(i: Int) = ((i * heat.stepMs + heat.stepMs / 2f) / durationMs * width).coerceIn(0f, width)
    path.moveTo(0f, 0f)
    path.lineTo(0f, -v[0])
    var px = 0f
    var py = -v[0]
    for (i in v.indices) {
        val cx = x(i)
        if (cx >= width && px >= width) break
        val cy = -v[i]
        // Through the midpoints, with each sample as the control point: a smooth hill.
        val mx = (px + cx) / 2f
        val my = (py + cy) / 2f
        path.quadraticTo(px, py, mx, my)
        px = cx
        py = cy
    }
    path.quadraticTo(px, py, width, py)
    path.lineTo(width, 0f)
    path.close()
    return path
}
