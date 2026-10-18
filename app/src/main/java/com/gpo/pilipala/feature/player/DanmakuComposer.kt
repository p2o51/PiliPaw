package com.gpo.pilipala.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Last colour / mode used for sending, so the next line starts the same way. */
internal val DanmakuSendColor = AppSettings.register("dm_send_color", 0xFFFFFF)
internal val DanmakuSendMode = AppSettings.register("dm_send_mode", DanmakuMode.SCROLL)

/** bilibili's preset danmaku colours (RGB). These are the danmaku's own colours, not UI theme. */
private val SendColors = listOf(
    0xFFFFFF, 0xFE0302, 0xFF7204, 0xFFAA02, 0xFFD302, 0xA0EE00,
    0x00CD00, 0x019899, 0x4266BE, 0x89D5FF, 0xCC0273, 0x9B9B9B,
)

private const val MaxLength = 100

/** True when the viewer can send danmaku for [target] (signed in, and the video has an aid/bvid). */
@Composable
internal fun rememberCanSendDanmaku(target: PlayTarget?): Boolean {
    val user by Account.user.collectAsState()
    target ?: return false
    return (user != null || BiliClient.isLoggedIn) && BiliClient.csrf.isNotEmpty() && target.cid > 0 &&
        (target.aid > 0 || target.bvid.isNotBlank())
}

/**
 * 发弹幕: text, mode (滚动 / 顶部 / 底部) and a preset colour. Posts at the current
 * playback position and drops the line onto the screen as soon as the server accepts it.
 */
@Composable
internal fun DanmakuComposerSheet(
    ctrl: PiliPlayerController,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val toast = rememberToast()
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val color by DanmakuSendColor.state.collectAsState()
    val mode by DanmakuSendMode.state.collectAsState()
    val motion = MaterialTheme.motionScheme

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    fun close() {
        scope.launch {
            keyboard?.hide()
            sheetState.hide()
        }.invokeOnCompletion { onDismiss() }
    }

    fun send() {
        val msg = text.trim()
        val target = ctrl.target
        if (msg.isEmpty() || sending || target == null) return
        sending = true
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        val progress = ctrl.exactPositionMs()
        val sentColor = color
        val sentMode = mode
        scope.launch {
            try {
                val dmid = DanmakuApi.send(target, msg, progress, sentColor, sentMode)
                DanmakuFeeds.get(target.cid).addSent(
                    DanmakuItem(
                        timeMs = progress,
                        mode = sentMode,
                        size = 25,
                        color = sentColor,
                        text = msg,
                        weight = 11,
                        id = dmid,
                        midHash = "",
                        pool = 0,
                        mine = true,
                    ),
                )
                text = ""
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                toast(e.message?.takeIf { it.isNotBlank() } ?: "发送失败")
            } finally {
                sending = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
        properties = rememberPlayerSheetProperties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", "").take(MaxLength) },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("弹幕") },
                    singleLine = true,
                    shape = CircleShape,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    leadingIcon = {
                        Box(
                            Modifier
                                .padding(start = 4.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(sentArgb(color))),
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                FilledIconButton(
                    onClick = { send() },
                    enabled = text.isNotBlank() && !sending,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(52.dp),
                ) {
                    AnimatedContent(
                        targetState = sending,
                        transitionSpec = {
                            (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f)) togetherWith
                                fadeOut(motion.fastEffectsSpec())
                        },
                        label = "dmSendState",
                    ) { busy ->
                        if (busy) {
                            LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(YoinSymbols.Send, contentDescription = "发送")
                        }
                    }
                }
            }

            ModeRow(
                mode = mode,
                onSelect = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    AppSettings.set(DanmakuSendMode, it)
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(SendColors) { c ->
                    ColorSwatch(
                        rgb = c,
                        selected = c == (color and 0xFFFFFF),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            AppSettings.set(DanmakuSendColor, c)
                        },
                    )
                }
            }
        }
    }
}

private fun sentArgb(rgb: Int): Int = rgb or 0xFF000000.toInt()

@Composable
private fun ModeRow(mode: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val modes: List<Triple<Int, String, ImageVector>> = listOf(
        Triple(DanmakuMode.SCROLL, "滚动", PiliSymbols.DanmakuScroll),
        Triple(DanmakuMode.TOP, "顶部", PiliSymbols.DanmakuTop),
        Triple(DanmakuMode.BOTTOM, "底部", PiliSymbols.DanmakuBottom),
    )
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { i, (value, label, icon) ->
            ToggleButton(
                checked = mode == value,
                onCheckedChange = { if (mode != value) onSelect(value) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColorSwatch(rgb: Int, selected: Boolean, onClick: () -> Unit) {
    val fill = Color(sentArgb(rgb))
    val size by animateDpAsState(if (selected) 36.dp else 28.dp, YoinMotion.fastSpatialSpec(), label = "swatchSize")
    val corner by animateDpAsState(if (selected) 12.dp else 14.dp, YoinMotion.defaultSpatialSpec(), label = "swatchCorner")
    // A tonal plate under every swatch keeps white / near-surface colours readable without an outline.
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))
                .background(fill)
                .semantics {
                    this.selected = selected
                    role = Role.RadioButton
                    contentDescription = "#%06X".format(rgb)
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(YoinMotion.fastEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.4f),
                exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.4f),
            ) {
                Icon(
                    YoinSymbols.Check,
                    contentDescription = null,
                    tint = if (fill.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Fullscreen bottom-bar entry: a quiet pill. */
@Composable
internal fun DanmakuSendPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    androidx.compose.material3.Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = YoinShapeTokens.Full,
        color = OnVideo.copy(alpha = 0.2f),
        contentColor = OnVideo,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(YoinSymbols.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("发弹幕", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
