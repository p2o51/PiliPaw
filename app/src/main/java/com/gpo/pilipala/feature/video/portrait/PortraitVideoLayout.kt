package com.gpo.pilipala.feature.video.portrait

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import com.gpo.pilipala.feature.player.BiliVideoPlayer
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayerChrome
import com.gpo.pilipala.feature.player.PlayerSheet
import com.gpo.pilipala.feature.player.SheetPage
import com.gpo.pilipala.feature.player.speedLabel
import com.gpo.pilipala.feature.reply.ReplyList
import com.gpo.pilipala.feature.video.VideoDetailViewModel
import com.gpo.pilipala.feature.video.findActivity
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.transition.LocalPageSettled
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.ExpressiveColorSchemeFactory
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols

/**
 * Douyin-style immersive layout for a portrait video (VideoDetailScreen picks it once the
 * detail says the picture is taller than wide). A vertical pager over the opened video and its
 * related videos: swiping up / down plays the next / previous one in place (the page's target,
 * intro and relation follow); system back still leaves the page.
 *
 * The coin / fav / triple sheets and the unfollow confirmation stay on the video page, reached
 * through [onCoin] / [onFav] / [onTriple] / [onFollow] (already login-gated there).
 */
@Composable
internal fun PortraitVideoLayout(
    vm: VideoDetailViewModel,
    playerState: BiliPlayerState,
    gate: (() -> Unit) -> Unit,
    onCoin: () -> Unit,
    onFav: () -> Unit,
    onTriple: () -> Unit,
    onFollow: () -> Unit,
) {
    val nav = LocalNavigator.current
    val ctrl = playerState as? PiliPlayerController
    val model: PortraitFeedModel = viewModel { PortraitFeedModel(headItem(vm)) }
    val settled = LocalPageSettled.current
    val width = widthClass()
    val sidePane = width == WidthClass.Expanded
    var commentsOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }

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

    val playerContent: @Composable (Modifier) -> Unit = { m ->
        BiliVideoPlayer(
            target = vm.target,
            state = playerState,
            // Only PiP (or turning the phone with a landscape video) asks for it.
            fullscreen = vm.fullscreen,
            onFullscreenChange = { vm.fullscreen = it },
            onBack = { if (vm.fullscreen) vm.fullscreen = false else nav.pop() },
            modifier = m,
            chrome = PlayerChrome.Minimal,
        )
    }

    // The picture takes over from the stacked player as is; the overlay springs in over it.
    val reveal = remember { Animatable(0f) }
    val revealSpec = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(reveal) { reveal.animateTo(1f, revealSpec) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val callbacks = remember(gate, onCoin, onFav, onTriple, onFollow) {
        PortraitCallbacks(
            gate = gate,
            onCoin = onCoin,
            onFav = onFav,
            onTriple = onTriple,
            onFollow = onFollow,
            onComments = { commentsOpen = true },
            onScrubbingChange = { scrubbing = it },
            reveal = { reveal.value.coerceIn(0f, 1f) },
        )
    }

    ImmersiveTheme {
        if (vm.fullscreen) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                playerContent(Modifier.fillMaxSize())
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val column = if (width == WidthClass.Compact) null else columnSize(maxWidth, maxHeight, statusTop, navBottom)
                val paneWidth = minOf(420.dp, maxWidth * 0.38f)
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
                                column = column,
                                statusTop = statusTop,
                                navBottom = navBottom,
                                playerState = playerState,
                                ctrl = ctrl,
                                player = playerContent,
                                callbacks = callbacks,
                            )
                        }
                        TopChrome(
                            modifier = Modifier.revealed({ reveal.value.coerceIn(0f, 1f) }),
                            statusTop = statusTop,
                            boosting = ctrl?.boosting == true,
                            speed = speedLabel(3f),
                            onBack = { nav.pop() },
                            onMore = { moreOpen = true },
                            showMore = ctrl != null,
                            scrim = column == null,
                        )
                    }
                    if (sidePane) {
                        AnimatedVisibility(
                            visible = commentsOpen,
                            enter = expandHorizontally(YoinMotion.defaultSpatialSpec(), expandFrom = Alignment.Start) +
                                fadeIn(YoinMotion.defaultEffectsSpec()),
                            exit = shrinkHorizontally(YoinMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Start) +
                                fadeOut(YoinMotion.fastEffectsSpec()),
                        ) {
                            CommentsPane(
                                aid = commentAid,
                                count = commentCount,
                                onSeek = seek,
                                onClose = { commentsOpen = false },
                                modifier = Modifier
                                    .width(paneWidth)
                                    .fillMaxHeight()
                                    .padding(top = statusTop + 12.dp, end = 12.dp, bottom = 12.dp),
                                bottomPadding = navBottom,
                            )
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = sidePane && commentsOpen) { commentsOpen = false }
    if (!sidePane && commentsOpen) {
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

/** Back and more as round translucent buttons, the 3x pill while a long press holds. */
@Composable
private fun TopChrome(
    modifier: Modifier,
    statusTop: androidx.compose.ui.unit.Dp,
    boosting: Boolean,
    speed: String?,
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
        AnimatedVisibility(
            visible = boosting && speed != null,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.7f) +
                slideInVertically(YoinMotion.fastSpatialSpec()) { -it / 2 },
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = statusTop + 60.dp),
        ) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(YoinSymbols.Speed, contentDescription = null, tint = OnPicture, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(speed.orEmpty(), style = MaterialTheme.typography.labelLarge, color = OnPicture)
            }
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f)) {
            CommentsHeader(count, onClose = null)
            if (aid > 0) {
                key(aid) {
                    ReplyList(
                        oid = aid,
                        type = 1,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        onTimestampClick = onSeek,
                    )
                }
            }
        }
    }
}

/** Expanded: comments in a pane beside the picture (follows the playing video). */
@Composable
private fun CommentsPane(
    aid: Long,
    count: Long,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Column(
        modifier
            .clip(YoinContainerShapes.Panel)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        CommentsHeader(count, onClose = onClose)
        if (aid > 0) {
            key(aid) {
                ReplyList(
                    oid = aid,
                    type = 1,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
                    onTimestampClick = onSeek,
                )
            }
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
        content = content,
    )
}

/** Light status / navigation bar icons over the picture; restored when the layout leaves. */
@Composable
private fun ImmersiveSystemBars() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (status != null) controller.isAppearanceLightStatusBars = status
            if (navigation != null) controller.isAppearanceLightNavigationBars = navigation
        }
    }
}
