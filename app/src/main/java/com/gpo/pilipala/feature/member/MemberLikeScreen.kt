package com.gpo.pilipala.feature.member

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.navigation.Route

@Composable
fun MemberLikeScreen(route: Route.MemberLike) {
    val vm = viewModel { MemberRecentViewModel { MemberRepo.likeVideos(route.mid) } }
    MemberRecentList(title = "最近点赞", emptyLabel = "暂无点赞", vm = vm)
}
