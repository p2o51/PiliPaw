package com.gpo.pilipala.feature.member

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.PiliLargeTopBar
import com.gpo.pilipala.ui.components.VideoItem

/** Single-page list (recent coins / likes) on the shared Pager so states come for free. */
internal class MemberRecentViewModel(load: suspend () -> List<VideoItem>) : ViewModel() {
    val pager = Pager<VideoItem>(viewModelScope, dedupKey = { it.key() }) { Page(load(), hasMore = false) }

    init {
        pager.refresh()
    }
}

@Composable
internal fun MemberRecentList(title: String, emptyLabel: String, vm: MemberRecentViewModel) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        topBar = { PiliLargeTopBar(title = title, scrollBehavior = scrollBehavior) },
    ) { padding ->
        MemberVideoGrid(
            pager = vm.pager,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(top = 4.dp, bottom = navBottom + 16.dp),
            emptyLabel = emptyLabel,
        )
    }
}

@Composable
fun MemberCoinScreen(route: Route.MemberCoin) {
    val vm = viewModel { MemberRecentViewModel { MemberRepo.coinVideos(route.mid) } }
    MemberRecentList(title = "最近投币", emptyLabel = "暂无投币", vm = vm)
}
