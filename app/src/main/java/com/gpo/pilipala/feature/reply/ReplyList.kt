package com.gpo.pilipala.feature.reply

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

private const val KEY_BAR = "reply:bar"
private const val KEY_COMPOSE = "reply:compose"
private const val KEY_STATE = "reply:state"
private const val KEY_FOOTER = "reply:footer"

/** The previous order while the new one loads after a 热门 / 最新 switch. */
private const val StaleAlpha = 0.38f

/**
 * Comment list for any subject. CONTRACT — signature is fixed.
 * type: 1 = video, 11 = dynamic (images), 17 = dynamic (text/forward), 12 = article, 14 = audio.
 * [header] lets the host put its own content above the comments in the same
 * LazyColumn (dynamic detail). [onTimestampClick] receives seconds when a
 * "03:21" link in a video comment is tapped.
 */
@Composable
fun ReplyList(
    oid: Long,
    type: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    header: (LazyListScope.() -> Unit)? = null,
    onTimestampClick: ((seconds: Long) -> Unit)? = null,
) {
    val vm: ReplyListViewModel = viewModel(key = "reply_list_${oid}_$type") { ReplyListViewModel(oid, type) }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val ui = rememberReplyUiState()
    val listState = rememberLazyListState()
    val pager = vm.pager
    val me by Account.user.collectAsState()
    val motion = replyMotion()
    LoadMoreEffect(listState) { pager.loadMore() }

    val actions = rememberReplyActions(
        vm = vm,
        ui = ui,
        onReply = { r -> ComposeTarget(root = r.rpid, parent = r.rpid, replyTo = r.member.name) },
        onOpenThread = { r -> nav.push(Route.ReplyDetail(r.oid.takeIf { it != 0L } ?: oid, type, r.rpid)) },
        onTimestampClick = onTimestampClick,
    )
    val openComposer = { if (requireLogin(nav, toast)) ui.composer = ComposeTarget() }
    val pillVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == KEY_COMPOSE } }
    }
    val hasContent = pager.items.isNotEmpty() || vm.top.isNotEmpty()
    // Rows: entrances are ours (Entrance), so no item fade-in; placement springs stand down while a row resizes.
    val placement = remember(ui.layout) { ui.layout.gated(motion.offset) }

    // 热门 / 最新: the old order dims while the new one loads, then leaves as the new rows rise in.
    val stale = remember { Animatable(1f) }
    val lastRequest = remember { intArrayOf(vm.requestedGen) }
    val staleSpec = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(vm.requestedGen) {
        val request = vm.requestedGen
        if (request == lastRequest[0]) return@LaunchedEffect
        val fulfilled = vm.listGen == lastRequest[0]
        lastRequest[0] = request
        if (fulfilled) stale.snapTo(1f)
        stale.animateTo(StaleAlpha, staleSpec)
    }

    // A comment just sent sits at the top: bring it into view when the list is scrolled away.
    LaunchedEffect(vm, listState) {
        vm.sentEvents.collect { target ->
            if (target.root != 0L) return@collect
            repeat(2) { withFrameNanos { } }
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.any { it.key == KEY_COMPOSE }) return@collect
            val composeIndex = info.totalItemsCount - 1 - pager.items.size - vm.top.size - 1
            if (composeIndex >= 0) listState.animateScrollToItem(composeIndex)
        }
    }

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
                items(vm.top, key = { "reply:t${it.rpid}" }, contentType = { "reply" }) { r ->
                    ReplyRow(
                        r,
                        actions,
                        Modifier
                            .animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = motion.fade)
                            .readingWidth(),
                    ) { actions.onOpenThread?.invoke(r) }
                }
                if (pager.items.isEmpty()) {
                    item(key = KEY_STATE, contentType = "reply:state") {
                        ListState(
                            pager = pager,
                            hasTop = vm.top.isNotEmpty(),
                            onRetry = vm::refresh,
                            modifier = Modifier.animateItem().readingWidth(),
                        )
                    }
                } else {
                    // Keyed by generation: a sort switch swaps every row (old ones fade, new ones rise).
                    val gen = vm.listGen
                    items(pager.items, key = { "reply:$gen:${it.rpid}" }, contentType = { "reply" }) { r ->
                        val rowGen = remember { gen }
                        ReplyRow(
                            r,
                            actions,
                            Modifier
                                .animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = motion.fade)
                                .graphicsLayer { alpha = if (rowGen != vm.requestedGen) stale.value else 1f }
                                .readingWidth(),
                        ) { actions.onOpenThread?.invoke(r) }
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
            enter = androidx.compose.animation.scaleIn(YoinMotion.fastSpatialSpec()) +
                androidx.compose.animation.fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = androidx.compose.animation.scaleOut(YoinMotion.fastSpatialSpec()) +
                androidx.compose.animation.fadeOut(YoinMotion.defaultEffectsSpec()),
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
    ReplyOverlays(ui, vm)
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
            Icon(PiliSymbols.Comment, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    style = MaterialTheme.typography.labelLarge,
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
