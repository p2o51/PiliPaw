package com.gpo.pilipala.feature.player

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.toBitmap
import kotlin.math.roundToInt
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asLong
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.double
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.theme.YoinShapeTokens

/** A chapter (bilibili "view point"): starts at [fromMs], titled [title]. */
internal data class Chapter(val fromMs: Long, val toMs: Long, val title: String)

/** The chapter that covers [ms], if any. */
internal fun List<Chapter>.at(ms: Long): Chapter? = lastOrNull { ms >= it.fromMs }

/**
 * Seek preview sprites (/x/player/videoshot): [images] are sprite sheets of [cols] × [rows]
 * tiles, each [tileW] × [tileH] px; [times] holds each tile's timestamp in seconds.
 */
internal class VideoShots(
    val images: List<String>,
    val cols: Int,
    val rows: Int,
    val tileW: Int,
    val tileH: Int,
    val times: IntArray,
) {
    val perImage: Int get() = cols * rows

    /** The tile shown for [ms]: the last one whose timestamp is at or before it. */
    fun frameAt(ms: Long): Int {
        if (times.isEmpty()) return 0
        val s = ms / 1000
        var lo = 0
        var hi = times.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= s) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found.coerceAtMost(images.size * perImage - 1).coerceAtLeast(0)
    }
}

internal object VideoShotsApi {
    private val cache = LruCache<Long, VideoShots>(24)

    /** Sprites for [target], cached per cid; null when the video has none. */
    suspend fun get(target: PlayTarget): VideoShots? {
        if (target.cid <= 0 || (target.bvid.isBlank() && target.aid <= 0)) return null
        cache.get(target.cid)?.let { return it }
        val params = buildMap<String, Any?> {
            if (target.bvid.isNotBlank()) put("bvid", target.bvid) else put("aid", target.aid)
            put("cid", target.cid)
            put("index", 1)
        }
        val data = BiliClient.getData("/x/player/videoshot", params).asObj
        val images = data.arr("image").map { Images.normalize(it.asString()) }.filter { it.isNotBlank() }
        val cols = data.int("img_x_len")
        val rows = data.int("img_y_len")
        val w = data.int("img_x_size")
        val h = data.int("img_y_size")
        if (images.isEmpty() || cols <= 0 || rows <= 0 || w <= 0 || h <= 0) return null
        // With index=1 the timestamps come inline; the first entry is a leading 0 that belongs
        // to no tile. Otherwise read them from pvdata (big-endian uint16, same layout).
        var stamps: List<Long> = data.arr("index").map { it.asLong() }
        if (stamps.isEmpty()) {
            val pv = data.str("pvdata")
            if (pv.isNotBlank()) {
                val bytes = runCatching { BiliClient.getBytes(BiliClient.resolve(pv), BiliClient.mediaHeaders()) }.getOrNull()
                if (bytes != null) {
                    stamps = (0 until bytes.size / 2).map { i ->
                        (((bytes[2 * i].toInt() and 0xFF) shl 8) or (bytes[2 * i + 1].toInt() and 0xFF)).toLong()
                    }
                }
            }
        }
        val times = stamps.drop(1).map { it.toInt() }.toIntArray()
        if (times.isEmpty()) return null
        return VideoShots(images, cols, rows, w, h, times).also { cache.put(target.cid, it) }
    }
}

/** Chapters from the wbi/v2 player info (`view_points`). */
internal fun parseChapters(data: kotlinx.serialization.json.JsonObject?): List<Chapter> =
    data.arr("view_points").objects().mapNotNull { p ->
        val from = (p.double("from") * 1000).toLong()
        val to = (p.double("to") * 1000).toLong()
        val title = p.str("content").trim()
        if (title.isEmpty() && to <= from) null else Chapter(from, to, title)
    }.sortedBy { it.fromMs }

/**
 * One tile of the sprite sheet for [timeMs], [width] wide (height from the tile's aspect).
 * Sheets are big (10 × 10 tiles of up to 480 × 270), so each is decoded only as large as
 * the tile is drawn, through the app's image loader (memory-cached per sheet). Draws a
 * dark plate until the sheet arrives.
 */
@Composable
internal fun SeekThumbnail(shots: VideoShots, timeMs: Long, width: Dp, modifier: Modifier = Modifier) {
    val frame = shots.frameAt(timeMs)
    val sheetIndex = frame / shots.perImage
    val url = shots.images.getOrNull(sheetIndex) ?: return
    val context = LocalContext.current
    val tileOutPx = with(LocalDensity.current) { width.roundToPx() }.coerceAtMost(shots.tileW).coerceAtLeast(1)
    val sheets = remember(shots) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(sheetIndex, tileOutPx) {
        val k = tileOutPx.toFloat() / shots.tileW
        suspend fun load(i: Int) {
            val u = shots.images.getOrNull(i) ?: return
            if (sheets.containsKey(u)) return
            val request = ImageRequest.Builder(context)
                .data(u)
                .size((shots.cols * shots.tileW * k).roundToInt(), (shots.rows * shots.tileH * k).roundToInt())
                .precision(Precision.INEXACT)
                .build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return
            sheets[u] = result.image.toBitmap().asImageBitmap()
        }
        // The sheet in view, then its neighbours so a scrub across a sheet edge never waits;
        // anything further goes back to Coil's cache.
        val keep = (sheetIndex - 1..sheetIndex + 1).mapNotNull { shots.images.getOrNull(it) }.toSet()
        sheets.keys.filter { it !in keep }.forEach { sheets.remove(it) }
        load(sheetIndex)
        load(sheetIndex + 1)
        load(sheetIndex - 1)
    }
    val tile = frame % shots.perImage
    val col = tile % shots.cols
    val row = tile / shots.cols
    // The last tile drawn bridges the moment a sheet is still decoding.
    val last = remember(shots) { arrayOfNulls<Any>(2) }
    val height = width * (shots.tileH.toFloat() / shots.tileW)
    Canvas(
        modifier
            .size(width, height)
            .clip(YoinShapeTokens.Medium)
            .background(Color.Black.copy(alpha = 0.6f)),
    ) {
        val sheet = sheets[url]
        val src: IntRect? = if (sheet != null) {
            // The decoded sheet's own scale (the loader may sample it by powers of two).
            val k = sheet.width.toFloat() / (shots.cols * shots.tileW)
            val sx = (col * shots.tileW * k).roundToInt()
            val sy = (row * shots.tileH * k).roundToInt()
            val sw = (shots.tileW * k).roundToInt().coerceAtMost(sheet.width - sx)
            val sh = (shots.tileH * k).roundToInt().coerceAtMost(sheet.height - sy)
            if (sw > 0 && sh > 0) IntRect(sx, sy, sx + sw, sy + sh) else null
        } else {
            null
        }
        val image = if (sheet != null && src != null) {
            last[0] = sheet
            last[1] = src
            sheet
        } else {
            last[0] as? ImageBitmap
        }
        val rect = src ?: (last[1] as? IntRect)
        if (image == null || rect == null) return@Canvas
        drawImage(
            image = image,
            srcOffset = rect.topLeft,
            srcSize = rect.size,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
}
