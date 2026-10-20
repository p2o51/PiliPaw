package com.gpo.pilipala.feature.reply

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.pilipala.ui.components.inlineIconSize
import com.gpo.pilipala.ui.components.centered

private const val KEY_BAR = "reply:bar"
private const val KEY_COMPOSE = "reply:compose"
private const val KEY_STATE = "reply:state"
private const val KEY_FOOTER = "reply:footer"
private const val KEY_ANCHOR = "reply:anchor"

/** The list's saveable state (expanded texts, item states) while a thread covers it. */
private const val LIST_STATE_KEY = "reply:list"

/** A thread open in place: its subject (a row may carry its own oid) and root rpid. */
private data class OpenThread(val oid: Long, val rpid: Long)

private val OpenThreadSaver = Saver<OpenThread?, LongArray>(
    save = { open -> open?.let { longArrayOf(it.oid, it.rpid) } },
    restore = { OpenThread(it[0], it[1]) },
)

/**
 * Comment list for any subject. CONTRACT — signature is fixed.
 * type: 1 = video, 11 = dynamic (images), 17 = dynamic (text/forward), 12 = article, 14 = audio.
 * [header] lets the host put its own content above the comments in the same
 * LazyColumn (dynamic detail). [onTimestampClick] receives seconds when a
 * "03:21" link in a video comment is tapped.
 *
 * Threads ("共N条回复", or tapping a row): on Compact windows they open as the
 * pushed Route.ReplyDetail page. On Medium / Expanded the list has two levels
 * of its own: the thread ([ReplyThreadPane], inline) replaces the list inside
 * this same pane with a shared-axis step, and system back or its header arrow
 * returns to the list where it was.
 */
@Composable
fun ReplyList(
    oid: Long,
    type: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    header: (LazyListScope.() -> Unit)? = null,
    onTimestampClick: ((seconds: Long) -> Unit)? = null,
    threadsInPlace: Boolean = false,
) {
    val vm: ReplyListViewModel = viewModel(key = "reply_list_${oid}_$type") { ReplyListViewModel(oid, type) }
    val nav = LocalNavigator.current
    val ui = rememberReplyUiState()
    val listState = rememberLazyListState()
    val motion = replyMotion()
    // A list inside a sheet keeps its threads in the sheet too: pushing a page from there
    // would drop the sheet's window and flash.
    val inPlace = threadsInPlace || widthClass() != WidthClass.Compact
    var thread by rememberSaveable(stateSaver = OpenThreadSaver) { mutableStateOf(null) }

    val actions = rememberReplyActions(
        vm = vm,
        ui = ui,
        onReply = { r -> ComposeTarget(root = r.rpid, parent = r.rpid, replyTo = r.member.name) },
        onOpenThread = { r ->
            val subject = r.oid.takeIf { it != 0L } ?: oid
            if (inPlace) {
                thread = OpenThread(subject, r.rpid)
            } else {
                nav.push(Route.ReplyDetail(subject, type, r.rpid))
            }
        },
        onTimestampClick = onTimestampClick,
        threadSource = !inPlace,
    )

    // A comment just sent sits at the top: bring it into view when the list is scrolled away.
    LaunchedEffect(vm, listState) {
        vm.sentEvents.collect { target ->
            if (target.root != 0L) return@collect
            repeat(2) { withFrameNanos { } }
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.any { it.key == KEY_COMPOSE }) return@collect
            val composeIndex = info.totalItemsCount - 1 - vm.pager.items.size - vm.top.size - 1
            if (composeIndex >= 0) listState.animateScrollToItem(composeIndex)
        }
    }

    // Back leaves the thread only while this pane is mostly on screen: a pager keeps its
    // neighbouring pages composed, and back on the 简介 / 相关 tab must not close an unseen thread.
    var onScreen by remember { mutableStateOf(true) }
    BackHandler(enabled = thread != null && onScreen) { thread = null }

    val listHolder = rememberSaveableStateHolder()
    val distance = with(LocalDensity.current) { SharedAxisDistance.roundToPx() }
    Box(
        modifier
            .clipToBounds()
            .onGloballyPositioned { c ->
                val width = c.size.width
                val visible = width > 0 && c.boundsInWindow().width >= width / 2f
                if (visible != onScreen) onScreen = visible
            },
    ) {
        AnimatedContent(
            targetState = thread,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { motion.sharedAxisX(forward = targetState != null, distancePx = distance) },
            label = "replyThread",
        ) { open ->
            if (open == null) {
                listHolder.SaveableStateProvider(LIST_STATE_KEY) {
                    CommentList(
                        vm = vm,
                        ui = ui,
                        actions = actions,
                        listState = listState,
                        contentPadding = contentPadding,
                        header = header,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                InPlaceThread(
                    vm = vm,
                    open = open,
                    type = type,
                    isOpen = { thread != null },
                    contentPadding = contentPadding,
                    onTimestampClick = onTimestampClick,
                    onClose = { thread = null },
                )
            }
        }
    }
    ReplyOverlays(ui, vm)
}

/** The list level: sort bar, composer pill, 置顶 + paged comments, and the compose FAB. */
@Composable
private fun CommentList(
    vm: ReplyListViewModel,
    ui: ReplyUiState,
    actions: ReplyActions,
    listState: LazyListState,
    contentPadding: PaddingValues,
    header: (LazyListScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val pager = vm.pager
    val me by Account.user.collectAsState()
    val motion = replyMotion()
    LoadMoreEffect(listState) { pager.loadMore() }

    val openComposer = { if (requireLogin(nav, toast)) ui.composer = ComposeTarget() }
    val pillVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == KEY_COMPOSE } }
    }
    val hasContent = pager.items.isNotEmpty() || vm.top.isNotEmpty() || vm.anchored != null
    // The comment a notification opened this list for lands pinned on top: bring it into
    // view (a host's header, the dynamic or the article, sits above it in this list).
    val anchoredId = vm.anchored?.rpid
    LaunchedEffect(anchoredId != null) {
        if (anchoredId == null || vm.anchorScrolled) return@LaunchedEffect
        vm.anchorScrolled = true
        repeat(2) { withFrameNanos { } }
        // Counted from the end: the header's items come first and their number is the host's.
        val tail = vm.top.size + if (pager.items.isEmpty()) 1 else pager.items.size + 1
        val anchorIndex = listState.layoutInfo.totalItemsCount - tail - 1
        val barIndex = anchorIndex - 1 - if (vm.readOnly) 0 else 1
        if (barIndex > 0) listState.animateScrollToItem(barIndex)
    }
    // Rows only fade as they land; placement springs stand down while a row resizes.
    val placement = remember(ui.layout) { ui.layout.gated(motion.offset) }

    Box(modifier) {
        RefreshBox(
            isRefreshing = pager.isRefreshing && pager.items.isNotEmpty(),
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
            indicatorPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
        ) {
            LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                header?.invoke(this)
                item(key = KEY_BAR, contentType = "reply:bar") {
                    SortBar(count = vm.count, sort = vm.sort, onSort = vm::changeSort, modifier = Modifier.readingWidth())
                }
                if (!vm.readOnly) {
                    item(key = KEY_COMPOSE, contentType = "reply:compose") {
                        ComposePill(face = me?.face, label = "评论", modifier = Modifier.readingWidth(), onClick = openComposer)
                    }
                }
                vm.anchored?.let { r ->
                    item(key = KEY_ANCHOR, contentType = "reply") {
                        AnchoredRow(vm, r, actions, replyItemMotion(motion, placement).readingWidth()) {
                            actions.onOpenThread?.invoke(r)
                        }
                    }
                }
                items(vm.top, key = { "reply:t${it.rpid}" }, contentType = { "reply" }) { r ->
                    ReplyRow(r, actions, replyItemMotion(motion, placement).readingWidth()) {
                        actions.onOpenThread?.invoke(r)
                    }
                }
                if (pager.items.isEmpty()) {
                    item(key = KEY_STATE, contentType = "reply:state") {
                        ListState(
                            pager = pager,
                            hasTop = vm.top.isNotEmpty(),
                            onRetry = vm::refresh,
                            modifier = replyItemMotion(motion, placement).readingWidth(),
                        )
                    }
                } else {
                    // Keyed by generation: a sort switch swaps every row as one plain cross-fade.
                    val gen = vm.listGen
                    items(pager.items, key = { "reply:$gen:${it.rpid}" }, contentType = { "reply" }) { r ->
                        ReplyRow(r, actions, replyItemMotion(motion, placement).readingWidth()) {
                            actions.onOpenThread?.invoke(r)
                        }
                    }
                    item(key = KEY_FOOTER, contentType = "reply:footer") {
                        PagerFooter(pager, Modifier.readingWidth())
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = !pillVisible && !vm.readOnly && hasContent,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            enter = scaleIn(settledFastSpatialSpec(), initialScale = 0.8f) + fadeIn(motion.fade),
            exit = scaleOut(settledFastSpatialSpec(), targetScale = 0.8f) + fadeOut(motion.fadeOut),
        ) {
            FloatingActionButton(
                onClick = openComposer,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(YoinSymbols.Edit, contentDescription = "评论")
            }
        }
    }
}

/**
 * The thread level of an in-place list. Its ViewModel lives in a store the
 * list's ViewModel owns: it survives a trip to another page and back (the
 * thread is still open then), and is cleared once the thread has closed and
 * left the screen. Sends and deletes made here update the list underneath.
 */
@Composable
private fun InPlaceThread(
    vm: ReplyListViewModel,
    open: OpenThread,
    type: Int,
    isOpen: () -> Boolean,
    contentPadding: PaddingValues,
    onTimestampClick: ((Long) -> Unit)?,
    onClose: () -> Unit,
) {
    val owner = remember(vm) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore get() = vm.threadStore
        }
    }
    val stillOpen by rememberUpdatedState(isOpen)
    DisposableEffect(owner) {
        onDispose { if (!stillOpen()) vm.threadStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        ThreadPane(
            oid = open.oid,
            type = type,
            rpid = open.rpid,
            onClose = onClose,
            modifier = Modifier.fillMaxSize(),
            inline = true,
            contentPadding = contentPadding,
            onTimestampClick = onTimestampClick,
            onChange = vm::mirror,
        )
    }
}

/** 热门 / 最新 as a connected toggle pair, count on the other side. */
@Composable
private fun SortBar(count: Long, sort: Int, onSort: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val motion = replyMotion()
    Row(
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            listOf(1 to "热门", 0 to "最新").forEachIndexed { i, (value, label) ->
                ToggleButton(
                    checked = sort == value,
                    onCheckedChange = {
                        if (sort != value) {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSort(value)
                        }
                    },
                    shapes = if (i == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    },
                    contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                    modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (count > 0) {
            val countStyle = MaterialTheme.typography.labelLarge.centered()
            Icon(PiliSymbols.Comment, null, Modifier.size(countStyle.inlineIconSize()), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    motion.crossfade()
                },
                label = "replyCount",
            ) { c ->
                Text(
                    Fmt.count(c),
                    style = countStyle,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Tappable field that opens the composer sheet. */
@Composable
internal fun ComposePill(face: String?, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!face.isNullOrEmpty()) {
                Avatar(face, 32.dp)
            } else {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(YoinSymbols.Person, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
            }
            Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Icon(ReplyIcons.Emoji, null, Modifier.padding(end = 8.dp).size(20.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

private enum class StateKind { Loading, Error, Empty, None }

/** Loading / error / empty block shown in place of the first page. */
@Composable
internal fun ListState(pager: Pager<Reply>, hasTop: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val motion = replyMotion()
    val kind = when {
        pager.initialLoading || pager.isRefreshing -> StateKind.Loading
        pager.error != null -> StateKind.Error
        !hasTop -> StateKind.Empty
        else -> StateKind.None
    }
    AnimatedContent(
        targetState = kind,
        modifier = modifier,
        transitionSpec = {
            motion.crossfade()
        },
        label = "replyState",
    ) { k ->
        Box(
            Modifier.fillMaxWidth().heightIn(min = if (k == StateKind.None) 24.dp else 260.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (k) {
                StateKind.Loading -> LoadingIndicator()
                StateKind.Error -> ErrorState(pager.error.orEmpty(), onRetry = onRetry)
                StateKind.Empty -> EmptyState("暂无评论", icon = PiliSymbols.Comment)
                StateKind.None -> Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Footer: loading more, retry, or the end. */
@Composable
internal fun PagerFooter(pager: Pager<Reply>, modifier: Modifier = Modifier) {
    // Pager keeps `error` after a later success; only show it while nothing new arrived.
    val errorAt = remember(pager.error) { pager.items.size }
    val showError = pager.error != null && pager.items.size == errorAt && !pager.isLoadingMore
    Box(modifier.fillMaxWidth().heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
        when {
            pager.isLoadingMore -> LoadingFooter()
            showError -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pager.error.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { pager.loadMore() }) { Text("重试") }
            }
            !pager.hasMore -> Text(
                "没有更多了",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            else -> Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The comment a notification opened the list for: it lands on a primaryContainer block
 * that settles away on a slow spring once it has been seen, the way 官方 marks it.
 */
@Composable
private fun AnchoredRow(vm: ReplyListViewModel, r: Reply, actions: ReplyActions, modifier: Modifier, onClick: () -> Unit) {
    val glow = remember { Animatable(if (vm.anchorGlowed) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (vm.anchorGlowed) return@LaunchedEffect
        delay(AnchorHoldMs)
        glow.animateTo(0f, spring(stiffness = Spring.StiffnessVeryLow))
        vm.anchorGlowed = true
    }
    val tint = MaterialTheme.colorScheme.primaryContainer
    ReplyRow(
        r,
        actions,
        modifier.drawBehind {
            val a = glow.value
            if (a > 0f) {
                val inset = 4.dp.toPx()
                drawRoundRect(
                    color = tint.copy(alpha = tint.alpha * a),
                    topLeft = Offset(inset, 0f),
                    size = Size(size.width - inset * 2, size.height),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                )
            }
        },
        onClick = onClick,
    )
}

private const val AnchorHoldMs = 1400L
