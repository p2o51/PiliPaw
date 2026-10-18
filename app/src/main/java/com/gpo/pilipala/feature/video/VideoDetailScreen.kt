package com.gpo.pilipala.feature.video

import android.content.Context
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
import com.gpo.pilipala.feature.player.rememberBiliPlayerState
import com.gpo.pilipala.feature.reply.ReplyList
import com.gpo.pilipala.feature.video.portrait.PortraitVideoLayout
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.VideoCardH
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
        VideoDetailContent(vm)
    }
}

@Composable
private fun VideoDetailContent(vm: VideoDetailViewModel) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val toast = rememberToast()
    val gate = rememberLoginGate()
    val playerState = rememberBiliPlayerState()
    var sheet by remember { mutableStateOf<VideoSheet?>(null) }
    var confirmUnfollow by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { merge(vm.messages, vm.actions.messages).collect { toast(it) } }
    LaunchedEffect(vm.redirect) { vm.redirect?.let { nav.replace(it) } }
    // Leaving for another page disposes the player; come back where we were.
    DisposableEffect(vm, playerState) {
        onDispose { vm.rememberPosition(playerState.positionMs) }
    }
    // The queue (收藏夹 / 稍后再看 as a playlist) drives the player: completion, notification
    // previous / next and the play mode. Attached before the player's first load runs.
    val queue = vm.queue
    DisposableEffect(playerState, queue) {
        playerState.attachQueue(queue)
        onDispose { playerState.attachQueue(null) }
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
    val player = remember(vm, playerState) {
        movableContentOf { m: Modifier ->
            BiliVideoPlayer(
                target = vm.target,
                state = playerState,
                fullscreen = vm.fullscreen,
                onFullscreenChange = { vm.fullscreen = it },
                onBack = { if (vm.fullscreen) vm.fullscreen = false else nav.pop() },
                modifier = m,
                // With a queue the player steps it itself; pages / season only without one.
                onCompleted = { if (AppSettings.autoPlayNext.value && vm.queue == null) vm.playNext() },
            )
        }
    }

    val replyCount = vm.detail?.reply?.takeIf { it > 0 }?.let { Fmt.count(it) }
    val openSheet: (VideoSheet) -> Unit = { sheet = it }
    val onFollow: () -> Unit = {
        gate { if (vm.up.following) confirmUnfollow = true else vm.up.toggle() }
    }

    // Portrait videos: the Douyin-style immersive pager (feature/video/portrait); the choice is
    // made once the detail arrives and then sticks for the page.
    if (vm.immersive == true) {
        PortraitVideoLayout(
            vm = vm,
            playerState = playerState,
            gate = gate,
            onCoin = { gate { sheet = VideoSheet.Coin } },
            onFav = { gate { sheet = VideoSheet.Fav } },
            onTriple = { gate { sheet = VideoSheet.Triple } },
            onFollow = onFollow,
        )
    } else DetailScaffold(
        fullscreen = vm.fullscreen,
        onExitFullscreen = { vm.fullscreen = false },
        player = player,
        compactTabs = listOf(DetailTab("简介"), DetailTab("评论", replyCount)),
        compactPagerState = rememberPagerState { 2 },
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
                    relatedItems(vm)
                }
            }
        },
    )

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
        VideoSheet.Fav -> FavSheet(a, onDismiss = { sheet = null })
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
                        relatedGridItems(vm)
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
    fun label(n: Long, fallback: String) = if (n > 0) Fmt.count(n) else fallback
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
                )
            }
        }
    }
}

/** 相关推荐 for the side pane's 相关 tab (the tab names it, so no title). */
private fun LazyListScope.relatedItems(vm: VideoDetailViewModel) {
    when {
        vm.relatedLoading && vm.related.isEmpty() -> item("relatedLoading") { LoadingFooter() }
        vm.related.isEmpty() -> item("relatedEmpty") {
            Box(Modifier.fillMaxWidth().height(200.dp)) { EmptyState("暂无相关推荐") }
        }
        else -> items(vm.related, key = { "r_${it.bvid}" }) { VideoCardH(it) }
    }
}

/** Narrowest cell for the 相关推荐 rows: one column on phones, two from tablet portrait. */
private val RelatedMinCell = 360.dp

/** A full-line item of the intro grid. */
private fun LazyGridScope.full(key: String, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)

private fun LazyGridScope.relatedGridItems(vm: VideoDetailViewModel) {
    full("relatedTitle") { SectionTitle("相关推荐", Modifier.padding(bottom = 4.dp)) }
    when {
        vm.relatedLoading && vm.related.isEmpty() -> full("relatedLoading") { LoadingFooter() }
        vm.related.isEmpty() -> full("relatedEmpty") {
            Box(Modifier.fillMaxWidth().height(200.dp)) { EmptyState("暂无相关推荐") }
        }
        else -> gridItems(vm.related, key = { "r_${it.bvid}" }) { VideoCardH(it) }
    }
}
