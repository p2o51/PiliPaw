package com.gpo.pilipala.feature.member

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
            modifier = Modifier.fillMaxWidth(),
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
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

/** Sort header used by the archive tab and the full archive page. */
internal fun LazyGridScope.archiveSortHeader(
    order: ArchiveOrder,
    total: Long,
    onOrder: (ArchiveOrder) -> Unit,
) {
    fullSpan("sort") {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectedToggleRow(
                options = ArchiveOrder.entries,
                selected = order,
                label = { it.label },
                onSelect = onOrder,
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(Modifier.weight(1f))
            if (total > 0) {
                Stat(PiliSymbols.Video, Fmt.count(total), Modifier.padding(start = 12.dp))
            }
        }
    }
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    color = MaterialTheme.colorScheme.outline,
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
