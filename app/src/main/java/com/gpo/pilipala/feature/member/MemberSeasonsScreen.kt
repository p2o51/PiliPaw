package com.gpo.pilipala.feature.member

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.FameSelf
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.PiliLargeTopBar
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols

/** category 0 = 合集 (season), 1 = 系列 (series). */
internal class MemberSeasonsViewModel(val mid: Long, val id: Long, val category: Int) : ViewModel() {
    var reverse by mutableStateOf(false)
        private set
    var total by mutableIntStateOf(-1)
        private set
    val pager = Pager<VideoItem>(viewModelScope, dedupKey = { it.key() }) { cursor ->
        val pn = cursor?.toIntOrNull() ?: 1
        val r = if (category == 1) {
            MemberRepo.seriesArchives(mid, id, pn, reverse)
        } else {
            MemberRepo.seasonArchives(mid, id, pn, reverse)
        }
        if (pn == 1) total = r.total
        r.page
    }

    init {
        pager.refresh()
    }

    fun toggleSort() {
        reverse = !reverse
        pager.update { emptyList() }
        pager.refresh()
    }
}

@Composable
fun MemberSeasonsScreen(route: Route.MemberSeasons) {
    val vm = viewModel { MemberSeasonsViewModel(route.mid, route.seasonId, route.category) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val haptics = LocalHapticFeedback.current
    val flip by animateFloatAsState(if (vm.reverse) 180f else 0f, YoinMotion.defaultSpatialSpec(), label = "sort")
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            PiliLargeTopBar(
                title = route.title.ifEmpty { if (route.category == 1) "系列" else "合集" },
                subtitle = if (vm.total > 0) "${vm.total} 个视频" else null,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            vm.toggleSort()
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            YoinSymbols.Sort,
                            contentDescription = if (vm.reverse) "倒序" else "正序",
                            modifier = Modifier.graphicsLayer { rotationX = flip },
                        )
                    }
                },
            )
        },
    ) { padding ->
        // A collection in the user's own space counts as their own for 名声模式.
        FameSelf(route.mid) {
            MemberVideoGrid(
                pager = vm.pager,
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 4.dp, bottom = navBottom + 16.dp),
            )
        }
    }
}
