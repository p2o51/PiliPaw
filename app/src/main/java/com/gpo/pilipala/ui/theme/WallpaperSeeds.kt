package com.gpo.pilipala.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import hct.Hct

/** Two theme seeds: [primary] drives primary / secondary / neutrals, [accent] tertiary (Yoin's 双 seed). */
@Immutable
data class SeedPair(val primary: Int, val accent: Int?)

/**
 * The wallpaper's two seeds for the expressive dynamic theme.
 *
 * - Primary: the system's own dynamic seed hue (`system_accent1`, API 31+), so
 *   the app keeps the hue the launcher and Settings wear — including a colour
 *   the user picked in Wallpaper & style — only with Expressive's palettes
 *   instead of TonalSpot's grey ones. Before API 31: the wallpaper's first
 *   colour with a hue.
 * - Accent: the wallpaper's most vivid other colour ([WallpaperManager]'s
 *   primary / secondary / tertiary) whose hue stands apart from the primary;
 *   else the system tertiary seed (`system_accent3`); else none (Expressive's
 *   own companion hue).
 *
 * A monochrome system palette yields no seeds: the theme then shows the system
 * scheme as it is, rather than inventing a colour the user turned off.
 *
 * Read synchronously once (a cached binder call plus two resource reads), then
 * refreshed when the wallpaper's colours change and when the app resumes.
 */
internal object WallpaperSeeds {
    /** Below this a system palette is monochrome. */
    private const val SystemMinChroma = 6.0

    /** A wallpaper colour needs this much chroma to carry a seed hue. */
    private const val WallpaperMinChroma = 12.0

    /** The accent must stand this far (HCT hue degrees) from the primary. */
    private const val AccentHueGap = 30.0

    private const val RefreshThrottleMs = 1_000L
    private const val SystemPaletteSettleMs = 1_500L

    private var cached: SeedPair? = null
    private var loaded = false
    private var lastRefresh = 0L
    private var listening = false

    /** Bumped when the seeds change; read by [rememberWallpaperSeeds] so themes recompose. */
    internal val version = mutableIntStateOf(0)

    fun current(context: Context): SeedPair? {
        if (!loaded) {
            cached = read(context)
            loaded = true
            lastRefresh = SystemClock.uptimeMillis()
        }
        return cached
    }

    fun refresh(context: Context, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && loaded && now - lastRefresh < RefreshThrottleMs) return
        lastRefresh = now
        val next = read(context)
        loaded = true
        if (next != cached) {
            cached = next
            version.intValue++
        }
    }

    /** One process-wide listener on the application context. */
    fun ensureListening(context: Context) {
        if (listening || Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        listening = true
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        runCatching {
            WallpaperManager.getInstance(app).addOnColorsChangedListener(
                { _, which ->
                    if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                        refresh(app, force = true)
                        // The system palette (accent1 / accent3) follows the wallpaper a moment later.
                        handler.postDelayed({ refresh(app, force = true) }, SystemPaletteSettleMs)
                    }
                },
                handler,
            )
        }
    }

    private fun read(context: Context): SeedPair? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        val wallpaper = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        val wallpaperColors = listOfNotNull(
            wallpaper?.primaryColor,
            wallpaper?.secondaryColor,
            wallpaper?.tertiaryColor,
        ).map { it.toArgb() }

        val system = systemSeeds(context)
        val primary = when {
            system != null -> system.first
            // A system palette that exists but is monochrome: keep it as it is.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> return null
            else -> wallpaperColors.firstOrNull { Hct.fromInt(it).chroma >= WallpaperMinChroma } ?: return null
        }
        val primaryHct = Hct.fromInt(primary)
        val accent = wallpaperColors
            .map { Hct.fromInt(it) }
            .filter { it.chroma >= WallpaperMinChroma }
            .filter { ExpressiveColorSchemeFactory.hueDistance(it.hue, primaryHct.hue) >= AccentHueGap }
            .maxByOrNull { it.chroma }
            ?.toInt()
            ?: system?.second
        return SeedPair(primary, accent)
    }

    /** (accent1, accent3) of the system palette, null below API 31 or when it is monochrome. */
    private fun systemSeeds(context: Context): Pair<Int, Int?>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val primary = context.getColor(android.R.color.system_accent1_500)
            if (Hct.fromInt(primary).chroma < SystemMinChroma) return@runCatching null
            val tertiary = context.getColor(android.R.color.system_accent3_500)
                .takeIf { Hct.fromInt(it).chroma >= SystemMinChroma }
            primary to tertiary
        }.getOrNull()
    }
}

/**
 * The wallpaper's [SeedPair] while [enabled] (null otherwise, or when the
 * wallpaper offers none). Recomposes only when the seeds actually change.
 */
@Composable
internal fun rememberWallpaperSeeds(enabled: Boolean): SeedPair? {
    if (!enabled) return null
    val context = LocalContext.current.applicationContext
    WallpaperSeeds.version.intValue // observe changes
    WallpaperSeeds.ensureListening(context)
    LifecycleResumeEffect(context) {
        WallpaperSeeds.refresh(context)
        onPauseOrDispose { }
    }
    return WallpaperSeeds.current(context)
}
