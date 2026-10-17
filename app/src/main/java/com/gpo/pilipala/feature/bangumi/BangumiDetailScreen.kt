package com.gpo.pilipala.feature.bangumi

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.rememberPagerState
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
import com.gpo.pilipala.feature.player.BiliVideoPlayer
import com.gpo.pilipala.feature.player.rememberBiliPlayerState
import com.gpo.pilipala.feature.reply.ReplyList
import com.gpo.pilipala.feature.video.ActionSpec
import com.gpo.pilipala.feature.video.CoinSheet
import com.gpo.pilipala.feature.video.ConnectedActionGroup
import com.gpo.pilipala.feature.video.DetailScaffold
import com.gpo.pilipala.feature.video.DetailTab
import com.gpo.pilipala.feature.video.EpisodeEntry
import com.gpo.pilipala.feature.video.EpisodeRow
import com.gpo.pilipala.feature.video.FavSheet
import com.gpo.pilipala.feature.video.SectionTitle
import com.gpo.pilipala.feature.video.rememberLoginGate
import com.gpo.pilipala.feature.video.shareText
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.flow.merge

private enum class BangumiSheet { Coin, Fav, Episodes, Triple }

@Composable
fun BangumiDetailScreen(route: Route.Bangumi) {
    val vm = viewModel { BangumiDetailViewModel(route) }
    CoverTheme(vm.cover) {
        BangumiDetailContent(vm)
    }
}

@Composable
private fun BangumiDetailContent(vm: BangumiDetailViewModel) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val toast = rememberToast()
    val gate = rememberLoginGate()
    val playerState = rememberBiliPlayerState()
    var sheet by remember { mutableStateOf<BangumiSheet?>(null) }

    LaunchedEffect(vm) { merge(vm.messages, vm.actions.messages).collect { toast(it) } }
    DisposableEffect(vm, playerState) {
        onDispose { vm.rememberPosition(playerState.positionMs) }
    }
    LaunchedEffect(playerState, vm.detail?.title) { playerState.artist = vm.detail?.title.orEmpty() }

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
                onCompleted = { if (AppSettings.autoPlayNext.value) vm.playNext() },
            )
        }
    }

    val replyCount = vm.episodeReplies.takeIf { it > 0 }?.let { Fmt.count(it) }
    val openSheet: (BangumiSheet) -> Unit = { sheet = it }
    val onFollow: () -> Unit = { gate { vm.toggleFollow() } }

    DetailScaffold(
        fullscreen = vm.fullscreen,
        onExitFullscreen = { vm.fullscreen = false },
        player = player,
        compactTabs = listOf(DetailTab("简介"), DetailTab("评论", replyCount)),
        compactPagerState = rememberPagerState { 2 },
        compactPage = { page, pad ->
            if (page == 0) {
                BangumiIntroPane(vm, pad, gate, openSheet, onFollow, context)
            } else {
                BangumiComments(vm, pad, seek)
            }
        },
        expandedMain = { pad -> BangumiIntroPane(vm, pad, gate, openSheet, onFollow, context) },
        sideTabs = listOf(DetailTab("评论", replyCount), DetailTab("选集", vm.detail?.episodes?.size?.takeIf { it > 0 }?.toString())),
        sidePage = { page, pad ->
            if (page == 0) {
                BangumiComments(vm, pad, seek)
            } else {
                val d = vm.detail
                if (d == null) {
                    LoadingState()
                } else {
                    EpisodeGrid(
                        groups = episodeGroups(d),
                        currentId = vm.epId,
                        onSelect = vm::selectEpisode,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = pad.calculateBottomPadding()),
                    )
                }
            }
        },
    )

    val d = vm.detail
    val a = vm.actions
    when (sheet) {
        BangumiSheet.Coin -> CoinSheet(
            maxCoins = (2 - a.coined).coerceAtLeast(0),
            liked = a.liked,
            onDismiss = { sheet = null },
            onConfirm = { n, like ->
                a.coin(n, like)
                sheet = null
            },
        )
        BangumiSheet.Fav -> FavSheet(a, onDismiss = { sheet = null })
        BangumiSheet.Triple -> com.gpo.pilipala.feature.video.TripleSheet(
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
        BangumiSheet.Episodes -> if (d != null) {
            EpisodeGridSheet(
                title = "选集",
                groups = episodeGroups(d),
                currentId = vm.epId,
                onSelect = {
                    vm.selectEpisode(it)
                    sheet = null
                },
                onDismiss = { sheet = null },
            )
        }
        null -> Unit
    }
}

private fun episodeGroups(d: BangumiDetail): List<BangumiSection> =
    listOfNotNull(BangumiSection("正片", d.episodes).takeIf { d.episodes.isNotEmpty() }) + d.sections

private fun BangumiEpisode.toEntry() = EpisodeEntry(
    key = id,
    index = title,
    title = longTitle.ifEmpty { showTitle },
    cover = cover,
    duration = duration,
    badge = badge,
)

@Composable
private fun BangumiIntroPane(
    vm: BangumiDetailViewModel,
    pad: PaddingValues,
    gate: (() -> Unit) -> Unit,
    onSheet: (BangumiSheet) -> Unit,
    onFollow: () -> Unit,
    context: Context,
) {
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    val state = when {
        vm.detail != null -> 2
        vm.error != null -> 1
        else -> 0
    }
    AnimatedContent(state, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "bgmIntro") { s ->
        when (s) {
            0 -> LoadingState()
            1 -> ErrorState(vm.error.orEmpty(), onRetry = { vm.load() })
            else -> {
                val d = vm.detail ?: return@AnimatedContent
                LazyColumn(Modifier.fillMaxSize().readingWidth(), contentPadding = pad) {
                    item("header") {
                        BangumiHeader(d, vm.following, vm.followBusy, onFollow, Modifier.padding(top = 16.dp))
                    }
                    item("desc") {
                        Spacer(Modifier.height(16.dp))
                        BangumiDescription(d)
                    }
                    item("actions") {
                        Spacer(Modifier.height(16.dp))
                        BangumiActions(vm, d, gate, onSheet, context)
                    }
                    if (d.episodes.isNotEmpty()) {
                        item("episodes") {
                            Spacer(Modifier.height(20.dp))
                            EpisodeRow(
                                title = "选集",
                                entries = d.episodes.map { it.toEntry() },
                                currentKey = vm.epId,
                                onSelect = { e -> d.episodes.firstOrNull { it.id == e.key }?.let(vm::selectEpisode) },
                                onShowAll = { onSheet(BangumiSheet.Episodes) },
                                subtitle = vm.currentEpisode?.showTitle,
                                withCovers = d.episodes.size <= 60,
                            )
                        }
                    }
                    d.sections.forEachIndexed { i, sec ->
                        item("section$i") {
                            Spacer(Modifier.height(16.dp))
                            EpisodeRow(
                                title = sec.title.ifEmpty { "更多" },
                                entries = sec.episodes.map { it.toEntry() },
                                currentKey = vm.epId,
                                onSelect = { e -> sec.episodes.firstOrNull { it.id == e.key }?.let(vm::selectEpisode) },
                                onShowAll = null,
                                withCovers = true,
                            )
                        }
                    }
                    if (d.episodes.isEmpty() && d.sections.isEmpty()) {
                        item("noEp") { EmptyState("暂无剧集", Modifier.height(200.dp)) }
                    }
                    if (d.seasons.size > 1) {
                        item("seasons") {
                            Spacer(Modifier.height(20.dp))
                            SectionTitle("系列", trailing = "${d.seasons.size}")
                            Spacer(Modifier.height(8.dp))
                            SeasonSwitcher(d.seasons, d.seasonId, vm::switchSeason)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BangumiActions(
    vm: BangumiDetailViewModel,
    d: BangumiDetail,
    gate: (() -> Unit) -> Unit,
    onSheet: (BangumiSheet) -> Unit,
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
                onLongClick = { gate { onSheet(BangumiSheet.Triple) } },
                onClick = { gate { a.toggleLike() } },
            ),
            ActionSpec(
                label = label(a.coinCount, "投币"),
                icon = PiliSymbols.Coin,
                checkedIcon = PiliSymbols.CoinFilled,
                checked = a.coined > 0,
                onClick = { gate { onSheet(BangumiSheet.Coin) } },
            ),
            ActionSpec(
                label = label(a.favCount, "收藏"),
                icon = YoinSymbols.Star,
                checkedIcon = YoinSymbols.StarFilled,
                checked = a.favored,
                onLongClick = { gate { a.quickFav() } },
                onClick = { gate { onSheet(BangumiSheet.Fav) } },
            ),
            ActionSpec(
                label = label(a.shareCount, "分享"),
                icon = YoinSymbols.Share,
                toggle = false,
                onClick = {
                    val ep = vm.currentEpisode
                    val url = if (ep != null) "https://www.bilibili.com/bangumi/play/ep${ep.id}" else "https://www.bilibili.com/bangumi/play/ss${d.seasonId}"
                    shareText(context, listOfNotNull(d.title, ep?.showTitle).joinToString(" "), url)
                },
            ),
        ),
        Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun BangumiComments(vm: BangumiDetailViewModel, pad: PaddingValues, onSeek: (Long) -> Unit) {
    val aid = vm.currentEpisode?.aid ?: 0L
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    AnimatedContent(aid, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "bgmComments") { id ->
        when {
            id != 0L -> key(id) {
                ReplyList(
                    oid = id,
                    type = 1,
                    modifier = Modifier.fillMaxSize().readingWidth(),
                    contentPadding = pad,
                    onTimestampClick = onSeek,
                )
            }
            vm.error != null -> ErrorState(vm.error.orEmpty(), onRetry = { vm.load() })
            vm.detail != null -> EmptyState("暂无评论")
            else -> LoadingState()
        }
    }
}
