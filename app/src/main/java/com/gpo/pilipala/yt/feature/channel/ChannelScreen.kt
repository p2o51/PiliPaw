package com.gpo.pilipala.yt.feature.channel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.feature.home.SegmentTabs
import com.gpo.pilipala.feature.library.RowGridMinCell
import com.gpo.pilipala.feature.sources.toCard
import com.gpo.pilipala.feature.video.ExpandableText
import com.gpo.pilipala.feature.video.shareText
import com.gpo.pilipala.ui.components.BackButton
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.ExpressivePageBackground
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.pilipala.yt.feature.player.SubscribeButton
import com.gpo.pilipala.yt.feature.player.miniPlayerInset
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.yt.ui.components.LoadMoreEffect
import com.gpo.pilipala.yt.ui.components.Pager
import com.gpo.pilipala.yt.ui.components.friendlyMessage
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.linkhandler.ChannelTabs

class ChannelViewModel(private val url: String) : ViewModel() {
    var info by mutableStateOf<ChannelInfo?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** One ChannelInfo request feeds both the header and the first page of uploads. */
    private var request: Deferred<ChannelInfo>? = null

    val videos = Pager<VideoItem>(viewModelScope, dedupKey = { it.url }) { cursor ->
        if (cursor == null) Yt.channelFirstPage(fetch().await()) else Yt.channelVideos(url, cursor)
    }

    /** The channel's other video tabs (Shorts, 直播), each paged on its own; 视频 is [videos]. */
    val tabs: List<ChannelTab>
        get() {
            val info = info ?: return emptyList()
            return info.tabs.mapNotNull { h ->
                val kind = h.contentFilters.firstOrNull()?.name ?: return@mapNotNull null
                val label = when (kind) {
                    ChannelTabs.SHORTS -> if (AppSettings.hideShorts.value) null else "Shorts"
                    ChannelTabs.LIVESTREAMS -> "直播"
                    else -> null
                } ?: return@mapNotNull null
                tabPagers.getOrPut(kind) {
                    ChannelTab(label, Pager(viewModelScope, dedupKey = { it.url }) { cursor -> Yt.channelTab(h, cursor) })
                }
            }
        }
    private val tabPagers = LinkedHashMap<String, ChannelTab>()

    init { load() }

    private fun fetch(): Deferred<ChannelInfo> =
        request?.takeUnless { it.isCompleted && it.getCompletionExceptionOrNull() != null }
            ?: viewModelScope.async { Yt.channel(url) }.also { request = it }

    fun load() {
        error = null
        if (request?.isCompleted == true && request?.getCompletionExceptionOrNull() != null) request = null
        val r = fetch()
        viewModelScope.launch {
            runCatching { r.await() }
                .onSuccess { info = it }
                .onFailure { error = it.friendlyMessage() }
        }
        videos.refresh()
    }
}

/** One of a channel's tabs: its label and its own pager. */
class ChannelTab(val label: String, val pager: Pager<VideoItem>)

/** How far the banner reaches under the bar, PiliPaw's UP space. */
private val BannerReach = 88.dp
private val AvatarSize = 96.dp

/** The banner's alpha: whole at the top, gone at the bottom, so it dissolves into the wash. */
private val BannerFade = Brush.verticalGradient(0.35f to Color.Black, 1f to Color.Transparent)

/**
 * A channel, laid out as PiliPaw's UP space: the banner full-bleed under the status bar,
 * dissolving into the page wash, the cookie avatar over it, name and subscribers, the 订阅
 * toggle; then 视频 / Shorts / 直播 as segment tabs over row cards. The bar fills in and shows
 * the avatar and name once the header has scrolled away. Colours come from the avatar.
 */
@Composable
fun ChannelScreen(route: Route.Channel) {
    val vm = viewModel(key = route.url) { ChannelViewModel(route.url) }
    val info = vm.info
    val avatar = info?.avatarUrl ?: route.avatar
    val name = info?.name ?: route.name
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabs = vm.tabs
    var selected by rememberSaveable(route.url) { mutableIntStateOf(0) }
    val pager = if (selected == 0) vm.videos else tabs.getOrNull(selected - 1)?.pager ?: vm.videos
    LaunchedEffect(pager) { if (pager.initialLoading && !pager.isRefreshing) pager.refresh() }
    val grid = rememberLazyGridState()
    LoadMoreEffect(grid) { pager.loadMore() }
    // 0 at the top → 1 once the header is gone: the bar fills and shows who this is.
    val density = LocalDensity.current
    val collapse by remember {
        derivedStateOf {
            if (grid.firstVisibleItemIndex > 0) 1f
            else (grid.firstVisibleItemScrollOffset / with(density) { BannerReach.toPx() }).coerceIn(0f, 1f)
        }
    }
    CoverTheme(avatar) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            when {
                vm.error != null && info == null -> ErrorState(vm.error.orEmpty(), Modifier.padding(top = topInset + 64.dp), onRetry = vm::load)
                info == null && vm.videos.initialLoading -> LoadingState()
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(RowGridMinCell),
                    state = grid,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = navBottom + miniPlayerInset() + 24.dp),
                ) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Header(route, info, topInset) }
                    if (tabs.isNotEmpty()) {
                        item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) {
                            SegmentTabs(
                                labels = listOf("视频") + tabs.map { it.label },
                                selected = selected,
                                onSelect = { selected = it },
                            )
                        }
                    }
                    when {
                        pager.items.isEmpty() && (pager.initialLoading || pager.isRefreshing) -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().height(StateHeight), contentAlignment = Alignment.Center) { LoadingIndicator() }
                        }
                        pager.items.isEmpty() && pager.error != null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                            ErrorState(pager.error.orEmpty(), Modifier.height(StateHeight), onRetry = pager::refresh)
                        }
                        pager.items.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState("暂无视频", Modifier.height(StateHeight), icon = YoinSymbols.Video)
                        }
                        else -> items(pager.items, key = { "v_${selected}_${it.url}" }) { v ->
                            VideoCardH(v.toCard(), Modifier.animateItem(), onClick = { Player.play(v.url, v) })
                        }
                    }
                    if (pager.items.isNotEmpty()) item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { Footer(pager) }
                }
            }
            TopBar(name, avatar, route.url, collapse, topInset)
        }
    }
}

private val StateHeight = 360.dp

/** Under the last card: loading, a failed page with 重试, or 没有更多了. */
@Composable
private fun Footer(pager: Pager<VideoItem>) {
    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
        when {
            pager.isLoadingMore -> LoadingFooter()
            pager.error != null -> FilledTonalButton(onClick = pager::loadMore) { Text("重试") }
            !pager.hasMore -> Text("没有更多了", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** PiliPaw's UP-space bar: back, then (collapsed) the avatar and name fading in; 分享 at the end. */
@Composable
private fun TopBar(name: String, avatar: String?, url: String, collapse: Float, topInset: Dp) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(surface.copy(alpha = collapse)) }
            .padding(top = topInset)
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton { nav.pop() }
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.weight(1f).graphicsLayer { alpha = collapse },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetImage(avatar, Modifier.size(32.dp), shape = MaterialShapes.Cookie9Sided.toShape(), thumbWidth = 64.dp)
            Spacer(Modifier.width(10.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { shareText(context, name, url) }, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Share, "分享")
        }
    }
}

@Composable
private fun Header(route: Route.Channel, info: ChannelInfo?, topInset: Dp) {
    val channel = ChannelItem(
        // The canonical /channel/UC… once loaded (a link may be an @handle): what subscriptions key on.
        url = info?.url?.takeIf { it.contains("/channel/") } ?: route.url,
        name = info?.name ?: route.name,
        avatar = info?.avatarUrl ?: route.avatar,
        subscribers = info?.subscriberCount ?: -1,
        videos = -1,
        verified = info?.isVerified ?: false,
    )
    val scheme = MaterialTheme.colorScheme
    val bannerHeight = topInset + BannerReach
    val banner = info?.bannerUrl.orEmpty()
    ExpressivePageBackground(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            // The banner dissolving into the page wash.
            Box(Modifier.fillMaxWidth().height(bannerHeight + AvatarSize / 2)) {
                AnimatedVisibility(
                    visible = banner.isNotEmpty(),
                    enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                    exit = fadeOut(YoinMotion.defaultEffectsSpec()),
                    modifier = Modifier.matchParentSize(),
                ) {
                    NetImage(
                        banner,
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
                    .padding(top = bannerHeight - AvatarSize / 2)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    NetImage(channel.avatar, Modifier.size(AvatarSize), shape = MaterialShapes.Cookie9Sided.toShape(), thumbWidth = AvatarSize * 2)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.padding(bottom = 8.dp)) { SubscribeButton(channel) }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.verified) Tag("认证", container = scheme.primaryContainer, content = scheme.onPrimaryContainer)
                }
                info?.description?.takeIf { it.isNotBlank() }?.let { d ->
                    Spacer(Modifier.height(8.dp))
                    ExpandableText(
                        text = AnnotatedString(d.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                        collapsedLines = 2,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.clip(YoinContainerShapes.ListRow),
                    )
                }
                if (channel.subscribers >= 0) {
                    Spacer(Modifier.height(14.dp))
                    StatBlock(channel.subscribers, "订阅者")
                }
            }
        }
    }
}

/** A number over its label (PiliPaw's UP-space stat block). */
@Composable
private fun StatBlock(value: Long, label: String) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FameCount(Fmt.count(value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, revealable = false)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
