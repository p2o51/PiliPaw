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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.FameSelf
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.PiliLargeTopBar
import com.gpo.pilipala.ui.components.VideoItem

internal class MemberArchiveViewModel(val mid: Long) : ViewModel() {
    var order by mutableStateOf(ArchiveOrder.Pubdate)
        private set
    var total by mutableLongStateOf(-1L)
        private set
    val pager = Pager<VideoItem>(viewModelScope, dedupKey = { it.key() }) { cursor ->
        val r = MemberRepo.archive(mid, cursor, order)
        if (cursor == null) total = r.total
        r.page
    }

    init {
        pager.refresh()
    }

    fun changeOrder(o: ArchiveOrder) {
        if (o == order) return
        order = o
        pager.update { emptyList() }
        pager.refresh()
    }
}

@Composable
fun MemberArchiveScreen(route: Route.MemberArchive) {
    val vm = viewModel { MemberArchiveViewModel(route.mid) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            PiliLargeTopBar(
                title = "投稿",
                subtitle = route.name.ifEmpty { null },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        // The user's own uploads count as their own for 名声模式.
        FameSelf(route.mid) {
            MemberVideoGrid(
                pager = vm.pager,
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 4.dp, bottom = navBottom + 16.dp),
                emptyLabel = "暂无投稿",
            ) {
                archiveSortHeader(vm.order, vm.total, vm::changeOrder)
            }
        }
    }
}
