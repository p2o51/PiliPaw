package com.gpo.pilipala.feature.rank

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.home.CollapsingHeader
import com.gpo.pilipala.feature.home.SegmentTabs
import com.gpo.pilipala.feature.home.mainTabBottomInset
import com.gpo.pilipala.feature.home.rememberHeaderScrollBehavior
import com.gpo.pilipala.feature.home.toArchiveItem
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.DurationChip
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Zones as in Flutter's rank_type.dart (rid per tab). */
private data class RankZone(val label: String, val rid: Int)

private val RankZones = listOf(
    RankZone("全站", 0),
    RankZone("动画", 1005),
    RankZone("音乐", 1003),
    RankZone("舞蹈", 1004),
    RankZone("游戏", 1008),
    RankZone("知识", 1010),
    RankZone("科技", 1012),
    RankZone("运动", 1018),
    RankZone("汽车", 1013),
    RankZone("美食", 1020),
    RankZone("动物圈", 1024),
    RankZone("鬼畜", 1007),
    RankZone("时尚", 1014),
    RankZone("娱乐", 1002),
    RankZone("影视", 1001),
)

private val CoverShape = ContinuousRoundedCornerShape(12.dp)

@Composable
fun RankScreen() {
    val pagerState = rememberPagerState { RankZones.size }
    val scope = rememberCoroutineScope()
    val behavior = rememberHeaderScrollBehavior()
    val pageSpec = YoinMotion.defaultSpatialSpec<Float>()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .nestedScroll(behavior.nestedScrollConnection),
    ) {
        CollapsingHeader(behavior) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("排行榜", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        SegmentTabs(
            labels = RankZones.map { it.label },
            selected = pagerState.targetPage,
            onSelect = { i -> scope.launch { pagerState.animateScrollToPage(i, animationSpec = pageSpec) } },
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            key = { RankZones[it].rid },
        ) { page ->
            RankZonePage(RankZones[page].rid)
        }
    }
}

// ── Data ────────────────────────────────────────────────────────────────────

private class RankZoneViewModel(private val rid: Int) : ViewModel() {
    var items by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages
    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            if (items.isEmpty()) loading = true else refreshing = true
            error = null
            try {
                val data = BiliClient.getData("/x/web-interface/ranking/v2", mapOf("rid" to rid, "type" to "all")).asObj
                items = data.arr("list").objects().map { it.toArchiveItem() }.filter { it.bvid.isNotEmpty() }.distinctBy { it.bvid }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (items.isEmpty()) error = e.friendlyMessage() else _messages.tryEmit(e.friendlyMessage())
            } finally {
                loading = false
                refreshing = false
            }
        }
    }
}

private enum class RankPhase { Loading, Error, Empty, Content }

@Composable
private fun RankZonePage(rid: Int) {
    val vm: RankZoneViewModel = viewModel(key = "rank_zone_$rid") { RankZoneViewModel(rid) }
    val toast = rememberToast()
    LaunchedEffect(vm) { vm.messages.collect { toast(it) } }
    val state = rememberLazyGridState()
    val bottom = mainTabBottomInset()
    val expanded = widthClass() == WidthClass.Expanded
    val compact = widthClass() == WidthClass.Compact
    val enterSpec = YoinMotion.defaultEffectsSpec<Float>()
    val exitSpec = YoinMotion.fastEffectsSpec<Float>()
    val phase = when {
        vm.items.isEmpty() && vm.loading -> RankPhase.Loading
        vm.items.isEmpty() && vm.error != null -> RankPhase.Error
        vm.items.isEmpty() -> RankPhase.Empty
        else -> RankPhase.Content
    }
    AnimatedContent(
        targetState = phase,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { fadeIn(enterSpec).togetherWith(fadeOut(exitSpec)) },
        label = "rankPhase",
    ) { p ->
        when (p) {
            RankPhase.Loading -> LoadingState(Modifier.padding(bottom = bottom))
            RankPhase.Error -> ErrorState(vm.error.orEmpty(), Modifier.padding(bottom = bottom), onRetry = vm::load)
            RankPhase.Empty -> EmptyState(modifier = Modifier.padding(bottom = bottom))
            RankPhase.Content -> RefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::load, modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (expanded) 2 else 1),
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottom + 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(vm.items, key = { _, it -> it.bvid }) { index, item ->
                        RankRow(rank = index + 1, item = item, coverWidth = if (compact) 136.dp else 168.dp)
                    }
                }
            }
        }
    }
}

// ── Row ─────────────────────────────────────────────────────────────────────

/**
 * One ranked video. Hierarchy comes from type: the rank is the largest glyph
 * on the row; the top three sit on a filled surfaceContainer card with their
 * number inside a coloured MaterialShapes block (primary, primaryContainer,
 * tertiaryContainer). The rest keep a quiet outline-coloured numeral.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RankRow(rank: Int, item: VideoItem, coverWidth: Dp) {
    val nav = LocalNavigator.current
    val top = rank <= 3
    Row(
        Modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .background(if (top) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .clickable {
                nav.push(
                    item.target ?: Route.Video(bvid = item.bvid, aid = item.aid, cid = item.cid, cover = item.cover, title = item.title),
                )
            }
            .padding(horizontal = if (top) 12.dp else 4.dp, vertical = if (top) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankNumber(rank)
        Box(Modifier.width(coverWidth).aspectRatio(16f / 10f)) {
            NetImage(item.cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = coverWidth)
            if (item.duration > 0) {
                DurationChip(Fmt.duration(item.duration), Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
        }
        Column(
            Modifier.weight(1f).height(coverWidth * 10f / 16f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                item.title,
                style = if (top) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (item.ownerName.isNotEmpty()) {
                    Text(
                        item.ownerName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.views >= 0) Stat(PiliSymbols.Views, Fmt.count(item.views))
                    if (item.danmaku >= 0) Stat(PiliSymbols.Danmaku, Fmt.count(item.danmaku))
                    if (item.likes >= 0) Stat(PiliSymbols.ThumbUp, Fmt.count(item.likes))
                }
            }
        }
    }
}

@Composable
private fun RankNumber(rank: Int) {
    if (rank <= 3) {
        val (shape, container, content) = when (rank) {
            1 -> Triple(MaterialShapes.Cookie9Sided.toShape(), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
            2 -> Triple(MaterialShapes.Cookie7Sided.toShape(), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            else -> Triple(MaterialShapes.Cookie6Sided.toShape(), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        }
        Box(
            Modifier.size(44.dp).background(container, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                rank.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = content,
            )
        }
    } else {
        Text(
            rank.toString(),
            modifier = Modifier.width(44.dp),
            style = if (rank < 10) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
