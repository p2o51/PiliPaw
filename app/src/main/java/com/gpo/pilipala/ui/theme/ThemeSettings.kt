package com.gpo.pilipala.ui.theme

import com.gpo.pilipala.core.settings.AppSettings

/**
 * The colour settings the theme reads beyond [AppSettings.dynamicColor] /
 * [AppSettings.seedColor] (主色) / [AppSettings.coverColor].
 */
object ThemeSettings {
    /** 配色风格 with dynamic colour on: wallpaper seeds through SchemeExpressive (default). */
    const val StyleExpressive = 0

    /** 配色风格 with dynamic colour on: the system's own dynamic scheme. */
    const val StyleSystem = 1

    val colorStyle = AppSettings.register("color_style", StyleExpressive)

    /** 强调色 — the tertiary seed when dynamic colour is off (天蓝 next to 哔哩粉 by default). */
    val accentSeed = AppSettings.register("accent_seed", 0xFF23ADE5.toInt())
}
