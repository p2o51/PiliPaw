package com.gpo.pilipala.feature.video.portrait

import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.theme.LightBarIcons
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import com.gpo.pilipala.feature.player.rememberPlayerSheetProperties
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.style.TextOverflow
import com.gpo.pilipala.feature.video.StaffRow
import com.gpo.pilipala.feature.video.TagChips
import com.gpo.pilipala.feature.video.UpCard
import com.gpo.pilipala.feature.video.VideoHeader
import com.gpo.pilipala.ui.components.ErrorState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.player.BiliPlayerState
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayerSheet
import com.gpo.pilipala.feature.player.SheetPage
import com.gpo.pilipala.feature.reply.ReplyList
import com.gpo.pilipala.feature.video.VideoDetailViewModel
import com.gpo.pilipala.feature.video.findActivity
import com.gpo.pilipala.navigation.transition.LocalPageSettled
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.theme.ExpressiveColorSchemeFactory
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols

/**
 * Douyin-style immersive layout for a portrait video (VideoDetailScreen picks it once the
 * detail says the picture is taller than wide, or when 全屏 is tapped on one). A vertical pager
 * over the opened video and its related videos: swiping up / down plays the next / previous one
 * in place (the page's target, intro and relation follow); back leaves the page, or returns to
 * the classic layout when the pager was opened from it; 缩小 goes to the classic layout.
 *
 * Window shapes ([PortraitMode]): a phone held upright gets the full-bleed picture; a phone
 * held sideways the picture at full height, centered, with the actions beside it; a tablet
 * upright the phone-like column; a landscape window with room (tablet landscape) the column,
 * its actions and a side panel with the video's info and comments, following the pager.
 *
 * [player] is the page's one player (moved here from the classic slot, never reloaded): the
 * settled page puts it in its picture.
 *
 * The coin / fav / triple sheets and the unfollow confirmation stay on the video page, reached
 * through [onCoin] / [onFav] / [onTriple] / [onFollow] (already login-gated there).
 */
@Composable
internal fun PortraitVideoLayout(
    vm: VideoDetailViewModel,
    playerState: BiliPlayerState,
    player: @Composable (Modifier) -> Unit,
    gate: (() -> Unit) -> Unit,
    onBack: () -> Unit,
    onCollapse: () -> Unit,
    onCoin: () -> Unit,
    onFav: () -> Unit,
    onTriple: () -> Unit,
    onFollow: () -> Unit,
) {
    val ctrl = playerState as? PiliPlayerController
    val model: PortraitFeedModel = viewModel { PortraitFeedModel(headItem(vm)) }
    // Back from the classic layout: the pager opens on the video the page shows now.
    remember(model) { model.focus(headItem(vm)) }
    val settled = LocalPageSettled.current
    var commentsOpen by rememberSaveable { mutableStateOf(false) }
    // The side panel (landscape windows with room) shows by default; 评论 folds it away and back.
    var panelOpen by rememberSaveable { mutableStateOf(true) }
    var moreOpen by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(PortraitMode.Phone) }

    ImmersiveSystemBars()
    // Douyin loops a video; restored when the layout goes away.
    DisposableEffect(ctrl) {
        val wasLooping = ctrl?.loop ?: false
        ctrl?.changeLoop(true)
        onDispose { ctrl?.changeLoop(wasLooping) }
    }
    // The player joins only once the page has landed (a SurfaceView ignores the entry
    // transition's rounded clip); the stream starts loading right away all the same. With no
    // target yet (a video whose cid is still resolving) the previous one must not keep playing.
    LaunchedEffect(vm.target) {
        val t = vm.target
        if (t != null) ctrl?.load(t) else playerState.pause()
    }

    val pager = rememberPagerState(initialPage = model.page.coerceIn(0, (model.items.size - 1).coerceAtLeast(0))) { model.items.size }
    LaunchedEffect(pager, model) {
        snapshotFlow { pager.settledPage }.collect { p ->
            model.page = p
            model.items.getOrNull(p)?.let(vm::openInPlace)
        }
    }
    // Related videos of whatever plays near the end extend the list.
    LaunchedEffect(model, vm.relatedFor, vm.related) {
        // An empty relatedFor is the opened video's list when the route only carried an aid.
        val i = if (vm.relatedFor.isEmpty()) 0 else model.items.indexOfFirst { it.bvid == vm.relatedFor }
        if (i >= 0 && i >= model.items.size - 4) model.offer(vm.related, vm.relatedAspects)
    }

    val current = model.items.getOrNull(pager.settledPage)
    val currentDetail = vm.detail?.takeIf { it.bvid == current?.bvid }
    val commentAid = currentDetail?.aid ?: current?.aid ?: 0L
    val commentCount = currentDetail?.reply ?: 0L
    val seek: (Long) -> Unit = { s -> playerState.seekTo(s * 1000) }

    // The picture takes over from the classic layout's; the overlay springs in over it.
    val reveal = remember { Animatable(0f) }
    val revealSpec = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(reveal) { reveal.animateTo(1f, revealSpec) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val callbacks = remember(gate, onCoin, onFav, onTriple, onFollow, onCollapse) {
        PortraitCallbacks(
            gate = gate,
            onCoin = onCoin,
            onFav = onFav,
            onTriple = onTriple,
            onFollow = onFollow,
            onComments = { if (mode == PortraitMode.Panel) panelOpen = !panelOpen else commentsOpen = true },
            onScrubbingChange = { scrubbing = it },
            onCollapse = onCollapse,
            reveal = { reveal.value.coerceIn(0f, 1f) },
        )
    }

    ImmersiveTheme {
        if (vm.fullscreen) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                player(Modifier.fillMaxSize())
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val m = portraitMode(maxWidth, maxHeight, statusTop, navBottom)
                SideEffect { mode = m }
                val withPanel = m == PortraitMode.Panel
                val panelShown = withPanel && panelOpen
                val panelWidth = if (withPanel) panelWidth(maxWidth, maxHeight, statusTop, navBottom) else 0.dp
                if (withPanel) {
                    // One backdrop for the whole window: the pages' columns slide over it and it
                    // follows the settled video.
                    val effects = YoinMotion.slowEffectsSpec<Float>()
                    AnimatedContent(
                        targetState = current?.cover.orEmpty(),
                        transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
                        modifier = Modifier.matchParentSize(),
                        label = "panelBackdrop",
                    ) { cover -> CoverBackdrop(cover, Modifier.fillMaxSize()) }
                }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        VerticalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                            key = { model.items.getOrNull(it)?.bvid ?: it },
                            userScrollEnabled = !scrubbing && ctrl?.boosting != true,
                        ) { page ->
                            val item = model.items[page]
                            // The settled page takes the player once the target is its video (the
                            // switch lands a moment after the settle), so it never shows the last one.
                            val targetBvid = vm.target?.bvid
                            val host = settled && page == pager.settledPage && (targetBvid == null || targetBvid == item.bvid)
                            PortraitPage(
                                vm = vm,
                                item = item,
                                aspect = aspectOf(vm, item, ctrl, host),
                                host = host,
                                mode = m,
                                // With the side panel open the info lives there, not over the picture.
                                showInfo = !panelShown,
                                statusTop = statusTop,
                                navBottom = navBottom,
                                playerState = playerState,
                                ctrl = ctrl,
                                player = player,
                                callbacks = callbacks,
                            )
                        }
                        TopChrome(
                            modifier = Modifier.revealed({ reveal.value.coerceIn(0f, 1f) }),
                            statusTop = statusTop,
                            onBack = onBack,
                            onMore = { moreOpen = true },
                            showMore = ctrl != null,
                            scrim = m == PortraitMode.Phone,
                        )
                    }
                    if (withPanel) {
                        AnimatedVisibility(
                            visible = panelOpen,
                            enter = expandHorizontally(YoinMotion.defaultSpatialSpec(), expandFrom = Alignment.Start) +
                                fadeIn(YoinMotion.defaultEffectsSpec()),
                            exit = shrinkHorizontally(YoinMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Start) +
                                fadeOut(YoinMotion.fastEffectsSpec()),
                        ) {
                            InfoPanel(
                                vm = vm,
                                item = current,
                                onSeek = seek,
                                onFollow = onFollow,
                                onClose = { panelOpen = false },
                                modifier = Modifier
                                    .width(panelWidth)
                                    .fillMaxHeight()
                                    .padding(top = statusTop + ColumnVerticalMargin, end = PanelEndMargin, bottom = navBottom + ColumnVerticalMargin)
                                    .revealed({ reveal.value.coerceIn(0f, 1f) }),
                            )
                        }
                    }
                }
            }
        }
    }

    if (mode != PortraitMode.Panel && commentsOpen) {
        CommentsSheet(aid = commentAid, count = commentCount, onSeek = seek, onDismiss = { commentsOpen = false })
    }
    if (moreOpen && ctrl != null) {
        PlayerSheet(ctrl = ctrl, start = SheetPage.Main, onDismiss = { moreOpen = false })
    }
}

/** The picture's aspect: the detail's / related list's dimension, else the stream's once it shows. */
private fun aspectOf(vm: VideoDetailViewModel, item: VideoItem, ctrl: PiliPlayerController?, host: Boolean): Float {
    val d = vm.detail
    if (d != null && d.bvid == item.bvid && vm.videoAspect > 0f) return vm.videoAspect
    vm.relatedAspects[item.bvid]?.takeIf { it > 0f }?.let { return it }
    if (host && ctrl != null && ctrl.firstFrameRendered && ctrl.target?.bvid == item.bvid) return ctrl.videoAspect
    return DefaultAspect
}

/**
 * Back and more as round translucent buttons. The hold-to-speed pill belongs to the page
 * whose picture is held (PortraitPage), just under these.
 */
@Composable
private fun TopChrome(
    modifier: Modifier,
    statusTop: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onMore: () -> Unit,
    showMore: Boolean,
    scrim: Boolean,
) {
    Box(modifier.fillMaxWidth()) {
        if (scrim) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(statusTop + 88.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.36f), Color.Transparent))),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = statusTop + 4.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(YoinSymbols.Back, "返回", onBack)
            Spacer(Modifier.weight(1f))
            if (showMore) RoundButton(YoinSymbols.MoreHorizontal, "更多", onMore)
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.28f),
            contentColor = OnPicture,
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/** Compact / Medium: comments in a sheet at ~70% of the height, in the page's own theme. */
@Composable
private fun CommentsSheet(aid: Long, count: Long, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Edge to edge: the sheet runs under the navigation bar; the list pads itself.
        contentWindowInsets = { WindowInsets(0) },
        properties = rememberPlayerSheetProperties(),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f)) {
            CommentsHeader(count, onClose = null)
            if (aid > 0) {
                key(aid) {
                    ReplyList(
                        oid = aid,
                        type = 1,
                        // Comments sink under the sheet's header with the seam, not a hard cut.
                        modifier = Modifier.weight(1f).fillMaxWidth().seamViewport(),
                        contentPadding = PaddingValues(bottom = 16.dp + navBottom),
                        onTimestampClick = onSeek,
                        threadsInPlace = true,
                    )
                }
            }
        }
    }
}

/**
 * Landscape windows with room: the settled video's info (UP, title, numbers, description,
 * tags) and its comments in one list, on a surface panel beside the pager.
 */
@Composable
private fun InfoPanel(
    vm: VideoDetailViewModel,
    item: VideoItem?,
    onSeek: (Long) -> Unit,
    onFollow: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = vm.detail?.takeIf { item != null && it.bvid == item.bvid }
    val aid = detail?.aid ?: 0L
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    Box(
        modifier
            .clip(YoinContainerShapes.Panel)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        AnimatedContent(
            targetState = aid,
            transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
            modifier = Modifier.fillMaxSize(),
            label = "infoPanel",
        ) { id ->
            val d = vm.detail?.takeIf { it.aid == id && id > 0 }
            if (d == null) {
                Column(Modifier.fillMaxSize()) {
                    PanelTopRow(onClose) {
                        Text(
                            item?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val error = vm.error
                        if (error != null) ErrorState(error, onRetry = { vm.load() }) else LoadingIndicator()
                    }
                }
            } else {
                val up = vm.up
                val known = up.mid == d.ownerMid && d.ownerMid > 0
                key(id) {
                    ReplyList(
                        oid = id,
                        type = 1,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        onTimestampClick = onSeek,
                        header = {
                            item("panelUp") {
                                PanelTopRow(onClose) {
                                    if (d.staff.size > 1) {
                                        StaffRow(d.staff)
                                    } else {
                                        UpCard(
                                            mid = d.ownerMid,
                                            name = d.ownerName,
                                            face = d.ownerFace,
                                            fans = if (known) up.fans else -1L,
                                            following = known && up.following,
                                            busy = !known || up.busy,
                                            onFollow = onFollow,
                                            mutual = known && up.mutual,
                                        )
                                    }
                                }
                            }
                            item("panelHeader") { VideoHeader(d, onSeek, Modifier.padding(top = 4.dp)) }
                            if (vm.tags.isNotEmpty()) {
                                item("panelTags") { TagChips(vm.tags, Modifier.padding(top = 4.dp)) }
                            }
                            item("panelComments") {
                                Spacer(Modifier.height(8.dp))
                                CommentsHeader(d.reply, onClose = null)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The panel's first row: [content] (UP or title) with 关闭 at the end, which folds the panel. */
@Composable
private fun PanelTopRow(onClose: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { content() }
        IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Close, contentDescription = "关闭")
        }
    }
}

@Composable
private fun CommentsHeader(count: Long, onClose: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("评论", style = MaterialTheme.typography.titleMedium)
        if (count > 0) {
            Spacer(Modifier.width(8.dp))
            Text(Fmt.count(count), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        if (onClose != null) {
            IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.Close, contentDescription = "关闭")
            }
        }
    }
}

/**
 * Everything drawn over the picture uses a dark scheme seeded from the page's (cover-tinted)
 * primary, so accents are light tones that read on video; the side comment pane shares it.
 */
@Composable
private fun ImmersiveTheme(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = LocalPiliDark.current
    val seed = cs.primary.toArgb()
    val scheme = if (dark) cs else remember(seed) { ExpressiveColorSchemeFactory.fromSeed(seed, isDark = true) }
    MaterialTheme(
        colorScheme = scheme,
        motionScheme = MaterialTheme.motionScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
    ) {
        // A nested scheme needs its own default content colour; the outer one may be light.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

/** Light status / navigation bar icons over the picture; restored when the layout leaves. */
@Composable
private fun ImmersiveSystemBars() {
    LightBarIcons(status = true, navigation = true)
}
