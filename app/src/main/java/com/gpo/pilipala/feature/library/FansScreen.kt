package com.gpo.pilipala.feature.library

import com.gpo.pilipala.ui.components.FameMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager

internal class FansViewModel(val mid: Long) : ViewModel() {
    val isOwner = mid > 0 && mid == BiliClient.mid
    var total by mutableStateOf(0)
        private set
    val relation = RelationState(followsBack = isOwner)
    val pager = Pager(viewModelScope, dedupKey = { it.mid }) { cursor ->
        val pn = cursor?.toIntOrNull() ?: 1
        val ps = 20
        val data = BiliClient.getData(
            "/x/relation/fans",
            mapOf("vmid" to mid, "pn" to pn, "ps" to ps, "order" to "desc"),
        ).asObj
        total = data.int("total")
        val list = data.arr("list").objects().map { it.toLibUser() }
        Page(list, hasMore = list.size >= ps && pn * ps < total, nextCursor = (pn + 1).toString())
    }

    init {
        pager.refresh()
    }
}

@Composable
fun FansScreen(route: Route.Fans) {
    val mid = if (route.mid > 0) route.mid else BiliClient.mid
    val vm = viewModel { FansViewModel(mid) }
    val title = when {
        vm.isOwner -> "我的粉丝"
        route.name.isNotEmpty() -> "${route.name}的粉丝"
        else -> "粉丝"
    }
    // The page every hidden 粉丝 line leads to: its total is that same number.
    val showTotal = vm.total > 0 && !FameMode.hides(FameMode.isSelf(mid))
    LibraryScaffold(title = title, subtitle = if (showTotal) "${vm.total}个粉丝" else null) { padding ->
        UserList(
            vm.pager,
            padding,
            vm.relation,
            showToggle = vm.isOwner,
            emptyLabel = "暂无粉丝",
            unfollowedLabel = "回关",
        )
    }
}
