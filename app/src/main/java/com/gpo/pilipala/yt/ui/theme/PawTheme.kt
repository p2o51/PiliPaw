package com.gpo.pilipala.yt.ui.theme

import com.gpo.pilipala.ui.theme.*

import androidx.compose.material3.LocalContentColor
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.yt.core.util.Images

/** True when the resolved theme is dark (after the user's theme-mode override). */
val LocalPawDark = staticCompositionLocalOf { true }

@Composable
fun piliIsDark(): Boolean {
    val mode by AppSettings.themeMode.state.collectAsState()
    return when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
}

/**
 * The app's scheme from its colour settings: the wallpaper's two seeds when given, the
 * system dynamic scheme for 系统 (or a monochrome palette), else 主色 + 强调色. Shared by
 * [PawTheme] and the home-screen widgets.
 */
internal fun appColorScheme(
    context: android.content.Context,
    dark: Boolean,
    dynamic: Boolean,
    seed: Int,
    accent: Int,
    wallpaper: SeedPair?,
): ColorScheme = when {
    wallpaper != null -> ExpressiveColorSchemeFactory.fromSeeds(wallpaper.primary, wallpaper.accent, dark)
    // 系统, or a monochrome system palette that offers no seeds.
    dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    else -> ExpressiveColorSchemeFactory.fromSeeds(seed, accent, dark)
}

/**
 * App theme — Yoin's design language: M3 Expressive motion scheme, Google Sans
 * Flex type scale, continuous-corner shapes, and an expressive color scheme:
 *
 * - dynamic color on, 配色风格 表现力 (default): SPEC_2025 `SchemeExpressive`
 *   from the wallpaper's two seeds ([WallpaperSeeds]) — follows the wallpaper
 *   like the system does, without TonalSpot's grey palettes;
 * - dynamic color on, 配色风格 系统: the system dynamic scheme as it is;
 * - dynamic color off: the user's 主色 + 强调色 through the same factory.
 *
 * [seedOverride] / [accentOverride] (cover pages) win over all of these.
 * Every token change washes with the effects spring (no hard cuts); scheme
 * instances are remembered per input (and cached by the factory), never
 * rebuilt per recomposition.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PawTheme(
    seedOverride: Int? = null,
    accentOverride: Int? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = piliIsDark()
    val dynamic by AppSettings.dynamicColor.state.collectAsState()
    val seed by AppSettings.seedColor.state.collectAsState()
    val accent by ThemeSettings.accentSeed.state.collectAsState()
    val style by ThemeSettings.colorStyle.state.collectAsState()
    val systemSchemeSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val wantsWallpaper = seedOverride == null && dynamic &&
        (style == ThemeSettings.StyleExpressive || !systemSchemeSupported)
    val wallpaper = rememberWallpaperSeeds(enabled = wantsWallpaper)

    val target: ColorScheme = remember(context, dark, dynamic, style, seed, accent, wallpaper, seedOverride, accentOverride) {
        if (seedOverride != null) {
            ExpressiveColorSchemeFactory.fromSeeds(seedOverride, accentOverride, dark)
        } else {
            appColorScheme(context, dark, dynamic, seed, accent, wallpaper)
        }
    }
    val motionScheme = MotionScheme.expressive()
    val colorScheme = animateColorScheme(
        targetColorScheme = target,
        darkTheme = dark,
        motionScheme = motionScheme,
    )
    CompositionLocalProvider(LocalYoinColors provides colorScheme, LocalPawDark provides dark) {
        MaterialTheme(
            colorScheme = colorScheme,
            motionScheme = motionScheme,
            shapes = YoinShapes,
            typography = YoinTypography,
        ) {
            // Text and icons that name no colour take the scheme's, not the platform's black:
            // without this an app-forced dark theme draws them black on the dark surface.
            CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface, content = content)
        }
    }
}

val LocalYoinColors = staticCompositionLocalOf<ColorScheme?> { null }

/**
 * A page's two backdrop colours (Yoin's base + accent) for
 * [com.gpo.pilipala.yt.ui.components.ExpressivePageBackground]. The values are
 * spring-animated states: read them in the draw phase so the hand-off from
 * the app colours to the cover's never recomposes the page.
 */
@Stable
class PageBackdrop internal constructor(
    private val baseState: State<Color>,
    private val accentState: State<Color>,
) {
    val base: Color get() = baseState.value
    val accent: Color get() = accentState.value
}

/** Provided by [CoverTheme]; null elsewhere (the background then uses primary / tertiary). */
val LocalPageBackdrop = staticCompositionLocalOf<PageBackdrop?> { null }

/**
 * Re-themes [content] from a cover image with Yoin's two cover colours
 * ([BackdropColors]: base + accent → [ExpressiveColorSchemeFactory.fromSeeds])
 * and provides them as [LocalPageBackdrop]. Used by the video / bangumi / live
 * pages and the UP space.
 *
 * Colours are cached per cover URL: a page that returns wears its colours
 * from its first frame. A new cover keeps the previous colours until its own
 * read lands, then washes straight over (never via the app theme). Grey
 * covers keep the app theme.
 */
@Composable
fun CoverTheme(
    coverUrl: String?,
    enabled: Boolean = AppSettings.coverColor.state.collectAsState().value,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Normalised, so the card's `http://` cover and the detail's `https://` one share an entry.
    val key = coverUrl?.takeIf { enabled }?.let(Images::normalize)?.takeIf { it.isNotEmpty() }
    var colors by remember { mutableStateOf(key?.let(CoverBackdropCache::colorsOrNull)) }
    LaunchedEffect(key) {
        colors = if (key == null) null else loadCoverBackdrop(context, key)
    }
    val resolved = colors
    PawTheme(
        seedOverride = resolved?.base?.toArgb(),
        accentOverride = resolved?.accent?.toArgb(),
    ) {
        ProvidePageBackdrop(resolved, content)
    }
}

@Composable
private fun ProvidePageBackdrop(colors: BackdropColors?, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val base = animateColorAsState(
        targetValue = colors?.base ?: scheme.primary,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "backdropBase",
    )
    val accent = animateColorAsState(
        targetValue = colors?.accent ?: scheme.tertiary,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "backdropAccent",
    )
    val backdrop = remember(base, accent) { PageBackdrop(base, accent) }
    CompositionLocalProvider(LocalPageBackdrop provides backdrop, content = content)
}
