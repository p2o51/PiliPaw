package com.gpo.pilipala.feature.reply

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.LocalPageSettled
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.PiliTopBar
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.flow.first

/** Reply thread ("楼中楼") as a pushed page (phones; message notifications). */
@Composable
fun ReplyDetailScreen(route: Route.ReplyDetail) {
    val nav = LocalNavigator.current
    ThreadPane(oid = route.oid, type = route.type, rpid = route.rpid, onClose = { nav.pop() }, focusRpid = route.focus)
}

/**
 * Reply thread ("楼中楼"): the root comment, then its paged replies, with a
 * reply composer.
 *
 * [inline] false: a whole page (top app bar, composer bar over the navigation
 * bar), the body of Route.ReplyDetail. true: drawn inside a comment pane in
 * place of the list (ReplyList does this on Medium / Expanded windows): a
 * header row (back arrow, 评论详情, reply count) over the root comment and the
 * composer pill at the pane's bottom, both padded by the pane's
 * [contentPadding]. The host owns system back in that mode.
 *
 * [onClose] leaves the thread (the page pops, the pane returns to the list);
 * it also runs when the root comment is deleted here.
 */
@Composable
fun ReplyThreadPane(
    oid: Long,
    type: Int,
    rpid: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    onTimestampClick: ((seconds: Long) -> Unit)? = null,
) {
    ThreadPane(oid, type, rpid, onClose, modifier, inline, contentPadding, onTimestampClick, onChange = null)
}

/**
 * [ReplyThreadPane] plus [onChange]: sends and deletes made in the thread, for the list it
 * covers; and [focusRpid]: a reply a notification points at, scrolled to and marked once
 * the thread has arrived.
 */
@Composable
internal fun ThreadPane(
    oid: Long,
    type: Int,
    rpid: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    onTimestampClick: ((seconds: Long) -> Unit)? = null,
    onChange: ((ReplyChange) -> Unit)? = null,
    focusRpid: Long = 0L,
) {
    val vm: ReplyThreadViewModel = viewModel(key = "reply_thread_${oid}_${type}_$rpid") {
        ReplyThreadViewModel(oid, type, rpid)
    }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val ui = rememberReplyUiState()
    val listState = rememberLazyListState()
    val me by Account.user.collectAsState()
    val close by rememberUpdatedState(onClose)
    val change by rememberUpdatedState(onChange)

    val actions = rememberReplyActions(
        vm = vm,
        ui = ui,
        onReply = { r ->
            if (r.rpid == rpid || r.root == 0L) {
                ComposeTarget(root = rpid, parent = r.rpid, replyTo = r.member.name)
            } else {
                ComposeTarget(root = rpid, parent = r.rpid, replyTo = r.member.name, prefixMention = true)
            }
        },
        onOpenThread = null,
        onTimestampClick = onTimestampClick,
    )

    // A reply just sent lands under the header: bring it into view when the thread is scrolled away.
    LaunchedEffect(vm, listState) {
        vm.sentEvents.collect {
            repeat(2) { withFrameNanos { } }
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == KEY_THREAD_HEADER }) {
                listState.animateScrollToItem(1)
            }
        }
    }
    LaunchedEffect(vm) { vm.changes.collect { change?.invoke(it) } }

    val focusMark = rememberAnchorMark()
    val pageSettled by rememberUpdatedState(LocalPageSettled.current)
    val focusGapPx = with(LocalDensity.current) { FocusGap.roundToPx() }
    LaunchedEffect(vm, focusRpid) {
        if (focusRpid == 0L) return@LaunchedEffect
        if (!vm.focusHandled) {
            snapshotFlow { pageSettled }.first { it }
            val found = findFocus(vm, focusRpid)
            vm.focusHandled = true
            if (found < 0) return@LaunchedEffect
            repeat(2) { withFrameNanos { } }
            val index = found + FIRST_REPLY_INDEX
            val info = listState.layoutInfo
            val shown = info.visibleItemsInfo.firstOrNull { it.index == index }
            val fullyShown = shown != null && shown.offset >= 0 &&
                shown.offset + shown.size <= info.viewportEndOffset - info.afterContentPadding
            if (!fullyShown) listState.animateScrollToItem(index, -focusGapPx)
            vm.focusLanded = focusRpid
        }
        if (vm.focusLanded != 0L) {
            try {
                focusMark.play()
            } finally {
                vm.focusLanded = 0L
            }
        }
    }

    val replyToRoot = {
        val root = vm.root
        if (requireLogin(nav, toast)) {
            ui.composer = ComposeTarget(root = rpid, parent = rpid, replyTo = root?.member?.name.orEmpty())
        }
    }
    val pillLabel = vm.root?.member?.name?.let { "回复 @$it" } ?: "回复"

    if (inline) {
        val direction = LocalLayoutDirection.current
        Column(modifier) {
            ThreadPaneHeader(
                count = vm.count,
                onBack = { close() },
                modifier = Modifier
                    .padding(top = contentPadding.calculateTopPadding())
                    .readingWidth(),
            )
            ThreadBody(
                vm = vm,
                actions = actions,
                listState = listState,
                padding = PaddingValues(
                    start = contentPadding.calculateStartPadding(direction),
                    end = contentPadding.calculateEndPadding(direction),
                    bottom = 8.dp,
                ),
                showCount = false,
                focusMark = focusMark,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            // The pill's own 8dp margin counts toward the pane's bottom inset.
            ComposePill(
                face = me?.face,
                label = pillLabel,
                modifier = Modifier
                    .padding(bottom = (contentPadding.calculateBottomPadding() - 8.dp).coerceAtLeast(0.dp))
                    .readingWidth(),
                onClick = replyToRoot,
            )
        }
    } else {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = { PiliTopBar("评论详情", onBack = { close() }, scrollBehavior = scrollBehavior) },
            bottomBar = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .navigationBarsPadding(),
                ) {
                    ComposePill(face = me?.face, label = pillLabel, modifier = Modifier.readingWidth(), onClick = replyToRoot)
                }
            },
        ) { padding ->
            ThreadBody(
                vm = vm,
                actions = actions,
                listState = listState,
                padding = padding,
                showCount = true,
                focusMark = focusMark,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    ReplyOverlays(ui, vm) { deleted -> if (deleted.rpid == rpid) close() }
}

private const val KEY_THREAD_HEADER = "thread:header"

/** The paged replies' first index in the thread's list: after the root and the header. */
private const val FIRST_REPLY_INDEX = 2

/** Pages the thread loads looking for a notification's reply before it gives up. */
private const val FOCUS_MAX_PAGES = 10

/** Room left above the reply scrolled to, so the one before it still shows. */
private val FocusGap = 56.dp

/**
 * Pages through [vm]'s thread until [rpid] is listed: its index among the replies, or -1
 * when the thread ends, a page fails, or [FOCUS_MAX_PAGES] pass without it.
 */
private suspend fun findFocus(vm: ReplyThreadViewModel, rpid: Long): Int {
    val pager = vm.pager
    repeat(FOCUS_MAX_PAGES + 1) {
        snapshotFlow { pager.initialLoading || pager.isRefreshing || pager.isLoadingMore }.first { !it }
        val i = pager.items.indexOfFirst { it.rpid == rpid }
        if (i >= 0) return i
        if (!pager.hasMore || pager.error != null) return -1
        pager.loadMore()
    }
    return -1
}

/** Root comment, the 相关回复 block, then the paged replies; [focusMark] marks the reply a notification points at. */
@Composable
private fun ThreadBody(
    vm: ReplyThreadViewModel,
    actions: ReplyActions,
    listState: LazyListState,
    padding: PaddingValues,
    showCount: Boolean,
    focusMark: AnchorMark,
    modifier: Modifier = Modifier,
) {
    val pager = vm.pager
    val motion = replyMotion()
    val placement = remember(actions.layout) { actions.layout?.gated(motion.offset) ?: motion.offset }
    LoadMoreEffect(listState) { pager.loadMore() }
    RefreshBox(
        isRefreshing = pager.isRefreshing && vm.root != null,
        onRefresh = vm::refresh,
        modifier = modifier,
        indicatorPadding = PaddingValues(top = padding.calculateTopPadding()),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        ) {
            val root = vm.root
            if (root == null) {
                item(key = "thread:state") {
                    ListState(pager = pager, hasTop = false, onRetry = vm::refresh, modifier = replyItemMotion(motion, placement).readingWidth())
                }
            } else {
                item(key = "thread:root", contentType = "root") {
                    ReplyRow(
                        reply = root,
                        actions = actions,
                        modifier = replyItemMotion(motion, placement).readingWidth(),
                        avatarSize = 40.dp,
                        showPreview = false,
                        clampLines = false,
                    ) { actions.onReply(root) }
                }
                item(key = KEY_THREAD_HEADER, contentType = "header") {
                    ThreadHeader(vm.count, showCount, replyItemMotion(motion, placement).readingWidth())
                }
                if (pager.items.isEmpty()) {
                    item(key = "thread:empty") {
                        ListState(pager = pager, hasTop = false, onRetry = vm::refresh, modifier = replyItemMotion(motion, placement).readingWidth())
                    }
                } else {
                    items(pager.items, key = { "thread:${it.rpid}" }, contentType = { "reply" }) { r ->
                        ReplyRow(
                            reply = r,
                            actions = actions,
                            modifier = replyItemMotion(motion, placement).readingWidth(),
                            avatarSize = 32.dp,
                            showPreview = false,
                            clampLines = false,
                            mark = focusMark.takeIf { r.rpid == vm.focusLanded },
                        ) { actions.onReply(r) }
                    }
                    item(key = "thread:footer") { PagerFooter(pager, Modifier.readingWidth()) }
                }
            }
        }
    }
}

/** The in-pane header: back, the title, the reply count as its own quieter label. */
@Composable
private fun ThreadPaneHeader(count: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val motion = replyMotion()
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Back, contentDescription = "返回")
        }
        Spacer(Modifier.width(8.dp))
        Text("评论详情", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        AnimatedContent(
            targetState = count,
            transitionSpec = { motion.crossfade() },
            label = "threadPaneCount",
        ) { c ->
            if (c > 0) {
                Text(
                    Fmt.count(c),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Separates the root from its replies; carries the count on the page, where no header shows it. */
@Composable
private fun ThreadHeader(count: Long, showCount: Boolean, modifier: Modifier = Modifier) {
    val motion = replyMotion()
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, ContinuousRoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("相关回复", style = MaterialTheme.typography.titleSmall)
            if (showCount) {
                AnimatedContent(
                    targetState = count,
                    transitionSpec = { motion.crossfade() },
                    label = "threadCount",
                ) { c ->
                    Text(
                        Fmt.count(c),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
