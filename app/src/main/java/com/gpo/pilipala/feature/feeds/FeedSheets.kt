package com.gpo.pilipala.feature.feeds

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.sheetBottomPadding

/** The editor route that starts a new feed from [this] source. */
internal fun FeedSource.newFeedRoute(): Route.FeedEditor = when (this) {
    is FeedSource.Keyword -> Route.FeedEditor(keyword = term, order = order.key)
    is FeedSource.Up -> Route.FeedEditor(upMid = mid, upName = name, upFace = face)
}

// ── 新建 Feed / 添加到 Feed ─────────────────────────────────────────────────

/**
 * Menu for one keyword or UP (search history chip, search result page, user
 * row): 新建 Feed (editor prefilled with it) and 添加到 Feed ›, which turns the
 * sheet into the list of feeds, each a toggle. [onRemoveHistory] adds the
 * history chip's own 删除记录.
 */
@Composable
fun FeedSourceSheet(
    keyword: String? = null,
    keywordOrder: String = "",
    upMid: Long = 0,
    upName: String = "",
    upFace: String = "",
    onDismiss: () -> Unit,
    onRemoveHistory: (() -> Unit)? = null,
) {
    val source: FeedSource = when {
        !keyword.isNullOrBlank() -> FeedSource.Keyword(
            keyword.trim(),
            KeywordOrder.entries.firstOrNull { it.key == keywordOrder } ?: KeywordOrder.Totalrank,
        )
        upMid > 0 -> FeedSource.Up(upMid, upName, upFace)
        else -> {
            LaunchedEffect(Unit) { onDismiss() }
            return
        }
    }
    FeedSourceSheet(source, onDismiss, onRemoveHistory)
}

@Composable
internal fun FeedSourceSheet(
    source: FeedSource,
    onDismiss: () -> Unit,
    onRemoveHistory: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val feeds by FeedStore.feeds.collectAsState()
    var listing by remember { mutableStateOf(false) }

    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = sheetBottomPadding(24.dp)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SourceMark(source, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        source.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(source.kindLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            val enter = YoinMotion.defaultEffectsSpec<Float>()
            val exit = YoinMotion.fastEffectsSpec<Float>()
            val slideIn = YoinMotion.defaultSpatialSpec<IntOffset>()
            val slideOut = YoinMotion.fastSpatialSpec<IntOffset>()
            val size = YoinMotion.defaultSpatialSpec<IntSize>()
            AnimatedContent(
                targetState = listing && feeds.isNotEmpty(),
                transitionSpec = {
                    val dir = if (targetState) 1 else -1
                    (fadeIn(enter) + slideInHorizontally(slideIn) { dir * it / 5 }) togetherWith
                        (fadeOut(exit) + slideOutHorizontally(slideOut) { -dir * it / 5 }) using
                        SizeTransform(clip = false) { _, _ -> size }
                },
                label = "feedSourceSheet",
            ) { showList ->
                if (!showList) {
                    Column(Modifier.fillMaxWidth().clip(YoinContainerShapes.Card).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        val canCreate = feeds.size < MaxFeeds
                        FeedSheetRow(
                            label = "新建 Feed",
                            icon = YoinSymbols.Add,
                            enabled = canCreate,
                            onClick = { close { nav.push(source.newFeedRoute()) } },
                            trailing = if (canCreate) null else ({ QuietLabel("已满") }),
                        )
                        if (feeds.isNotEmpty()) {
                            FeedSheetRow(
                                label = "添加到 Feed",
                                icon = YoinSymbols.LibraryAdd,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    listing = true
                                },
                                trailing = {
                                    Icon(YoinSymbols.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                            )
                        }
                        if (onRemoveHistory != null) {
                            FeedSheetRow(
                                label = "删除记录",
                                icon = YoinSymbols.Delete,
                                tint = MaterialTheme.colorScheme.error,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    close(onRemoveHistory)
                                },
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            IconButton(onClick = { listing = false }, shapes = IconButtonDefaults.shapes()) {
                                Icon(YoinSymbols.ChevronLeft, contentDescription = "返回")
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("添加到 Feed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.fillMaxWidth().clip(YoinContainerShapes.Card).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            feeds.forEach { feed ->
                                val inFeed = feed.contains(source)
                                val full = !inFeed && feed.sources.size >= MaxSources
                                // The last source cannot leave: a feed is never empty.
                                val locked = inFeed && feed.sources.size <= 1
                                FeedSheetRow(
                                    label = feed.name,
                                    enabled = !full && !locked,
                                    leading = { FeedBadge(selected = inFeed) },
                                    onClick = {
                                        if (inFeed) {
                                            if (FeedStore.removeSource(feed.id, source.key)) {
                                                haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                            }
                                        } else {
                                            when (FeedStore.addSource(feed.id, source)) {
                                                AddResult.Added -> {
                                                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                                    toast("已添加到 ${feed.name}")
                                                }
                                                AddResult.Full -> toast("最多 $MaxSources 个来源")
                                                else -> Unit
                                            }
                                        }
                                    },
                                    trailing = {
                                        if (full) QuietLabel("已满") else CountLabel(feed.sources.size, "来源")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Leading disc of a feed row: a check when the source is in it. */
@Composable
private fun FeedBadge(selected: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.primary else scheme.surfaceContainerHighest
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        val enter = YoinMotion.defaultEffectsSpec<Float>()
        val pop = YoinMotion.fastSpatialSpec<Float>()
        val exit = YoinMotion.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = selected,
            transitionSpec = { (fadeIn(enter) + scaleIn(pop, initialScale = 0.5f)) togetherWith fadeOut(exit) },
            label = "feedBadge",
        ) { on ->
            Icon(
                if (on) YoinSymbols.Check else YoinSymbols.Playlist,
                contentDescription = if (on) "已添加" else null,
                modifier = Modifier.size(18.dp),
                tint = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuietLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ── Manage one feed (long-press on its home tab) ────────────────────────────

private enum class ManageMode { Actions, Rename, Delete }

/**
 * Long-press menu of a feed tab: 前移 / 后移, 编辑, 重命名 (inline field) and
 * 删除 (confirmation inside the sheet, like 拉黑 in the card sheet).
 */
@Composable
internal fun FeedManageSheet(feedId: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val nav = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val feeds by FeedStore.feeds.collectAsState()
    val index = feeds.indexOfFirst { it.id == feedId }
    val feed = feeds.getOrNull(index)
    var mode by remember { mutableStateOf(ManageMode.Actions) }
    // Gone (deleted elsewhere): close.
    if (feed == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var name by remember { mutableStateOf(TextFieldValue(feed.name, TextRange(0, feed.name.length))) }

    fun close(then: () -> Unit = {}) {
        keyboard?.hide()
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }

    fun saveName() {
        if (name.text.isBlank()) return
        FeedStore.rename(feed.id, name.text)
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        close()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                feed.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CountLabel(feed.sources.count { it is FeedSource.Keyword }, "关键词")
                CountLabel(feed.sources.count { it is FeedSource.Up }, "UP主")
                Text(feed.mix.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(20.dp))
            val enter = YoinMotion.defaultEffectsSpec<Float>()
            val exit = YoinMotion.fastEffectsSpec<Float>()
            val size = YoinMotion.defaultSpatialSpec<IntSize>()
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) using SizeTransform(clip = false) { _, _ -> size } },
                label = "feedManage",
            ) { m ->
                when (m) {
                    ManageMode.Actions -> Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    FeedStore.move(feed.id, -1)
                                },
                                enabled = index > 0,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                shapes = ButtonDefaults.shapes(ButtonGroupDefaults.connectedLeadingButtonShape, ButtonGroupDefaults.connectedLeadingButtonPressShape),
                            ) {
                                Icon(YoinSymbols.ChevronLeft, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("前移", style = MaterialTheme.typography.labelLarge)
                            }
                            FilledTonalButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    FeedStore.move(feed.id, 1)
                                },
                                enabled = index < feeds.lastIndex,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                shapes = ButtonDefaults.shapes(ButtonGroupDefaults.connectedTrailingButtonShape, ButtonGroupDefaults.connectedTrailingButtonPressShape),
                            ) {
                                Text("后移", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.width(6.dp))
                                Icon(YoinSymbols.ChevronRight, null, Modifier.size(20.dp))
                            }
                        }
                        SheetSpacer()
                        Column(Modifier.fillMaxWidth().clip(YoinContainerShapes.Card).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            FeedSheetRow(
                                label = "编辑",
                                icon = YoinSymbols.Edit,
                                onClick = { close { nav.push(Route.FeedEditor(feedId = feed.id)) } },
                            )
                            FeedSheetRow(
                                label = "重命名",
                                icon = YoinSymbols.EditNote,
                                onClick = { mode = ManageMode.Rename },
                            )
                            FeedSheetRow(
                                label = "删除",
                                icon = YoinSymbols.Delete,
                                tint = MaterialTheme.colorScheme.error,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    mode = ManageMode.Delete
                                },
                            )
                        }
                    }
                    ManageMode.Rename -> {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) {
                            withFrameNanos { }
                            runCatching { focus.requestFocus() }
                        }
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            FeedTextField(
                                value = name,
                                onValueChange = { if (it.text.length <= MaxFeedName) name = it },
                                label = "名称",
                                focusRequester = focus,
                                onImeAction = ::saveName,
                            )
                            ConfirmRow(
                                cancel = "取消",
                                confirm = "保存",
                                confirmEnabled = name.text.isNotBlank(),
                                onCancel = {
                                    keyboard?.hide()
                                    mode = ManageMode.Actions
                                },
                                onConfirm = ::saveName,
                            )
                        }
                    }
                    ManageMode.Delete -> Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(YoinContainerShapes.Card)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("删除", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                feed.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ConfirmRow(
                            cancel = "取消",
                            confirm = "删除",
                            destructive = true,
                            onCancel = { mode = ManageMode.Actions },
                            onConfirm = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                val id = feed.id
                                close { FeedStore.delete(id) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmRow(
    cancel: String,
    confirm: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(52.dp),
            shapes = ButtonDefaults.shapes(),
        ) { Text(cancel, style = MaterialTheme.typography.titleSmall) }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f).height(52.dp),
            shapes = ButtonDefaults.shapes(),
            colors = if (destructive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) { Text(confirm, style = MaterialTheme.typography.titleSmall) }
    }
}
