package com.gpo.pilipala.feature.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.player.sheets.QualitySliderPage
import com.gpo.pilipala.feature.player.sheets.SpeedSliderPage
import com.gpo.pilipala.feature.widget.WidgetStore
import com.gpo.pilipala.ui.components.SheetIconAction
import com.gpo.pilipala.ui.components.SheetIconRow
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SheetPage { Main, Quality, Audio, Codec, Speed, Danmaku, Sleep, Subtitle, DanmakuBlock, Chapters, Cache }

private val SleepOptions = listOf(0, 15, 30, 45, 60, 90)

internal fun speedLabel(v: Float): String =
    if (v == v.toInt().toFloat()) "${v.toInt()}.0x" else "${String.format(Locale.US, "%.2f", v).trimEnd('0')}x"

/**
 * The player's "more" sheet (Flutter header_control: 画质 / 音质 / 解码格式 /
 * 播放速度 / 弹幕设置 / 定时关闭 / 播放顺序 / 字幕 / 稍后再看). One sheet with
 * spring-animated pages; quality / speed buttons open it straight on their page.
 * Inline it is a bottom sheet; fullscreen uses [PlayerSidePanel] with the same pages.
 */
@Composable
internal fun PlayerSheet(
    ctrl: PiliPlayerController,
    start: SheetPage,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    // Edge to edge: the sheet's surface runs under the navigation bar (the pages pad
    // themselves by its inset and scroll beneath it), and the sheet window keeps the
    // page's status-bar icons, which sit on the black player strip (light) instead of
    // the theme's dark ones that vanished there.
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // A tall page stops short of the status bar (which stays over the scrim, as the page
    // has it) instead of sliding the sheet under it.
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val maxContent = (windowHeight - statusTop - 72.dp).coerceAtLeast(240.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        properties = rememberPlayerSheetProperties(),
    ) {
        Box(Modifier.heightIn(max = maxContent)) {
            PlayerSheetPages(ctrl = ctrl, start = start, close = close, topGap = 0.dp, bottomGap = 24.dp + navBottom)
        }
    }
}

/**
 * Sheet window properties that match the page under it: status-bar icons as the page has
 * them (light over the black player strip), navigation-bar icons for the sheet's surface.
 */
@Composable
internal fun rememberPlayerSheetProperties(): ModalBottomSheetProperties =
    com.gpo.pilipala.ui.components.rememberSheetProperties()

/**
 * Fullscreen settings: a panel sliding in from the end edge inside the picture, over a
 * scrim that closes it. It lives in the player frame, so it turns with a turned picture
 * and never opens a window (which would bring the system bars back).
 */
@Composable
internal fun PlayerSidePanel(
    page: SheetPage?,
    onDismiss: () -> Unit,
    content: @Composable (start: SheetPage, close: () -> Unit) -> Unit,
) {
    // The last page stays drawn while the panel slides out.
    val shown = remember { arrayOfNulls<SheetPage>(1) }
    if (page != null) shown[0] = page
    val visible = page != null
    BackHandler(enabled = visible) { onDismiss() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = (maxWidth * 0.42f).coerceIn(300.dp, 420.dp).coerceAtMost(maxWidth)
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = fadeOut(YoinMotion.defaultEffectsSpec()),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(YoinMotion.defaultSpatialSpec()) { it } + fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = slideOutHorizontally(YoinMotion.fastSpatialSpec()) { it } + fadeOut(YoinMotion.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                shape = YoinContainerShapes.Panel,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .padding(8.dp)
                    // Taps inside stay inside (they would otherwise close the panel through the scrim).
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                shown[0]?.let { start ->
                    key(start) { content(start, onDismiss) }
                }
            }
        }
    }
}

/** The sheet's pages: a header with back, then the page, springing between pages. */
@Composable
internal fun PlayerSheetPages(
    ctrl: PiliPlayerController,
    start: SheetPage,
    close: () -> Unit,
    topGap: Dp = 12.dp,
    bottomGap: Dp = 16.dp,
) {
    var page by remember { mutableStateOf(start) }
    val motion = MaterialTheme.motionScheme
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
                .padding(top = topGap, bottom = bottomGap),
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
                    SheetPage.Chapters -> "章节"
                    SheetPage.Cache -> "缓存"
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
                SheetPage.Quality -> QualitySliderPage(ctrl)
                SheetPage.Audio -> AudioPage(ctrl, close)
                SheetPage.Codec -> CodecPage(ctrl, close)
                SheetPage.Speed -> SpeedSliderPage(ctrl)
                SheetPage.Danmaku -> DanmakuPage(onOpenBlock = { page = SheetPage.DanmakuBlock })
                SheetPage.Sleep -> SleepPage(ctrl, close)
                SheetPage.Subtitle -> SubtitlePage(ctrl, close)
                SheetPage.DanmakuBlock -> DanmakuBlockRulesEditor()
                SheetPage.Chapters -> ChaptersPage(ctrl, close)
                SheetPage.Cache -> com.gpo.pilipala.feature.download.CachePage(ctrl, close)
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

/**
 * 播放设置 in tiers rather than one long list: round one-tap actions (稍后再看, 缓存, 截图,
 * 定时关闭), the speed strip, the stream rows (画质, 音质…), 画面, then the 播放 switches.
 */
@Composable
private fun MainPage(ctrl: PiliPlayerController, onOpen: (SheetPage) -> Unit, onClose: () -> Unit) {
    val pi = ctrl.info
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val autoNext by AppSettings.autoPlayNext.state.collectAsState()
    val background by AppSettings.backgroundPlay.state.collectAsState()
    val t = ctrl.target
    val cdn by com.gpo.pilipala.feature.player.cdn.CdnRouter.status.collectAsState()
    val shot = LocalPlayerShot.current
    // 稍后再看 answers in its own circle (a tick, 已添加) before the sheet goes.
    var laterDone by remember { mutableStateOf<String?>(null) }
    val canLater = BiliClient.isLoggedIn && t != null && !t.isPgc && (t.aid > 0 || t.bvid.isNotBlank())
    val sleepValue = if (ctrl.sleepMinutes > 0) {
        val left = ((ctrl.sleepDeadline - SystemClock.elapsedRealtime()) / 60_000L).coerceAtLeast(0L) + 1
        "$left 分钟"
    } else {
        null
    }

    SheetIconRow(Modifier.padding(bottom = 16.dp)) {
        if (canLater && t != null) {
            SheetIconAction(PiliSymbols.WatchLater, "稍后再看", done = laterDone, enabled = laterDone == null, onClick = {
                scope.launch {
                    val result = runCatching {
                        BiliClient.postData(
                            "/x/v2/history/toview/add",
                            buildMap {
                                if (t.aid > 0) put("aid", t.aid) else put("bvid", t.bvid)
                            },
                        )
                        WidgetStore.laterChanged()
                    }
                    result.onSuccess {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        laterDone = "已添加"
                        kotlinx.coroutines.delay(820)
                        onClose()
                    }.onFailure { toast(it.message ?: "添加失败") }
                }
            })
        }
        if (t != null) SheetIconAction(YoinSymbols.Download, "缓存", onClick = { onOpen(SheetPage.Cache) })
        if (shot != null) {
            SheetIconAction(ScreenshotSymbol, "截图", onClick = {
                onClose()
                shot()
            })
        }
        SheetIconAction(YoinSymbols.Sleep, sleepValue ?: "定时关闭", onClick = { onOpen(SheetPage.Sleep) })
    }

    SpeedStrip(ctrl, onMore = { onOpen(SheetPage.Speed) })
    Spacer(Modifier.height(12.dp))

    val playback = buildList {
        add(RowSpec(PiliSymbols.Hd, "画质", value = Qualities.videoShort(ctrl.currentQn).takeIf { pi != null }) { onOpen(SheetPage.Quality) })
        if (pi != null && pi.isDash && StreamPicker.playableAudios(pi).size > 1) {
            add(RowSpec(YoinSymbols.Headphones, "音质", value = Qualities.audioName(ctrl.currentAudio)) { onOpen(SheetPage.Audio) })
        }
        if (pi != null && pi.isDash && StreamPicker.codecsFor(pi, ctrl.currentQn).size > 1) {
            add(RowSpec(YoinSymbols.Memory, "解码格式", value = Qualities.codecName(ctrl.currentCodec)) { onOpen(SheetPage.Codec) })
        }
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
        if (ctrl.chapters.isNotEmpty()) {
            add(RowSpec(YoinSymbols.Playlist, "章节", value = "${ctrl.chapters.size}") { onOpen(SheetPage.Chapters) })
        }
        add(RowSpec(PiliSymbols.Danmaku, "弹幕设置") { onOpen(SheetPage.Danmaku) })
    }
    SegmentedGroup(playback)

    PictureFitChoice()

    SectionLabel("播放")
    SegmentedGroup(
        listOf(
            RowSpec(YoinSymbols.RepeatOne, "单集循环", switch = ctrl.loop) { ctrl.changeLoop(!ctrl.loop) },
            RowSpec(YoinSymbols.SkipNext, "自动连播", switch = autoNext) { AppSettings.set(AppSettings.autoPlayNext, !autoNext) },
            RowSpec(YoinSymbols.MusicNote, "仅听声音", switch = ctrl.audioOnly) { ctrl.toggleAudioOnly() },
            RowSpec(YoinSymbols.Headphones, "后台播放", switch = background) { AppSettings.set(AppSettings.backgroundPlay, !background) },
        ),
    )
}

/** Speeds offered right in the strip; the rest (1.75, 3.0) are one tap away on the slider page. */
private val StripSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private fun stripFigure(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}.0" else v.toString()

/** 播放速度 as a strip: its name and value (tap for the slider page), then the common speeds. */
@Composable
private fun SpeedStrip(ctrl: PiliPlayerController, onMore: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Surface(shape = YoinContainerShapes.Panel, color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(YoinContainerShapes.ListRow)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onMore()
                    }
                    .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(YoinSymbols.Speed, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("播放速度", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(speedLabel(ctrl.speed), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                Icon(YoinSymbols.ChevronRight, null, Modifier.padding(start = 4.dp).size(18.dp), tint = scheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StripSpeeds.forEach { v ->
                    val on = kotlin.math.abs(ctrl.speed - v) < 0.01f
                    val spec = YoinMotion.defaultEffectsSpec<Color>()
                    val container by animateColorAsState(if (on) scheme.primary else scheme.surfaceContainerHighest, spec, label = "speedPill")
                    val content by animateColorAsState(if (on) scheme.onPrimary else scheme.onSurfaceVariant, spec, label = "speedPillText")
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(YoinShapeTokens.Full)
                            .background(container)
                            .clickable {
                                if (!on) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    ctrl.changeSpeed(v)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stripFigure(v), style = MaterialTheme.typography.labelLarge.withTabularFigures(), color = content, maxLines = 1)
                    }
                }
            }
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

/**
 * The chapters: a still, the title and the start time per row; the one under the playhead
 * sits on the selected block. Tapping a row seeks to its start.
 */
@Composable
private fun ChaptersPage(ctrl: PiliPlayerController, close: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val chapters = ctrl.chapters
    val current = rememberCurrentChapter(ctrl)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        chapters.forEachIndexed { i, c ->
            val selected = c == current
            val container by androidx.compose.animation.animateColorAsState(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                YoinMotion.defaultEffectsSpec(),
                label = "chapterRow",
            )
            val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
            Surface(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    ctrl.seekTo(c.fromMs)
                    close()
                },
                shape = segmentShape(i, chapters.size),
                color = container,
                contentColor = content,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.gpo.pilipala.ui.components.NetImage(
                        url = c.imgUrl.takeIf { it.isNotBlank() },
                        shape = YoinShapeTokens.Small,
                        thumbWidth = 112.dp,
                        modifier = Modifier.width(96.dp).height(54.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.title.ifEmpty { "${i + 1}" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Medium else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            clock(c.fromMs),
                            style = MaterialTheme.typography.labelMedium.withTabularFigures(),
                            color = if (selected) content.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(
                        visible = selected,
                        enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.4f),
                        exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.4f),
                    ) {
                        Icon(YoinSymbols.PlayArrow, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                    }
                }
            }
        }
    }
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
