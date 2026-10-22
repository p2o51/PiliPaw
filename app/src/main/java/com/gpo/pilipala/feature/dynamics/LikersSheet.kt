package com.gpo.pilipala.feature.dynamics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

/**
 * Opens [onLongPress] on a long press without letting the press through: once the long-press
 * timeout passes, the rest of the gesture is consumed in the Initial pass, so the clickable
 * under it (the like toggle) sees a cancelled press and never fires. A short tap passes
 * through untouched. Pass a remembered [onLongPress] (it keys the gesture). Also offered to accessibility services as the long-click action.
 */
internal fun Modifier.longPressAside(label: String, onLongPress: () -> Unit): Modifier =
    semantics {
        onLongClick(label) {
            onLongPress()
            true
        }
    }.pointerInput(onLongPress) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            // Ends early (true) when the finger lifts, drifts past the slop or someone else takes it.
            val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var ended = false
                while (!ended) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    ended = change == null ||
                        !change.pressed ||
                        change.isConsumed ||
                        (change.position - down.position).getDistance() > slop
                }
                true
            }
            if (endedEarly == null) {
                onLongPress()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }

/**
 * The 点赞 list of one dynamic ([dynId]), an edge-to-edge sheet: bold total over a quiet
 * 人赞了, then the people (avatar, name, 已关注 / 转发了 tags); a row opens their space.
 * Under 名声模式 the total follows the same scope as the card ([ownerMid] decides whether
 * it is the user's own); the list itself always shows. [fallbackTotal]: the card's like
 * count, used while the server's total is unknown or 0.
 */
@Composable
internal fun LikersSheet(
    dynId: String,
    ownerMid: Long,
    fallbackTotal: Long,
    onDismiss: () -> Unit,
) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var total by remember(dynId) { mutableLongStateOf(0L) }
    val pager = remember(dynId) {
        Pager<Reactor>(scope, dedupKey = { it.mid to it.action }) { cursor ->
            val result = DynamicsApi.reactions(dynId, cursor)
            if (cursor == null || result.total > 0) total = result.total
            result.page
        }.also { it.refresh() }
    }
    val listState = rememberLazyListState()
    LoadMoreEffect(listState) { pager.loadMore() }
    val shown = if (total > 0) total else fallbackTotal
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (shown > 0) {
                    FameCount(
                        Fmt.count(shown),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        self = FameMode.isSelf(ownerMid),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "人赞了",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                } else {
                    Text("点赞", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            val phase = when {
                pager.items.isEmpty() && (pager.initialLoading || pager.isRefreshing) -> 0
                pager.items.isEmpty() && pager.error != null -> 1
                pager.items.isEmpty() -> 2
                else -> 3
            }
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(fadeInSpec).togetherWith(fadeOut(fadeOutSpec)) },
                label = "likers",
            ) { p ->
                when (p) {
                    0 -> LoadingState(Modifier.height(240.dp))
                    1 -> ErrorState(pager.error.orEmpty(), Modifier.height(280.dp), onRetry = pager::refresh)
                    2 -> Box(Modifier.padding(bottom = sheetBottomPadding(0.dp))) {
                        EmptyState("暂无点赞", Modifier.height(240.dp), icon = PiliSymbols.ThumbUp)
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = sheetBottomPadding(16.dp),
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val last = pager.items.lastIndex
                        itemsIndexed(pager.items, key = { _, r -> "${r.mid}_${r.action}" }) { index, r ->
                            ReactorRow(r, index == 0, index == last) {
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                                nav.push(Route.Member(r.mid, r.face, r.name))
                            }
                        }
                        if (pager.isLoadingMore) {
                            item(key = "more") { LoadingFooter() }
                        } else if (pager.error != null) {
                            item(key = "retry") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    TextButton(onClick = pager::loadMore) { Text("重试") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One person: avatar, name, and the quiet tags (已关注, an action other than 赞了). */
@Composable
private fun ReactorRow(r: Reactor, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val big = 20.dp
    val small = 6.dp
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = if (first) big else small,
        topEnd = if (first) big else small,
        bottomStart = if (last) big else small,
        bottomEnd = if (last) big else small,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(r.face, 40.dp)
        Text(
            r.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (r.following) {
            Tag("已关注")
        }
        val action = r.action.trim()
        if (action.isNotEmpty() && action != "赞了") {
            Tag(
                action,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(YoinSymbols.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/**
 * Detail page entry: a filled block with 点赞, the count (名声模式 line when hidden) and a
 * chevron; opens [LikersSheet].
 */
@Composable
internal fun LikersEntry(count: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val click by rememberUpdatedState(onClick)
    Row(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = "点赞列表") { click() }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(PiliSymbols.ThumbUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("点赞", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (count > 0) {
            FameCount(
                Fmt.count(count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                revealable = false,
            )
        }
        Icon(YoinSymbols.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
    }
}
