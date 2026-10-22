package com.gpo.pilipala.feature.video

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.gpo.pilipala.feature.player.LocalPlayerPictureShape
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayerChrome
import com.gpo.pilipala.feature.video.portrait.PlayerFlightLayer
import com.gpo.pilipala.feature.video.portrait.PlayerHandoff
import com.gpo.pilipala.feature.video.portrait.reportWindowRect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.player.BiliPlayerState
import com.gpo.pilipala.feature.player.BiliVideoPlayer
import com.gpo.pilipala.feature.player.BindPlayerPage
import com.gpo.pilipala.feature.player.PlayQueue
import com.gpo.pilipala.feature.player.PlayerPageHooks
import com.gpo.pilipala.feature.player.attachQueue
import com.gpo.pilipala.feature.player.listening
import com.gpo.pilipala.feature.player.setAudioOnly
import com.gpo.pilipala.feature.listen.ListenFlight
import com.gpo.pilipala.feature.listen.LocalPicturePinchIn
import com.gpo.pilipala.feature.listen.PicturePinchIn
import com.gpo.pilipala.feature.listen.PinchDriver
import com.gpo.pilipala.feature.listen.PinchToListenHost
import com.gpo.pilipala.feature.player.rememberBiliPlayerState
import com.gpo.pilipala.feature.player.captureVideoFrame
import com.gpo.pilipala.feature.player.findVideoView
import com.gpo.pilipala.navigation.transition.LocalPageTransitions
import com.gpo.pilipala.navigation.transition.sharedKey
import androidx.compose.ui.draw.drawWithContent
import com.gpo.pilipala.navigation.transition.PageHeroEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalView
import com.gpo.pilipala.feature.reply.ReplyAnchors
import com.gpo.pilipala.feature.reply.ReplyList
import com.gpo.pilipala.feature.video.portrait.PortraitVideoLayout
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.LocalPageSettled
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.singleRoute
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.flow.merge

private enum class VideoSheet { Coin, Fav, Pages, Season, Triple, Queue }

@Composable
fun VideoDetailScreen(route: Route.Video) {
    val vm = viewModel { VideoDetailViewModel(route) }
    CoverTheme(vm.cover) {
        // A swipe down on the inline picture leaves the page (into the mini bar with 继续播放).
        // 继续播放 with videos to go back to: the back button shows its three dots.
        val backHasMore = com.gpo.pilipala.feature.sources.KeepPlaying.on && vm.queue == null &&
            com.gpo.pilipala.feature.sources.WatchTrail.entries.any { it.source == com.gpo.pilipala.core.source.VideoSource.Bilibili }
        androidx.compose.runtime.CompositionLocalProvider(
            com.gpo.pilipala.feature.player.LocalPictureSwipeDown provides rememberVideoSwipeDown(),
            com.gpo.pilipala.feature.sources.LocalBackHasMore provides backHasMore,
        ) {
            VideoDetailContent(vm, route)
        }
    }
}

@Composable
private fun VideoDetailContent(vm: VideoDetailViewModel, route: Route.Video) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val toast = rememberToast()
    val gate = rememberLoginGate()
    val playerState = rememberBiliPlayerState(adoptBvid = vm.bvid)
    var sheet by remember { mutableStateOf<VideoSheet?>(null) }
    var confirmUnfollow by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { merge(vm.messages, vm.actions.messages).collect { toast(it) } }
    LaunchedEffect(vm.redirect) { vm.redirect?.let { nav.replace(it) } }
    // Leaving for another page disposes the player; come back where we were.
    DisposableEffect(vm, playerState) {
        onDispose { vm.rememberPosition(playerState.positionMs) }
    }
    // The launcher's 继续看 shortcut follows the video left part-way.
    ContinueShortcutEffect(vm, playerState)
    // The queue (收藏夹 / 稍后再看 as a playlist) drives the player: completion, notification
    // previous / next and the play mode. Attached before the player's first load runs.
    val queue = vm.queue
    DisposableEffect(playerState, queue) {
        playerState.attachQueue(queue)
        // 仅听声音 from the start (Route.Video.audioOnly): set before the first load, so no picture stream.
        if (queue == null && vm.listening) playerState.setAudioOnly(true)
        onDispose {
            if (queue == null) vm.listening = playerState.listening
            // Kept in the mini bar (继续播放): the queue stays with the player, so the list plays on
            // and 仅听声音 holds (letting the queue go switched the picture back on, and the page
            // reopened from the bar came back as video).
            val kept = (playerState as? PiliPlayerController)?.let(com.gpo.pilipala.feature.sources.BiliKeeper::wouldKeep) == true
            if (!kept) playerState.attachQueue(null)
        }
    }
    LaunchedEffect(playerState, vm.detail?.ownerName) { playerState.artist = vm.detail?.ownerName.orEmpty() }
    // 上一个 / 下一个 (分P / 合集) and 点赞 for the player's overlay and the system media controls.
    BindPlayerPage(
        playerState,
        remember(vm) {
            PlayerPageHooks(
                target = { vm.target },
                hasNext = vm::hasNext,
                next = { vm.playNext() },
                hasPrevious = vm::hasPrevious,
                previous = { vm.playPrevious() },
                liked = { vm.actions.liked.takeIf { vm.actions.aid > 0 } },
                toggleLike = { vm.actions.toggleLike() },
            )
        },
    )

    val seek: (Long) -> Unit = remember(playerState) { { s -> playerState.seekTo(s * 1000) } }
    val ctrl = playerState as? PiliPlayerController
    // The picture's trip between the classic page and the immersive pager (PlayerHandoff.kt).
    val handoff = remember { PlayerHandoff() }
    // Reopening the docked player already knows its layout. Do not show the classic page
    // for one flight and then launch another flight when the detail request returns.
    remember(ctrl, vm) { ctrl?.detailImmersive?.let(vm::showImmersive) }
    androidx.compose.runtime.SideEffect {
        vm.immersive?.let { ctrl?.detailImmersive = it }
    }
    val immersiveOn = vm.immersive == true
    // A route that does not know the picture's shape opens classic; when the detail then
    // says portrait, the picture flies from the classic slot into the pager like 全屏 does,
    // instead of the page cross-fading into another layout around it. A decision that lands
    // while the page is still growing from its card waits for the landing: the classic slot's
    // rect is the scaled one inside the container until then, and the grow carries the
    // classic page it started with.
    val decided = remember { arrayOf(vm.immersive) }
    val holdClassic = remember { booleanArrayOf(false) }
    val settled = LocalPageSettled.current
    if (decided[0] == null && immersiveOn) {
        if (settled) {
            if (handoff.flight == null) handoff.toImmersive()
        } else {
            holdClassic[0] = true
        }
    }
    decided[0] = vm.immersive
    if (holdClassic[0] && settled) {
        holdClassic[0] = false
        if (immersiveOn && handoff.flight == null) handoff.toImmersive()
    }
    val showImmersive = immersiveOn && !holdClassic[0]
    // 缩小 (immersive → classic) and 全屏 on a portrait picture (classic → immersive), for this visit only.
    val toImmersive: () -> Unit = {
        if (vm.immersive != true && vm.queue == null) {
            handoff.fromClassic = true
            handoff.toImmersive()
            vm.showImmersive(true)
        }
    }
    val toClassic: () -> Unit = {
        if (vm.immersive == true) {
            handoff.fromClassic = false
            handoff.toClassic()
            vm.showImmersive(false)
        }
    }
    // The stream's own aspect once it shows (a 分P may differ), else the detail's.
    val portraitPicture: () -> Boolean = {
        val a = if (ctrl != null && ctrl.firstFrameRendered) ctrl.videoAspect else vm.videoAspect
        a > 0f && a < 1f
    }
    // A portrait picture's 全屏 is the immersive pager (no rotation, no pillarboxed black);
    // PiP still asks for the plain fullscreen layout.
    val onFullscreenChange: (Boolean) -> Unit = { fs ->
        if (fs && vm.immersive != true && vm.queue == null && ctrl?.pipForcedFullscreen != true && portraitPicture()) {
            toImmersive()
        } else {
            vm.fullscreen = fs
        }
    }
    val latestFullscreenChange by rememberUpdatedState(onFullscreenChange)
    // The one player of the page: it moves between the classic slot, the immersive pager's
    // current page and the flight between them, so playback never reloads.
    // Pinching the picture in flies it into 仅听声音 (filled in by the listen overlay below).
    val pinchToListen = remember { PinchToListenHost() }
    // 继续播放's way back: videos this page switched away from in place (WatchTrail).
    val trailMenu = com.gpo.pilipala.feature.sources.rememberTrailMenuState()
    val trailEntries = com.gpo.pilipala.feature.sources.WatchTrail.entries.filter { it.source == com.gpo.pilipala.core.source.VideoSource.Bilibili }
    val trailOn = com.gpo.pilipala.feature.sources.KeepPlaying.on && vm.queue == null && trailEntries.isNotEmpty()
    val latestTrailOn by rememberUpdatedState(trailOn)
    val pageTransitions = LocalPageTransitions.current
    val heroKey = remember(vm.bvid) { Route.Video(bvid = vm.bvid).sharedKey() }
    val player = remember(vm, playerState) {
        movableContentOf { m: Modifier, chrome: PlayerChrome ->
            androidx.compose.runtime.CompositionLocalProvider(LocalPicturePinchIn provides pinchToListen) {
                BiliVideoPlayer(
                    target = vm.target,
                    state = playerState,
                    fullscreen = vm.fullscreen,
                    onFullscreenChange = { latestFullscreenChange(it) },
                    onBack = {
                        when {
                            vm.fullscreen -> vm.fullscreen = false
                            latestTrailOn -> trailMenu.open()
                            else -> nav.pop()
                        }
                    },
                    // While a back flies a copy of the picture out (to its card, the bar), the
                    // page draws none of its own: never two pictures. Not drawn at all, so a
                    // SurfaceView punches no hole either.
                    modifier = m.drawWithContent {
                        if (heroKey == null || pageTransitions?.hero?.page != heroKey) drawContent()
                    },
                    // With a queue the player steps it itself; pages / season only without one.
                    // (The immersive pager loops a video and steps nothing.)
                    onCompleted = { if (AppSettings.autoPlayNext.value && vm.queue == null) vm.playNext() },
                    chrome = chrome,
                )
            }
        }
    }

    val replyCount = vm.detail?.reply?.takeIf { it > 0 }?.let { Fmt.count(it) }
    val openSheet: (VideoSheet) -> Unit = { sheet = it }
    val onFollow: () -> Unit = {
        gate { if (vm.up.following) confirmUnfollow = true else vm.up.toggle() }
    }
    val compactPagerState = rememberPagerState { 2 }

    // Back flies the picture's current frame home into the card it was opened from, or into the
    // bar or window it was opened from (PageHero.kt): the classic layout's inline picture or the
    // immersive pager's, as laid out while no back runs.
    val heroView = LocalView.current
    val transitions = LocalPageTransitions.current
    val heroRect = remember { arrayOfNulls<Rect>(1) }
    val heroImmersiveRect = remember { arrayOfNulls<Rect>(1) }
    LaunchedEffect(handoff) {
        snapshotFlow { handoff.classicRect }.collect { r ->
            if (transitions?.backIdle != false) heroRect[0] = r
        }
    }
    LaunchedEffect(handoff) {
        snapshotFlow { handoff.immersiveRect }.collect { r ->
            if (transitions?.backIdle != false) heroImmersiveRect[0] = r
        }
    }
    // 仅听声音 shows no picture (its video track is off): the listen page's cover flies home
    // instead, from its place on that page, its 28dp corners and all.
    val listenFlight = remember { arrayOfNulls<ListenFlight>(1) }
    val listenRect = remember { arrayOfNulls<Rect>(1) }
    PageHeroEffect(Route.Video(bvid = vm.bvid)) {
        listenFlight[0]?.takeIf { ctrl?.audioOnly == true }?.let { lf ->
            if (lf.flying) return@PageHeroEffect null
            val rect = listenRect[0] ?: return@PageHeroEffect null
            val image = lf.coverLayer?.toImageBitmap() ?: return@PageHeroEffect null
            return@PageHeroEffect image to rect
        }
        if (vm.fullscreen || handoff.flight != null) return@PageHeroEffect null
        val rect = (if (showImmersive) heroImmersiveRect[0] else heroRect[0]) ?: return@PageHeroEffect null
        val view = findVideoView(heroView.rootView, rect, ctrl?.player) ?: return@PageHeroEffect null
        val frame = captureVideoFrame(view, view.width, view.height) ?: return@PageHeroEffect null
        // The video view sits fitted, centred, in its slot: its rect as laid out at rest.
        // (Its place in the window is not used: a back gesture has already shrunk and moved the
        // page, and the flight poses the rect the way the page is posed, so that place would be
        // posed twice: the copy appeared below and smaller than the picture it replaces.)
        val bounds = Rect(
            rect.center.x - view.width / 2f,
            rect.center.y - view.height / 2f,
            rect.center.x + view.width / 2f,
            rect.center.y + view.height / 2f,
        )
        // The picture's shape goes with it: a bar or window it lands in starts in that shape.
        ctrl?.videoAspect?.takeIf { it.isFinite() && it > 0f }?.let { com.gpo.pilipala.feature.sources.KeepPlaying.knownAspect = it.coerceIn(0.4f, 2.5f) }
        val loc = IntArray(2).also { view.getLocationInWindow(it) }
        com.gpo.pilipala.feature.sources.MiniLog.event(
            "hero start",
            "slot ${rect.left.toInt()},${rect.top.toInt()} ${view.width}x${view.height}; view now at ${loc[0]},${loc[1]}",
        )
        frame.asImageBitmap() to bounds
    }
    // Opened from a comment notification: straight to 评论, where the comment waits pinned.
    val anchorAid = vm.detail?.aid ?: 0L
    LaunchedEffect(anchorAid) {
        if (anchorAid > 0 && ReplyAnchors.has(anchorAid, 1)) compactPagerState.animateScrollToPage(1)
    }

    // 继续播放: a related video or a link opened here takes this page's place (the one left goes on
    // the back menu, where it was); only while this page is the one on top.
    val latestCtrl by rememberUpdatedState(ctrl)
    val trailEntryOf: () -> com.gpo.pilipala.feature.sources.TrailEntry? = {
        val t = vm.target
        if (t == null || vm.bvid.isEmpty()) {
            null
        } else {
            com.gpo.pilipala.feature.sources.TrailEntry(
                key = vm.bvid,
                source = com.gpo.pilipala.core.source.VideoSource.Bilibili,
                title = vm.detail?.title ?: t.title,
                cover = vm.cover.ifEmpty { t.cover },
                positionMs = playerState.positionMs,
                durationMs = playerState.durationMs,
                target = Route.Video(bvid = vm.bvid, aid = vm.detail?.aid ?: t.aid, cid = vm.cid, cover = vm.cover, title = vm.detail?.title ?: t.title),
            )
        }
    }
    val switchTo: (Route.Video, Long) -> Unit = { r, startMs ->
        val bv = r.bvid.ifEmpty { if (r.aid > 0) com.gpo.pilipala.core.util.IdUtils.av2bv(r.aid) else "" }
        trailEntryOf()?.let { com.gpo.pilipala.feature.sources.WatchTrail.push(it) }
        com.gpo.pilipala.feature.sources.WatchTrail.forget(bv)
        com.gpo.pilipala.feature.sources.MiniLog.event("in place", "${vm.bvid} → $bv at ${startMs / 1000}s")
        vm.openTarget(bv, r.aid, r.cid, r.title, r.cover, startMs)
    }
    DisposableEffect(vm) {
        val take: (Any) -> Boolean = take@{ r ->
            val v = r as? Route.Video ?: return@take false
            if (!com.gpo.pilipala.feature.sources.KeepPlaying.on || vm.queue != null || v.playlistId.isNotEmpty()) return@take false
            if (nav.backStack.lastOrNull() != route || latestCtrl == null) return@take false
            if (v.bvid.isNotEmpty() && v.bvid == vm.bvid) return@take true
            trailMenu.dismiss()
            switchTo(v, v.progress * 1000)
            true
        }
        com.gpo.pilipala.feature.sources.WatchTrail.inPlace = take
        onDispose { if (com.gpo.pilipala.feature.sources.WatchTrail.inPlace === take) com.gpo.pilipala.feature.sources.WatchTrail.inPlace = null }
    }
    // A switch in place starts the page over: 简介 from its top.
    val switchedBvid = remember { arrayOf(vm.bvid) }
    LaunchedEffect(vm.bvid) {
        if (switchedBvid[0] != vm.bvid) {
            switchedBvid[0] = vm.bvid
            if (compactPagerState.currentPage != 0) compactPagerState.animateScrollToPage(0)
        }
    }
    // Back with videos to go back to opens the menu (registered before the in-page handlers below,
    // fullscreen's and the immersive pager's, so they still come first).
    com.gpo.pilipala.feature.sources.TrailBackHandler(trailMenu, enabled = trailOn && !vm.fullscreen && !(immersiveOn && handoff.fromClassic))
    // The menu steps aside while the page itself is leaving (its 返回首页), back if that is cancelled.
    LaunchedEffect(trailMenu, pageTransitions) {
        snapshotFlow { pageTransitions?.backIdle != false }.collect { idle -> trailMenu.aside = !idle }
    }

    // Back from an immersive pager opened on this page returns to the classic layout.
    BackHandler(enabled = immersiveOn && handoff.fromClassic && !vm.fullscreen) { toClassic() }

    Box(Modifier.fillMaxSize()) {
        // Portrait videos: the Douyin-style immersive pager (feature/video/portrait); the choice
        // is made once the detail arrives, then only the user's 缩小 / 全屏 changes it.
        val effects = YoinMotion.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = showImmersive,
            transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
            label = "videoLayout",
        ) { on ->
            // Only the layout being shown carries the player, and neither while it flies.
            val hosts = on == showImmersive && handoff.flight == null
            if (on) {
                PortraitVideoLayout(
                    vm = vm,
                    playerState = playerState,
                    player = { m ->
                        val report = m.reportWindowRect { handoff.immersiveRect = it }
                        if (hosts) player(report, PlayerChrome.Minimal) else Box(report.background(Color.Black))
                    },
                    gate = gate,
                    onBack = { if (handoff.fromClassic) toClassic() else nav.pop() },
                    onCollapse = toClassic,
                    onCoin = { gate { sheet = VideoSheet.Coin } },
                    onFav = { gate { sheet = VideoSheet.Fav } },
                    onTriple = { gate { sheet = VideoSheet.Triple } },
                    onFollow = onFollow,
                )
            } else {
                DetailScaffold(
                    fullscreen = vm.fullscreen,
                    onExitFullscreen = { vm.fullscreen = false },
                    player = { m ->
                        // Only the 16:9 picture is the flight's slot (the control strip under it is not).
                        val report = m.reportWindowRect { r ->
                            handoff.classicRect = if (vm.fullscreen) null else Rect(r.topLeft, Size(r.width, r.width * 9f / 16f))
                        }
                        if (hosts) player(report, PlayerChrome.Full) else PlayerSlot(report)
                    },
                    compactTabs = listOf(DetailTab("简介"), DetailTab("评论", replyCount)),
                    compactPagerState = compactPagerState,
                    compactPage = { page, pad ->
                        if (page == 0) {
                            IntroPane(
                                vm, pad, includeRelated = true, onSeek = seek, gate = gate, onSheet = openSheet, onFollow = onFollow, context = context,
                                queue = queue, player = playerState,
                            )
                        } else {
                            CommentsPane(vm, pad, seek)
                        }
                    },
                    expandedMain = { pad ->
                        IntroPane(vm, pad, includeRelated = false, onSeek = seek, gate = gate, onSheet = openSheet, onFollow = onFollow, context = context)
                    },
                    sideTabs = buildList {
                        add(DetailTab("评论", replyCount))
                        add(DetailTab("相关"))
                        if (queue != null) add(DetailTab("播放列表", "${queue.size}"))
                    },
                    sidePage = { page, pad ->
                        when {
                            page == 0 -> CommentsPane(vm, pad, seek)
                            page == 2 && queue != null -> QueueTab(queue, playerState, pad)
                            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                                relatedItems(vm) { playerState.listening }
                            }
                        }
                    },
                    // Paused: pushing the comments up folds the player away (继续播放 brings it back).
                    collapsible = ctrl != null && !ctrl.isPlaying && !ctrl.playWhenReady,
                    onResume = { playerState.play() },
                )
            }
        }
        handoff.flight?.let { f ->
            PlayerFlightLayer(
                flight = f,
                target = if (f.toImmersive) handoff.immersiveRect else handoff.classicRect,
                onLanded = { if (handoff.flight === f) handoff.flight = null },
            ) { m -> player(m, PlayerChrome.Minimal) }
        }
        // 仅听声音 gets its own page over this one (the player keeps running beneath it); the
        // picture flies into its cover on the way in and back out of it (ListenFlight).
        val listening = ctrl?.audioOnly == true && !vm.fullscreen && !showImmersive
        // Opened already listening (the mini bar, 仅听声音 from a card): the cover still flies in
        // from the picture's slot, with no frame to start from (none was ever drawn).
        val listenP = remember { Animatable(0f) }
        val openedListening = remember { booleanArrayOf(listening) }
        var listenShot by remember { mutableStateOf<ImageBitmap?>(null) }
        // Slower than the page springs: the trip is short (picture slot → cover slot) and has to
        // read as the picture becoming the cover, not a cut.
        val listenIn = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.86f, stiffness = 110f)
        val listenOut = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.9f, stiffness = 170f)
        // The picture as it is now, so a flight into the cover starts from what is on screen.
        suspend fun capturePicture(): ImageBitmap? = handoff.classicRect?.let { r ->
            findVideoView(heroView.rootView, r)?.let { v ->
                withTimeoutOrNull(80) { captureVideoFrame(v, v.width, v.height) }?.asImageBitmap()
            }
        }
        LaunchedEffect(listening) {
            if (listening) {
                if (listenP.value < 0.01f && !openedListening[0]) listenShot = capturePicture()
                openedListening[0] = false
                listenP.animateTo(1f, listenIn)
                listenShot = null
            } else {
                openedListening[0] = false
                listenShot = null
                // Still listening, only gone fullscreen or immersive (the lyrics move into the
                // player there): the page steps aside at once, nothing flies home.
                if (ctrl?.audioOnly == true) {
                    listenP.snapTo(0f)
                    return@LaunchedEffect
                }
                // 显示画面: the cover flies home once the picture under the page has drawn its
                // first frame (the video track picks up at the playhead), so it lands on the
                // video, not on the loading cover; a slow line lets it go after a moment.
                if (listenP.value > 0.99f && ctrl != null) {
                    withTimeoutOrNull(ShowVideoWaitMs) { snapshotFlow { ctrl.firstFrameRendered }.first { it } }
                }
                listenP.animateTo(0f, listenOut)
            }
        }
        val listenShown by remember { derivedStateOf { listenP.value > 0.001f } }
        val flight = remember(listenP) {
            ListenFlight(progress = { listenP.value }, from = { handoff.classicRect }, shot = { listenShot })
        }
        // The back's hero: the cover's rect as laid out while no back runs (PageHero.kt).
        listenFlight[0] = flight
        LaunchedEffect(flight) {
            snapshotFlow { flight.coverRect }.collect { r ->
                if (transitions?.backIdle != false) listenRect[0] = r
            }
        }
        // The pinch drives the same flight by hand: the picture shrinks toward the cover with the
        // fingers; a release past the line goes on in (the spring takes it from there), short of
        // it the picture springs back home. Spreading two fingers on the cover runs it back out.
        val pinchScope = androidx.compose.runtime.rememberCoroutineScope()
        val pinchDriver = remember(listenP) { PinchDriver(pinchScope, listenP) }
        val latestCanPinch by rememberUpdatedState(ctrl != null && !listening && !vm.fullscreen && !showImmersive)
        val latestListening by rememberUpdatedState(listening)
        pinchToListen.target = remember(listenP) {
            object : PicturePinchIn {
                var capture: Job? = null

                override fun start(): Boolean {
                    if (!latestCanPinch || listenP.isRunning) return false
                    capture = pinchScope.launch { listenShot = capturePicture() }
                    pinchDriver.begin()
                    return true
                }

                override fun update(progress: Float) = pinchDriver.drive(progress)

                override fun end(commit: Boolean) {
                    if (commit && latestCanPinch) {
                        pinchDriver.finish { ctrl?.listenOnly(true) }
                    } else {
                        // A frame still on its way must not land after the picture is home.
                        capture?.cancel()
                        pinchDriver.finish {
                            listenP.animateTo(0f, listenOut)
                            listenShot = null
                        }
                    }
                }
            }
        }
        flight.pinchOut = remember(listenP) {
            object : PicturePinchIn {
                override fun start(): Boolean {
                    if (!latestListening || listenP.isRunning) return false
                    pinchDriver.begin()
                    return true
                }

                override fun update(progress: Float) = pinchDriver.drive(1f - progress)

                override fun end(commit: Boolean) {
                    if (commit && latestListening) {
                        pinchDriver.finish { ctrl?.listenOnly(false) }
                    } else {
                        pinchDriver.finish { listenP.animateTo(1f, listenIn) }
                    }
                }
            }
        }
        if (ctrl != null && (listening || listenShown)) {
            ListenScreen(vm, ctrl, queue, gate, flight, onTriple = { sheet = VideoSheet.Triple })
        }
        // 继续播放's back menu, out of the picture's back button (top start of the player).
        val statusTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
        val twoPane = isTwoPane()
        com.gpo.pilipala.feature.sources.TrailMenu(
            menu = trailMenu,
            entries = trailEntries,
            anchorX = if (twoPane && !showImmersive) 26.dp else 10.dp,
            anchorY = statusTop + if (twoPane && !showImmersive) 18.dp else 10.dp,
            onPick = { e ->
                (e.target as? Route.Video)?.let { r ->
                    trailMenu.close()
                    com.gpo.pilipala.feature.sources.WatchTrail.take(e.key)
                    switchTo(r, e.positionMs)
                }
            },
            onHome = {
                // The page leaves and its video docks in the bar (the usual flight).
                trailMenu.dismiss()
                nav.pop()
            },
        )
    }

    val d = vm.detail
    val a = vm.actions
    when (sheet) {
        VideoSheet.Coin -> CoinSheet(
            maxCoins = ((if (d?.copyright == 2) 1 else 2) - a.coined).coerceAtLeast(0),
            liked = a.liked,
            onDismiss = { sheet = null },
            onConfirm = { n, like ->
                a.coin(n, like)
                sheet = null
            },
        )
        VideoSheet.Fav -> FavSheet(
            a,
            onDismiss = { sheet = null },
            mixItem = d?.let { com.gpo.pilipala.feature.sources.MixItems.bili(it.bvid, it.title, it.pic, it.ownerName, it.ownerMid, it.duration) },
        )
        VideoSheet.Triple -> TripleSheet(
            liked = a.liked,
            coined = a.coined,
            favored = a.favored,
            coinsLeft = com.gpo.pilipala.core.account.Account.user.value?.coins,
            onDismiss = { sheet = null },
            onConfirm = {
                a.triple()
                sheet = null
            },
        )
        VideoSheet.Pages -> if (d != null) {
            EpisodeListSheet(
                title = "分P",
                groups = listOf(EpisodeGroup("", d.pages.map { it.toEntry() })),
                currentKey = vm.cid,
                onSelect = { e ->
                    d.pages.firstOrNull { it.cid == e.key }?.let(vm::selectPage)
                    sheet = null
                },
                onDismiss = { sheet = null },
            )
        }
        VideoSheet.Season -> d?.season?.let { s ->
            EpisodeListSheet(
                title = s.title,
                groups = s.sections.map { sec -> EpisodeGroup(sec.title, sec.episodes.mapIndexed { i, ep -> ep.toEntry(i) }) },
                currentKey = currentSeasonKey(d, vm.cid),
                onSelect = { e ->
                    s.episodes.firstOrNull { it.cid == e.key }?.let(vm::selectEpisode)
                    sheet = null
                },
                onDismiss = { sheet = null },
            )
        }
        VideoSheet.Queue -> if (queue != null) {
            QueueSheet(queue, playerState, onDismiss = { sheet = null })
        }
        null -> Unit
    }

    if (confirmUnfollow) {
        AlertDialog(
            onDismissRequest = { confirmUnfollow = false },
            title = { Text("取消关注") },
            text = { Text(d?.ownerName.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnfollow = false
                    vm.up.toggle()
                }) { Text("取消关注") }
            },
            dismissButton = { TextButton(onClick = { confirmUnfollow = false }) { Text("保留") } },
        )
    }
}

/** The classic player's empty 16:9 picture while the player is elsewhere (flying, or the other layout). */
@Composable
private fun PlayerSlot(modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(LocalPlayerPictureShape.current)
            .background(Color.Black),
    )
}

private fun VideoPage.toEntry() = EpisodeEntry(key = cid, index = "P$page", title = part, duration = duration)

private fun SeasonEpisode.toEntry(i: Int) = EpisodeEntry(key = cid, index = "${i + 1}", title = title, cover = cover, duration = duration)

private fun currentSeasonKey(d: VideoDetail, cid: Long): Long {
    val eps = d.season?.episodes.orEmpty()
    return eps.firstOrNull { it.cid == cid }?.cid ?: eps.firstOrNull { it.bvid == d.bvid }?.cid ?: -1
}

@Composable
private fun IntroPane(
    vm: VideoDetailViewModel,
    pad: PaddingValues,
    includeRelated: Boolean,
    onSeek: (Long) -> Unit,
    gate: (() -> Unit) -> Unit,
    onSheet: (VideoSheet) -> Unit,
    onFollow: () -> Unit,
    context: Context,
    queue: PlayQueue? = null,
    player: BiliPlayerState? = null,
) {
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    val state = when {
        vm.detail != null -> 2
        vm.error != null -> 1
        else -> 0
    }
    AnimatedContent(state, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "intro") { s ->
        when (s) {
            0 -> LoadingState()
            1 -> ErrorState(vm.error.orEmpty(), onRetry = { vm.load() })
            else -> {
                val d = vm.detail ?: return@AnimatedContent
                // A grid so 相关推荐 runs two columns once the pane is wide enough (tablet
                // portrait); every intro block spans the full line.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(RelatedMinCell),
                    modifier = Modifier.fillMaxSize().readingWidth(),
                    contentPadding = pad,
                ) {
                    full("header") { VideoHeader(d, onSeek, Modifier.padding(top = 14.dp)) }
                    full("up") {
                        Spacer(Modifier.height(12.dp))
                        if (d.staff.size > 1) {
                            StaffRow(d.staff)
                        } else {
                            UpCard(
                                mid = d.ownerMid,
                                name = d.ownerName,
                                face = d.ownerFace,
                                fans = vm.up.fans,
                                following = vm.up.following,
                                busy = vm.up.busy,
                                onFollow = onFollow,
                                mutual = vm.up.mutual,
                            )
                        }
                    }
                    if (vm.tags.isNotEmpty()) {
                        full("tags") {
                            // The chips' 48dp touch targets already add space above them.
                            TagChips(vm.tags)
                        }
                    }
                    full("actions") {
                        Spacer(Modifier.height(16.dp))
                        VideoActions(vm, d, gate, onSheet, context)
                    }
                    if (queue != null && player != null) {
                        full("queue") {
                            Spacer(Modifier.height(20.dp))
                            QueueBlock(queue, player, onShowAll = { onSheet(VideoSheet.Queue) })
                        }
                    }
                    if (d.pages.size > 1) {
                        full("pages") {
                            Spacer(Modifier.height(20.dp))
                            EpisodeRow(
                                title = "分P",
                                entries = d.pages.map { it.toEntry() },
                                currentKey = vm.cid,
                                onSelect = { e -> d.pages.firstOrNull { it.cid == e.key }?.let(vm::selectPage) },
                                onShowAll = { onSheet(VideoSheet.Pages) },
                            )
                        }
                    }
                    d.season?.let { s ->
                        full("season") {
                            Spacer(Modifier.height(20.dp))
                            EpisodeRow(
                                title = "合集",
                                subtitle = s.title,
                                entries = s.episodes.mapIndexed { i, ep -> ep.toEntry(i) },
                                currentKey = currentSeasonKey(d, vm.cid),
                                onSelect = { e -> s.episodes.firstOrNull { it.cid == e.key }?.let(vm::selectEpisode) },
                                onShowAll = { onSheet(VideoSheet.Season) },
                                withCovers = true,
                            )
                        }
                    }
                    if (includeRelated) {
                        full("relatedGap") { Spacer(Modifier.height(20.dp)) }
                        relatedGridItems(vm) { player?.listening == true }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoActions(
    vm: VideoDetailViewModel,
    d: VideoDetail,
    gate: (() -> Unit) -> Unit,
    onSheet: (VideoSheet) -> Unit,
    context: Context,
) {
    val a = vm.actions
    // 名声模式: the group takes plain labels, so a hidden count falls back to the action's name.
    val fame = FameMode.hides(d.isFameSelf)
    fun label(n: Long, fallback: String) = if (n > 0 && !fame) Fmt.count(n) else fallback
    ConnectedActionGroup(
        listOf(
            ActionSpec(
                label = label(a.likeCount, "点赞"),
                icon = PiliSymbols.ThumbUp,
                checkedIcon = PiliSymbols.ThumbUpFilled,
                checked = a.liked,
                onLongClick = { gate { onSheet(VideoSheet.Triple) } },
                onClick = { gate { a.toggleLike() } },
            ),
            ActionSpec(
                label = label(a.coinCount, "投币"),
                icon = PiliSymbols.Coin,
                checkedIcon = PiliSymbols.CoinFilled,
                checked = a.coined > 0,
                onClick = { gate { onSheet(VideoSheet.Coin) } },
            ),
            ActionSpec(
                label = label(a.favCount, "收藏"),
                icon = YoinSymbols.Star,
                checkedIcon = YoinSymbols.StarFilled,
                checked = a.favored,
                onLongClick = { gate { a.quickFav() } },
                onClick = { gate { onSheet(VideoSheet.Fav) } },
            ),
            ActionSpec(
                label = "稍后再看",
                icon = PiliSymbols.WatchLater,
                checked = a.inLater,
                onClick = { gate { a.toggleLater() } },
            ),
            ActionSpec(
                label = label(a.shareCount, "分享"),
                icon = YoinSymbols.Share,
                toggle = false,
                onClick = { shareText(context, d.title, "https://www.bilibili.com/video/${d.bvid}") },
            ),
        ),
        Modifier.padding(horizontal = 16.dp),
        triple = TripleCharge(celebration = a.tripleCelebration),
    )
}

@Composable
private fun CommentsPane(vm: VideoDetailViewModel, pad: PaddingValues, onSeek: (Long) -> Unit) {
    val aid = vm.detail?.aid ?: 0L
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    AnimatedContent(aid, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "comments") { id ->
        if (id == 0L) {
            if (vm.error != null) ErrorState(vm.error.orEmpty(), onRetry = { vm.load() }) else LoadingState()
        } else {
            key(id) {
                ReplyList(
                    oid = id,
                    type = 1,
                    modifier = Modifier.fillMaxSize().readingWidth(),
                    contentPadding = pad,
                    onTimestampClick = onSeek,
                    // Threads open inside the tab: pushing a page would leave the player and pause it.
                    threadsInPlace = true,
                )
            }
        }
    }
}

/** 相关推荐 for the side pane's 相关 tab (the tab names it, so no title). */
private fun LazyListScope.relatedItems(vm: VideoDetailViewModel, listening: () -> Boolean) {
    when {
        vm.relatedLoading && vm.related.isEmpty() -> item("relatedLoading") { LoadingFooter() }
        vm.related.isEmpty() -> item("relatedEmpty") {
            Box(Modifier.fillMaxWidth().height(200.dp)) { EmptyState("暂无相关推荐") }
        }
        else -> items(vm.related, key = { "r_${it.bvid}" }) { RelatedCard(it, listening) }
    }
}

/** A 相关推荐 row; while the page only plays the sound, the video it opens listens too. */
@Composable
private fun RelatedCard(item: VideoItem, listening: () -> Boolean) {
    val nav = LocalNavigator.current
    VideoCardH(item, onClick = { nav.push(item.singleRoute(audioOnly = listening())) })
}

/** Narrowest cell for the 相关推荐 rows: one column on phones, two from tablet portrait. */
private val RelatedMinCell = 360.dp

/** A full-line item of the intro grid. */
private fun LazyGridScope.full(key: String, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)

private fun LazyGridScope.relatedGridItems(vm: VideoDetailViewModel, listening: () -> Boolean) {
    full("relatedTitle") { SectionTitle("相关推荐", Modifier.padding(bottom = 4.dp)) }
    when {
        vm.relatedLoading && vm.related.isEmpty() -> full("relatedLoading") { LoadingFooter() }
        vm.related.isEmpty() -> full("relatedEmpty") {
            Box(Modifier.fillMaxWidth().height(200.dp)) { EmptyState("暂无相关推荐") }
        }
        else -> gridItems(vm.related, key = { "r_${it.bvid}" }) { RelatedCard(it, listening) }
    }
}

/** How long 显示画面 waits for the video's first frame before the cover flies home anyway. */
private const val ShowVideoWaitMs = 700L
