package com.gpo.pilipala.feature.settings

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import coil3.SingletonImageLoader
import com.gpo.pilipala.BuildConfig
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.asBool
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.player.FeedPreviewMode
import com.gpo.pilipala.ui.components.HomeSingleColumn
import com.gpo.pilipala.ui.shell.ShellSection
import com.gpo.pilipala.ui.shell.ShellSections
import com.gpo.pilipala.feature.home.AppAccessKey
import com.gpo.pilipala.feature.home.AppAuth
import com.gpo.pilipala.feature.home.RcmdExemptFollowed
import com.gpo.pilipala.feature.home.RcmdMinDuration
import com.gpo.pilipala.feature.home.RcmdMinLikeRatio
import com.gpo.pilipala.feature.live.LiveDefaultQn
import com.gpo.pilipala.feature.live.LiveQnNames
import com.gpo.pilipala.feature.player.DanmakuBlockLevel
import com.gpo.pilipala.feature.player.DanmakuBlockRules
import com.gpo.pilipala.feature.player.DanmakuBlockRulesSheet
import com.gpo.pilipala.feature.player.DanmakuMergeDuplicates
import com.gpo.pilipala.feature.player.DanmakuRules
import com.gpo.pilipala.feature.player.SubtitleDefaultMode
import com.gpo.pilipala.feature.player.danmakuLevelLabel
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.seam.SeamTopPreference
import com.gpo.pilipala.ui.components.seam.currentSeamTopStyle
import com.gpo.pilipala.ui.theme.MorphShape
import com.gpo.pilipala.ui.theme.ThemeSettings
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsPageScreen(route: Route.SettingsPage) {
    SettingsPageContent(page = route.page, showBack = true)
}

/** One settings sub-page; also the right pane of the two-pane Settings on Expanded. */
@Composable
internal fun SettingsPageContent(page: String, showBack: Boolean) {
    val id = SettingsPageId.of(page)
    if (id == SettingsPageId.About) {
        AboutContent(showBack = showBack)
        return
    }
    SettingsScaffold(title = id.title, showBack = showBack) {
        when (id) {
            SettingsPageId.Style -> StyleBody()
            SettingsPageId.Play -> PlayBody()
            SettingsPageId.Danmaku -> DanmakuBody()
            SettingsPageId.Skip -> SkipBody()
            SettingsPageId.Recommend -> RecommendBody()
            SettingsPageId.Habit -> HabitBody()
            SettingsPageId.Network -> NetworkBody()
            SettingsPageId.Privacy -> PrivacyBody()
            SettingsPageId.Extra -> ExtraBody()
            SettingsPageId.About -> Unit
        }
    }
}

// ── 外观 ────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.StyleBody() {
    val mode by AppSettings.themeMode.state.collectAsState()
    val dynamic by AppSettings.dynamicColor.state.collectAsState()
    val style by ThemeSettings.colorStyle.state.collectAsState()
    val seed by AppSettings.seedColor.state.collectAsState()
    val accent by ThemeSettings.accentSeed.state.collectAsState()
    val cover by AppSettings.coverColor.state.collectAsState()
    val homeTab by AppSettings.homeDefaultTab.state.collectAsState()
    val singleColumn by HomeSingleColumn.state.collectAsState()
    val previewMode by FeedPreviewMode.state.collectAsState()
    val hiddenSections by ShellSections.hiddenKey.state.collectAsState()
    val sideRailRight by com.gpo.pilipala.ui.components.SideRailRight.state.collectAsState()
    val railCapable = androidx.compose.ui.platform.LocalConfiguration.current.let {
        com.gpo.pilipala.ui.components.sideRailFor(maxOf(it.screenWidthDp, it.screenHeightDp), minOf(it.screenWidthDp, it.screenHeightDp))
    }
    // 底栏 lists only the sections the ticked sources have (订阅 with YouTube alone, 排行 / 动态 with Bilibili).
    val barSources by com.gpo.pilipala.core.source.Sources.enabled.collectAsState()
    val seamStyle = currentSeamTopStyle()
    // Wallpaper colours exist from 8.1; the system's own dynamic scheme from 12.
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
    val systemStyleSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicOn = dynamic && dynamicSupported

    SettingsGroup("主题") {
        item {
            SettingsToggleItem(
                title = "主题模式",
                icon = PiliSymbols.DarkMode,
                choices = SettingsChoices.themeMode,
                selected = mode,
                onSelect = { AppSettings.set(AppSettings.themeMode, it) },
            )
        }
        item {
            Column {
                SettingsSwitchItem(
                    title = "动态取色",
                    icon = PiliSymbols.Palette,
                    summary = if (dynamicSupported) "跟随系统壁纸" else "需要 Android 8.1",
                    checked = dynamicOn,
                    enabled = dynamicSupported,
                    onCheckedChange = { AppSettings.set(AppSettings.dynamicColor, it) },
                )
                AnimatedVisibility(
                    visible = dynamicOn && systemStyleSupported,
                    enter = expandVertically(YoinMotion.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                        YoinMotion.fadeIn(YoinMotionRole.Standard),
                    exit = shrinkVertically(YoinMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Top) +
                        YoinMotion.fadeOut(YoinMotionRole.Standard),
                ) {
                    SettingsToggleItem(
                        title = "配色风格",
                        icon = YoinSymbols.Sparkle,
                        choices = SettingsChoices.colorStyle,
                        selected = style,
                        onSelect = { AppSettings.set(ThemeSettings.colorStyle, it) },
                    )
                }
                AnimatedVisibility(
                    visible = !dynamicOn,
                    enter = expandVertically(YoinMotion.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                        YoinMotion.fadeIn(YoinMotionRole.Standard),
                    exit = shrinkVertically(YoinMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Top) +
                        YoinMotion.fadeOut(YoinMotionRole.Standard),
                ) {
                    SeedPicker(
                        primary = seed,
                        accent = accent,
                        onPrimary = { AppSettings.set(AppSettings.seedColor, it) },
                        onAccent = { AppSettings.set(ThemeSettings.accentSeed, it) },
                    )
                }
            }
        }
        item {
            SettingsSwitchItem(
                title = "封面取色",
                icon = PiliSymbols.Image,
                summary = "详情页跟随封面配色",
                checked = cover,
                onCheckedChange = { AppSettings.set(AppSettings.coverColor, it) },
            )
        }
    }
    SettingsGroup("动效") {
        item {
            SettingsToggleItem(
                title = "滚动边缘",
                icon = TideGlyph,
                choices = SettingsChoices.seamTopStyle,
                selected = seamStyle,
                onSelect = { SeamTopPreference.select(it) },
            )
        }
    }
    SettingsGroup("首页") {
        item {
            SettingsChoiceItem(
                title = "默认启动页",
                icon = YoinSymbols.Home,
                choices = SettingsChoices.homeTab,
                selected = homeTab,
                onSelect = { AppSettings.set(AppSettings.homeDefaultTab, it) },
            )
        }
        item {
            SettingsToggleItem(
                title = "首页布局",
                icon = PiliSymbols.Video,
                choices = SettingsChoices.homeLayout,
                selected = singleColumn,
                onSelect = { AppSettings.set(HomeSingleColumn, it) },
            )
        }
        item {
            // 单列's cards play the one in view; 双列 has no preview, so the row waits for 单列.
            SettingsToggleItem(
                title = "预览播放",
                icon = YoinSymbols.PlayCircle,
                choices = SettingsChoices.feedPreview,
                selected = previewMode,
                onSelect = { AppSettings.set(FeedPreviewMode, it) },
                enabled = singleColumn,
            )
        }
    }
    SettingsGroup("底栏") {
        // 侧栏位置: the side rail of large windows (横屏平板); dragging the rail across sets it too.
        // Only where the window gets a rail when held sideways (not on a phone).
        if (railCapable) item(key = "side_rail") {
            SettingsToggleItem(
                title = "侧栏位置",
                icon = com.gpo.yoin.symbols.YoinSymbols.Tablet,
                choices = SettingsChoices.sideRail,
                selected = sideRailRight,
                onSelect = { AppSettings.set(com.gpo.pilipala.ui.components.SideRailRight, it) },
            )
        }
        ShellSection.entries.filter { it.removable && it.fits(barSources) }.forEach { section ->
            item(key = "shell_${section.key}") {
                SettingsSwitchItem(
                    title = section.tab.label,
                    icon = section.tab.icon,
                    checked = ShellSections.isShown(hiddenSections, section),
                    onCheckedChange = { ShellSections.setShown(section, it) },
                )
            }
        }
    }
}

/**
 * The two seeds with dynamic colour off: curated 主色 + 强调色 pairs first,
 * then each seed on its own row. Swatches are clipped to MaterialShapes; the
 * selected one morphs circle → cookie and turns.
 */
@Composable
private fun SeedPicker(
    primary: Int,
    accent: Int,
    onPrimary: (Int) -> Unit,
    onAccent: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val pair = SettingsChoices.seedPairs.firstOrNull { it.primary == primary && it.accent == accent }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SeedRowLabel("搭配", pair?.label ?: "自定义")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(SettingsChoices.seedPairs, key = { it.label }) { choice ->
                PairSwatch(
                    primary = Color(choice.primary),
                    accent = Color(choice.accent),
                    label = choice.label,
                    selected = choice == pair,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onPrimary(choice.primary)
                        onAccent(choice.accent)
                    },
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        SeedRowLabel("主色", seedName(primary))
        SeedRow(selected = primary) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onPrimary(it)
        }
        Spacer(Modifier.height(2.dp))
        SeedRowLabel("强调色", seedName(accent))
        SeedRow(selected = accent) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onAccent(it)
        }
    }
}

/** "主色" over its current value, as two texts (no joiner). */
@Composable
private fun SeedRowLabel(title: String, value: String) {
    Row(
        Modifier.padding(start = SettingsRowTextInset, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedContent(
            targetState = value,
            transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard) },
            label = "seedName",
        ) { name ->
            Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SeedRow(selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(SettingsChoices.seeds, key = { _, it -> it.first }) { _, (argb, label) ->
            SeedSwatch(
                color = Color(argb),
                label = label,
                selected = argb == selected,
                onClick = { onSelect(argb) },
            )
        }
    }
}

@Composable
private fun SeedSwatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    MorphSwatch(
        label = label,
        selected = selected,
        checkTint = if (color.luminance() > 0.5f) Color.Black else Color.White,
        onClick = onClick,
        paint = { drawRect(color) },
    )
}

/** A pair: 主色 on the upper-left half, 强调色 on the lower-right, split on the diagonal. */
@Composable
private fun PairSwatch(primary: Color, accent: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val luminance = (primary.luminance() + accent.luminance()) / 2f
    // Repainted every frame of the morph's turn: one path, rewound.
    val path = remember { Path() }
    MorphSwatch(
        label = label,
        selected = selected,
        checkTint = if (luminance > 0.5f) Color.Black else Color.White,
        onClick = onClick,
        paint = {
            drawRect(primary)
            path.apply {
                rewind()
                moveTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, accent)
        },
    )
}

@Composable
private fun MorphSwatch(
    label: String,
    selected: Boolean,
    checkTint: Color,
    onClick: () -> Unit,
    paint: DrawScope.() -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "seedMorph",
    )
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val shape = remember(progress) { MorphShape(morph, progress) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer { rotationZ = progress * 40f }
            .clip(shape)
            .drawBehind(paint)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            YoinSymbols.Check,
            contentDescription = null,
            tint = checkTint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    rotationZ = -progress * 40f
                    alpha = progress.coerceIn(0f, 1f)
                    scaleX = 0.6f + 0.4f * progress
                    scaleY = 0.6f + 0.4f * progress
                },
        )
    }
}

// ── 播放 ────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.PlayBody() {
    val qn by AppSettings.defaultVideoQuality.state.collectAsState()
    val aq by AppSettings.defaultAudioQuality.state.collectAsState()
    val codec by AppSettings.preferredCodec.state.collectAsState()
    val speed by AppSettings.defaultSpeed.state.collectAsState()
    val autoPlay by AppSettings.autoPlay.state.collectAsState()
    val autoNext by AppSettings.autoPlayNext.state.collectAsState()
    val autoFs by AppSettings.autoFullscreenLandscape.state.collectAsState()
    val bg by AppSettings.backgroundPlay.state.collectAsState()
    val pip by AppSettings.autoPip.state.collectAsState()
    val liveQn by LiveDefaultQn.state.collectAsState()
    val subtitleMode by SubtitleDefaultMode.state.collectAsState()

    SettingsGroup("画质") {
        item {
            SettingsChoiceItem("默认画质", SettingsChoices.videoQuality, qn, { AppSettings.set(AppSettings.defaultVideoQuality, it) }, icon = PiliSymbols.Hd)
        }
        item {
            SettingsChoiceItem("默认音质", SettingsChoices.audioQuality, aq, { AppSettings.set(AppSettings.defaultAudioQuality, it) }, icon = YoinSymbols.Headphones)
        }
        item {
            SettingsToggleItem("解码格式", SettingsChoices.codec, codec, { AppSettings.set(AppSettings.preferredCodec, it) }, icon = PiliSymbols.Video)
        }
        item {
            SettingsChoiceItem(
                "直播默认画质",
                SettingsChoices.liveQuality,
                liveQn,
                { AppSettings.set(LiveDefaultQn, it) },
                icon = PiliSymbols.Live,
                fallbackLabel = LiveQnNames[liveQn] ?: liveQn.toString(),
            )
        }
    }
    SettingsGroup("播放") {
        item {
            SettingsChoiceItem(
                "默认倍速",
                SettingsChoices.speed,
                speed,
                { AppSettings.set(AppSettings.defaultSpeed, it) },
                icon = YoinSymbols.Speed,
                fallbackLabel = speedLabel(speed),
            )
        }
        item {
            SettingsSwitchItem("自动播放", autoPlay, { AppSettings.set(AppSettings.autoPlay, it) }, icon = YoinSymbols.PlayCircle, summary = "进入详情页自动播放")
        }
        item {
            SettingsSwitchItem("自动连播", autoNext, { AppSettings.set(AppSettings.autoPlayNext, it) }, icon = YoinSymbols.SkipNext, summary = "播放结束后续播下一个")
        }
        item {
            SettingsSwitchItem("横屏自动全屏", autoFs, { AppSettings.set(AppSettings.autoFullscreenLandscape, it) }, icon = PiliSymbols.Fullscreen, summary = "旋转至横屏时进入全屏")
        }
        item {
            SettingsChoiceItem("默认字幕", SettingsChoices.subtitleDefault, subtitleMode, { AppSettings.set(SubtitleDefaultMode, it) }, icon = PiliSymbols.Subtitles)
        }
    }
    SettingsGroup("后台") {
        item {
            val keep by com.gpo.pilipala.feature.sources.KeepPlaying.mode.state.collectAsState()
            SettingsSwitchItem(
                "离开视频后继续播放",
                keep == 2,
                { AppSettings.set(com.gpo.pilipala.feature.sources.KeepPlaying.mode, if (it) 2 else 1) },
                icon = YoinSymbols.PlayFilled,
                summary = "回到首页时视频收进底部的迷你条，像 YouTube 那样",
            )
        }
        item {
            SettingsSwitchItem("后台播放", bg, { AppSettings.set(AppSettings.backgroundPlay, it) }, icon = YoinSymbols.VolumeUp, summary = "进入后台时继续播放")
        }
        item {
            SettingsSwitchItem("自动画中画", pip, { AppSettings.set(AppSettings.autoPip, it) }, icon = PiliSymbols.Pip, summary = "进入后台时画中画播放")
        }
    }
}

// ── 弹幕 ────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.DanmakuBody() {
    val enabled by AppSettings.danmakuEnabled.state.collectAsState()
    val opacity by AppSettings.danmakuOpacity.state.collectAsState()
    val fontScale by AppSettings.danmakuFontScale.state.collectAsState()
    val area by AppSettings.danmakuArea.state.collectAsState()
    val speed by AppSettings.danmakuSpeed.state.collectAsState()
    val hideTop by AppSettings.danmakuHideTop.state.collectAsState()
    val hideBottom by AppSettings.danmakuHideBottom.state.collectAsState()
    val hideScroll by AppSettings.danmakuHideScroll.state.collectAsState()
    val blockLevel by DanmakuBlockLevel.state.collectAsState()
    val merge by DanmakuMergeDuplicates.state.collectAsState()
    val rulesRaw by DanmakuBlockRules.state.collectAsState()
    val ruleCount = remember(rulesRaw) { DanmakuRules.parse(rulesRaw).size }
    var rulesOpen by remember { mutableStateOf(false) }

    DanmakuPreview(
        enabled = enabled,
        opacity = opacity,
        fontScale = fontScale,
        area = area,
        speed = speed,
        hideTop = hideTop,
        hideBottom = hideBottom,
        hideScroll = hideScroll,
    )
    SettingsGroup(null) {
        item {
            SettingsSwitchItem("弹幕", enabled, { AppSettings.set(AppSettings.danmakuEnabled, it) }, icon = if (enabled) PiliSymbols.Danmaku else PiliSymbols.DanmakuOff)
        }
    }
    SettingsGroup("显示") {
        item {
            SettingsSliderItem(
                title = "不透明度",
                value = opacity,
                onValueChange = { AppSettings.set(AppSettings.danmakuOpacity, it) },
                valueRange = 0.1f..1f,
                steps = 17,
                label = { "${(it * 100).roundToInt()}%" },
                icon = YoinSymbols.Visibility,
                enabled = enabled,
            )
        }
        item {
            SettingsSliderItem(
                title = "字体大小",
                value = fontScale,
                onValueChange = { AppSettings.set(AppSettings.danmakuFontScale, it) },
                valueRange = 0.5f..2f,
                steps = 14,
                label = { "${(it * 100).roundToInt()}%" },
                icon = PiliSymbols.Subtitles,
                enabled = enabled,
            )
        }
        item {
            SettingsSliderItem(
                title = "显示区域",
                value = area,
                onValueChange = { AppSettings.set(AppSettings.danmakuArea, it) },
                valueRange = 0.25f..1f,
                steps = 2,
                label = ::areaLabel,
                icon = YoinSymbols.OpenInFull,
                enabled = enabled,
            )
        }
        item {
            SettingsSliderItem(
                title = "滚动速度",
                value = speed,
                onValueChange = { AppSettings.set(AppSettings.danmakuSpeed, it) },
                valueRange = 0.5f..2f,
                steps = 5,
                label = ::speedLabel,
                icon = YoinSymbols.Speed,
                enabled = enabled,
            )
        }
        item {
            SettingsSwitchItem("合并重复弹幕", merge, { AppSettings.set(DanmakuMergeDuplicates, it) }, icon = YoinSymbols.Copy, enabled = enabled)
        }
    }
    SettingsGroup("屏蔽") {
        item {
            SettingsItem(
                title = "屏蔽词",
                icon = YoinSymbols.VisibilityOff,
                enabled = enabled,
                onClick = { rulesOpen = true },
                trailing = {
                    ValueLabel(if (ruleCount > 0) "$ruleCount 条" else "无")
                    Icon(YoinSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                },
            )
        }
        item {
            SettingsSliderItem(
                title = "屏蔽等级",
                value = blockLevel.toFloat(),
                onValueChange = { AppSettings.set(DanmakuBlockLevel, it.roundToInt().coerceIn(0, 10)) },
                valueRange = 0f..10f,
                steps = 9,
                label = { danmakuLevelLabel(it.roundToInt()) },
                icon = YoinSymbols.Filter,
                enabled = enabled,
            )
        }
        item {
            SettingsSwitchItem("顶部弹幕", hideTop, { AppSettings.set(AppSettings.danmakuHideTop, it) }, icon = PiliSymbols.DanmakuTop, enabled = enabled)
        }
        item {
            SettingsSwitchItem("底部弹幕", hideBottom, { AppSettings.set(AppSettings.danmakuHideBottom, it) }, icon = PiliSymbols.DanmakuBottom, enabled = enabled)
        }
        item {
            SettingsSwitchItem("滚动弹幕", hideScroll, { AppSettings.set(AppSettings.danmakuHideScroll, it) }, icon = PiliSymbols.DanmakuScroll, enabled = enabled)
        }
    }
    if (rulesOpen) {
        DanmakuBlockRulesSheet(onDismiss = { rulesOpen = false })
    }
}

// ── 推荐 ────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.RecommendBody() {
    val source by AppSettings.rcmdSource.state.collectAsState()
    val minDuration by RcmdMinDuration.state.collectAsState()
    val minLikeRatio by RcmdMinLikeRatio.state.collectAsState()
    val exemptFollowed by RcmdExemptFollowed.state.collectAsState()
    val accessKey by AppAccessKey.state.collectAsState()
    val user by Account.user.collectAsState()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf(false) }
    val hasKey = accessKey.isNotEmpty() && AppAuth.validKey.isNotEmpty()
    SettingsGroup(null) {
        item {
            SettingsToggleItem(
                title = "首页推荐类型",
                icon = YoinSymbols.Sparkle,
                choices = SettingsChoices.rcmdSource,
                selected = source,
                onSelect = { AppSettings.set(AppSettings.rcmdSource, it) },
            )
        }
        if (source == "app" && user != null) {
            item {
                SettingsItem(
                    title = "access_key",
                    icon = PiliSymbols.Lock,
                    enabled = !fetching,
                    onClick = {
                        fetching = true
                        scope.launch {
                            val result = runCatching { AppAuth.refresh() }
                            fetching = false
                            result.onSuccess { toast("已刷新 access_key") }
                                .onFailure { toast(it.friendlyMessage()) }
                        }
                    },
                    trailing = { ValueLabel(if (fetching) "获取中" else if (hasKey) "已获取" else "未获取") },
                )
            }
        }
    }
    SettingsGroup("过滤") {
        item {
            SettingsChoiceItem(
                title = "视频时长",
                icon = PiliSymbols.Video,
                choices = SettingsChoices.rcmdMinDuration,
                selected = minDuration,
                onSelect = { AppSettings.set(RcmdMinDuration, it) },
                fallbackLabel = "$minDuration 秒",
            )
        }
        item {
            SettingsChoiceItem(
                title = "点赞率",
                icon = PiliSymbols.ThumbUp,
                choices = SettingsChoices.rcmdMinLikeRatio,
                selected = minLikeRatio,
                onSelect = { AppSettings.set(RcmdMinLikeRatio, it) },
                fallbackLabel = "$minLikeRatio%",
            )
        }
        item {
            SettingsSwitchItem(
                title = "已关注 UP 豁免",
                checked = exemptFollowed,
                onCheckedChange = { AppSettings.set(RcmdExemptFollowed, it) },
                icon = PiliSymbols.PersonCheck,
            )
        }
    }
}

// ── 隐私 ────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.PrivacyBody() {
    val user by Account.user.collectAsState()
    val loggedIn = user != null
    val pause by AppSettings.pauseHistory.state.collectAsState()
    val history by AppSettings.searchHistory.state.collectAsState()
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var confirmCookies by remember { mutableStateOf(false) }

    // The server owns the "pause history" switch once signed in.
    LaunchedEffect(loggedIn) {
        if (!loggedIn) return@LaunchedEffect
        runCatching { BiliClient.getData("/x/v2/history/shadow") }
            .onSuccess { AppSettings.set(AppSettings.pauseHistory, it.asBool()) }
    }

    SettingsGroup("观看历史") {
        item {
            SettingsSwitchItem(
                title = "暂停观看历史",
                icon = PiliSymbols.History,
                summary = if (loggedIn) "同步至账号" else null,
                checked = pause,
                onCheckedChange = { v ->
                    AppSettings.set(AppSettings.pauseHistory, v)
                    if (loggedIn) {
                        scope.launch {
                            runCatching { BiliClient.postData("/x/v2/history/shadow/set", mapOf("switch" to v)) }
                                .onSuccess { toast(if (v) "已暂停观看历史" else "已恢复观看历史") }
                                .onFailure {
                                    AppSettings.set(AppSettings.pauseHistory, !v)
                                    toast(it.friendlyMessage())
                                }
                        }
                    }
                },
            )
        }
    }
    SettingsGroup("搜索") {
        item {
            SettingsItem(
                title = "清空搜索历史",
                icon = YoinSymbols.Search,
                enabled = history.isNotEmpty(),
                onClick = {
                    AppSettings.set(AppSettings.searchHistory, "")
                    toast("已清空")
                },
                trailing = { if (history.isEmpty()) ValueLabel("无") },
            )
        }
    }
    SettingsGroup("账号") {
        item {
            SettingsLinkItem(
                icon = PiliSymbols.PersonCheck,
                title = "黑名单管理",
                summary = "已拉黑用户",
                onClick = { if (loggedIn) nav.push(Route.Blacklist) else toast("登录后查看") },
            )
        }
        item {
            SettingsItem(
                title = "清除 Cookie",
                icon = YoinSymbols.Delete,
                summary = "同时退出登录",
                onClick = { confirmCookies = true },
            )
        }
    }

    if (confirmCookies) {
        AlertDialog(
            onDismissRequest = { confirmCookies = false },
            icon = { Icon(YoinSymbols.Delete, null) },
            title = { Text("清除 Cookie") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCookies = false
                    clearAllCookies()
                    toast("已清除")
                    scope.launch { BiliClient.ensureBuvid() }
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { confirmCookies = false }) { Text("取消") } },
        )
    }
}

/** App cookie jar + WebView cookies/storage, and the signed-in user with them. */
internal fun clearAllCookies() {
    Account.logout()
    runCatching {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}

// ── 其他 ────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.ExtraBody() {
    val context = LocalContext.current
    val replySort by AppSettings.replySort.state.collectAsState()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) {
            runCatching { SingletonImageLoader.get(context).diskCache?.size ?: 0L }.getOrDefault(0L)
        }
    }

    SettingsGroup("评论") {
        item {
            SettingsToggleItem(
                title = "评论展示",
                icon = PiliSymbols.Comment,
                choices = SettingsChoices.replySort,
                selected = replySort,
                onSelect = { AppSettings.set(AppSettings.replySort, it) },
            )
        }
    }
    SettingsGroup("存储") {
        item {
            SettingsItem(
                title = "清除图片缓存",
                icon = YoinSymbols.Storage,
                enabled = cacheSize != 0L,
                onClick = {
                    scope.launch {
                        val cleared = withContext(Dispatchers.IO) {
                            runCatching {
                                val loader = SingletonImageLoader.get(context)
                                loader.memoryCache?.clear()
                                loader.diskCache?.clear()
                            }
                        }
                        if (cleared.isFailure) {
                            toast("清除失败")
                            return@launch
                        }
                        cacheSize = 0L
                        toast("已清除")
                    }
                },
                trailing = { if (cacheSize >= 0) ValueLabel(formatBytes(cacheSize)) },
            )
        }
    }
    CloudSyncGroup()
    SettingsGroup("应用") {
        item {
            SettingsItem(
                title = "版本",
                icon = YoinSymbols.Info,
                trailing = { ValueLabel(BuildConfig.VERSION_NAME) },
            )
        }
        item { CrashLogItem(toast) }
    }
}

/**
 * 崩溃日志 (core/util/CrashLog.kt), always listed so it can be found: the newest crash's line, a tap
 * copies every kept one.
 */
@Composable
internal fun CrashLogItem(toast: (String) -> Unit) {
    val context = LocalContext.current
    val summaries = androidx.compose.runtime.remember { com.gpo.pilipala.core.util.CrashLog.summaries() }
    SettingsItem(
        title = "崩溃日志",
        icon = YoinSymbols.Info,
        summary = summaries.firstOrNull()?.let { "${summaries.size} 次 · 最近：" + it.substringAfter(" · ") } ?: "没有记录",
        onClick = {
            val text = com.gpo.pilipala.core.util.CrashLog.read()
            if (text == null) {
                toast("没有崩溃记录")
            } else {
                val copied = runCatching {
                    context.getSystemService(android.content.ClipboardManager::class.java)!!
                        .setPrimaryClip(android.content.ClipData.newPlainText("PiliPaw crash", text))
                }.isSuccess
                if (copied) {
                    toast("已复制 ${summaries.size} 次崩溃日志")
                } else {
                    // The clipboard refused it: hand it to the share sheet instead.
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, text.take(100_000)),
                                "崩溃日志",
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { toast("复制失败") }
                }
            }
        },
    )
}

// ── 云同步 ──────────────────────────────────────────────────────────────────

/**
 * 云同步 (feature/sources/PawCloud.kt): on/off, 立即同步, and what the state needs from the user:
 * Google's consent sheet, or, when the Google Cloud project does not know this build, its package
 * and certificate to register.
 */
@Composable
private fun ColumnScope.CloudSyncGroup() {
    val on by com.gpo.pilipala.feature.sources.PawCloud.enabled.state.collectAsState()
    val state = com.gpo.pilipala.feature.sources.PawCloud.state
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { r -> com.gpo.pilipala.feature.sources.PawCloud.resumeAfterConsent(r.resultCode == android.app.Activity.RESULT_OK, r.data) }
    androidx.compose.runtime.LaunchedEffect(state) {
        if (state is com.gpo.pilipala.feature.sources.PawCloud.State.NeedsUser) {
            consent.launch(androidx.activity.result.IntentSenderRequest.Builder(state.sender).build())
        }
    }
    val summary = when (state) {
        com.gpo.pilipala.feature.sources.PawCloud.State.Syncing -> "正在同步…"
        is com.gpo.pilipala.feature.sources.PawCloud.State.Done -> "已同步"
        is com.gpo.pilipala.feature.sources.PawCloud.State.Failed -> "同步失败：" + state.message
        is com.gpo.pilipala.feature.sources.PawCloud.State.Misconfigured -> "Google Cloud 未登记 ${state.packageName}（SHA-1 ${state.sha1 ?: "未知"}）"
        else -> "Mix、订阅、稍后再看、歌词和设置；不含登录信息"
    }
    SettingsGroup("云同步") {
        item {
            SettingsSwitchItem(
                "同步到 Google Drive",
                on,
                { if (it) com.gpo.pilipala.feature.sources.PawCloud.sync(interactive = true) else com.gpo.pilipala.feature.sources.PawCloud.turnOff() },
                icon = YoinSymbols.Cloud,
                summary = summary,
            )
        }
        if (on) {
            item {
                SettingsItem(
                    title = "立即同步",
                    icon = YoinSymbols.Refresh,
                    enabled = state != com.gpo.pilipala.feature.sources.PawCloud.State.Syncing,
                    onClick = { com.gpo.pilipala.feature.sources.PawCloud.sync(interactive = true) },
                )
            }
        }
    }
}
