package com.gpo.pilipala.ui.theme

import android.content.Context
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontStyle as PlatformFontStyle
import android.os.Build
import androidx.annotation.FontRes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import com.gpo.pilipala.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Chinese in the app's own weights. Google Sans Flex has no CJK glyphs, so Chinese falls back to
 * the system font, and some ROMs (Samsung's) ship it in one weight only: a SemiBold lyric line
 * turns its Latin bold and leaves its Chinese regular. On such a phone Noto Sans SC, from the
 * Google Fonts provider (downloaded once, kept by Play services), sits right after Google Sans
 * Flex in one fallback chain per weight, so Latin is still Google Sans Flex and Chinese has real
 * weights. A system CJK font that has its weights (Pixel's Noto Sans CJK) is kept: it also
 * squeezes CJK punctuation, which the downloaded cut does not.
 *
 * Each [Font] loads asynchronously: until it lands (or when it can't — offline, no Play
 * services, Android 9 and older), the family's plain resource font is used and Chinese comes
 * from the system as before; never an error, never blank text. [preload] starts the download
 * at launch so the first page usually has it already.
 */
internal object CjkFonts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val noto = HashMap<Int, Deferred<PlatformFont?>>()
    private var needed: Deferred<Boolean>? = null

    /** The weights the type scale and the lyrics use. */
    private val Weights = intArrayOf(400, 500, 600, 700)

    fun preload(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val app = context.applicationContext
        scope.launch { if (neededAsync(app).await()) Weights.forEach { notoAsync(app, it) } }
    }

    private fun neededAsync(context: Context): Deferred<Boolean> = synchronized(noto) {
        needed ?: scope.async { !systemCjkHasWeights(context) }.also { needed = it }
    }

    /**
     * Whether the system's Chinese gets heavier with the weight asked for: 中 drawn through
     * Google Sans Flex 400 and 700 (as the app's text falls back) and its ink compared. One
     * weight only leaves the two about equal.
     */
    private fun systemCjkHasWeights(context: Context): Boolean = runCatching {
        fun ink(@FontRes res: Int): Int {
            val size = 96
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ALPHA_8)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, res)
                textSize = size * 0.8f
            }
            android.graphics.Canvas(bitmap).drawText("中", size * 0.1f, size * 0.82f, paint)
            val pixels = ByteArray(size * size)
            bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(pixels))
            bitmap.recycle()
            return pixels.count { (it.toInt() and 0xff) > 127 }
        }
        val regular = ink(R.font.google_sans_flex_400)
        val bold = ink(R.font.google_sans_flex_700)
        regular > 0 && bold > regular * 1.1f
    }.getOrDefault(true)

    private fun notoAsync(context: Context, weight: Int): Deferred<PlatformFont?> = synchronized(noto) {
        noto.getOrPut(weight) { scope.async { fetchNoto(context, weight) } }
    }

    private fun fetchNoto(context: Context, weight: Int): PlatformFont? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val request = FontRequest(
            "com.google.android.gms.fonts",
            "com.google.android.gms",
            "name=Noto Sans SC&weight=$weight",
            R.array.com_google_android_gms_fonts_certs,
        )
        val result = FontsContractCompat.fetchFonts(context, null, request)
        if (result.statusCode != FontsContractCompat.FontFamilyResult.STATUS_OK) return null
        val info = result.fonts.firstOrNull { it.resultCode == FontsContractCompat.Columns.RESULT_CODE_OK } ?: return null
        context.contentResolver.openFileDescriptor(info.uri, "r")?.use { fd ->
            PlatformFont.Builder(fd).setWeight(weight).build()
        }
    }.getOrNull()

    /** Google Sans Flex [res] at [weight], then Noto Sans SC at the same weight. */
    @OptIn(ExperimentalTextApi::class)
    class Font(
        @FontRes val res: Int,
        override val weight: FontWeight,
        /** Font variation settings for a variable [res] (e.g. "'wght' 600, 'ROND' 100"). */
        val variation: String? = null,
    ) : AndroidFont(FontLoadingStrategy.Async, Loader, FontVariation.Settings()) {
        override val style: FontStyle get() = FontStyle.Normal

        override fun equals(other: Any?): Boolean =
            other is Font && other.res == res && other.weight == weight && other.variation == variation

        override fun hashCode(): Int = (res * 31 + weight.weight) * 31 + variation.hashCode()

        override fun toString(): String = "CjkFonts.Font(res=$res, weight=$weight, variation=$variation)"
    }

    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): android.graphics.Typeface? = null

        override suspend fun awaitLoad(context: Context, font: AndroidFont): android.graphics.Typeface? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val f = font as Font
            if (!neededAsync(context.applicationContext).await()) return null
            val noto = notoAsync(context.applicationContext, f.weight.weight).await() ?: return null
            return runCatching {
                val latin = PlatformFont.Builder(context.resources, f.res)
                    .setWeight(f.weight.weight)
                    .apply { f.variation?.let { setFontVariationSettings(it) } }
                    .build()
                android.graphics.Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(latin).build())
                    .addCustomFallback(PlatformFontFamily.Builder(noto).build())
                    .setStyle(PlatformFontStyle(f.weight.weight, PlatformFontStyle.FONT_SLANT_UPRIGHT))
                    .setSystemFallback("sans-serif")
                    .build()
            }.getOrNull()
        }
    }
}
