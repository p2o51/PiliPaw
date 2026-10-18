package com.gpo.pilipala.feature.reply

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

private val FieldShape = ContinuousRoundedCornerShape(18.dp)

/**
 * New comment / reply sheet: text field lifted by the IME, an emote panel that
 * swaps with the keyboard, and send. [onSend] returns true when the comment landed.
 */
@Composable
internal fun ReplyComposerSheet(
    target: ComposeTarget,
    onDismiss: () -> Unit,
    onSend: suspend (String) -> Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var panel by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val motion = replyMotion()
    val peek = remember { EmotePeekState() }

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

    fun insert(text: String) {
        val sel = value.selection
        val start = sel.min.coerceIn(0, value.text.length)
        val end = sel.max.coerceIn(0, value.text.length)
        val next = value.text.substring(0, start) + text + value.text.substring(end)
        value = TextFieldValue(next, TextRange(start + text.length))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // The hold bubble floats over the whole sheet, so the top row's can rise above the panel.
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                if (target.replyTo.isNotEmpty()) {
                    Text(
                        "回复 @${target.replyTo}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    )
                }
                TextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .heightIn(min = 112.dp, max = 220.dp)
                        .focusRequester(focus)
                        .onFocusChanged { if (it.isFocused) panel = false },
                    placeholder = { Text("评论") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = FieldShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (panel) {
                                panel = false
                                runCatching { focus.requestFocus() }
                                keyboard?.show()
                            } else {
                                keyboard?.hide()
                                panel = true
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        AnimatedContent(
                            targetState = panel,
                            transitionSpec = {
                                androidx.compose.animation.scaleIn(motion.scale) +
                                    androidx.compose.animation.fadeIn(motion.fade) togetherWith
                                    androidx.compose.animation.scaleOut(motion.scale) +
                                    androidx.compose.animation.fadeOut(motion.fade)
                            },
                            label = "composerToggle",
                        ) { showingPanel ->
                            Icon(
                                if (showingPanel) ReplyIcons.Keyboard else ReplyIcons.Emoji,
                                contentDescription = if (showingPanel) "键盘" else "表情",
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (sending) return@Button
                            sending = true
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            scope.launch {
                                val ok = onSend(value.text.trim())
                                sending = false
                                if (ok) close()
                            }
                        },
                        shapes = ButtonDefaults.shapes(),
                        enabled = value.text.isNotBlank() && !sending,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        AnimatedContent(
                            targetState = sending,
                            transitionSpec = {
                                motion.crossfade()
                            },
                            label = "sendState",
                        ) { busy ->
                            if (busy) {
                                LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(YoinSymbols.Send, null, Modifier.size(18.dp))
                                    Text("发送")
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = panel,
                    enter = expandVertically(YoinMotion.defaultSpatialSpec()) + androidx.compose.animation.fadeIn(YoinMotion.defaultEffectsSpec()),
                    exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + androidx.compose.animation.fadeOut(YoinMotion.defaultEffectsSpec()),
                ) {
                    EmotePanel(
                        peek = peek,
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        onPick = { insert(it.text) },
                    )
                }
            }
            EmotePeekOverlay(peek, Modifier.matchParentSize())
        }
    }
}

/**
 * Packages as a tab row under their grid. A tap inserts; press and hold
 * previews ([emotePeek]) without inserting. Shared with the dynamic composer
 * (feature/dynamics), which pairs it with its own [EmotePeekOverlay].
 */
@Composable
internal fun EmotePanel(peek: EmotePeekState, modifier: Modifier = Modifier, onPick: (Emote) -> Unit) {
    var packages by remember { mutableStateOf(EmoteCache.packages) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val motion = replyMotion()
    LaunchedEffect(Unit) {
        if (packages == null) {
            runCatching { ReplyApi.emotePanel() }
                .onSuccess {
                    EmoteCache.packages = it
                    packages = it
                }
                .onFailure { error = it.friendlyMessage() }
        }
    }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        val list = packages
        when {
            list == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            list.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    error ?: "暂无表情",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val pkg = list[selected.coerceIn(0, list.lastIndex)]
                AnimatedContent(
                    targetState = pkg,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        motion.crossfade()
                    },
                    label = "emotePackage",
                ) { p ->
                    val big = p.emotes.firstOrNull()?.size ?: 1
                    val kaomoji = p.type == 4
                    val gridState = rememberLazyGridState()
                    val cells = remember(p) { HashMap<String, LayoutCoordinates>() }
                    val cellPx = with(LocalDensity.current) { (if (big >= 2) 64.dp else 44.dp).roundToPx() * 2 }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (kaomoji) 88.dp else if (big >= 2) 64.dp else 44.dp),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .emotePeek(
                                peek = peek,
                                gridState = gridState,
                                cells = cells,
                                emotes = p.emotes,
                                kaomoji = kaomoji,
                                placeholder = { Images.thumb(it.url, cellPx) },
                                haptic = haptic,
                            ),
                    ) {
                        items(p.emotes, key = { it.text }) { e ->
                            Box(
                                Modifier
                                    .onPlaced { cells[e.text] = it }
                                    .clip(ContinuousRoundedCornerShape(10.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                        onPick(e)
                                    }
                                    .padding(4.dp)
                                    .then(if (p.type == 4) Modifier.height(36.dp) else Modifier.aspectRatio(1f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (kaomoji || e.url.isEmpty()) {
                                    Text(e.text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Clip)
                                } else {
                                    AsyncImage(
                                        model = Images.thumb(e.url, cellPx),
                                        contentDescription = e.text,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(list, key = { _, p -> p.id }) { i, p ->
                        val on = i == selected
                        val bg by androidx.compose.animation.animateColorAsState(
                            if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            YoinMotion.defaultEffectsSpec(),
                            label = "pkgBg",
                        )
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(bg)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    selected = i
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (p.icon.isNotEmpty() && p.type != 4) {
                                NetImage(
                                    p.icon,
                                    Modifier.size(24.dp),
                                    contentDescription = p.title,
                                    shape = ContinuousRoundedCornerShape(4.dp),
                                    thumbWidth = 48.dp,
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Text(p.title, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
