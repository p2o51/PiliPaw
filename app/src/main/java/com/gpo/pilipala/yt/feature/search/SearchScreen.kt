package com.gpo.pilipala.yt.feature.search

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.gpo.pilipala.yt.feature.mute.Mutes
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.sheetBottomPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.yt.core.extractor.ListItem
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.feature.player.miniPlayerInset
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.yt.ui.components.BackButton
import com.gpo.pilipala.yt.ui.components.ChannelRow
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.yt.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.yt.ui.components.PawTopBar
import com.gpo.pilipala.yt.ui.components.Pager
import com.gpo.pilipala.yt.ui.components.VideoRow
import com.gpo.pilipala.yt.ui.components.readingWidth
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.delay

/** The search field and YouTube's suggestions as you type. */
@Composable
fun SearchScreen(route: Route.Search) {
    val nav = LocalNavigator.current
    var query by rememberSaveable { mutableStateOf(route.hint) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        delay(200)
        suggestions = runCatching { Yt.suggestions(query) }.getOrDefault(emptyList())
    }
    val submit: (String) -> Unit = { q -> if (q.isNotBlank()) nav.replace(Route.SearchResult(q.trim())) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton()
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(ContinuousRoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text("搜索", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().readingWidth()) {
            items(suggestions, key = { it }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { submit(s) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(YoinSymbols.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { query = s }) { Icon(YoinSymbols.Edit, "填入") }
                }
            }
        }
    }
}

class SearchResultViewModel(private val query: String) : ViewModel() {
    var filter by mutableStateOf(Yt.SearchFilter())
        private set

    val pager = Pager<ListItem>(viewModelScope, dedupKey = { it.key }) { cursor ->
        val page = Yt.search(query, cursor, filter)
        page.copy(items = page.items.filterNot(Mutes::hides))
    }

    init { pager.refresh() }

    fun apply(f: Yt.SearchFilter) {
        if (f == filter) return
        filter = f
        pager.refresh()
    }
}

/**
 * 筛选: 类型, 排序, 上传时间, 时长 as rows of toggle buttons (the time and length rows only for
 * videos); applies as it changes.
 */
@Composable
private fun SearchFilterSheet(filter: Yt.SearchFilter, onChange: (Yt.SearchFilter) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = sheetBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FilterRow("类型", Yt.SearchFilter.Type.entries, filter.type, { it.label }) { onChange(filter.copy(type = it)) }
            FilterRow("排序", Yt.SearchFilter.Sort.entries, filter.sort, { it.label }) { onChange(filter.copy(sort = it)) }
            val videos = filter.type == Yt.SearchFilter.Type.All || filter.type == Yt.SearchFilter.Type.Videos
            if (videos) {
                FilterRow("上传时间", Yt.SearchFilter.Date.entries, filter.date, { it.label }) { onChange(filter.copy(date = it)) }
                FilterRow("时长", Yt.SearchFilter.Length.entries, filter.length, { it.label }) { onChange(filter.copy(length = it)) }
            }
        }
    }
}

@Composable
private fun <T> FilterRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                ToggleButton(
                    checked = o == selected,
                    onCheckedChange = {
                        if (o != selected) {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(o)
                        }
                    },
                ) { Text(label(o)) }
            }
        }
    }
}

/** Results: videos as rows, channels as channel rows, in YouTube's order. */
@Composable
fun SearchResultScreen(route: Route.SearchResult) {
    val nav = LocalNavigator.current
    val vm = viewModel(key = route.query) { SearchResultViewModel(route.query) }
    val pager = vm.pager
    val state = rememberLazyListState()
    LoadMoreEffect(state) { pager.loadMore() }
    var filters by remember { mutableStateOf(false) }
    // A channel muted from a row's menu goes at once (the next pages are filtered as they load).
    val mutes by Mutes.version.collectAsState()
    val shown = remember(pager.items, mutes) { pager.items.filterNot(Mutes::hides) }
    if (filters) SearchFilterSheet(vm.filter, vm::apply, onDismiss = { filters = false })
    Scaffold(
        topBar = {
            PawTopBar(route.query, actions = {
                // 筛选: filled while any filter is set.
                if (vm.filter.isDefault) {
                    IconButton(onClick = { filters = true }) { Icon(YoinSymbols.Filter, "筛选") }
                } else {
                    FilledTonalIconButton(onClick = { filters = true }) { Icon(YoinSymbols.Filter, "筛选") }
                }
                IconButton(onClick = { nav.replace(Route.Search(route.query)) }) { Icon(YoinSymbols.Search, "搜索") }
            })
        },
    ) { inner ->
        when {
            pager.initialLoading -> LoadingState(Modifier.padding(inner))
            pager.error != null && pager.items.isEmpty() -> ErrorState(pager.error.orEmpty(), Modifier.padding(inner), onRetry = pager::refresh)
            pager.items.isEmpty() -> EmptyState("没有结果", Modifier.padding(inner))
            else -> LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().padding(top = inner.calculateTopPadding()).readingWidth().seamViewport(state),
                contentPadding = PaddingValues(top = 8.dp, bottom = inner.calculateBottomPadding() + miniPlayerInset() + 16.dp),
            ) {
                items(shown, key = { it.key }) { item ->
                    when (item) {
                        is ListItem.Video -> VideoRow(item.video)
                        is ListItem.Channel -> ChannelRow(item.channel)
                    }
                }
                if (pager.isLoadingMore) item(key = "more") { LoadingFooter() }
            }
        }
    }
}
