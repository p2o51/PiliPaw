package com.gpo.pilipala.feature.settings

import androidx.compose.ui.graphics.vector.ImageVector
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import java.util.Locale

/** The settings sub-pages (Route.SettingsPage.page) plus About. */
internal enum class SettingsPageId(val key: String, val label: String, val title: String, val icon: ImageVector) {
    Style("style", "外观", "外观设置", PiliSymbols.Palette),
    Play("play", "播放", "播放设置", YoinSymbols.PlayCircle),
    Danmaku("danmaku", "弹幕", "弹幕设置", PiliSymbols.Danmaku),
    Recommend("recommend", "推荐", "推荐设置", YoinSymbols.Sparkle),
    Privacy("privacy", "隐私", "隐私设置", PiliSymbols.Lock),
    Extra("extra", "其他", "其他设置", YoinSymbols.Settings),
    About("about", "关于", "关于", YoinSymbols.Info),
    ;

    companion object {
        fun of(key: String): SettingsPageId = entries.firstOrNull { it.key == key } ?: Style
    }
}

internal object SettingsChoices {
    val themeMode = listOf(
        Choice(0, "跟随系统", YoinSymbols.Smartphone),
        Choice(1, "浅色", YoinSymbols.LightMode),
        Choice(2, "深色", PiliSymbols.DarkMode),
    )

    /** Home top tabs, in HomeScreen order. */
    val homeTab = listOf(
        Choice(0, "直播"),
        Choice(1, "推荐"),
        Choice(2, "热门"),
        Choice(3, "番剧"),
    )

    val videoQuality = listOf(
        Choice(16, "360P 流畅"),
        Choice(32, "480P 清晰"),
        Choice(64, "720P 高清"),
        Choice(80, "1080P 高清"),
        Choice(112, "1080P+ 高码率"),
        Choice(116, "1080P60 高帧率"),
        Choice(120, "4K 超清"),
    )

    val audioQuality = listOf(
        Choice(30216, "64K"),
        Choice(30232, "132K"),
        Choice(30280, "192K"),
        Choice(30250, "杜比全景声"),
        Choice(30251, "Hi-Res 无损"),
    )

    val codec = listOf(
        Choice(0, "AVC"),
        Choice(1, "HEVC"),
        Choice(2, "AV1"),
    )

    val speed = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f).map { Choice(it, speedLabel(it)) }

    val rcmdSource = listOf(
        Choice("web", "Web 端"),
        Choice("app", "App 端"),
    )

    val rcmdMinDuration = listOf(0, 30, 60, 90, 120).map { Choice(it, if (it == 0) "不过滤" else "$it 秒") }

    val rcmdMinLikeRatio = listOf(0, 1, 2, 3, 4).map { Choice(it, if (it == 0) "不过滤" else "$it%") }

    val replySort = listOf(
        Choice(0, "最新"),
        Choice(1, "最热"),
    )

    /** Seed palette (pilipala's colour list, Bilibili pink first). */
    val seeds: List<Pair<Int, String>> = listOf(
        0xFFFB7299.toInt() to "哔哩粉",
        0xFF5CB67B.toInt() to "默认绿",
        0xFFE91E63.toInt() to "粉红色",
        0xFFF44336.toInt() to "红色",
        0xFFFF9800.toInt() to "橙色",
        0xFFFFC107.toInt() to "琥珀色",
        0xFFFFEB3B.toInt() to "黄色",
        0xFFCDDC39.toInt() to "酸橙色",
        0xFF8BC34A.toInt() to "浅绿色",
        0xFF4CAF50.toInt() to "绿色",
        0xFF009688.toInt() to "青色",
        0xFF00BCD4.toInt() to "蓝绿色",
        0xFF03A9F4.toInt() to "浅蓝色",
        0xFF2196F3.toInt() to "蓝色",
        0xFF3F51B5.toInt() to "靛蓝色",
        0xFF9C27B0.toInt() to "紫色",
        0xFF673AB7.toInt() to "深紫色",
        0xFF607D8B.toInt() to "蓝灰色",
        0xFF795548.toInt() to "棕色",
        0xFF9E9E9E.toInt() to "灰色",
    )
}

internal fun speedLabel(v: Float): String {
    val s = if (v % 1f == 0f) String.format(Locale.US, "%.1f", v) else v.toString().trimEnd('0')
    return "${s}x"
}

internal fun areaLabel(v: Float): String = when {
    v <= 0.3f -> "1/4 屏"
    v <= 0.55f -> "半屏"
    v <= 0.8f -> "3/4 屏"
    else -> "全屏"
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", v, units[i])
}
