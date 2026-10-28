package com.gpo.pilipala.feature.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.live.LiveQnChoices
import com.gpo.pilipala.ui.components.seam.SeamTopStyle
import com.gpo.pilipala.ui.theme.ThemeSettings
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import java.util.Locale

/** The settings sub-pages (Route.SettingsPage.page) plus About. */
internal enum class SettingsPageId(val key: String, val label: String, val title: String, val icon: ImageVector) {
    Style("style", "外观", "外观设置", PiliSymbols.Palette),
    Play("play", "播放", "播放设置", YoinSymbols.PlayCircle),
    Danmaku("danmaku", "弹幕", "弹幕设置", PiliSymbols.Danmaku),
    Skip("skip", "跳过片段", "跳过片段", YoinSymbols.SkipNext),
    Recommend("recommend", "推荐", "推荐设置", YoinSymbols.Sparkle),
    Habit("habit", "习惯", "习惯", YoinSymbols.Person),
    Network("network", "网络与线路", "网络与线路", YoinSymbols.Speed),
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
    /** 首页布局: false = 双列 (the grid), true = 单列 (large cards). */
    val homeLayout = listOf(
        Choice(false, "双列"),
        Choice(true, "单列"),
    )

    /** 侧栏位置: false = 左, true = 右. */
    val sideRail = listOf(
        Choice(false, "左"),
        Choice(true, "右"),
    )

    /** 预览播放 for 单列 cards (FeedPreview.kt). */
    val feedPreview = listOf(
        Choice(com.gpo.pilipala.feature.player.PreviewOff, "关闭"),
        Choice(com.gpo.pilipala.feature.player.PreviewWifi, "仅 Wi‑Fi"),
        Choice(com.gpo.pilipala.feature.player.PreviewAlways, "始终"),
    )

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

    val liveQuality = LiveQnChoices.map { (qn, label) -> Choice(qn, label) }

    val codec = listOf(
        Choice(0, "AVC"),
        Choice(1, "HEVC"),
        Choice(2, "AV1"),
    )

    val speed = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f).map { Choice(it, speedLabel(it)) }

    val subtitleDefault = listOf(
        Choice("off", "关闭"),
        Choice("auto", "自动"),
        Choice("zh", "优先中文"),
    )

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

    /** 滚动边缘: how content meets pinned chrome at the top (Yoin's Scroll edge). */
    val seamTopStyle = SeamTopStyle.entries.map { Choice(it, it.label) }

    /** 配色风格 (dynamic colour on). */
    val colorStyle = listOf(
        Choice(ThemeSettings.StyleExpressive, "表现力"),
        Choice(ThemeSettings.StyleSystem, "系统"),
    )

    /** Curated 主色 + 强调色 pairs (dynamic colour off); both colours come from [seeds]. */
    val seedPairs: List<SeedPairChoice> = listOf(
        SeedPairChoice(0xFFFB7299.toInt(), 0xFF23ADE5.toInt(), "哔哩"),
        SeedPairChoice(0xFF5CB67B.toInt(), 0xFFFFC107.toInt(), "薄荷"),
        SeedPairChoice(0xFFFF9800.toInt(), 0xFF673AB7.toInt(), "落日"),
        SeedPairChoice(0xFF2196F3.toInt(), 0xFFE91E63.toInt(), "汽水"),
        SeedPairChoice(0xFF9C27B0.toInt(), 0xFF00BCD4.toInt(), "极光"),
        SeedPairChoice(0xFFF44336.toInt(), 0xFFFFEB3B.toInt(), "晚霞"),
        SeedPairChoice(0xFF3F51B5.toInt(), 0xFFFFC107.toInt(), "星夜"),
    )

    /** Seed palette (pilipala's colour list, Bilibili pink first, then its sky blue). */
    val seeds: List<Pair<Int, String>> = listOf(
        0xFFFB7299.toInt() to "哔哩粉",
        0xFF23ADE5.toInt() to "天蓝",
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

internal data class SeedPairChoice(val primary: Int, val accent: Int, val label: String)

internal fun seedName(argb: Int): String = SettingsChoices.seeds.firstOrNull { it.first == argb }?.second ?: "自定义"

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

/** 滚动边缘's glyph: two waves (the tide line). 24 viewport, 1.5 stroke, round caps. */
internal val TideGlyph: ImageVector by lazy {
    ImageVector.Builder("Tide", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M3 9.5c1.5 0 1.5 -1.5 3 -1.5s1.5 1.5 3 1.5s1.5 -1.5 3 -1.5s1.5 1.5 3 1.5s1.5 -1.5 3 -1.5s1.5 1.5 3 1.5" +
                    "M3 15.5c1.5 0 1.5 -1.5 3 -1.5s1.5 1.5 3 1.5s1.5 -1.5 3 -1.5s1.5 1.5 3 1.5s1.5 -1.5 3 -1.5s1.5 1.5 3 1.5",
            ).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
