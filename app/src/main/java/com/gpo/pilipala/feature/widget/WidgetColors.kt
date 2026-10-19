package com.gpo.pilipala.feature.widget

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.color.colorProviders
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.theme.ThemeSettings
import com.gpo.pilipala.ui.theme.WallpaperSeeds
import com.gpo.pilipala.ui.theme.appColorScheme

/**
 * The widgets' colours: the app's own scheme (the wallpaper's two seeds through the
 * expressive factory, 系统, or 主色 + 强调色), light and dark, and the app's 深浅 override.
 *
 * The widgets wear the app's surfaces, not glance-material3's mapping: that one paints the
 * background with secondaryContainer, and Expressive turns the secondary hue far from the
 * primary (a teal app got a peach widget). So the background is surfaceContainer, as the
 * app's pages are, and the blocks drawn in the secondaryContainer slot are
 * surfaceContainerHighest with onSurface, as the app's chips and rows are.
 */
internal object WidgetColors {
    fun provide(context: Context): ColorProviders {
        val app = context.applicationContext
        val dynamic = AppSettings.dynamicColor.value
        val style = ThemeSettings.colorStyle.value
        val wantsWallpaper = dynamic &&
            (style == ThemeSettings.StyleExpressive || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        val wallpaper = if (wantsWallpaper) {
            WallpaperSeeds.refresh(app)
            WallpaperSeeds.current(app)
        } else {
            null
        }
        val seed = AppSettings.seedColor.value
        val accent = ThemeSettings.accentSeed.value
        fun scheme(dark: Boolean) = appColorScheme(app, dark, dynamic, seed, accent, wallpaper)
        return when (AppSettings.themeMode.value) {
            1 -> scheme(false).let { providers(it, it) }
            2 -> scheme(true).let { providers(it, it) }
            else -> providers(day = scheme(false), night = scheme(true))
        }
    }

    private fun providers(day: ColorScheme, night: ColorScheme): ColorProviders {
        fun c(pick: (ColorScheme) -> Color) = ColorProvider(pick(day), pick(night))
        return colorProviders(
            primary = c { it.primary },
            onPrimary = c { it.onPrimary },
            primaryContainer = c { it.primaryContainer },
            onPrimaryContainer = c { it.onPrimaryContainer },
            secondary = c { it.secondary },
            onSecondary = c { it.onSecondary },
            secondaryContainer = c { it.surfaceContainerHighest },
            onSecondaryContainer = c { it.onSurface },
            tertiary = c { it.tertiary },
            onTertiary = c { it.onTertiary },
            tertiaryContainer = c { it.tertiaryContainer },
            onTertiaryContainer = c { it.onTertiaryContainer },
            error = c { it.error },
            errorContainer = c { it.errorContainer },
            onError = c { it.onError },
            onErrorContainer = c { it.onErrorContainer },
            background = c { it.background },
            onBackground = c { it.onBackground },
            surface = c { it.surface },
            onSurface = c { it.onSurface },
            surfaceVariant = c { it.surfaceVariant },
            onSurfaceVariant = c { it.onSurfaceVariant },
            outline = c { it.outline },
            inverseOnSurface = c { it.inverseOnSurface },
            inverseSurface = c { it.inverseSurface },
            inversePrimary = c { it.inversePrimary },
            widgetBackground = c { it.surfaceContainer },
        )
    }
}
