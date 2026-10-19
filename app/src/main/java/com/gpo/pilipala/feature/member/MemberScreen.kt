package com.gpo.pilipala.feature.member

import androidx.compose.runtime.saveable.rememberSaveable
import com.gpo.pilipala.feature.feeds.FeedSourceSheet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.dynamics.MemberDynamicsList
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.BackButton
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.ExpressivePageBackground
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.FameSelf
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.VideoCardV
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.seam.seamGraphic
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import com.gpo.pilipala.ui.components.inlineIconSize
import com.gpo.pilipala.ui.components.centered

// ── ViewModel ───────────────────────────────────────────────────────────────

internal class MemberViewModel(val mid: Long, val initFace: String, val initName: String) : ViewModel() {
    var profile by mutableStateOf<MemberProfile?>(null)
        private set
    var profileError by mutableStateOf<String?>(null)
        private set
    var relation by mutableStateOf(Relation())
        private set
    var relationBusy by mutableStateOf(false)
        private set

    val isOwner: Boolean get() = BiliClient.isLoggedIn && BiliClient.mid == mid

    var order by mutableStateOf(ArchiveOrder.Pubdate)
        private set
    var archiveTotal by mutableLongStateOf(-1L)
        private set
    val archive = Pager<VideoItem>(viewModelScope, dedupKey = { it.key() }) { cursor ->
        val r = MemberRepo.archive(mid, cursor, order)
        if (cursor == null) archiveTotal = r.total
        r.page
    }
    val search = ArchiveSearch(viewModelScope, mid) { order }
    val collections = Pager<MemberCollection>(viewModelScope, dedupKey = { "${it.category}-${it.id}" }) { cursor ->
        MemberRepo.collections(mid, cursor?.toIntOrNull() ?: 1)
    }
    var coins by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var likes by mutableStateOf<List<VideoItem>>(emptyList())
        private set

    init {
        loadProfile()
        archive.refresh()
        collections.refresh()
        viewModelScope.launch { runCatching { MemberRepo.coinVideos(mid) }.onSuccess { coins = it } }
        viewModelScope.launch { runCatching { MemberRepo.likeVideos(mid) }.onSuccess { likes = it } }
    }

    fun loadProfile() {
        profileError = null
        viewModelScope.launch {
            runCatching { MemberRepo.profile(mid) }
                .onSuccess {
                    profile = it
                    // The card already knows the follow state; use it until /x/relation answers.
                    if (relation.attribute == -1 && it.isFollowedCard && !isOwner) relation = Relation(attribute = 2)
                }
                .onFailure { profileError = it.friendlyMessage() }
        }
        refreshRelation()
    }

    private fun refreshRelation() {
        viewModelScope.launch { runCatching { MemberRepo.relation(mid) }.onSuccess { relation = it } }
    }

    fun changeOrder(o: ArchiveOrder) {
        if (o == order) return
        order = o
        archive.update { emptyList() }
        archive.refresh()
        search.onOrderChanged()
    }

    /** act: 1 follow, 2 unfollow, 5 block, 6 unblock. */
    fun modify(act: Int, onResult: (String) -> Unit) {
        if (relationBusy) return
        relationBusy = true
        viewModelScope.launch {
            runCatching { MemberRepo.modifyRelation(mid, act) }
                .onSuccess {
                    relation = relation.copy(
                        attribute = when (act) {
                            1 -> 2
                            5 -> 128
                            else -> 0
                        },
                    )
                    onResult(
                        when (act) {
                            1 -> "已关注"
                            2 -> "已取消关注"
                            5 -> "已拉黑"
                            else -> "已移出黑名单"
                        },
                    )
                    refreshRelation()
                }
                .onFailure { onResult(it.friendlyMessage()) }
            relationBusy = false
        }
    }
}

// ── Collapsing header plumbing ──────────────────────────────────────────────

/** exitUntilCollapsed for a header above a pager of lists. */
@Stable
private class CollapsingState {
    var offset by mutableFloatStateOf(0f)
    /** Written during measure; read by the scroll connection. Plain field on purpose. */
    var maxCollapse = 0f

    val fraction: Float get() = if (maxCollapse <= 0f) 0f else (-offset / maxCollapse).coerceIn(0f, 1f)

    /** The running header fling, cancelled when a new drag starts. Plain field on purpose. */
    var fling: Job? = null

    fun consume(delta: Float): Float {
        val old = offset
        offset = (offset + delta).coerceIn(-maxCollapse, 0f)
        return offset - old
    }

    /** Continues a header drag with the platform decay until the header stops or hits an end. */
    suspend fun flingBy(velocity: Float, decay: DecayAnimationSpec<Float>) {
        if (abs(velocity) < 1f) return
        var last = 0f
        AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(decay) {
            val delta = value - last
            last = value
            val used = consume(delta)
            if (abs(delta - used) > 0.5f) cancelAnimation()
        }
    }

    companion object {
        val Saver: androidx.compose.runtime.saveable.Saver<CollapsingState, Float> = androidx.compose.runtime.saveable.Saver(
            save = { it.offset },
            restore = { saved -> CollapsingState().also { it.offset = saved } },
        )
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0f) Offset(0f, consume(available.y)) else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0f) Offset(0f, consume(available.y)) else Offset.Zero
    }
}

@Composable
private fun CollapsingHeaderLayout(
    state: CollapsingState,
    pinnedTop: Dp,
    header: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(header, tabs, body),
        modifier = modifier.clipToBounds().nestedScroll(state.connection),
    ) { (headerM, tabsM, bodyM), constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val loose = Constraints(minWidth = w, maxWidth = w)
        val hp = headerM.first().measure(loose)
        val tp = tabsM.first().measure(loose)
        val pinned = pinnedTop.roundToPx()
        val bodyH = (h - pinned - tp.height).coerceAtLeast(0)
        val bp = bodyM.first().measure(Constraints.fixed(w, bodyH))
        val max = (hp.height - pinned).coerceAtLeast(0).toFloat()
        state.maxCollapse = max
        layout(w, h) {
            val off = state.offset.coerceIn(-max, 0f).roundToInt()
            hp.place(0, off)
            tp.place(0, off + hp.height)
            bp.place(0, off + hp.height + tp.height)
        }
    }
}

// ── Screen ──────────────────────────────────────────────────────────────────

private val Tabs = listOf("主页", "投稿", "动态", "合集")

@Composable
fun MemberScreen(route: Route.Member) {
    val vm = viewModel { MemberViewModel(route.mid, route.face, route.name) }
    CoverTheme(vm.profile?.face ?: vm.initFace) {
        // The user's own space: 粉丝 / 获赞 / 播放 and the counts on their uploads count as
        // their own for 名声模式 (cards that name another owner decide for themselves).
        FameSelf(vm.mid) {
            MemberContent(vm)
        }
    }
}

@Composable
private fun MemberContent(vm: MemberViewModel) {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val wide = widthClass() == WidthClass.Expanded
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barHeight = 64.dp
    val pinnedTop = statusTop + barHeight

    val pagerState = rememberPagerState(initialPage = 1) { Tabs.size }
    // Saved with the page: coming back from a pushed page keeps the header as collapsed as
    // the list under it is scrolled.
    val collapsing = rememberSaveable(saver = CollapsingState.Saver) { CollapsingState() }
    val decay = rememberSplineBasedDecay<Float>()
    // Wide mode: the left header column scrolls on its own; the overlay bar fills as it does.
    val wideScroll = rememberScrollState()
    val barFillDistance = with(LocalDensity.current) { 88.dp.toPx() }
    val barFill: () -> Float = {
        if (wide) (wideScroll.value / barFillDistance).coerceIn(0f, 1f) else collapsing.fraction
    }
    var showMenu by remember { mutableStateOf(false) }
    var feedSheet by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Int?>(null) }

    val profile = vm.profile
    val name = profile?.name ?: vm.initName
    val shareLine = "${name.ifEmpty { "UP主" }} - https://space.bilibili.com/${vm.mid}"

    val onFollow: () -> Unit = {
        when {
            !BiliClient.isLoggedIn -> {
                toast("账号未登录")
                nav.push(Route.Login)
            }
            vm.relation.blocked -> confirm = 6
            vm.relation.following -> confirm = 2
            else -> {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                vm.modify(1, toast)
            }
        }
    }
    val onMessage: () -> Unit = {
        if (!BiliClient.isLoggedIn) nav.push(Route.Login)
        else nav.push(Route.WhisperDetail(vm.mid, name, profile?.face ?: vm.initFace))
    }

    val header: @Composable () -> Unit = {
        ExpressivePageBackground(Modifier.fillMaxWidth()) {
            MemberHeader(
                vm = vm,
                topInset = pinnedTop,
                onFollow = onFollow,
                onMessage = onMessage,
                onCopyUid = {
                    scope.launch { clipboard.copyText("UID", vm.mid.toString()) }
                    toast("已复制")
                },
                onCopySign = { sign ->
                    scope.launch { clipboard.copyText("sign", sign) }
                    toast("已复制")
                },
            )
        }
    }
    val tabRow: @Composable () -> Unit = {
        PrimaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = {},
        ) {
            Tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        // The count sits on the title's baseline.
                        Row {
                            Text(title, maxLines = 1, modifier = Modifier.alignByBaseline())
                            val count = when (index) {
                                1 -> vm.archiveTotal.takeIf { it > 0 } ?: profile?.archiveCount ?: -1
                                else -> -1L
                            }
                            if (count > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    Fmt.count(count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.alignByBaseline(),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
    val listPadding = PaddingValues(top = 4.dp, bottom = navBottom + 16.dp)
    // Hoisted: survives the 投稿 page leaving the pager window and a search round trip.
    val archiveListState = rememberLazyGridState()
    val body: @Composable () -> Unit = {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { it }) { page ->
            when (page) {
                0 -> HomeTab(vm, listPadding, onArchive = { scope.launch { pagerState.animateScrollToPage(1) } })
                1 -> ArchiveListWithSearch(
                    search = vm.search,
                    archive = vm.archive,
                    order = vm.order,
                    total = vm.archiveTotal,
                    onOrder = vm::changeOrder,
                    listState = archiveListState,
                    contentPadding = listPadding,
                    modifier = Modifier.fillMaxSize(),
                    refreshable = false,
                )
                2 -> MemberDynamicsList(vm.mid, Modifier.fillMaxSize(), contentPadding = listPadding)
                else -> CollectionsTab(vm, listPadding)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (profile == null && vm.profileError != null) {
            ErrorState(
                vm.profileError.orEmpty(),
                Modifier.padding(top = pinnedTop),
                onRetry = vm::loadProfile,
            )
        } else if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                        // The header column runs up under the overlay bar: it sinks under the
                        // tide line there instead of showing through the bar.
                        .seamViewport(wideScroll, topChrome = pinnedTop)
                        .verticalScroll(wideScroll)
                        .padding(bottom = navBottom + 16.dp),
                ) { header() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = pinnedTop),
                ) {
                    tabRow()
                    Box(Modifier.weight(1f)) { body() }
                }
            }
        } else {
            CollapsingHeaderLayout(
                state = collapsing,
                pinnedTop = pinnedTop,
                header = {
                    Box(
                        Modifier.draggable(
                            state = rememberDraggableState { collapsing.consume(it) },
                            orientation = Orientation.Vertical,
                            onDragStarted = { collapsing.fling?.cancel() },
                            onDragStopped = { velocity ->
                                collapsing.fling = coroutineContext[Job]
                                collapsing.flingBy(velocity, decay)
                            },
                        ),
                    ) { header() }
                },
                tabs = tabRow,
                body = body,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Overlay bar: transparent over the backdrop, fills with surface as the header collapses
        // (or, in wide mode, as the header column scrolls under it).
        val surface = MaterialTheme.colorScheme.surface
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(surface.copy(alpha = barFill())) }
                .padding(top = statusTop)
                .height(barHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton()
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = barFill() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(profile?.face ?: vm.initFace, 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { feedSheet = true }, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.LibraryAdd, contentDescription = "添加到 Feed")
            }
            IconButton(
                onClick = { shareText(context, shareLine, name) },
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(YoinSymbols.Share, contentDescription = "分享") }
            IconButton(onClick = { showMenu = true }, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
            }
        }
    }

    if (feedSheet) {
        FeedSourceSheet(
            upMid = vm.mid,
            upName = name,
            upFace = profile?.face ?: vm.initFace,
            onDismiss = { feedSheet = false },
        )
    }

    if (showMenu) {
        MemberMenuSheet(
            vm = vm,
            onDismiss = { showMenu = false },
            onShare = { shareText(context, shareLine, name) },
            onCopyLink = {
                scope.launch { clipboard.copyText("link", "https://space.bilibili.com/${vm.mid}") }
                toast("已复制")
            },
            onBlock = {
                if (!BiliClient.isLoggedIn) {
                    toast("账号未登录")
                    nav.push(Route.Login)
                } else {
                    confirm = if (vm.relation.blocked) 6 else 5
                }
            },
        )
    }

    confirm?.let { act ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    when (act) {
                        2 -> "取消关注"
                        5 -> "加入黑名单"
                        else -> "移出黑名单"
                    },
                )
            },
            text = { Text(name, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    haptics.performHapticFeedback(
                        if (act == 2 || act == 5) HapticFeedbackType.ToggleOff else HapticFeedbackType.Confirm,
                    )
                    vm.modify(act, toast)
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

// ── Header ──────────────────────────────────────────────────────────────────

/** The banner's alpha: whole at the top, gone at the bottom, so it dissolves into the wash. */
private val BannerFade = Brush.verticalGradient(0.35f to Color.Black, 1f to Color.Transparent)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberHeader(
    vm: MemberViewModel,
    topInset: Dp,
    onFollow: () -> Unit,
    onMessage: () -> Unit,
    onCopyUid: () -> Unit,
    onCopySign: (String) -> Unit,
) {
    val nav = LocalNavigator.current
    val profile = vm.profile
    val scheme = MaterialTheme.colorScheme
    val bannerHeight = topInset + 88.dp
    val avatarSize = 96.dp
    val cookie = MaterialShapes.Cookie9Sided.toShape()

    Box(Modifier.fillMaxWidth()) {
        // Soft backdrop: the space banner dissolving into the page wash.
        Box(Modifier.fillMaxWidth().height(bannerHeight + avatarSize / 2)) {
            AnimatedVisibility(
                visible = !profile?.banner.isNullOrEmpty(),
                enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                exit = fadeOut(YoinMotion.defaultEffectsSpec()),
                modifier = Modifier.matchParentSize(),
            ) {
                NetImage(
                    profile?.banner,
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(BannerFade, blendMode = BlendMode.DstIn)
                        },
                    shape = RectangleShape,
                    thumbWidth = 960.dp,
                )
            }
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to scheme.surface.copy(alpha = 0.6f),
                            0.4f to scheme.surface.copy(alpha = 0.15f),
                            1f to scheme.surface.copy(alpha = 0f),
                        ),
                    ),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = bannerHeight - avatarSize / 2)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                val live = profile?.takeIf { it.liveOn && it.liveRoomId > 0 }
                val openLive: () -> Unit = {
                    live?.let { nav.push(Route.LiveRoom(it.liveRoomId, it.liveCover, it.liveTitle)) }
                }
                Box {
                    // While live, the whole 96dp avatar opens the room too: the badge alone is
                    // an ~18dp-tall target.
                    NetImage(
                        profile?.face ?: vm.initFace,
                        Modifier
                            .size(avatarSize)
                            .then(if (live != null) Modifier.clip(cookie).clickable(onClick = openLive) else Modifier),
                        shape = cookie,
                        thumbWidth = avatarSize * 2,
                    )
                    if (live != null) {
                        LiveBadge(Modifier.align(Alignment.BottomCenter), onClick = openLive)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!vm.isOwner) {
                    RelationButtons(vm, onFollow, onMessage)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile?.name ?: vm.initName,
                    style = MaterialTheme.typography.headlineSmall.centered(),
                    fontWeight = FontWeight.SemiBold,
                    color = if (profile?.vip == true) scheme.primary else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (profile != null && profile.level > 0) {
                    Spacer(Modifier.width(8.dp))
                    Tag(
                        "LV${profile.level}",
                        container = if (profile.level >= 6) scheme.primaryContainer else scheme.secondaryContainer,
                        content = if (profile.level >= 6) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
                    )
                }
                if (profile?.isSenior == true) {
                    Spacer(Modifier.width(4.dp))
                    Tag("硬核", container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
                }
                if (profile?.vip == true && profile.vipLabel.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Tag(profile.vipLabel, container = scheme.primary, content = scheme.onPrimary)
                }
            }
            Text(
                "UID ${vm.mid}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = {}, onLongClick = onCopyUid),
            )

            if (profile != null && profile.officialLabel.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Icon centred on the label's (first) line; label and title on one baseline.
                    val officialStyle = MaterialTheme.typography.labelLarge
                    val iconSize = officialStyle.inlineIconSize()
                    val lineHeight = with(LocalDensity.current) { officialStyle.lineHeight.toDp() }
                    Icon(
                        YoinSymbols.SparkleFilled,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.padding(top = ((lineHeight - iconSize) / 2).coerceAtLeast(0.dp)).size(iconSize),
                    )
                    Text(
                        profile.officialLabel,
                        style = officialStyle,
                        color = scheme.primary,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        profile.officialTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }

            if (!profile?.sign.isNullOrEmpty()) {
                ExpandableSign(profile!!.sign, onLongClick = { onCopySign(profile.sign) })
            }

            Row(
                Modifier.padding(top = 14.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val displayName = profile?.name ?: vm.initName
                StatBlock(profile?.following ?: -1, "关注") { nav.push(Route.Follow(vm.mid, displayName)) }
                StatBlock(profile?.fans ?: -1, "粉丝", fame = true) { nav.push(Route.Fans(vm.mid, displayName)) }
                StatBlock(profile?.likes ?: -1, "获赞", fame = true, onClick = null)
                if ((profile?.views ?: -1) > 0) StatBlock(profile!!.views, "播放", fame = true, onClick = null)
            }
        }
    }
}

@Composable
private fun RelationButtons(vm: MemberViewModel, onFollow: () -> Unit, onMessage: () -> Unit) {
    val relation = vm.relation
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        ToggleButton(
            checked = relation.following || relation.blocked,
            onCheckedChange = { onFollow() },
            enabled = !vm.relationBusy,
        ) {
            val fx = YoinMotion.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = relation,
                transitionSpec = { fadeIn(fx) togetherWith fadeOut(fx) },
                label = "relation",
            ) { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            r.blocked -> BlockGlyph
                            r.following -> PiliSymbols.PersonCheck
                            else -> PiliSymbols.PersonAdd
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(r.label + if (r.special && r.following) " 特别关注" else "", maxLines = 1)
                }
            }
        }
        FilledTonalIconButton(onClick = onMessage, shapes = IconButtonDefaults.shapes()) {
            Icon(PiliSymbols.Mail, contentDescription = "发消息")
        }
    }
}

@Composable
private fun LiveBadge(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall.centered()
        Icon(PiliSymbols.Live, null, Modifier.size(style.inlineIconSize()), tint = MaterialTheme.colorScheme.onPrimary)
        Text("直播中", style = style, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpandableSign(sign: String, onLongClick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }
    Text(
        sign,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        modifier = Modifier
            .padding(top = 10.dp)
            .animateContentSize(YoinMotion.defaultSpatialSpec())
            .clip(YoinContainerShapes.ListRow)
            .combinedClickable(
                onClick = { if (overflowing || expanded) expanded = !expanded },
                onLongClick = onLongClick,
            ),
    )
}

/**
 * Number over label. [fame]: a popularity count (粉丝 / 获赞 / 播放), a line under 名声模式.
 * A block that opens a list ([onClick]) keeps the line a line, so the whole block opens it;
 * a block with no tap of its own (获赞, 播放) shows the number when the line is tapped.
 */
@Composable
private fun StatBlock(value: Long, label: String, fame: Boolean = false, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .clip(YoinContainerShapes.ListRow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        val fx = YoinMotion.defaultEffectsSpec<Float>()
        // Held outside the cross-fade: a shown number stays shown when the count moves.
        val revealed = remember { mutableStateOf(false) }
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(fx) togetherWith fadeOut(fx) },
            label = "stat",
        ) { v ->
            FameCount(
                if (v >= 0) Fmt.count(v) else "-",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                revealable = onClick == null,
                hide = fame && v >= 0,
                revealState = revealed,
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── More sheet ──────────────────────────────────────────────────────────────

@Composable
private fun MemberMenuSheet(
    vm: MemberViewModel,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onBlock: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetRow(YoinSymbols.Share, if (vm.isOwner) "分享我的主页" else "分享UP主") { close(onShare) }
            SheetRow(PiliSymbols.Link, "复制链接") { close(onCopyLink) }
            if (!vm.isOwner) {
                SheetRow(BlockGlyph, if (vm.relation.blocked) "移出黑名单" else "加入黑名单") { close(onBlock) }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .clip(YoinContainerShapes.ListRow)
            .clickable(onClick = onClick),
    )
}

// ── Tabs ────────────────────────────────────────────────────────────────────

private data class EntryTile(val label: String, val icon: ImageVector, val count: Long, val onClick: () -> Unit)

@Composable
private fun HomeTab(vm: MemberViewModel, contentPadding: PaddingValues, onArchive: () -> Unit) {
    val nav = LocalNavigator.current
    val profile = vm.profile
    val name = profile?.name ?: vm.initName
    val tiles = listOf(
        EntryTile("投稿", PiliSymbols.Video, vm.archiveTotal.takeIf { it >= 0 } ?: profile?.archiveCount ?: -1, onArchive),
        EntryTile("动态", PiliSymbols.Dynamic, -1) { nav.push(Route.MemberDynamics(vm.mid, name)) },
        EntryTile("收藏", YoinSymbols.Star, -1) { nav.push(Route.Fav(vm.mid)) },
        EntryTile("专栏", PiliSymbols.Article, profile?.articleCount ?: -1) { nav.push(Route.MemberArticle(vm.mid)) },
        EntryTile("投币", PiliSymbols.Coin, -1) { nav.push(Route.MemberCoin(vm.mid)) },
        EntryTile("点赞", PiliSymbols.ThumbUp, -1) { nav.push(Route.MemberLike(vm.mid)) },
    )
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        state = gridState,
        // Under the pinned tab row: tiles and rows sink under the tide line.
        modifier = Modifier.fillMaxSize().seamViewport(gridState),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tiles.size, key = { "tile-${tiles[it].label}" }) { i ->
            val tile = tiles[i]
            val (bg, fg) = palette[i % palette.size]
            Surface(
                onClick = tile.onClick,
                modifier = Modifier.seamGraphic(),
                shape = YoinContainerShapes.Card,
                color = bg,
                contentColor = fg,
            ) {
                Column(Modifier.padding(14.dp).seamText()) {
                    Icon(tile.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(tile.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (tile.count >= 0) Fmt.count(tile.count) else " ",
                        style = MaterialTheme.typography.labelMedium,
                        color = fg.copy(alpha = 0.72f),
                    )
                }
            }
        }
        if (vm.coins.isNotEmpty()) {
            recentSection("coins", "最近投币", vm.coins) { nav.push(Route.MemberCoin(vm.mid)) }
        }
        if (vm.likes.isNotEmpty()) {
            recentSection("likes", "最近点赞", vm.likes) { nav.push(Route.MemberLike(vm.mid)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.recentSection(
    key: String,
    title: String,
    items: List<VideoItem>,
    onMore: () -> Unit,
) {
    fullSpan("$key-head") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(YoinContainerShapes.ListRow)
                .clickable(onClick = onMore)
                .padding(vertical = 6.dp)
                .seamText(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(YoinSymbols.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    fullSpan("$key-row") {
        // Bleed to the page edges; the row's own padding matches the 16dp margin.
        Box(Modifier.layoutBleed(16.dp)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.key() }) { item ->
                    VideoCardV(item, Modifier.width(184.dp))
                }
            }
        }
    }
}

/** Expands a child horizontally by [bleed] on both sides (cancels the parent's padding). */
private fun Modifier.layoutBleed(bleed: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = (bleed * 2).roundToPx()
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = constraints.minWidth + extra,
                maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
            ),
        )
        layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed.roundToPx(), 0) }
    },
)

@Composable
private fun CollectionsTab(vm: MemberViewModel, contentPadding: PaddingValues) {
    val pager = vm.collections
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, threshold = 2) { pager.loadMore() }
    when {
        pager.items.isEmpty() && (pager.initialLoading || pager.isRefreshing) -> LoadingState()
        pager.items.isEmpty() && pager.error != null -> ErrorState(pager.error.orEmpty(), onRetry = pager::refresh)
        pager.items.isEmpty() -> EmptyState("暂无合集")
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().seamViewport(listState),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pager.items, key = { "${it.category}-${it.id}" }) { c -> CollectionRow(vm.mid, c) }
            if (pager.isLoadingMore) item("loading") { LoadingFooter() }
        }
    }
}

@Composable
private fun CollectionRow(mid: Long, c: MemberCollection) {
    val nav = LocalNavigator.current
    val scheme = MaterialTheme.colorScheme
    val open = { nav.push(Route.MemberSeasons(mid, c.id, c.category, c.title)) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(YoinContainerShapes.ListRow)
                .clickable(onClick = open)
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .seamText(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tag(
                if (c.category == 0) "合集" else "系列",
                container = if (c.category == 0) scheme.primaryContainer else scheme.tertiaryContainer,
                content = if (c.category == 0) scheme.onPrimaryContainer else scheme.onTertiaryContainer,
            )
            Text(
                c.title,
                style = MaterialTheme.typography.titleMedium.centered(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                c.total.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Icon(YoinSymbols.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
        if (c.archives.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(c.archives, key = { it.key() }) { item ->
                    VideoCardV(item, Modifier.width(184.dp))
                }
            }
        }
    }
}
