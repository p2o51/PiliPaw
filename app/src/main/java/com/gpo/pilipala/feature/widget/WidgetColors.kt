package com.gpo.pilipala.feature.widget

import android.content.Context
import android.os.Build
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.theme.ThemeSettings
import com.gpo.pilipala.ui.theme.WallpaperSeeds
import com.gpo.pilipala.ui.theme.appColorScheme

/**
 * The widgets' colours: the app's own scheme (the wallpaper's two seeds through the
 * expressive factory, 系统, or 主色 + 强调色), light and dark, and the app's 深浅 override.
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
            1 -> ColorProviders(scheme = scheme(false))
            2 -> ColorProviders(scheme = scheme(true))
            else -> ColorProviders(light = scheme(false), dark = scheme(true))
        }
    }
}
