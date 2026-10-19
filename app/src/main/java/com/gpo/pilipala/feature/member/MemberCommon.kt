package com.gpo.pilipala.feature.member

import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.horizontalScroll
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

internal fun VideoItem.key(): String = bvid.ifEmpty { "av$aid" }

/** Min cell width for row cards: one column on phones, two+ on Medium/Expanded. */
internal val RowCardMinWidth = 340.dp

/** Bodies of states inside a grid get a fixed height so they center in the visible area. */
private val StateHeight = 360.dp

/**
 * The paged row-card grid every member list uses: VideoCardH, one column on
 * Compact and an adaptive grid on wider windows, with loading / error / empty
 * states and an optional header (sort toggles) that stays put while reloading.
 */
@Composable
internal fun MemberVideoGrid(
    pager: Pager<VideoItem>,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    emptyLabel: String = "暂无视频",
    refreshable: Boolean = true,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    LoadMoreEffect(state) { pager.loadMore() }
    val grid: @Composable () -> Unit = {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(RowCardMinWidth),
            state = state,
            // Under the member page's tab row or a top bar: rows sink under the tide line.
            modifier = Modifier.fillMaxWidth().seamViewport(state),
            contentPadding = contentPadding,
        ) {
            header?.invoke(this)
            val items = pager.items
            when {
                items.isEmpty() && (pager.initialLoading || pager.isRefreshing) -> fullSpan("loading") {
                    LoadingState(Modifier.height(StateHeight))
                }
                items.isEmpty() && pager.error != null -> fullSpan("error") {
                    StateBox(pager.error.orEmpty(), onRetry = pager::refresh)
                }
                items.isEmpty() -> fullSpan("empty") { EmptyState(emptyLabel, Modifier.height(StateHeight)) }
                else -> {
                    items(items, key = { it.key() }) { item -> VideoCardH(item) }
                    pagerFooter(pager)
                }
            }
        }
    }
    Box(modifier) {
        if (refreshable) {
            RefreshBox(isRefreshing = pager.isRefreshing && pager.items.isNotEmpty(), onRefresh = pager::refresh) { grid() }
        } else {
            grid()
        }
    }
}

/** Error body; privacy errors (53013) read as a locked empty state instead of a retry. */
@Composable
internal fun StateBox(message: String, onRetry: () -> Unit) {
    if (message.contains("隐私")) {
        EmptyState(message, Modifier.height(StateHeight), icon = PiliSymbols.Lock)
    } else {
        ErrorState(message, Modifier.height(StateHeight), onRetry = onRetry)
    }
}

internal fun LazyGridScope.fullSpan(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

internal fun <T> LazyGridScope.pagerFooter(pager: Pager<T>) {
    when {
        pager.isLoadingMore -> fullSpan("footer-loading") { LoadingFooter() }
        pager.error != null && pager.hasMore -> fullSpan("footer-retry") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = pager::loadMore) { Text("重试") }
            }
        }
    }
}

/**
 * Connected toggle buttons (M3 Expressive ButtonGroup look) for one-of-N
 * choices like archive sort.
 */
@Composable
internal fun <T> ConnectedToggleRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = {
                    if (option != selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(option)
                    }
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text(label(option), maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * Sort header used by the archive tab and the full archive page; [onSearch]
 * adds the search toggle at its end (a close glyph while [searching]).
 */
internal fun LazyGridScope.archiveSortHeader(
    order: ArchiveOrder,
    total: Long,
    onOrder: (ArchiveOrder) -> Unit,
    searching: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    fullSpan("sort") {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = if (onSearch != null) 4.dp else 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectedToggleRow(
                options = ArchiveOrder.entries,
                selected = order,
                label = { it.label },
                onSelect = onOrder,
                // The rest of the row, start-aligned; scrolls rather than squeezing labels.
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            )
            if (total > 0) {
                Stat(PiliSymbols.Video, Fmt.count(total), Modifier.padding(start = 12.dp))
            }
            if (onSearch != null) {
                SearchToggle(searching, onSearch, Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** Search / close glyph that springs between the two; used in the sort row and top bars. */
@Composable
internal fun SearchToggle(searching: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()
    val scaleSpec = YoinMotion.fastSpatialSpec<Float>()
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes(), modifier = modifier) {
        AnimatedContent(
            targetState = searching,
            transitionSpec = {
                (fadeIn(fadeInSpec) + scaleIn(scaleSpec, initialScale = 0.6f))
                    .togetherWith(fadeOut(fadeOutSpec) + scaleOut(scaleSpec, targetScale = 0.6f))
            },
            label = "searchToggle",
        ) { on ->
            Icon(if (on) YoinSymbols.Close else YoinSymbols.Search, contentDescription = if (on) "关闭搜索" else "搜索")
        }
    }
}

// ── Search inside the uploads ───────────────────────────────────────────────

/**
 * In-page search over one UP's uploads. It owns its own pager, so the plain
 * list keeps its items (and, with a hoisted grid state, its scroll) while
 * results show; an empty keyword means the plain list is on screen.
 */
@Stable
internal class ArchiveSearch(
    scope: CoroutineScope,
    private val mid: Long,
    private val order: () -> ArchiveOrder,
) {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    /** The submitted keyword; non-empty while results replace the list. */
    var keyword by mutableStateOf("")
        private set
    var total by mutableLongStateOf(-1L)
        private set
    val pager = Pager<VideoItem>(scope, dedupKey = { it.key() }) { cursor ->
        val r = MemberRepo.archive(mid, cursor, order(), keyword = keyword)
        if (cursor == null) total = r.total
        r.page
    }

    fun toggle() {
        if (open) close() else open = true
    }

    fun close() {
        open = false
        query = ""
        reset()
    }

    fun onQuery(q: String) {
        query = q
        // Clearing the field goes straight back to the list.
        if (q.isBlank() && keyword.isNotEmpty()) reset()
    }

    fun submit() {
        val k = query.trim()
        if (k.isEmpty()) return reset()
        keyword = k
        total = -1L
        pager.update { emptyList() }
        pager.refresh()
    }

    /** The shared sort changed: results follow it. */
    fun onOrderChanged() {
        if (keyword.isEmpty()) return
        pager.update { emptyList() }
        pager.refresh()
    }

    // The old results stay in the pager so they fade out intact; the next submit clears them.
    private fun reset() {
        keyword = ""
    }
}

/**
 * The 投稿 list with its search: the field drops in above the grid, a
 * submitted keyword brings the results grid in, clearing or closing springs
 * back to [listState]'s list exactly where it was.
 */
@Composable
internal fun ArchiveListWithSearch(
    search: ArchiveSearch,
    archive: Pager<VideoItem>,
    order: ArchiveOrder,
    total: Long,
    onOrder: (ArchiveOrder) -> Unit,
    listState: LazyGridState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    refreshable: Boolean = true,
    // The full archive page puts the toggle in its top bar instead.
    toggleInHeader: Boolean = true,
) {
    BackHandler(enabled = search.open) { search.close() }
    val onSearch: (() -> Unit)? = if (toggleInHeader) search::toggle else null
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()
    val scaleSpec = YoinMotion.defaultSpatialSpec<Float>()
    Column(modifier) {
        AnimatedVisibility(
            visible = search.open,
            enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = shrinkVertically(YoinMotion.fastSpatialSpec()) + fadeOut(YoinMotion.fastEffectsSpec()),
        ) {
            ArchiveSearchField(search.query, onChange = search::onQuery, onSearch = search::submit)
        }
        AnimatedContent(
            targetState = search.keyword,
            transitionSpec = {
                (fadeIn(fadeInSpec) + scaleIn(scaleSpec, initialScale = 0.96f)).togetherWith(fadeOut(fadeOutSpec))
            },
            // Only list and results swap with motion; a second keyword just reloads the results grid.
            contentKey = { it.isNotEmpty() },
            modifier = Modifier.weight(1f),
            label = "archiveSearch",
        ) { keyword ->
            if (keyword.isNotEmpty()) {
                // A new keyword starts at the top.
                val resultsState = remember(keyword) { LazyGridState() }
                MemberVideoGrid(
                    pager = search.pager,
                    modifier = Modifier.fillMaxSize(),
                    state = resultsState,
                    contentPadding = contentPadding,
                    emptyLabel = "无结果",
                    refreshable = refreshable,
                ) {
                    archiveSortHeader(order, search.total, onOrder, searching = search.open, onSearch = onSearch)
                }
            } else {
                MemberVideoGrid(
                    pager = archive,
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = contentPadding,
                    emptyLabel = "暂无投稿",
                    refreshable = refreshable,
                ) {
                    archiveSortHeader(order, total, onOrder, searching = search.open, onSearch = onSearch)
                }
            }
        }
    }
}

/** Filled pill search field (no indicator line, no outline); the IME action submits. */
@Composable
private fun ArchiveSearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("搜索投稿") },
        leadingIcon = { Icon(YoinSymbols.Search, null) },
        trailingIcon = if (value.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(YoinSymbols.Close, contentDescription = "清除") } }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            onSearch()
        }),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focus),
    )
}

/** 专栏 row: text on the left, cover on the right, stats as icon + number pairs. */
@Composable
internal fun ArticleRow(article: MemberArticle, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .clickable { nav.push(Route.Read(article.id, article.title)) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).seamText(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                article.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (article.summary.isNotEmpty()) {
                Text(
                    article.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (article.views >= 0) Stat(PiliSymbols.Views, Fmt.count(article.views))
                if (article.likes >= 0) Stat(PiliSymbols.ThumbUp, Fmt.count(article.likes))
                if (article.replies >= 0) Stat(PiliSymbols.Comment, Fmt.count(article.replies))
                Text(
                    Fmt.relativeTime(article.publishTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (article.cover.isNotEmpty()) {
            NetImage(
                article.cover,
                Modifier.width(120.dp).aspectRatio(16f / 10f),
                shape = ContinuousRoundedCornerShape(12.dp),
                thumbWidth = 120.dp,
            )
        }
    }
}

// ── Native helpers ──────────────────────────────────────────────────────────

internal fun shareText(context: Context, text: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (title != null) putExtra(Intent.EXTRA_TITLE, title)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal suspend fun Clipboard.copyText(label: String, text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
}

/** Block glyph (circle with a slash) — not in the symbol set. 24 viewport, 1.5 stroke, round caps. */
internal val BlockGlyph: ImageVector by lazy {
    ImageVector.Builder("Block", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M12 3.75a8.25 8.25 0 1 0 0 16.5a8.25 8.25 0 1 0 0 -16.5Z M6.17 6.17L17.83 17.83",
            ).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
