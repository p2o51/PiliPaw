package com.gpo.pilipala.feature.widget

import android.content.Context
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.toBitmap
import com.gpo.pilipala.core.util.Images
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Everything one widget update draws: loaded before the composition starts, again whenever WidgetStore.version moves. */
internal class WatchData(
    val resume: ContinueEntry?,
    val resumeCover: Bitmap?,
    /** 稍后再看 without the video already shown as 继续看; empty when logged out. */
    val later: List<LaterEntry>,
    val laterCount: Int,
    /** Thumbnails for the first entries of [later] (as many as the tallest layout shows). */
    val laterThumbs: List<Bitmap?>,
    val loggedIn: Boolean,
)

internal object WidgetData {
    /** The tallest layout shows this many 稍后再看 rows. */
    const val MaxLaterRows = 4

    // Every bitmap of one update travels in a single RemoteViews parcel (shared by the size
    // buckets): about 0.5MB at most, under the binder's 1MB.
    private const val CoverW = 360
    private const val CoverH = 203
    private const val ThumbW = 144
    private const val ThumbH = 81

    /** Per cover; the Coil disk cache answers at once offline, the network gets this long. */
    private const val ImageTimeoutMs = 5_000L

    suspend fun load(context: Context): WatchData = coroutineScope {
        val resume = withContext(Dispatchers.IO) { WidgetStore.readContinue(context) }
        val cover = async { resume?.let { cover(context, it.cover, CoverW, CoverH) } }
        val cache = withContext(Dispatchers.IO) { WidgetStore.laterForWidget(context) }
        val later = cache?.items.orEmpty().filter { it.epId > 0 || it.bvid != resume?.bvid }
        val thumbs = later.take(MaxLaterRows).map { async { cover(context, it.cover, ThumbW, ThumbH) } }.awaitAll()
        WatchData(
            resume = resume,
            resumeCover = cover.await(),
            later = later,
            laterCount = maxOf(cache?.count ?: 0, cache?.items?.size ?: 0),
            laterThumbs = thumbs,
            // From the account, not the list: a failed or offline fetch with no cache is still signed in.
            loggedIn = WidgetStore.currentMid() > 0L,
        )
    }

    /**
     * A cover at about [w]×[h] px (the CDN crops it to that box), software and 16-bit: the
     * widget's bitmaps travel in one RemoteViews parcel, so they stay small.
     */
    internal suspend fun cover(context: Context, url: String, w: Int, h: Int): Bitmap? {
        val src = Images.normalize(url).ifEmpty { return null }
        return try {
            withTimeoutOrNull(ImageTimeoutMs) {
                withContext(Dispatchers.IO) {
                    val request = ImageRequest.Builder(context)
                        .data(Images.thumb(src, w, h))
                        .size(w, h)
                        .allowHardware(false)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .build()
                    val bmp = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
                    bmp?.let { fit(it, w, h) }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
    }

    /** Never more pixels than the box needs (a cover the CDN didn't resize comes back huge). */
    private fun fit(bmp: Bitmap, w: Int, h: Int): Bitmap {
        if (bmp.width <= w * 3 / 2 && bmp.height <= h * 3 / 2 && bmp.config != Bitmap.Config.HARDWARE) return bmp
        val scale = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val sw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val sh = (bmp.height * scale).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bmp, sw, sh, true) }.getOrDefault(bmp)
    }
}
