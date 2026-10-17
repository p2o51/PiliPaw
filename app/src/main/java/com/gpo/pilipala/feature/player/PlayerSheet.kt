package com.gpo.pilipala.feature.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SheetPage { Main, Quality, Audio, Codec, Speed, Danmaku, Sleep, Subtitle, DanmakuBlock }

private val Speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
private val SleepOptions = listOf(0, 15, 30, 45, 60, 90)

internal fun speedLabel(v: Float): String =
    if (v == v.toInt().toFloat()) "${v.toInt()}.0x" else "${String.format(Locale.US, "%.2f", v).trimEnd('0')}x"

/**
 * The player's "more" sheet (Flutter header_control: 画质 / 音质 / 解码格式 /
 * 播放速度 / 弹幕设置 / 定时关闭 / 播放顺序 / 字幕 / 稍后再看). One sheet with
 * spring-animated pages; quality / speed buttons open it straight on their page.
 */
@Composable
internal fun PlayerSheet(
    ctrl: PiliPlayerController,
    start: SheetPage,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(start) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val motion = MaterialTheme.motionScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (
                    fadeIn(motion.defaultEffectsSpec()) +
                        slideInHorizontally(motion.defaultSpatialSpec()) { w -> if (forward) w / 6 else -w / 6 }
                    ) togetherWith fadeOut(motion.fastEffectsSpec()) using
                    SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() }
            },
            label = "playerSheetPage",
        ) { p ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                SheetHeader(
                    title = when (p) {
                        SheetPage.Main -> "播放设置"
                        SheetPage.Quality -> "画质"
                        SheetPage.Audio -> "音质"
                        SheetPage.Codec -> "解码格式"
                        SheetPage.Speed -> "播放速度"
                        SheetPage.Danmaku -> "弹幕设置"
                        SheetPage.Sleep -> "定时关闭"
                        SheetPage.Subtitle -> "字幕"
                        SheetPage.DanmakuBlock -> "屏蔽词"
                    },
                    onBack = when {
                        p == SheetPage.DanmakuBlock -> {
                            { page = SheetPage.Danmaku }
                        }
                        p != SheetPage.Main && start == SheetPage.Main -> {
                            { page = SheetPage.Main }
                        }
                        else -> null
                    },
                )
                when (p) {
                    SheetPage.Main -> MainPage(ctrl, onOpen = { page = it }, onClose = close)
                    SheetPage.Quality -> QualityPage(ctrl, close)
                    SheetPage.Audio -> AudioPage(ctrl, close)
                    SheetPage.Codec -> CodecPage(ctrl, close)
                    SheetPage.Speed -> SpeedPage(ctrl)
                    SheetPage.Danmaku -> DanmakuPage(onOpenBlock = { page = SheetPage.DanmakuBlock })
                    SheetPage.Sleep -> SleepPage(ctrl, close)
                    SheetPage.Subtitle -> SubtitlePage(ctrl, close)
                    SheetPage.DanmakuBlock -> DanmakuBlockRulesEditor()
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = onBack != null,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.defaultSpatialSpec(), initialScale = 0.6f),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.6f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onBack?.invoke() }, shapes = IconButtonDefaults.shapes()) {
                    Icon(YoinSymbols.Back, contentDescription = "返回")
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = if (onBack == null) 8.dp else 0.dp),
        )
    }
}

/** Segmented-list corner shape: big outer corners, small inner ones (M3 Expressive lists). */
private fun segmentShape(index: Int, count: Int): Shape {
    val big = 20.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
}

private class RowSpec(
    val icon: ImageVector?,
    val title: String,
    val value: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val tag: String? = null,
    val switch: Boolean? = null,
    val onClick: () -> Unit,
)

@Composable
private fun SegmentedGroup(rows: List<RowSpec>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, r -> SegmentRow(r, segmentShape(i, rows.size)) }
    }
}

@Composable
private fun SegmentRow(r: RowSpec, shape: Shape) {
    val haptics = LocalHapticFeedback.current
    val container = if (r.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (r.selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = {
            if (r.switch != null) {
                haptics.performHapticFeedback(if (r.switch) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            r.onClick()
        },
        enabled = r.enabled,
        shape = shape,
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (r.icon != null) {
                Icon(
                    r.icon,
                    contentDescription = null,
                    tint = if (r.selected) content else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(
                r.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (r.enabled) content else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (r.tag != null) {
                Surface(
                    shape = YoinShapeTokens.Full,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        r.tag,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            if (r.value != null) {
                Text(
                    r.value,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            if (r.switch != null) {
                Switch(checked = r.switch, onCheckedChange = { r.onClick() })
            }
            AnimatedVisibility(
                visible = r.selected,
                enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.4f),
                exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.4f),
            ) {
                Icon(YoinSymbols.Check, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(20.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun MainPage(ctrl: PiliPlayerController, onOpen: (SheetPage) -> Unit, onClose: () -> Unit) {
    val pi = ctrl.info
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val autoNext by AppSettings.autoPlayNext.state.collectAsState()
    val background by AppSettings.backgroundPlay.state.collectAsState()
    val t = ctrl.target
    val cdn by com.gpo.pilipala.feature.player.cdn.CdnRouter.status.collectAsState()

    val playback = buildList {
        add(RowSpec(PiliSymbols.Hd, "画质", value = Qualities.videoShort(ctrl.currentQn).takeIf { pi != null }) { onOpen(SheetPage.Quality) })
        if (pi != null && pi.isDash && StreamPicker.playableAudios(pi).size > 1) {
            add(RowSpec(YoinSymbols.Headphones, "音质", value = Qualities.audioName(ctrl.currentAudio)) { onOpen(SheetPage.Audio) })
        }
        if (pi != null && pi.isDash && StreamPicker.codecsFor(pi, ctrl.currentQn).size > 1) {
            add(RowSpec(YoinSymbols.Memory, "解码格式", value = Qualities.codecName(ctrl.currentCodec)) { onOpen(SheetPage.Codec) })
        }
        add(RowSpec(YoinSymbols.Speed, "播放速度", value = speedLabel(ctrl.speed)) { onOpen(SheetPage.Speed) })
        if (cdn.host.isNotEmpty()) {
            add(RowSpec(YoinSymbols.Storage, "线路", value = cdnLabel(cdn)) {
                scope.launch {
                    com.gpo.pilipala.feature.player.cdn.CdnRouter.testNow(null)
                    kotlinx.coroutines.delay(150)
                    val done = com.gpo.pilipala.feature.player.cdn.CdnRouter.status.first { !it.racing }
                    if (done.note.isNotEmpty()) toast(done.note)
                }
            })
        }
        if (ctrl.subtitles.isNotEmpty()) {
            add(
                RowSpec(
                    PiliSymbols.Subtitles,
                    "字幕",
                    value = ctrl.subtitles.getOrNull(ctrl.subtitleIndex)?.label ?: "关闭",
                ) { onOpen(SheetPage.Subtitle) },
            )
        }
        add(RowSpec(PiliSymbols.Danmaku, "弹幕设置") { onOpen(SheetPage.Danmaku) })
    }
    SegmentedGroup(playback)

    SectionLabel("播放")
    val sleepValue = if (ctrl.sleepMinutes > 0) {
        val left = ((ctrl.sleepDeadline - SystemClock.elapsedRealtime()) / 60_000L).coerceAtLeast(0L) + 1
        "$left 分钟"
    } else {
        "关闭"
    }
    SegmentedGroup(
        listOf(
            RowSpec(YoinSymbols.Sleep, "定时关闭", value = sleepValue) { onOpen(SheetPage.Sleep) },
            RowSpec(YoinSymbols.RepeatOne, "单集循环", switch = ctrl.loop) { ctrl.changeLoop(!ctrl.loop) },
            RowSpec(YoinSymbols.SkipNext, "自动连播", switch = autoNext) { AppSettings.set(AppSettings.autoPlayNext, !autoNext) },
            RowSpec(YoinSymbols.Headphones, "后台播放", switch = background) { AppSettings.set(AppSettings.backgroundPlay, !background) },
        ),
    )

    if (BiliClient.isLoggedIn && t != null && !t.isPgc && (t.aid > 0 || t.bvid.isNotBlank())) {
        Spacer(Modifier.height(16.dp))
        SegmentedGroup(
            listOf(
                RowSpec(PiliSymbols.WatchLater, "添加至稍后再看") {
                    scope.launch {
                        val result = runCatching {
                            BiliClient.postData(
                                "/x/v2/history/toview/add",
                                buildMap {
                                    if (t.aid > 0) put("aid", t.aid) else put("bvid", t.bvid)
                                },
                            )
                        }
                        toast(result.fold({ "已添加至稍后再看" }, { it.message ?: "添加失败" }))
                    }
                    onClose()
                },
            ),
        )
    }
}

@Composable
private fun QualityPage(ctrl: PiliPlayerController, close: () -> Unit) {
    val pi = ctrl.info ?: return
    val loggedIn = BiliClient.isLoggedIn
    SegmentedGroup(
        pi.qualities.map { q ->
            RowSpec(
                icon = null,
                title = q.label,
                selected = q.qn == ctrl.currentQn,
                enabled = q.available,
                tag = if (q.available) null else if (loggedIn) "大会员" else "登录",
            ) {
                ctrl.selectQuality(q.qn)
                close()
            }
        },
    )
    val default by AppSettings.defaultVideoQuality.state.collectAsState()
    if (ctrl.currentQn != default && pi.qualities.any { it.qn == ctrl.currentQn }) {
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { AppSettings.set(AppSettings.defaultVideoQuality, ctrl.currentQn) }) {
            Text("设为默认画质")
        }
    }
}

@Composable
private fun AudioPage(ctrl: PiliPlayerController, close: () -> Unit) {
    val pi = ctrl.info ?: return
    SegmentedGroup(
        StreamPicker.playableAudios(pi).sortedByDescending { Qualities.audioRank(it.id) }.map { a ->
            RowSpec(icon = null, title = Qualities.audioName(a.id), selected = a.id == ctrl.currentAudio) {
                ctrl.selectAudio(a.id)
                close()
            }
        },
    )
}

@Composable
private fun CodecPage(ctrl: PiliPlayerController, close: () -> Unit) {
    val pi = ctrl.info ?: return
    SegmentedGroup(
        StreamPicker.codecsFor(pi, ctrl.currentQn).map { c ->
            RowSpec(icon = null, title = Qualities.codecName(c), selected = c == ctrl.currentCodec) {
                ctrl.selectCodec(c)
                close()
            }
        },
    )
}

@Composable
private fun SpeedPage(ctrl: PiliPlayerController) {
    val haptics = LocalHapticFeedback.current
    val default by AppSettings.defaultSpeed.state.collectAsState()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Speeds.forEach { s ->
            ToggleButton(
                checked = ctrl.speed == s,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    ctrl.changeSpeed(s)
                },
                shapes = ToggleButtonDefaults.shapes(),
            ) {
                Text(speedLabel(s), style = MaterialTheme.typography.labelLarge.withTabularFigures())
            }
        }
    }
    AnimatedVisibility(
        visible = ctrl.speed != default,
        enter = fadeIn(YoinMotion.defaultEffectsSpec()),
        exit = fadeOut(YoinMotion.fastEffectsSpec()),
    ) {
        Box(Modifier.padding(top = 16.dp)) {
            FilledTonalButton(onClick = { AppSettings.set(AppSettings.defaultSpeed, ctrl.speed) }) {
                Text("设为默认速度")
            }
        }
    }
}

@Composable
private fun DanmakuPage(onOpenBlock: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val hideScroll by AppSettings.danmakuHideScroll.state.collectAsState()
    val hideTop by AppSettings.danmakuHideTop.state.collectAsState()
    val hideBottom by AppSettings.danmakuHideBottom.state.collectAsState()
    val area by AppSettings.danmakuArea.state.collectAsState()
    val opacity by AppSettings.danmakuOpacity.state.collectAsState()
    val fontScale by AppSettings.danmakuFontScale.state.collectAsState()
    val speed by AppSettings.danmakuSpeed.state.collectAsState()
    val blockLevel by DanmakuBlockLevel.state.collectAsState()
    val merge by DanmakuMergeDuplicates.state.collectAsState()
    val rulesRaw by DanmakuBlockRules.state.collectAsState()
    val ruleCount = remember(rulesRaw) { DanmakuRules.parse(rulesRaw).size }

    Text(
        "显示类型",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
    )
    val types = listOf(
        Triple("滚动", !hideScroll, AppSettings.danmakuHideScroll),
        Triple("顶部", !hideTop, AppSettings.danmakuHideTop),
        Triple("底部", !hideBottom, AppSettings.danmakuHideBottom),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        types.forEachIndexed { i, (label, shown, key) ->
            ToggleButton(
                checked = shown,
                onCheckedChange = { on ->
                    haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    AppSettings.set(key, !on)
                },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    types.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                AnimatedVisibility(visible = shown) {
                    Icon(YoinSymbols.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                }
                Text(label)
            }
        }
    }

    SliderSetting(
        label = "显示区域",
        value = area,
        range = 0.25f..1f,
        steps = 2,
        display = {
            when ((it * 4).roundToInt()) {
                1 -> "1/4"
                2 -> "半屏"
                3 -> "3/4"
                else -> "满屏"
            }
        },
        onChange = { AppSettings.set(AppSettings.danmakuArea, ((it * 4).roundToInt() / 4f).coerceIn(0.25f, 1f)) },
    )
    SliderSetting(
        label = "不透明度",
        value = opacity,
        range = 0.1f..1f,
        steps = 0,
        display = { "${(it * 100).roundToInt()}%" },
        onChange = { AppSettings.set(AppSettings.danmakuOpacity, it) },
    )
    SliderSetting(
        label = "字体大小",
        value = fontScale,
        range = 0.5f..2f,
        steps = 0,
        display = { "${(it * 100).roundToInt()}%" },
        onChange = { AppSettings.set(AppSettings.danmakuFontScale, (it * 20).roundToInt() / 20f) },
    )
    SliderSetting(
        label = "弹幕速度",
        value = speed,
        range = 0.5f..2f,
        steps = 0,
        display = { "${String.format(Locale.US, "%.1f", it)}x" },
        onChange = { AppSettings.set(AppSettings.danmakuSpeed, (it * 10).roundToInt() / 10f) },
    )

    SectionLabel("屏蔽")
    SegmentedGroup(
        listOf(
            RowSpec(YoinSymbols.VisibilityOff, "屏蔽词", value = if (ruleCount > 0) "$ruleCount 条" else null) { onOpenBlock() },
            RowSpec(YoinSymbols.Copy, "合并重复弹幕", switch = merge) { AppSettings.set(DanmakuMergeDuplicates, !merge) },
        ),
    )
    SliderSetting(
        label = "屏蔽等级",
        value = blockLevel.toFloat(),
        range = 0f..10f,
        steps = 9,
        display = { danmakuLevelLabel(it.roundToInt()) },
        onChange = { AppSettings.set(DanmakuBlockLevel, it.roundToInt().coerceIn(0, 10)) },
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Surface(
        shape = segmentShape(0, 1),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    display(value),
                    style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onChange,
                valueRange = range,
                steps = steps,
            )
        }
    }
}

@Composable
private fun SleepPage(ctrl: PiliPlayerController, close: () -> Unit) {
    SegmentedGroup(
        SleepOptions.map { m ->
            RowSpec(
                icon = null,
                title = if (m == 0) "关闭" else "$m 分钟",
                selected = ctrl.sleepMinutes == m,
            ) {
                ctrl.setSleepTimer(m)
                close()
            }
        },
    )
}

@Composable
private fun SubtitlePage(ctrl: PiliPlayerController, close: () -> Unit) {
    val rows = buildList {
        add(RowSpec(icon = null, title = "关闭", selected = ctrl.subtitleIndex < 0) {
            chooseSubtitle(ctrl, -1)
            close()
        })
        ctrl.subtitles.forEachIndexed { i, s ->
            add(RowSpec(icon = null, title = s.label, selected = ctrl.subtitleIndex == i) {
                chooseSubtitle(ctrl, i)
                close()
            })
        }
    }
    SegmentedGroup(rows)
}

/** "腾讯云（海外） 12 Mbps" — the line the video is read from, and its measured speed. */
private fun cdnLabel(st: com.gpo.pilipala.feature.player.cdn.CdnRouter.Status): String {
    if (st.racing) return "测试中"
    val (region, vendor) = com.gpo.pilipala.feature.player.cdn.CdnHosts.describe(st.host)
    val name = if (region.isEmpty()) vendor else "$vendor（$region）"
    if (st.goodputBps <= 0) return name
    val mbps = st.goodputBps / 1e6
    return name + " " + String.format(java.util.Locale.US, if (mbps >= 10) "%.0f Mbps" else "%.1f Mbps", mbps)
}
