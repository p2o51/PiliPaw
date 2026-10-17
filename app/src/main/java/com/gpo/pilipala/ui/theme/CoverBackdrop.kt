package com.gpo.pilipala.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
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
import kotlin.math.abs

/**
 * A cover's two colours (Yoin's ExpressiveBackdropPalette): [base] is the
 * cover's seed swatch toned for a backdrop, [accent] the first light / vivid
 * swatch in the base's hue family, else the base lifted. Cover pages theme
 * from both ([ExpressiveColorSchemeFactory.fromSeeds]) and paint their
 * [com.gpo.pilipala.ui.components.ExpressivePageBackground] wash with them.
 */
@Immutable
data class BackdropColors(val base: Color, val accent: Color)

/** How far (HSL hue degrees) the accent may sit from the base: the two read as the cover's one colour. */
private const val AccentHueWindow = 45f

/** Below this HSL saturation a colour has no hue worth matching (greys, near-whites). */
private const val AccentGreyBelow = 0.12f

/** With no swatch in the base's family, the accent is the toned base lifted by this much lightness. */
private const val AccentLift = 0.18f

/** The unfiltered read's colour count — only for covers the seed pass finds no colour in. */
private const val FallbackColorCount = 12

/** A seed swatch under this HCT chroma is a grey cover: the page keeps the app theme. */
private const val CoverMinChroma = 6.0

/**
 * Per-cover-URL cache, so going back and forth between pages never decodes and
 * quantises the same cover twice — and a page that returns wears its colours
 * from its first frame. Covers with no colour are remembered too.
 */
internal object CoverBackdropCache {
    private object NoColor

    private val cache = LruCache<String, Any>(96)

    /** True once [url] has been read, with or without colours. */
    fun isKnown(url: String): Boolean = cache.get(url) != null

    /** The colours read off [url]; null when unread or colourless. */
    fun colorsOrNull(url: String): BackdropColors? = cache.get(url) as? BackdropColors

    fun put(url: String, colors: BackdropColors?) {
        cache.put(url, colors ?: NoColor)
    }
}

/**
 * Decodes [url] small (the request CoverTheme always made, so a Coil memory-cache
 * hit when it was read before) and reads its backdrop colours. Null when the
 * cover has no colour or can't be read; only readable covers are cached.
 */
internal suspend fun loadCoverBackdrop(context: Context, url: String): BackdropColors? {
    CoverBackdropCache.colorsOrNull(url)?.let { return it }
    if (CoverBackdropCache.isKnown(url)) return null
    val request = ImageRequest.Builder(context)
        .data(Images.thumb(url, CoverSeedExtractor.BitmapSizePx))
        .allowHardware(false)
        .size(CoverSeedExtractor.BitmapSizePx)
        .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val colors = try {
        withContext(Dispatchers.Default) { extractBackdropColors(result.image.toBitmap()) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }
    CoverBackdropCache.put(url, colors)
    return colors
}

/**
 * The base is the seed swatch ([CoverSeedExtractor]: Palette's default filter —
 * no near-black, near-white or skin-tone swatches — vivid, then dominant, then
 * muted), toned for a backdrop; the accent the first light / vivid swatch of
 * the same pass in the base's hue family. Covers the seed pass finds nothing in
 * fall back to an unfiltered read. Null for a grey cover.
 */
internal fun extractBackdropColors(bitmap: Bitmap): BackdropColors? {
    val palette = CoverSeedExtractor.palette(bitmap)
    CoverSeedExtractor.seedSwatch(palette)?.let { seed ->
        if (Hct.fromInt(seed.rgb).chroma < CoverMinChroma) return null
        return backdropColorsFrom(Color(seed.rgb), palette.accentCandidates())
    }
    val unfiltered = Palette.from(bitmap)
        .maximumColorCount(FallbackColorCount)
        .clearFilters()
        .generate()
    val swatch = unfiltered.vibrantSwatch
        ?: unfiltered.dominantSwatch
        ?: unfiltered.mutedSwatch
        ?: unfiltered.darkVibrantSwatch
        ?: return null
    if (Hct.fromInt(swatch.rgb).chroma < CoverMinChroma) return null
    return backdropColorsFrom(Color(swatch.rgb), unfiltered.accentCandidates())
}

/** The accent's candidates, best first: light / vivid swatches before muted ones. */
private fun Palette.accentCandidates(): List<Color> = listOfNotNull(
    lightVibrantSwatch,
    vibrantSwatch,
    lightMutedSwatch,
    mutedSwatch,
).map { swatch -> Color(swatch.rgb) }

/**
 * The toned seed as the base; as the accent the first of [accentCandidates] in
 * the seed's hue family, toned — or, when that is the seed itself or none
 * qualifies, the base lifted.
 */
internal fun backdropColorsFrom(seed: Color, accentCandidates: List<Color>): BackdropColors {
    val base = toneBackdropBase(seed)
    val picked = accentCandidates.firstOrNull { inBackdropHueFamily(seed, it) }
    val accentSource = if (picked == null || picked.toArgb() == seed.toArgb()) lighten(base, AccentLift) else picked
    return BackdropColors(base = base, accent = toneBackdropAccent(accentSource))
}

/** Whether [candidate] reads as [seed]'s colour: within the hue window, or either is a grey. */
internal fun inBackdropHueFamily(seed: Color, candidate: Color): Boolean {
    val a = hsl(seed)
    val b = hsl(candidate)
    if (a[1] < AccentGreyBelow || b[1] < AccentGreyBelow) return true
    val distance = abs(a[0] - b[0]) % 360f
    return minOf(distance, 360f - distance) <= AccentHueWindow
}

internal fun toneBackdropBase(color: Color): Color {
    val hsl = hsl(color)
    hsl[1] = (hsl[1] * 0.82f).coerceIn(0.18f, 0.78f)
    hsl[2] = (hsl[2] * 0.72f + 0.12f).coerceIn(0.24f, 0.62f)
    return Color(ColorUtils.HSLToColor(hsl))
}

internal fun toneBackdropAccent(color: Color): Color {
    val hsl = hsl(color)
    hsl[1] = (hsl[1] * 1.08f).coerceIn(0.24f, 0.92f)
    hsl[2] = (hsl[2] + 0.14f).coerceIn(0.36f, 0.82f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun lighten(color: Color, amount: Float): Color {
    val hsl = hsl(color)
    hsl[2] = (hsl[2] + amount).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun hsl(color: Color): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
