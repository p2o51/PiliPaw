package com.gpo.pilipala.feature.live

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.gpo.pilipala.feature.player.rememberLandscapeTurn
import com.gpo.pilipala.feature.player.turnedContent
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.delay

private val Scrim = Color.Black.copy(alpha = 0.55f)

/**
 * The live video with a light overlay: back + title on top; play/pause,
 * refresh, danmaku, quality and fullscreen at the bottom. Tap toggles the
 * chrome, which hides itself after a few seconds of playback.
 */
@Composable
internal fun LivePlayerView(
    vm: LiveRoomViewModel,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pip: Boolean = false,
    onPip: (() -> Unit)? = null,
) {
    val info = vm.info
    val haptic = LocalHapticFeedback.current
    var chromeVisible by remember { mutableStateOf(true) }
    var tapCount by remember { mutableStateOf(0) }
    val live = info != null && info.liveStatus != 0 && vm.streamError != "未开播"
    val fadeInSpec = fadeIn(YoinMotion.defaultEffectsSpec())
    val fadeOutSpec = fadeOut(YoinMotion.defaultEffectsSpec())

    LaunchedEffect(chromeVisible, vm.isPlaying, tapCount) {
        if (chromeVisible && vm.isPlaying) {
            delay(4_000)
            chromeVisible = false
        }
    }

    // Large screens ignore (or letterbox) orientation requests, so a landscape stream in a
    // portrait window turns in software, like the video player: the picture springs from
    // the inline size into the whole window and follows the device's turn direction.
    val configuration = LocalConfiguration.current
    val activity = LocalActivity.current
    val phone = configuration.smallestScreenWidthDp < 600
    val windowPortrait = configuration.screenHeightDp > configuration.screenWidthDp
    val multiWindow = remember(configuration) { activity?.isInMultiWindowMode == true }
    val large = com.gpo.pilipala.feature.player.rememberLargeScreenFullscreen(
        activity = activity,
        want = fullscreen && !pip && !phone && !vm.isPortraitStream && !multiWindow,
    )
    val turned = large == com.gpo.pilipala.feature.player.LargeFullscreen.Turn && windowPortrait
    val turn = rememberLandscapeTurn(turned)
    val turnAngle = remember { Animatable(0f) }
    val turnTarget = if (turned) turn else 0f
    val turnSpec = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(turnTarget) { turnAngle.animateTo(turnTarget, turnSpec) }
    // A SurfaceView cannot be rotated; the turned picture (and its spring back) draws through a TextureView.
    val turning by remember { derivedStateOf { turnAngle.value != 0f } }
    val surfaceType = if (turned || turning) SURFACE_TYPE_TEXTURE_VIEW else SURFACE_TYPE_SURFACE_VIEW
    // Window insets describe the unturned window; the turned frame has no bars to avoid.
    val chromeInsets = fullscreen && !turned

    Box(
        modifier
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                chromeVisible = !chromeVisible
                tapCount++
            },
    ) {
        Box(Modifier.fillMaxSize().turnedContent(turned) { turnAngle.value }) {
            if (live) {
                // A TextureView keeps its old buffer size across a window resize (rotation) and
                // draws the picture small in a corner on some devices: give it a fresh view instead.
                key(surfaceType, if (surfaceType == SURFACE_TYPE_TEXTURE_VIEW) configuration.screenWidthDp * 10_000 + configuration.screenHeightDp else 0) {
                    ContentFrame(
                        player = vm.player,
                        modifier = Modifier.fillMaxSize(),
                        surfaceType = surfaceType,
                        contentScale = ContentScale.Fit,
                    )
                }
                LiveDanmakuLayer(vm.incoming, enabled = vm.danmakuOn && !pip, big = fullscreen, modifier = Modifier.fillMaxSize())
            } else {
                val cover = info?.cover?.ifEmpty { info.background }.orEmpty()
                NetImage(
                    cover,
                    Modifier.fillMaxSize(),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    thumbWidth = 720.dp,
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            }

            // Center: buffering / offline / error.
            AnimatedContent(
                targetState = when {
                    vm.loadingInfo -> CenterState.Loading
                    info != null && (info.liveStatus == 0 || vm.streamError == "未开播") -> CenterState.Offline
                    vm.streamError != null -> CenterState.Error
                    vm.loadingStream || vm.isBuffering -> CenterState.Loading
                    else -> CenterState.None
                },
                transitionSpec = { fadeInSpec togetherWith fadeOutSpec },
                modifier = Modifier.align(Alignment.Center),
                label = "center",
            ) { state ->
                when (state) {
                    CenterState.Loading -> LoadingIndicator(color = Color.White)
                    CenterState.Offline -> CenterPill("未开播")
                    CenterState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CenterPill(vm.streamError ?: "播放失败")
                        TextButton(onClick = { vm.loadStream() }) { Text("重试", color = Color.White) }
                    }
                    CenterState.None -> Spacer(Modifier.size(1.dp))
                }
            }

            AnimatedVisibility(
                visible = chromeVisible && !pip,
                enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                exit = fadeOut(YoinMotion.defaultEffectsSpec()),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Top bar
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Scrim, Color.Transparent)))
                            .then(if (chromeInsets) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OverlayIcon(YoinSymbols.Back, "返回", onBack)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            info?.title.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Bottom bar
                    if (live) {
                        Row(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                                .then(if (chromeInsets) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            OverlayIcon(
                                if (vm.isPlaying) YoinSymbols.PauseFilled else YoinSymbols.PlayFilled,
                                if (vm.isPlaying) "暂停" else "播放",
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                vm.togglePlay()
                                tapCount++
                            }
                            OverlayIcon(YoinSymbols.Refresh, "刷新") {
                                vm.loadStream()
                                tapCount++
                            }
                            Spacer(Modifier.weight(1f))
                            OverlayIcon(
                                if (vm.danmakuOn) PiliSymbols.Danmaku else PiliSymbols.DanmakuOff,
                                "弹幕",
                            ) {
                                vm.danmakuOn = !vm.danmakuOn
                                haptic.performHapticFeedback(
                                    if (vm.danmakuOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                                )
                                tapCount++
                            }
                            QualityButton(vm) { tapCount++ }
                            if (onPip != null) OverlayIcon(PiliSymbols.Pip, "小窗", onPip)
                            OverlayIcon(
                                if (fullscreen) PiliSymbols.FullscreenExit else PiliSymbols.Fullscreen,
                                if (fullscreen) "退出全屏" else "全屏",
                                onToggleFullscreen,
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class CenterState { Loading, Offline, Error, None }

@Composable
private fun CenterPill(text: String) {
    Text(
        text,
        modifier = Modifier
            .clip(ContinuousRoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
    )
}

@Composable
private fun OverlayIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
    ) {
        Icon(icon, contentDescription = desc)
    }
}

@Composable
private fun QualityButton(vm: LiveRoomViewModel, onInteract: () -> Unit) {
    if (vm.qualities.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val current = vm.qualities.firstOrNull { it.qn == vm.currentQn }?.desc ?: LiveQnNames[vm.currentQn] ?: "画质"
    Box {
        TextButton(onClick = { open = true; onInteract() }) {
            Text(current, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = ContinuousRoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            vm.qualities.forEach { q ->
                val selected = q.qn == vm.currentQn
                DropdownMenuItem(
                    text = {
                        Text(
                            q.desc,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingIcon = if (selected) {
                        { Icon(YoinSymbols.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = {
                        open = false
                        vm.changeQn(q.qn)
                    },
                )
            }
        }
    }
}
