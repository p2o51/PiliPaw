package com.gpo.pilipala.feature.feeds

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.home.Blacklist
import com.gpo.pilipala.feature.search.SearchApi
import com.gpo.pilipala.feature.search.SearchChip
import com.gpo.pilipala.feature.search.SearchHistoryStore
import com.gpo.pilipala.feature.search.SearchTab
import com.gpo.pilipala.feature.search.Suggestion
import com.gpo.pilipala.feature.search.UserHit
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.LocalPageSettled
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.BackButton
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.VideoCardV
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets

/** Videos the live preview mixes. */
private const val PreviewCount = 8

// ── ViewModel ───────────────────────────────────────────────────────────────

internal enum class SourceAdd { Added, Duplicate, Full, Empty }

@OptIn(FlowPreview::class)
internal class FeedEditorViewModel(route: Route.FeedEditor) : ViewModel() {
    /** The feed being edited; null = a new one. */
    val editing: Feed? = route.feedId.takeIf { it.isNotEmpty() }?.let(FeedStore::get)

    var name by mutableStateOf(TextFieldValue(editing?.name.orEmpty()))
    var sources by mutableStateOf(initialSources(route))
        private set
    var mix by mutableStateOf(editing?.mix ?: FeedMix.Mixed)

    /** The "添加关键词" field. */
    var keyword by mutableStateOf(TextFieldValue())
        private set
    var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
        private set

    var preview by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var previewLoading by mutableStateOf(false)
        private set
    var previewError by mutableStateOf<String?>(null)
        private set

    /** UP picker. */
    var userQuery by mutableStateOf(TextFieldValue())
        private set
    var users by mutableStateOf<List<UserHit>>(emptyList())
        private set
    var usersLoading by mutableStateOf(false)
        private set
    var usersError by mutableStateOf<String?>(null)
        private set

    private val settled = MutableStateFlow(false)
    private val keywordFlow = MutableStateFlow("")
    private val userFlow = MutableStateFlow("")
    private var previewRetry by mutableIntStateOf(0)

    val full: Boolean get() = sources.size >= MaxSources
    val canSave: Boolean get() = sources.isNotEmpty() && (editing != null || FeedStore.canCreate)

    init {
        viewModelScope.launch {
            keywordFlow.debounce(200).distinctUntilChanged().collectLatest { q ->
                suggestions = if (q.isBlank()) emptyList() else runCatching { SearchApi.suggest(q.trim()) }.getOrDefault(suggestions)
            }
        }
        viewModelScope.launch {
            userFlow.debounce(350).distinctUntilChanged().collectLatest { q -> searchUsers(q) }
        }
        viewModelScope.launch {
            // Nothing goes out while the page is still growing in.
            settled.first { it }
            snapshotFlow { Triple(sources, mix, previewRetry) }
                .distinctUntilChanged()
                .debounce(450)
                .collectLatest { (list, m, _) -> loadPreview(list, m) }
        }
    }

    fun onSettled() {
        settled.value = true
    }

    fun onKeyword(v: TextFieldValue) {
        keyword = v
        keywordFlow.value = v.text
    }

    fun onUserQuery(v: TextFieldValue) {
        userQuery = v
        userFlow.value = v.text
    }

    fun onName(v: TextFieldValue) {
        name = if (v.text.length <= MaxFeedName) v else v.copy(text = v.text.take(MaxFeedName), selection = TextRange(MaxFeedName))
    }

    fun addKeyword(raw: String): SourceAdd {
        val term = raw.trim()
        if (term.isEmpty()) return SourceAdd.Empty
        val source = FeedSource.Keyword(term)
        val result = add(source)
        if (result != SourceAdd.Full) onKeyword(TextFieldValue())
        return result
    }

    fun toggleUp(hit: UserHit): Boolean {
        val source = FeedSource.Up(hit.mid, hit.name, hit.face)
        return if (sources.any { it.key == source.key }) {
            remove(source.key)
            false
        } else {
            add(source) == SourceAdd.Added
        }
    }

    fun remove(key: String) {
        sources = sources.filterNot { it.key == key }
    }

    fun setOrder(key: String, order: KeywordOrder) {
        sources = sources.map { if (it.key == key && it is FeedSource.Keyword) it.copy(order = order) else it }
    }

    fun contains(source: FeedSource): Boolean = sources.any { it.key == source.key }

    fun retryPreview() {
        previewRetry++
    }

    /** Writes the feed; returns false when nothing was saved. */
    fun save(): Boolean {
        val feed = editing
        return if (feed != null) {
            FeedStore.update(feed.id, name.text, sources, mix)
        } else {
            val created = FeedStore.create(name.text, sources, mix) ?: return false
            FeedStore.requestFocus(created.id)
            true
        }
    }

    private fun add(source: FeedSource): SourceAdd = when {
        contains(source) -> SourceAdd.Duplicate
        full -> SourceAdd.Full
        else -> {
            sources = sources + source
            SourceAdd.Added
        }
    }

    private suspend fun loadPreview(list: List<FeedSource>, m: FeedMix) {
        if (list.isEmpty()) {
            preview = emptyList()
            previewError = null
            previewLoading = false
            return
        }
        previewLoading = true
        previewError = null
        try {
            Blacklist.ensureLoaded()
            val page = withContext(Dispatchers.Default) { FeedMixer(list, m).load(reset = true, target = PreviewCount) }
            preview = page.items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            preview = emptyList()
            previewError = e.friendlyMessage()
        } finally {
            previewLoading = false
        }
    }

    private suspend fun searchUsers(q: String) {
        if (q.isBlank()) {
            users = emptyList()
            usersError = null
            usersLoading = false
            return
        }
        usersLoading = true
        usersError = null
        try {
            val page = withContext(Dispatchers.Default) { SearchApi.search(SearchTab.User, q.trim(), 1, null, 0, -1) }
            users = page.items.filterIsInstance<UserHit>().filter { it.mid > 0 }.distinctBy { it.mid }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            users = emptyList()
            usersError = e.friendlyMessage()
        } finally {
            usersLoading = false
        }
    }

    private companion object {
        fun initialSources(route: Route.FeedEditor): List<FeedSource> {
            route.feedId.takeIf { it.isNotEmpty() }?.let(FeedStore::get)?.let { return it.sources }
            return buildList {
                val term = route.keyword.trim()
                if (term.isNotEmpty()) {
                    val order = KeywordOrder.entries.firstOrNull { it.key == route.order } ?: KeywordOrder.Totalrank
                    add(FeedSource.Keyword(term, order))
                }
                if (route.upMid > 0) add(FeedSource.Up(route.upMid, route.upName, route.upFace))
            }
        }
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────

/**
 * 新建 / 编辑 Feed: name, 混合 / 最新, the sources as tonal rows (keywords
 * carry their own 综合 / 最新), an add field with suggest + search history,
 * the UP picker, and a live preview of the mixed stream. Expanded puts the
 * preview in its own pane.
 */
@Composable
fun FeedEditorScreen(route: Route.FeedEditor) {
    val nav = LocalNavigator.current
    val vm = viewModel { FeedEditorViewModel(route) }
    val settled = LocalPageSettled.current
    LaunchedEffect(settled) { if (settled) vm.onSettled() }
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    var picker by rememberSaveable { mutableStateOf(false) }
    val wc = widthClass()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun addKeyword(term: String) {
        when (vm.addKeyword(term)) {
            SourceAdd.Added -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            SourceAdd.Duplicate -> toast("已在来源中")
            SourceAdd.Full -> toast("最多 $MaxSources 个来源")
            SourceAdd.Empty -> Unit
        }
    }

    val form: LazyGridScope.() -> Unit = {
        editorForm(
            vm = vm,
            onAddKeyword = ::addKeyword,
            onAddUp = {
                keyboard?.hide()
                picker = true
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        EditorTopBar(
            title = if (vm.editing == null) "新建 Feed" else "编辑 Feed",
            action = if (vm.editing == null) "创建" else "保存",
            enabled = vm.canSave,
            onSave = {
                if (vm.save()) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    keyboard?.hide()
                    nav.pop()
                } else if (vm.editing == null && !FeedStore.canCreate) {
                    toast("最多 $MaxFeeds 个 Feed")
                }
            },
        )
        if (wc == WidthClass.Expanded) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    modifier = Modifier
                        .weight(0.42f)
                        .widthIn(max = 560.dp)
                        .fillMaxHeight()
                        .imePadding(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = navBottom + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = form,
                )
                Box(
                    Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .padding(bottom = navBottom + 16.dp)
                        .clip(YoinContainerShapes.Panel)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        previewItems(vm, count = PreviewCount)
                    }
                }
            }
        } else {
            val cols = if (wc == WidthClass.Compact) 2 else 3
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier
                    .readingWidth(760.dp)
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = navBottom + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                form()
                previewItems(vm, count = cols * 2)
            }
        }
    }

    if (picker) UpPickerSheet(vm, onDismiss = { picker = false })
}

@Composable
private fun EditorTopBar(title: String, action: String, enabled: Boolean, onSave: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton()
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = onSave,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Text(action, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The form as full-span grid items, shared by the single-pane grid and the Expanded form pane. */
private fun LazyGridScope.editorForm(
    vm: FeedEditorViewModel,
    onAddKeyword: (String) -> Unit,
    onAddUp: () -> Unit,
) {
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    item(key = "name", span = full, contentType = "field") {
        FeedTextField(
            value = vm.name,
            onValueChange = vm::onName,
            modifier = Modifier.padding(top = 8.dp),
            label = "名称",
            placeholder = defaultFeedName(vm.sources),
            imeAction = ImeAction.Next,
        )
    }
    item(key = "mixTitle", span = full, contentType = "title") { FeedSectionTitle("顺序") }
    item(key = "mix", span = full, contentType = "toggles") {
        ConnectedToggles(
            options = FeedMix.entries,
            selected = vm.mix,
            label = { it.label },
            onSelect = { vm.mix = it },
            fill = true,
        )
    }
    item(key = "sourcesTitle", span = full, contentType = "title") {
        FeedSectionTitle("来源", trailing = "${vm.sources.size}/$MaxSources")
    }
    items(vm.sources, key = { "src:" + it.key }, span = { GridItemSpan(maxLineSpan) }, contentType = { "source" }) { source ->
        SourceRow(
            source = source,
            onOrder = { vm.setOrder(source.key, it) },
            onRemove = { vm.remove(source.key) },
            modifier = Modifier.animateItem(
                fadeInSpec = YoinMotion.defaultEffectsSpec(),
                placementSpec = YoinMotion.defaultSpatialSpec(),
                fadeOutSpec = YoinMotion.fastEffectsSpec(),
            ),
        )
    }
    item(key = "addKeyword", span = full, contentType = "field") {
        val blank = vm.keyword.text.isBlank()
        FeedTextField(
            value = vm.keyword,
            onValueChange = vm::onKeyword,
            modifier = Modifier.padding(top = 4.dp),
            placeholder = "添加关键词",
            leading = YoinSymbols.Search,
            enabled = !vm.full,
            onImeAction = { onAddKeyword(vm.keyword.text) },
            trailing = {
                AnimatedVisibility(
                    visible = !blank,
                    enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.6f),
                    exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.6f),
                ) {
                    IconButton(onClick = { onAddKeyword(vm.keyword.text) }, shapes = IconButtonDefaults.shapes()) {
                        Icon(YoinSymbols.Add, contentDescription = "添加")
                    }
                }
            },
        )
    }
    item(key = "keywordPicks", span = full, contentType = "picks") {
        KeywordPicks(vm, onAddKeyword)
    }
    item(key = "addUp", span = full, contentType = "row") {
        AddUpRow(enabled = !vm.full, onClick = onAddUp)
    }
}

/** Suggestions for what is typed, or the search history when the field is empty. */
@Composable
private fun KeywordPicks(vm: FeedEditorViewModel, onPick: (String) -> Unit) {
    val historyRaw by AppSettings.searchHistory.state.collectAsState()
    val typing = vm.keyword.text.isNotBlank()
    val picks = if (typing) {
        vm.suggestions.map { it.term }
    } else {
        remember(historyRaw) { SearchHistoryStore.parse(historyRaw) }
    }.filterNot { vm.contains(FeedSource.Keyword(it)) }.take(12)
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    val size = YoinMotion.defaultSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = typing to picks,
        transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) using SizeTransform { _, _ -> size } },
        contentKey = { it.first },
        label = "keywordPicks",
    ) { (isTyping, list) ->
        if (list.isEmpty() || vm.full) {
            Spacer(Modifier.fillMaxWidth().height(4.dp))
        } else {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                if (!isTyping) {
                    Text(
                        "搜索历史",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .animateContentSize(YoinMotion.defaultSpatialSpec()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    list.forEach { term -> SearchChip(term, maxWidth = 240.dp) { onPick(term) } }
                }
            }
        }
    }
}

@Composable
private fun AddUpRow(enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(YoinContainerShapes.ListRow)
            .background(scheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(scheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PiliSymbols.PersonAdd, null, Modifier.size(18.dp), tint = scheme.onTertiaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "添加 UP主",
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f),
        )
        Icon(YoinSymbols.ChevronRight, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
    }
}

private enum class PreviewPhase { Hidden, Loading, Error, Empty, Content }

/** "预览" and the first mixed videos (cards fill the grid's cells). */
private fun LazyGridScope.previewItems(vm: FeedEditorViewModel, count: Int) {
    val phase = when {
        vm.sources.isEmpty() -> PreviewPhase.Hidden
        vm.preview.isNotEmpty() -> PreviewPhase.Content
        vm.previewLoading -> PreviewPhase.Loading
        vm.previewError != null -> PreviewPhase.Error
        else -> PreviewPhase.Loading
    }
    if (phase == PreviewPhase.Hidden) return
    item(key = "previewTitle", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeedSectionTitle("预览", Modifier.weight(1f))
            AnimatedVisibility(
                visible = vm.previewLoading && vm.preview.isNotEmpty(),
                enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                exit = fadeOut(YoinMotion.fastEffectsSpec()),
            ) {
                LoadingIndicator(Modifier.padding(top = 10.dp).size(28.dp))
            }
        }
    }
    when (phase) {
        PreviewPhase.Content -> items(vm.preview.take(count), key = { "pv:" + it.bvid.ifEmpty { it.aid.toString() } }, contentType = { "card" }) {
            VideoCardV(
                it,
                Modifier.animateItem(
                    fadeInSpec = YoinMotion.defaultEffectsSpec(),
                    placementSpec = YoinMotion.defaultSpatialSpec(),
                    fadeOutSpec = YoinMotion.fastEffectsSpec(),
                ),
            )
        }
        PreviewPhase.Loading -> item(key = "previewLoading", span = { GridItemSpan(maxLineSpan) }, contentType = "state") {
            Box(Modifier.fillMaxWidth().height(200.dp).animateItem(fadeInSpec = YoinMotion.defaultEffectsSpec(), placementSpec = YoinMotion.defaultSpatialSpec(), fadeOutSpec = YoinMotion.fastEffectsSpec()), contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
        PreviewPhase.Error -> item(key = "previewError", span = { GridItemSpan(maxLineSpan) }, contentType = "state") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateItem(fadeInSpec = YoinMotion.defaultEffectsSpec(), placementSpec = YoinMotion.defaultSpatialSpec(), fadeOutSpec = YoinMotion.fastEffectsSpec())
                    .clip(YoinContainerShapes.Card)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    vm.previewError.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = vm::retryPreview, shapes = ButtonDefaults.shapes()) { Text("重试") }
            }
        }
        else -> item(key = "previewEmpty", span = { GridItemSpan(maxLineSpan) }, contentType = "state") {
            Box(Modifier.fillMaxWidth().height(200.dp).animateItem(fadeInSpec = YoinMotion.defaultEffectsSpec(), placementSpec = YoinMotion.defaultSpatialSpec(), fadeOutSpec = YoinMotion.fastEffectsSpec())) { EmptyState("无结果") }
        }
    }
}

// ── UP picker ───────────────────────────────────────────────────────────────

private enum class PickerPhase { Idle, Loading, Error, Empty, Content }

@Composable
private fun UpPickerSheet(vm: FeedEditorViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)) {
                Text(
                    "添加 UP主",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${vm.sources.size}/$MaxSources",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = {
                        keyboard?.hide()
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("完成") }
            }
            FeedTextField(
                value = vm.userQuery,
                onValueChange = vm::onUserQuery,
                placeholder = "搜索 UP主",
                leading = YoinSymbols.Search,
                imeAction = ImeAction.Search,
                onImeAction = { keyboard?.hide() },
                focusRequester = focus,
            )
            // The sheet's window attaches a frame after this composes.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { focus.requestFocus() }
            }
            Spacer(Modifier.height(8.dp))
            val phase = when {
                vm.users.isNotEmpty() -> PickerPhase.Content
                vm.usersLoading -> PickerPhase.Loading
                vm.usersError != null -> PickerPhase.Error
                vm.userQuery.text.isBlank() -> PickerPhase.Idle
                else -> PickerPhase.Empty
            }
            val enter = YoinMotion.defaultEffectsSpec<Float>()
            val exit = YoinMotion.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "upPicker",
            ) { p ->
                when (p) {
                    PickerPhase.Idle -> Spacer(Modifier.fillMaxSize())
                    PickerPhase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    PickerPhase.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(vm.usersError.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PickerPhase.Empty -> EmptyState("无结果", icon = YoinSymbols.Search)
                    PickerPhase.Content -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(vm.users, key = { it.mid }) { hit ->
                            val added = vm.contains(FeedSource.Up(hit.mid, hit.name))
                            UpPickRow(
                                hit = hit,
                                added = added,
                                enabled = added || !vm.full,
                                onToggle = {
                                    if (!added && vm.full) {
                                        toast("最多 $MaxSources 个来源")
                                    } else {
                                        val on = vm.toggleUp(hit)
                                        haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                    }
                                },
                                modifier = Modifier.animateItem(fadeInSpec = YoinMotion.defaultEffectsSpec(), placementSpec = YoinMotion.defaultSpatialSpec(), fadeOutSpec = YoinMotion.fastEffectsSpec()),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpPickRow(hit: UserHit, added: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(hit.face, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                hit.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CountLabelLong(hit.fans, "粉丝", self = FameMode.isSelf(hit.mid))
                CountLabelLong(hit.videos, "视频")
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconToggleButton(
            checked = added,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            shapes = IconButtonDefaults.toggleableShapes(),
        ) {
            val enter = YoinMotion.defaultEffectsSpec<Float>()
            val pop = YoinMotion.fastSpatialSpec<Float>()
            val exit = YoinMotion.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = added,
                transitionSpec = { (fadeIn(enter) + scaleIn(pop, initialScale = 0.6f)) togetherWith fadeOut(exit) },
                label = "upAdded",
            ) { on ->
                Icon(if (on) YoinSymbols.Check else YoinSymbols.Add, contentDescription = if (on) "已添加" else "添加")
            }
        }
    }
}

/**
 * Number then label; 粉丝 is a line under 名声模式 ([self]: the row is the signed-in
 * user), inside the row's tap, so never tapped open.
 */
@Composable
private fun CountLabelLong(n: Long, label: String, self: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom) {
        FameCount(
            com.gpo.pilipala.core.util.Fmt.count(n),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            revealable = false,
            hide = label == "粉丝",
            self = self,
        )
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
