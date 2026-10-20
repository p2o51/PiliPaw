package com.gpo.pilipala.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.gpo.pilipala.core.util.Images
import hct.Hct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the viewer reads off a picture before its large size arrives: the
 * bitmap the cell is already showing (straight from Coil's memory cache,
 * whatever `@…w_…h_1c` size the cell asked for, so the hero's first frame is
 * the cell's own pixels) and the deep tone of the wash.
 */
internal object ViewerImages {
    private val tones = LruCache<String, Int>(256)
    private const val MaxToneCopyPixels = 1_000_000L

    /** The hdslb file name (its hash), shared by every size the CDN serves of it. */
    fun id(image: ViewerImage): String =
        Images.normalize(image.url).substringBefore('?').substringBefore('@').substringAfterLast('/').substringBeforeLast('.')

    /** The largest decoded copy of [image] in the memory cache (any CDN size), or null. */
    fun cachedBitmap(context: Context, image: ViewerImage): Bitmap? {
        val id = id(image)
        if (id.length < 6) return null
        val cache = SingletonImageLoader.get(context).memoryCache ?: return null
        var best: Bitmap? = null
        for (key in runCatching { cache.keys }.getOrDefault(emptySet())) {
            if (!key.key.contains(id)) continue
            val bitmap = (cache[key]?.image as? BitmapImage)?.bitmap ?: continue
            if (bitmap.isRecycled) continue
            if (best == null || bitmap.width * bitmap.height > best.width * best.height) best = bitmap
        }
        return best
    }

    fun cachedTone(image: ViewerImage): Color? = tones.get(id(image))?.let { Color(it) }

    /** The deep tone of [image]'s own colours (Palette), darkened for the wash; null for no colour. */
    suspend fun tone(context: Context, image: ViewerImage, cached: Bitmap?): Color? {
        val id = id(image)
        tones.get(id)?.let { return Color(it) }
        val small = try {
            withContext(Dispatchers.Default) {
                cached?.let { copySmall(it) } ?: loadSmall(context, image)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            null
        } ?: return null
        val argb = withContext(Dispatchers.Default) {
            val palette = Palette.from(small).maximumColorCount(16).generate()
            val dominant = palette.dominantSwatch
            val swatch = dominant?.takeIf { Hct.fromInt(it.rgb).chroma >= 8.0 }
                ?: palette.vibrantSwatch
                ?: palette.mutedSwatch
                ?: dominant
                ?: palette.darkMutedSwatch
            swatch?.rgb
        } ?: return null
        val tone = deepTone(argb)
        tones.put(id, tone.toArgb())
        return tone
    }

    private fun copySmall(bitmap: Bitmap): Bitmap? {
        // Hardware bitmaps cannot be read pixel by pixel: copy to software first. Not a big one
        // (the viewer's own large decode can be the cached copy): a 128px fetch is far cheaper.
        val hardware = bitmap.config == Bitmap.Config.HARDWARE
        if (hardware && bitmap.width.toLong() * bitmap.height > MaxToneCopyPixels) return null
        val soft = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        soft ?: return null
        val edge = 128
        if (soft.width <= edge && soft.height <= edge) return soft
        val scale = edge.toFloat() / maxOf(soft.width, soft.height)
        return Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt().coerceAtLeast(1), (soft.height * scale).toInt().coerceAtLeast(1), true)
    }

    private suspend fun loadSmall(context: Context, image: ViewerImage): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(Images.thumb(image.url, 128))
            .allowHardware(false)
            .size(128)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap()
    }
}
