package com.gpo.pilipala.feature.search

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.feature.feeds.FeedSourceSheet
import com.gpo.pilipala.navigation.LinkRouter
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

// ── Request ─────────────────────────────────────────────────────────────────

/** What a submitted text opens. */
internal sealed interface SearchTarget {
    data class Link(val url: String) : SearchTarget
    data class Video(val bvid: String, val aid: Long = 0) : SearchTarget
    data class Keyword(val text: String) : SearchTarget
}

/** A submit: what to open, and the sort the bottom row had (the result page's video order). */
internal data class SearchRequest(val target: SearchTarget, val order: String)

private val urlRegex = Regex("""(https?://)?([a-z0-9-]+\.)*(bilibili\.com|b23\.tv)(/\S*)?""", RegexOption.IGNORE_CASE)
private val avRegex = Regex("""(?i)(?<![a-z0-9])av(\d+)""")

/** A bilibili link opens as a link, a BV / av id as its video, anything else is a keyword. */
internal fun resolveSearch(text: String): SearchTarget {
    val url = urlRegex.find(text)?.value
    if (url != null && url.contains('/')) {
        return SearchTarget.Link(if (url.startsWith("http", ignoreCase = true)) url else "https://$url")
    }
    IdUtils.findBvid(text)?.let { return SearchTarget.Video(it) }
    avRegex.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { return SearchTarget.Video(IdUtils.av2bv(it), aid = it) }
    return SearchTarget.Keyword(text)
}

/**
 * Opens [request]: the result page (its video tab on the request's sort), a
 * video, or a link. [replace] swaps the current page for it.
 */
internal fun Navigator.openSearch(request: SearchRequest, replace: Boolean = false) {
    val route: Route = when (val t = request.target) {
        is SearchTarget.Link -> LinkRouter.route(t.url)
        is SearchTarget.Video -> Route.Video(t.bvid, aid = t.aid)
        is SearchTarget.Keyword -> {
            SearchOrderHandoff.offer(t.text, request.order)
            Route.SearchResult(t.text)
        }
    }
    if (replace) replace(route) else push(route)
}

// ── State ───────────────────────────────────────────────────────────────────

/**
 * One search: the native bar's state, its text and the bottom row's sort.
 * [submitted] is true from a submit until the search opens again.
 */
@Stable
internal class PiliSearchState internal constructor(
    val bar: SearchBarState,
    val input: TextFieldState,
    private val orderState: MutableState<String>,
    private val altOrderState: MutableState<String>,
) {
    var submitted by mutableStateOf(false)
        internal set

    /** The bottom row's sort, one of [SearchSortOrders]. */
    var order: String
        get() = orderState.value
        set(value) {
            orderState.value = value
            if (value != DefaultSearchOrder) altOrderState.value = value
        }

    /** What the split button's leading part switches to: 默认排序 ⇄ the last other sort picked. */
    val toggleOrder: String
        get() = if (order == DefaultSearchOrder) altOrderState.value else DefaultSearchOrder

    val text: String get() = input.text.toString()

    val isOpen: Boolean get() = bar.targetValue == SearchBarValue.Expanded
}

@Composable
internal fun rememberPiliSearchState(
    initialText: String = "",
    initialOrder: String = DefaultSearchOrder,
    expanded: Boolean = false,
): PiliSearchState {
    val motion = MaterialTheme.motionScheme
    val bar = rememberContainedSearchBarState(
        initialValue = if (expanded) SearchBarValue.Expanded else SearchBarValue.Collapsed,
        // Springs, not M3's default eased tweens. The scheme's specs are cached instances, so
        // they are stable inputs for the saveable state.
        animationSpecForContentFadeIn = motion.defaultEffectsSpec(),
        animationSpecForContentFadeOut = motion.fastEffectsSpec(),
    )
    val input = rememberTextFieldState(initialText, TextRange(0, initialText.length))
    val order = rememberSaveable { mutableStateOf(searchSortOrder(initialOrder)) }
    val alt = rememberSaveable {
        mutableStateOf(order.value.takeIf { it != DefaultSearchOrder } ?: "pubdate")
    }
    return remember(bar, input) { PiliSearchState(bar, input, order, alt) }
}

// ── Bar ─────────────────────────────────────────────────────────────────────

private val FieldMaxWidth = 840.dp
private val ActionRowHeight = 64.dp

/**
 * The app's search on the native M3 Expressive search: a contained
 * [SearchBar] where it sits, opening into [ExpandedFullScreenContainedSearchBar]
 * with the field focused and the keyboard up (system and predictive back fold
 * it back). Under the field: [SearchPanel]; at the bottom, riding on the
 * keyboard, the sort / 添加到 Feed / 搜索 row. A submit folds the search away
 * first, then hands [onSubmit] the request. An empty field submits [hint].
 * [clearOnSubmit] empties the folded bar (back to [hint]) before [onSubmit],
 * once it has drawn so: a push freezes the page beneath as last drawn.
 */
@Composable
internal fun PiliSearchBar(
    state: PiliSearchState,
    hint: String,
    onSubmit: (SearchRequest) -> Unit,
    modifier: Modifier = Modifier,
    clearOnSubmit: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val vm = viewModel(key = "pili_search") { SearchViewModel() }
    val colors = SearchBarDefaults.containedColors(state.bar)
    val latestSubmit by rememberUpdatedState(onSubmit)

    LaunchedEffect(state) {
        snapshotFlow { state.bar.targetValue }.collect { if (it == SearchBarValue.Expanded) state.submitted = false }
    }

    fun submit(raw: String) {
        if (state.submitted) return
        val text = raw.trim().ifEmpty { hint.trim() }
        if (text.isEmpty()) return
        val target = resolveSearch(text)
        if (target !is SearchTarget.Link) SearchHistoryStore.add(text)
        if (state.text != text) state.input.setTextAndPlaceCursorAtEnd(text)
        state.submitted = true
        val request = SearchRequest(target, state.order)
        scope.launch {
            state.bar.animateToCollapsed()
            if (clearOnSubmit) {
                state.input.clearText()
                // One frame recomposes the bar, the next has drawn it.
                withFrameNanos { }
                withFrameNanos { }
            }
            latestSubmit(request)
        }
    }

    val inputField: @Composable () -> Unit = {
        SearchInputField(state = state, hint = hint, onSearch = ::submit)
    }
    SearchBar(
        state = state.bar,
        inputField = inputField,
        modifier = modifier,
        colors = colors,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    )
    // M3 reads the dialog's system-bar icon appearance from LocalContentColor, and the panel's
    // uncoloured text inherits it: give it the page's own content colour so both follow the theme.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        ExpandedFullScreenContainedSearchBar(
            state = state.bar,
            inputField = inputField,
            colors = colors,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            SearchExpandedContent(state = state, vm = vm, hint = hint, onSubmit = ::submit)
        }
    }
}

@Composable
private fun SearchInputField(state: PiliSearchState, hint: String, onSearch: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val scheme = MaterialTheme.colorScheme
    val open = state.isOpen
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    val scaleEnter = YoinMotion.fastSpatialSpec<Float>()
    // Full width up to a reading width, centred: the expanded field on a tablet lines up with the content.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SearchBarDefaults.InputField(
            textFieldState = state.input,
            searchBarState = state.bar,
            onSearch = {
                keyboard?.hide()
                onSearch(it)
            },
            modifier = Modifier.widthIn(max = FieldMaxWidth).fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = {
                AnimatedContent(
                    targetState = hint.ifBlank { "搜索" },
                    transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                    label = "searchHint",
                ) { text ->
                    Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            leadingIcon = {
                AnimatedContent(
                    targetState = open,
                    transitionSpec = {
                        (fadeIn(enter) + scaleIn(scaleEnter, initialScale = 0.6f)) togetherWith
                            (fadeOut(exit) + scaleOut(scaleEnter, targetScale = 0.6f))
                    },
                    contentAlignment = Alignment.Center,
                    label = "searchLeading",
                ) { isOpen ->
                    if (isOpen) {
                        IconButton(
                            onClick = { scope.launch { state.bar.animateToCollapsed() } },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(YoinSymbols.Back, contentDescription = "返回")
                        }
                    } else {
                        Icon(YoinSymbols.Search, contentDescription = null, tint = scheme.onSurfaceVariant)
                    }
                }
            },
            trailingIcon = {
                AnimatedVisibility(
                    visible = open && state.input.text.isNotEmpty(),
                    enter = fadeIn(enter) + scaleIn(scaleEnter, initialScale = 0.6f),
                    exit = fadeOut(exit) + scaleOut(scaleEnter, targetScale = 0.6f),
                ) {
                    IconButton(onClick = { state.input.clearText() }, shapes = IconButtonDefaults.shapes()) {
                        Icon(YoinSymbols.Close, contentDescription = "清除", tint = scheme.onSurfaceVariant)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
    }
}

/** The expanded search under its field: the panel, then the fixed bottom row. */
@Composable
private fun ColumnScope.SearchExpandedContent(
    state: PiliSearchState,
    vm: SearchViewModel,
    hint: String,
    onSubmit: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val text = state.text
    // History word whose 新建 Feed / 添加到 Feed sheet is open; the typed text's sheet.
    var historySheet by rememberSaveable { mutableStateOf<String?>(null) }
    var feedSheet by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) { vm.ensureHot() }
    LaunchedEffect(state.input, vm) {
        snapshotFlow { state.input.text.toString() }.collect { vm.onQuery(it) }
    }

    fun leaveField() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    SearchPanel(
        query = text,
        vm = vm,
        onSubmit = {
            keyboard?.hide()
            onSubmit(it)
        },
        onFill = { state.input.setTextAndPlaceCursorAtEnd(it) },
        onHistoryLongPress = {
            leaveField()
            historySheet = it
        },
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    )
    // The native search pads its content for the keyboard and the navigation
    // bar, so this row rides on the keyboard as it moves.
    SearchActionRow(
        order = state.order,
        toggleOrder = state.toggleOrder,
        onOrder = { state.order = it },
        canAddToFeed = text.isNotBlank(),
        canSearch = text.isNotBlank() || hint.isNotBlank(),
        onAddToFeed = {
            leaveField()
            feedSheet = text.trim()
        },
        onSearch = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            keyboard?.hide()
            onSubmit(state.text)
        },
    )

    historySheet?.let { word ->
        FeedSourceSheet(
            keyword = word,
            onDismiss = { historySheet = null },
            onRemoveHistory = { SearchHistoryStore.remove(word) },
        )
    }
    feedSheet?.let { word ->
        FeedSourceSheet(
            keyword = word,
            keywordOrder = feedKeywordOrder(state.order),
            onDismiss = { feedSheet = null },
        )
    }
}

@Composable
private fun SearchActionRow(
    order: String,
    toggleOrder: String,
    onOrder: (String) -> Unit,
    canAddToFeed: Boolean,
    canSearch: Boolean,
    onAddToFeed: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        Modifier
            .readingWidth()
            .fillMaxWidth()
            .height(ActionRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SortSplitButton(order = order, toggleOrder = toggleOrder, onOrder = onOrder)
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = onAddToFeed,
            enabled = canAddToFeed,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(YoinSymbols.LibraryAdd, contentDescription = "添加到 Feed")
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onSearch,
            enabled = canSearch,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(YoinSymbols.Search, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("搜索", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * The sort as an M3 Expressive split button: the leading part names the
 * current sort and switches 默认排序 ⇄ the last other sort; the trailing part
 * opens the menu of every sort.
 */
@Composable
private fun SortSplitButton(order: String, toggleOrder: String, onOrder: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    // The popup is not touch-modal, so a tap on the chevron while it is open dismisses it on
    // touch-down and would toggle it open again on touch-up: ignore that reopen.
    var dismissedAt by remember { mutableLongStateOf(0L) }
    val label = SearchSortOrders.firstOrNull { it.value == order }?.label ?: SearchSortOrders.first().label
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    val size = YoinMotion.defaultSpatialSpec<androidx.compose.ui.unit.IntSize>()
    val rotation by animateFloatAsState(
        if (menu) 180f else 0f,
        YoinMotion.fastSpatialSpec(),
        label = "sortChevron",
    )
    SplitButtonLayout(
        leadingButton = {
            SplitButtonDefaults.TonalLeadingButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onOrder(toggleOrder)
                },
            ) {
                Icon(YoinSymbols.Sort, contentDescription = null, modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        (fadeIn(enter) togetherWith fadeOut(exit)) using SizeTransform(clip = false) { _, _ -> size }
                    },
                    label = "sortLabel",
                ) { text ->
                    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        },
        trailingButton = {
            Box {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = menu,
                    onCheckedChange = { open ->
                        menu = open && SystemClock.uptimeMillis() - dismissedAt > 300
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "排序"
                        stateDescription = if (menu) "已展开" else "已收起"
                    },
                ) {
                    Icon(
                        YoinSymbols.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(SplitButtonDefaults.TrailingIconSize)
                            .graphicsLayer { rotationZ = rotation },
                    )
                }
                // Not focusable: the keyboard stays put while the menu is open (a focusable
                // popup would take the window focus and drop it). Back closes the menu first.
                BackHandler(enabled = menu) { menu = false }
                // The row rides on the keyboard, so there is never room below it: the menu always
                // opens upward from the button (M3's dropdown would measure the space under the
                // keyboard as free and open behind it).
                val menuState = remember { MutableTransitionState(false) }
                menuState.targetState = menu
                val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
                if (menuState.currentState || menuState.targetState) Popup(
                    popupPositionProvider = remember(gap) { AboveAnchor(gap) },
                    onDismissRequest = {
                        menu = false
                        dismissedAt = SystemClock.uptimeMillis()
                    },
                    properties = PopupProperties(focusable = false),
                ) {
                    AnimatedVisibility(
                        visibleState = menuState,
                        enter = fadeIn(YoinMotion.fastEffectsSpec()) +
                            scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.86f, transformOrigin = TransformOrigin(0.25f, 1f)),
                        exit = fadeOut(YoinMotion.fastEffectsSpec()) +
                            scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.92f, transformOrigin = TransformOrigin(0.25f, 1f)),
                    ) {
                    DropdownMenuGroup(
                        shapes = MenuDefaults.groupShape(0, 1),
                        // Sized to its longest item, as the M3 dropdown sizes itself.
                        modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = 160.dp, max = 280.dp),
                    ) {
                        SearchSortOrders.forEachIndexed { i, c ->
                            DropdownMenuItem(
                                selected = c.value == order,
                                onClick = {
                                    if (c.value != order) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onOrder(c.value)
                                    menu = false
                                },
                                text = { Text(c.label, maxLines = 1) },
                                shapes = MenuDefaults.itemShape(i, SearchSortOrders.size),
                                selectedLeadingIcon = {
                                    Icon(YoinSymbols.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                            )
                        }
                    }
                    }
                }
            }
        },
    )
}

/** Places a popup just above its anchor, start-aligned and kept inside the window. */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0),
        )
    }
}
