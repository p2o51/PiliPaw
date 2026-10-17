package com.gpo.pilipala.feature.dynamics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toPath
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.graphics.shapes.Morph
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.LocalBottomBarInset
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

private val FILTERS = listOf("全部" to "all", "投稿" to "video", "番剧" to "pgc", "专栏" to "article")

internal class DynamicsViewModel : ViewModel() {
    var type by mutableStateOf("all")
        private set
    var host by mutableStateOf<UpUser?>(null)
        private set
    var portal by mutableStateOf<Portal?>(null)
        private set
    private var baseline = ""
    private var loadedFor: Long = -1

    val pager = Pager<DynItem>(viewModelScope, dedupKey = { it.key }) { cursor ->
        val (page, base) = DynamicsApi.feed(type, cursor, baseline, host?.mid)
        if (cursor == null) baseline = base
        page
    }

    /** Called whenever the signed-in account changes. */
    fun onAccount(mid: Long) {
        if (mid <= 0 || mid == loadedFor) return
        loadedFor = mid
        host = null
        type = "all"
        refreshAll()
    }

    fun refreshAll() {
        pager.refresh()
        viewModelScope.launch { runCatching { DynamicsApi.portal() }.onSuccess { portal = it } }
    }

    fun selectType(t: String) {
        if (t == type) return
        type = t
        pager.update { emptyList() }
        pager.refresh()
    }

    /** Tapping the selected UP again clears the filter. */
    fun selectUp(up: UpUser?) {
        host = if (up == null || up.mid == host?.mid) null else up
        type = "all"
        if (up != null) {
            portal = portal?.let { p -> p.copy(ups = p.ups.map { if (it.mid == up.mid) it.copy(hasUpdate = false) else it }) }
        }
        pager.update { emptyList() }
        pager.refresh()
    }
}

/** Main tab: followed feed with the UP strip and type filter (Flutter pages/dynamics). */
@Composable
fun DynamicsScreen() {
    val user by Account.user.collectAsState()
    val loggedIn = user != null && BiliClient.isLoggedIn
    if (!loggedIn) {
        GuestState()
        return
    }
    val vm: DynamicsViewModel = viewModel(key = "dynamics_main") { DynamicsViewModel() }
    LaunchedEffect(user?.mid) { vm.onAccount(user?.mid ?: 0L) }
    DynamicsFeed(vm)
}

@Composable
private fun GuestState() {
    val nav = LocalNavigator.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PiliSymbols.Dynamic, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text("未登录", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Button(onClick = { nav.push(Route.Login) }, shapes = ButtonDefaults.shapes()) { Text("登录") }
    }
}

@Composable
private fun DynamicsFeed(vm: DynamicsViewModel) {
    val pager = vm.pager
    val wc = widthClass()
    val cols = if (wc == WidthClass.Expanded) 2 else 1
    val maxWidth = if (cols == 2) 1200.dp else 720.dp
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val side = max(16.dp, (screenW - maxWidth) / 2)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = LocalBottomBarInset.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val gridState = rememberLazyStaggeredGridState()
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()
    LoadMoreEffect(gridState) { pager.loadMore() }

    RefreshBox(
        isRefreshing = pager.isRefreshing && pager.items.isNotEmpty(),
        onRefresh = vm::refreshAll,
        modifier = Modifier.fillMaxSize(),
        indicatorPadding = PaddingValues(top = statusTop),
    ) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(cols),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = statusTop + 8.dp, bottom = bottom + 24.dp),
            verticalItemSpacing = 12.dp,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title", span = StaggeredGridItemSpan.FullLine) {
                Text(
                    "动态",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            val portal = vm.portal
            if (portal != null && (portal.ups.isNotEmpty() || portal.lives.isNotEmpty())) {
                item(key = "upsHeader", span = StaggeredGridItemSpan.FullLine) {
                    val nav = LocalNavigator.current
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "最新关注",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { nav.push(Route.Follow(portal.myMid.takeIf { it > 0 } ?: BiliClient.mid)) }) {
                            Text("查看全部")
                        }
                    }
                }
                item(key = "ups", span = StaggeredGridItemSpan.FullLine) {
                    UpStrip(
                        portal = portal,
                        selected = vm.host?.mid,
                        side = side,
                        onSelect = vm::selectUp,
                        modifier = Modifier.bleed(side),
                    )
                }
            }
            item(key = "filter", span = StaggeredGridItemSpan.FullLine) {
                AnimatedContent(
                    targetState = vm.host,
                    transitionSpec = { fadeIn(fadeInSpec).togetherWith(fadeOut(fadeOutSpec)) },
                    contentKey = { it?.mid },
                    label = "filterHeader",
                ) { host ->
                    if (host == null) {
                        TypeFilter(vm.type, vm::selectType)
                    } else {
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${host.name}的动态",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(onClick = { vm.selectUp(null) }, shapes = ButtonDefaults.shapes()) {
                                Icon(YoinSymbols.Close, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("全部")
                            }
                        }
                    }
                }
            }

            when {
                pager.items.isEmpty() && (pager.initialLoading || pager.isRefreshing) -> item(key = "loading", span = StaggeredGridItemSpan.FullLine) {
                    LoadingState(Modifier.height(360.dp))
                }
                pager.items.isEmpty() && pager.error != null -> item(key = "error", span = StaggeredGridItemSpan.FullLine) {
                    ErrorState(pager.error ?: "", Modifier.height(360.dp), onRetry = vm::refreshAll)
                }
                pager.items.isEmpty() -> item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    EmptyState("暂无动态", Modifier.height(360.dp), icon = PiliSymbols.Dynamic)
                }
            }

            items(pager.items, key = { it.key }, contentType = { it.kind }) { item ->
                DynamicCard(item)
            }

            if (pager.isLoadingMore) {
                item(key = "more", span = StaggeredGridItemSpan.FullLine) { LoadingFooter() }
            } else if (pager.error != null && pager.items.isNotEmpty()) {
                item(key = "retry", span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = pager::loadMore) { Text("重试") }
                    }
                }
            }
        }

        // Status-bar scrim: cards never scroll bare under the clock. It fades in
        // once the feed leaves the top; alpha is read in the layer only.
        val scrolled by remember(gridState) {
            derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
        }
        val scrimAlpha = animateFloatAsState(if (scrolled) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "statusScrim")
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .graphicsLayer { alpha = scrimAlpha.value }
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}

/** Connected toggle group: 全部 / 投稿 / 番剧 / 专栏. */
@Composable
private fun TypeFilter(selected: String, onSelect: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().widthIn(max = 480.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        FILTERS.forEachIndexed { i, (label, value) ->
            ToggleButton(
                checked = selected == value,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(value)
                },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    FILTERS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

// ── UP strip ────────────────────────────────────────────────────────────────

@Composable
private fun UpStrip(
    portal: Portal,
    selected: Long?,
    side: Dp,
    onSelect: (UpUser?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = side),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "all") {
            UpChip(
                name = "全部",
                face = null,
                selected = selected == null,
                dimmed = false,
                hasUpdate = false,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(null)
                },
                onLongClick = null,
            )
        }
        if (portal.myMid > 0) {
            item(key = "me") {
                val me = UpUser(portal.myMid, portal.myName.ifEmpty { "我" }, portal.myFace, false)
                UpChip(
                    name = "我",
                    face = portal.myFace,
                    selected = selected == portal.myMid,
                    dimmed = selected != null && selected != portal.myMid,
                    hasUpdate = false,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(me)
                    },
                    onLongClick = { nav.push(Route.Member(portal.myMid, portal.myFace, portal.myName)) },
                )
            }
        }
        items(portal.lives, key = { "live_${it.mid}" }) { live ->
            LiveChip(
                live = live,
                dimmed = selected != null,
                onClick = { nav.push(Route.LiveRoom(live.roomId, title = live.title)) },
                onLongClick = { nav.push(Route.Member(live.mid, live.face, live.name)) },
            )
        }
        items(portal.ups, key = { "up_${it.mid}" }) { up ->
            UpChip(
                name = up.name,
                face = up.face,
                selected = selected == up.mid,
                dimmed = selected != null && selected != up.mid,
                hasUpdate = up.hasUpdate,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(up)
                },
                onLongClick = { nav.push(Route.Member(up.mid, up.face, up.name)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpChip(
    name: String,
    face: String?,
    selected: Boolean,
    dimmed: Boolean,
    hasUpdate: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val cookie = remember(morph) { MorphShape(morph, 1f) }
    val progress = animateFloatAsState(if (selected) 1f else 0f, YoinMotion.defaultSpatialSpec(), label = "upMorph")
    val alpha = animateFloatAsState(if (dimmed) 0.45f else 1f, YoinMotion.defaultEffectsSpec(), label = "upAlpha")
    // Animated values are read in layer blocks only: the chip never recomposes per
    // frame. At rest an unselected avatar clips to a plain circle and the selected
    // one to a cached cookie outline; a fresh morph path exists only mid-animation.
    val avatarClip = Modifier.graphicsLayer {
        val p = progress.value
        shape = when (p) {
            0f -> CircleShape
            1f -> cookie
            else -> MorphShape(morph, p)
        }
        clip = true
    }
    Column(
        Modifier
            .width(60.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(52.dp)) {
            if (face.isNullOrEmpty()) {
                Box(
                    Modifier.matchParentSize().then(avatarClip).background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PiliSymbols.Dynamic,
                        null,
                        Modifier.size(24.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else {
                NetImage(face, Modifier.matchParentSize().then(avatarClip), shape = RectangleShape, thumbWidth = 104.dp)
            }
            if (hasUpdate) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 1.dp, y = 1.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveChip(live: LiveUser, dimmed: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val alpha = animateFloatAsState(if (dimmed) 0.45f else 1f, YoinMotion.defaultEffectsSpec(), label = "liveAlpha")
    Column(
        Modifier
            .width(60.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            // Live ring as two stacked color blocks (no stroke).
            Box(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp),
            ) {
                Avatar(live.face, 44.dp)
            }
            Text(
                "直播",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            live.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Circle ⇄ cookie morph for the selected UP avatar. */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress)
        val m = Matrix()
        m.scale(size.width, size.height)
        path.transform(m)
        return Outline.Generic(path)
    }
}

/** Lets a full-line item paint into the grid's side padding (full-bleed rows). */
internal fun Modifier.bleed(side: Dp): Modifier = layout { measurable, constraints ->
    val px = side.roundToPx()
    val w = constraints.maxWidth + px * 2
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}
