package com.gpo.pilipala.feature.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ── ViewModel ───────────────────────────────────────────────────────────────

/** 热搜 and suggestions behind [SearchPanel]. 热搜 loads the first time the panel shows. */
@OptIn(FlowPreview::class)
internal class SearchViewModel : ViewModel() {
    var hot by mutableStateOf<List<HotWord>>(emptyList())
        private set
    var hotLoading by mutableStateOf(false)
        private set
    var hotError by mutableStateOf<String?>(null)
        private set
    var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
        private set

    /** The query [suggestions] answer (they trail the typed text by the debounce). */
    var suggestionsFor by mutableStateOf("")
        private set

    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            query.debounce(200).distinctUntilChanged().collectLatest { q ->
                if (q.isBlank()) {
                    suggestions = emptyList()
                    suggestionsFor = ""
                } else {
                    runCatching { SearchApi.suggest(q.trim()) }.onSuccess {
                        suggestions = it
                        suggestionsFor = q
                    }
                }
            }
        }
    }

    fun onQuery(q: String) {
        query.value = q
    }

    /** Loads 热搜 unless it is here or on its way (a failed load retries). */
    fun ensureHot() {
        if (hot.isEmpty()) loadHot()
    }

    fun loadHot() {
        if (hotLoading) return
        viewModelScope.launch {
            hotLoading = true
            hotError = null
            runCatching { SearchApi.hot() }
                .onSuccess { hot = it }
                .onFailure { hotError = it.friendlyMessage() }
            hotLoading = false
        }
    }
}

// ── Panel ───────────────────────────────────────────────────────────────────

/**
 * What the search shows under its field: suggestions while [query] has text,
 * otherwise 搜索历史 (a long press opens [onHistoryLongPress]) and 热搜.
 * Window insets are the caller's: the native search pads its content already.
 */
@Composable
internal fun SearchPanel(
    query: String,
    vm: SearchViewModel,
    onSubmit: (String) -> Unit,
    onFill: (String) -> Unit,
    onHistoryLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val historyRaw by AppSettings.searchHistory.state.collectAsState()
    val history = remember(historyRaw) { SearchHistoryStore.parse(historyRaw) }
    val haptics = LocalHapticFeedback.current
    AnimatedContent(
        targetState = query.isNotBlank(),
        transitionSpec = fadeThrough(),
        modifier = modifier,
        label = "searchBody",
    ) { typing ->
        if (typing) {
            SuggestionList(
                suggestions = vm.suggestions,
                query = query,
                onPick = { onSubmit(it.term) },
                onFill = { onFill(it.term) },
            )
        } else {
            SearchHome(
                history = history,
                hot = vm.hot,
                hotLoading = vm.hotLoading,
                hotError = vm.hotError,
                onReloadHot = vm::loadHot,
                onPick = onSubmit,
                onLongPress = onHistoryLongPress,
                onClearHistory = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    SearchHistoryStore.clear()
                },
            )
        }
    }
}

@Composable
private fun SuggestionList(
    suggestions: List<Suggestion>,
    query: String,
    onPick: (Suggestion) -> Unit,
    onFill: (Suggestion) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val base = SpanStyle(color = scheme.onSurfaceVariant)
    val strong = SpanStyle(color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
    LazyColumn(
        Modifier
            .fillMaxSize()
            .seamViewport(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
    ) {
        items(suggestions, key = { it.term }) { s ->
            Row(
                Modifier
                    .animateItem()
                    .readingWidth()
                    .padding(horizontal = 8.dp)
                    .clip(YoinContainerShapes.ListRow)
                    .clickable { onPick(s) }
                    .heightIn(min = 52.dp)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(YoinSymbols.Search, null, Modifier.size(20.dp), tint = scheme.outline)
                Spacer(Modifier.width(16.dp))
                Text(
                    remember(s.name, query, base, strong) { highlightEm(s.name, query, base, strong) },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onFill(s) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(YoinSymbols.NorthWest, contentDescription = "填入", tint = scheme.outline)
                }
            }
        }
    }
}

@Composable
private fun SearchHome(
    history: List<String>,
    hot: List<HotWord>,
    hotLoading: Boolean,
    hotError: String?,
    onReloadHot: () -> Unit,
    onPick: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .seamViewport(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
    ) {
        if (history.isNotEmpty()) {
            item(key = "historyHead") {
                SectionHeader("搜索历史", Modifier.animateItem()) {
                    IconButton(onClick = onClearHistory, shapes = IconButtonDefaults.shapes()) {
                        Icon(YoinSymbols.DeleteSweep, contentDescription = "清空", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "history") {
                FlowRow(
                    Modifier
                        .animateItem()
                        .readingWidth()
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 20.dp)
                        .animateContentSize(YoinMotion.defaultSpatialSpec()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    history.forEach { word ->
                        key(word) {
                            SearchChip(word, onLongClick = { onLongPress(word) }) { onPick(word) }
                        }
                    }
                }
            }
        }
        item(key = "hotHead") {
            SectionHeader("热搜", Modifier.animateItem()) {
                IconButton(onClick = onReloadHot, shapes = IconButtonDefaults.shapes()) {
                    Icon(YoinSymbols.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "hot") {
            HotBlock(
                words = hot,
                loading = hotLoading,
                error = hotError,
                onRetry = onReloadHot,
                onPick = onPick,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .readingWidth()
            .height(52.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        action()
    }
}

private enum class HotState { Loading, Error, Content, Empty }

@Composable
private fun HotBlock(
    words: List<HotWord>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cols = if (widthClass() == WidthClass.Compact) 1 else 2
    val state = when {
        words.isNotEmpty() -> HotState.Content
        loading -> HotState.Loading
        error != null -> HotState.Error
        else -> HotState.Empty
    }
    // The search page is surfaceContainerLow: the block sits one step up.
    Box(
        modifier
            .readingWidth()
            .padding(horizontal = 16.dp)
            .clip(YoinContainerShapes.Card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .animateContentSize(YoinMotion.defaultSpatialSpec()),
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = fadeThrough(),
            label = "hotState",
        ) { s ->
            when (s) {
                HotState.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
                HotState.Error, HotState.Empty -> Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        error ?: "暂无热搜",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    FilledTonalButton(onClick = onRetry) { Text("重试") }
                }
                HotState.Content -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)) {
                    words.chunked(cols).forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEachIndexed { c, w ->
                                HotRow(
                                    rank = r * cols + c + 1,
                                    word = w,
                                    onClick = { onPick(w.keyword) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HotRow(rank: Int, word: HotWord, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val top = rank <= 3
    Row(
        modifier
            .clip(YoinContainerShapes.ListRow)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rank.toString(),
            modifier = Modifier.width(32.dp),
            style = if (top) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = if (top) FontWeight.Black else FontWeight.Medium,
            color = if (top) scheme.primary else scheme.outline,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            word.showName,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (top) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        word.mark?.let {
            Spacer(Modifier.width(8.dp))
            HotMarkView(it)
        }
    }
}

@Composable
private fun HotMarkView(mark: HotMark) {
    val scheme = MaterialTheme.colorScheme
    when (mark) {
        HotMark.Hot -> Icon(YoinSymbols.Fire, contentDescription = "热", modifier = Modifier.size(18.dp), tint = scheme.error)
        HotMark.Live -> Icon(YoinSymbols.Live, contentDescription = "直播", modifier = Modifier.size(18.dp), tint = scheme.primary)
        HotMark.New -> Tag("新", container = scheme.primaryContainer, content = scheme.onPrimaryContainer)
        HotMark.First -> Tag("首发", container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
        HotMark.Topic -> Tag("话题")
    }
}
