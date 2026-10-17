package com.gpo.pilipala.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

internal data class FollowTag(val id: Long?, val name: String, val count: Int)

/** Follow state the user changed on this page (mid → following), shared by follow/fans lists. */
internal class RelationState {
    val overrides = mutableStateMapOf<Long, Boolean>()
    fun isFollowing(u: LibUser): Boolean = overrides[u.mid] ?: (u.attribute == 2 || u.attribute == 6)

    suspend fun toggle(u: LibUser, follow: Boolean) {
        relationModify(u.mid, if (follow) 1 else 2)
        overrides[u.mid] = follow
    }
}

internal class FollowViewModel(val mid: Long) : ViewModel() {
    val isOwner = mid > 0 && mid == BiliClient.mid
    var tags by mutableStateOf(listOf(FollowTag(null, "全部", 0)))
        private set
    var total by mutableStateOf(0)
        private set
    val relation = RelationState()
    private val pagers = mutableMapOf<Long?, Pager<LibUser>>()

    init {
        if (isOwner) {
            viewModelScope.launch {
                runCatching { BiliClient.getData("/x/relation/tags").asArr }
                    .onSuccess { arr ->
                        val list = arr?.objects().orEmpty()
                            .map { FollowTag(it.long("tagid"), it.str("name"), it.int("count")) }
                            .filter { it.count > 0 }
                        tags = listOf(FollowTag(null, "全部", 0)) + list
                    }
            }
        }
    }

    fun pager(tag: Long?): Pager<LibUser> = pagers.getOrPut(tag) {
        Pager(viewModelScope, dedupKey = { it.mid }) { cursor ->
            val pn = cursor?.toIntOrNull() ?: 1
            val ps = 20
            if (tag == null) {
                val data = BiliClient.getData(
                    "/x/relation/followings",
                    mapOf("vmid" to mid, "pn" to pn, "ps" to ps, "order" to "desc", "order_type" to "attention"),
                ).asObj
                total = data.int("total")
                val list = data.arr("list").objects().map { it.toLibUser() }
                Page(list, hasMore = list.size >= ps && pn * ps < total, nextCursor = (pn + 1).toString())
            } else {
                val arr = BiliClient.getData(
                    "/x/relation/tag",
                    mapOf("mid" to mid, "tagid" to tag, "pn" to pn, "ps" to ps),
                ).asArr
                val list = arr?.objects().orEmpty().map { it.toLibUser() }
                Page(list, hasMore = list.size >= ps, nextCursor = (pn + 1).toString())
            }
        }.also { it.refresh() }
    }
}

@Composable
fun FollowScreen(route: Route.Follow) {
    val mid = if (route.mid > 0) route.mid else BiliClient.mid
    val vm = viewModel { FollowViewModel(mid) }
    val title = when {
        vm.isOwner -> "我的关注"
        route.name.isNotEmpty() -> "${route.name}的关注"
        else -> "关注"
    }
    LibraryScaffold(
        title = title,
        subtitle = if (vm.total > 0) "${vm.total}个关注" else null,
    ) { padding ->
        val tags = vm.tags
        val enterFade = YoinMotion.defaultEffectsSpec<Float>()
        val exitFade = YoinMotion.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = tags.size > 1,
            transitionSpec = { fadeIn(enterFade) togetherWith fadeOut(exitFade) },
            label = "followTabs",
        ) { tabbed ->
        if (!tabbed) {
            UserList(vm.pager(null), padding, vm.relation, showToggle = vm.isOwner, emptyLabel = "暂无关注")
        } else {
            val pagerState = rememberPagerState { tags.size }
            val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceAtMost(tags.lastIndex),
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    divider = {},
                ) {
                    tags.forEachIndexed { i, t ->
                        Tab(
                            selected = pagerState.currentPage == i,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            text = { Text(t.name, maxLines = 1) },
                        )
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val tag = tags.getOrNull(page) ?: return@HorizontalPager
                    UserList(
                        vm.pager(tag.id),
                        PaddingValues(bottom = padding.calculateBottomPadding()),
                        vm.relation,
                        showToggle = true,
                        emptyLabel = "暂无关注",
                    )
                }
            }
        }
        }
    }
}

/** Paged grid of [UserRow]s with an optional follow toggle. */
@Composable
internal fun UserList(
    pager: Pager<LibUser>,
    padding: PaddingValues,
    relation: RelationState,
    showToggle: Boolean,
    emptyLabel: String,
    unfollowedLabel: String = "关注",
    requireLogin: Boolean = false,
) {
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { pager.loadMore() }
    PagerBody(pager, padding, emptyLabel = emptyLabel, emptyIcon = YoinSymbols.Person, requireLogin = requireLogin) { listPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(RowGridMinCell),
            state = gridState,
            contentPadding = listPadding,
        ) {
            items(pager.items, key = { it.mid }) { u ->
                UserRow(
                    u,
                    modifier = Modifier.animateItem(),
                    badge = if (u.attribute == 6 && relation.overrides[u.mid] != false) "互关" else null,
                    trailing = if (showToggle && BiliClient.isLoggedIn && u.mid != BiliClient.mid) {
                        {
                            val following = relation.isFollowing(u)
                            ToggleButton(
                                checked = following,
                                onCheckedChange = { follow ->
                                    haptics.performHapticFeedback(if (follow) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                    scope.launch {
                                        runCatching { relation.toggle(u, follow) }
                                            .onSuccess { toast(if (follow) "已关注" else "已取消关注") }
                                            .onFailure { toast(it.friendlyMessage()) }
                                    }
                                },
                                shapes = ToggleButtonDefaults.shapes(),
                                colors = ToggleButtonDefaults.toggleButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    checkedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                Text(if (following) "已关注" else unfollowedLabel, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else null,
                )
            }
            pagerFooter(pager)
        }
    }
}
