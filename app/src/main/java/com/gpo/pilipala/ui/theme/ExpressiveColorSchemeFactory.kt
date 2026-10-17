package com.gpo.pilipala.ui.theme

import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dynamiccolor.ColorSpec
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import palettes.TonalPalette
import scheme.SchemeExpressive
import kotlin.math.abs

/**
 * Builds Compose [ColorScheme]s with the official MCU `SchemeExpressive`
 * algorithm (`SPEC_2025`).
 *
 * - [fromSeed]: one seed, Expressive's own companion hues.
 * - [fromSeeds]: Yoin's two seeds — the primary seed drives primary,
 *   secondary and the (tinted) neutrals; the accent seed becomes the
 *   tertiary palette, so a wallpaper's second colour / a cover's accent /
 *   the user's 强调色 shows up wherever tertiary does.
 *
 * Results are cached by their inputs: identical inputs return the very same
 * [ColorScheme] instance (theme-wash invariant — never allocate a scheme per
 * recomposition, and a page that comes back with the same cover gets the same
 * instance, so its wash is a no-op).
 */
object ExpressiveColorSchemeFactory {
    /** Below this HCT chroma an accent has no hue worth carrying (greys). */
    private const val AccentMinChroma = 8.0

    /**
     * An accent this close (HCT hue degrees) to the primary seed reads as the
     * same colour; tertiary then keeps Expressive's own companion hue instead
     * of collapsing onto primary.
     */
    private const val AccentDistinctHue = 20.0

    /** SPEC_2025 Expressive's tertiary chroma on phones. */
    private const val ExpressiveTertiaryChroma = 48.0

    private data class Key(val primary: Int, val accent: Int?, val dark: Boolean, val contrast: Double)

    private val cache = LruCache<Key, ColorScheme>(24)

    fun fromSeed(
        seedArgb: Int,
        isDark: Boolean = true,
        contrastLevel: Double = 0.0,
    ): ColorScheme = fromSeeds(seedArgb, null, isDark, contrastLevel)

    /**
     * Expressive scheme from two seeds. [accentSeedArgb] null (or a grey, or
     * the primary's own hue) falls back to [fromSeed]'s single-seed palettes.
     */
    fun fromSeeds(
        primarySeedArgb: Int,
        accentSeedArgb: Int?,
        isDark: Boolean = true,
        contrastLevel: Double = 0.0,
    ): ColorScheme {
        // An accent that would not change tertiary keys the single-seed entry.
        val accent = accentSeedArgb?.takeIf { usableAccent(Hct.fromInt(primarySeedArgb), Hct.fromInt(it)) }
        val key = Key(primarySeedArgb, accent, isDark, contrastLevel)
        cache.get(key)?.let { return it }
        val scheme = buildScheme(primarySeedArgb, accent, isDark, contrastLevel).toColorScheme(isDark)
        cache.put(key, scheme)
        return scheme
    }

    private fun buildScheme(primarySeed: Int, accentSeed: Int?, isDark: Boolean, contrastLevel: Double): DynamicScheme {
        val base = SchemeExpressive(
            sourceColorHct = Hct.fromInt(primarySeed),
            isDark = isDark,
            contrastLevel = contrastLevel,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            platform = DynamicScheme.Platform.PHONE,
        )
        if (accentSeed == null) return base
        val accent = TonalPalette.fromHueAndChroma(Hct.fromInt(accentSeed).hue, ExpressiveTertiaryChroma)
        return DynamicScheme(
            sourceColorHct = base.sourceColorHct,
            variant = Variant.EXPRESSIVE,
            isDark = isDark,
            contrastLevel = contrastLevel,
            platform = DynamicScheme.Platform.PHONE,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            primaryPalette = base.primaryPalette,
            secondaryPalette = base.secondaryPalette,
            tertiaryPalette = accent,
            neutralPalette = base.neutralPalette,
            neutralVariantPalette = base.neutralVariantPalette,
            errorPalette = base.errorPalette,
        )
    }

    /** Whether [accent] carries a hue of its own next to [primary] (not a grey, not primary's own hue). */
    private fun usableAccent(primary: Hct, accent: Hct): Boolean {
        if (accent.chroma < AccentMinChroma) return false
        return primary.chroma < AccentMinChroma || hueDistance(primary.hue, accent.hue) >= AccentDistinctHue
    }

    internal fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun DynamicScheme.toColorScheme(isDark: Boolean): ColorScheme = if (isDark) {
        darkColorScheme(
            primary = Color(primary),
            onPrimary = Color(onPrimary),
            primaryContainer = Color(primaryContainer),
            onPrimaryContainer = Color(onPrimaryContainer),
            inversePrimary = Color(inversePrimary),
            secondary = Color(secondary),
            onSecondary = Color(onSecondary),
            secondaryContainer = Color(secondaryContainer),
            onSecondaryContainer = Color(onSecondaryContainer),
            tertiary = Color(tertiary),
            onTertiary = Color(onTertiary),
            tertiaryContainer = Color(tertiaryContainer),
            onTertiaryContainer = Color(onTertiaryContainer),
            background = Color(background),
            onBackground = Color(onBackground),
            surface = Color(surface),
            onSurface = Color(onSurface),
            surfaceVariant = Color(surfaceVariant),
            onSurfaceVariant = Color(onSurfaceVariant),
            surfaceTint = Color(surfaceTint),
            inverseSurface = Color(inverseSurface),
            inverseOnSurface = Color(inverseOnSurface),
            error = Color(error),
            onError = Color(onError),
            errorContainer = Color(errorContainer),
            onErrorContainer = Color(onErrorContainer),
            outline = Color(outline),
            outlineVariant = Color(outlineVariant),
            scrim = Color(scrim),
            surfaceBright = Color(surfaceBright),
            surfaceDim = Color(surfaceDim),
            surfaceContainer = Color(surfaceContainer),
            surfaceContainerHigh = Color(surfaceContainerHigh),
            surfaceContainerHighest = Color(surfaceContainerHighest),
            surfaceContainerLow = Color(surfaceContainerLow),
            surfaceContainerLowest = Color(surfaceContainerLowest),
            primaryFixed = Color(primaryFixed),
            primaryFixedDim = Color(primaryFixedDim),
            onPrimaryFixed = Color(onPrimaryFixed),
            onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
            secondaryFixed = Color(secondaryFixed),
            secondaryFixedDim = Color(secondaryFixedDim),
            onSecondaryFixed = Color(onSecondaryFixed),
            onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
            tertiaryFixed = Color(tertiaryFixed),
            tertiaryFixedDim = Color(tertiaryFixedDim),
            onTertiaryFixed = Color(onTertiaryFixed),
            onTertiaryFixedVariant = Color(onTertiaryFixedVariant),
        )
    } else {
        lightColorScheme(
            primary = Color(primary),
            onPrimary = Color(onPrimary),
            primaryContainer = Color(primaryContainer),
            onPrimaryContainer = Color(onPrimaryContainer),
            inversePrimary = Color(inversePrimary),
            secondary = Color(secondary),
            onSecondary = Color(onSecondary),
            secondaryContainer = Color(secondaryContainer),
            onSecondaryContainer = Color(onSecondaryContainer),
            tertiary = Color(tertiary),
            onTertiary = Color(onTertiary),
            tertiaryContainer = Color(tertiaryContainer),
            onTertiaryContainer = Color(onTertiaryContainer),
            background = Color(background),
            onBackground = Color(onBackground),
            surface = Color(surface),
            onSurface = Color(onSurface),
            surfaceVariant = Color(surfaceVariant),
            onSurfaceVariant = Color(onSurfaceVariant),
            surfaceTint = Color(surfaceTint),
            inverseSurface = Color(inverseSurface),
            inverseOnSurface = Color(inverseOnSurface),
            error = Color(error),
            onError = Color(onError),
            errorContainer = Color(errorContainer),
            onErrorContainer = Color(onErrorContainer),
            outline = Color(outline),
            outlineVariant = Color(outlineVariant),
            scrim = Color(scrim),
            surfaceBright = Color(surfaceBright),
            surfaceDim = Color(surfaceDim),
            surfaceContainer = Color(surfaceContainer),
            surfaceContainerHigh = Color(surfaceContainerHigh),
            surfaceContainerHighest = Color(surfaceContainerHighest),
            surfaceContainerLow = Color(surfaceContainerLow),
            surfaceContainerLowest = Color(surfaceContainerLowest),
            primaryFixed = Color(primaryFixed),
            primaryFixedDim = Color(primaryFixedDim),
            onPrimaryFixed = Color(onPrimaryFixed),
            onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
            secondaryFixed = Color(secondaryFixed),
            secondaryFixedDim = Color(secondaryFixedDim),
            onSecondaryFixed = Color(onSecondaryFixed),
            onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
            tertiaryFixed = Color(tertiaryFixed),
            tertiaryFixedDim = Color(tertiaryFixedDim),
            onTertiaryFixed = Color(onTertiaryFixed),
            onTertiaryFixedVariant = Color(onTertiaryFixedVariant),
        )
    }
}
