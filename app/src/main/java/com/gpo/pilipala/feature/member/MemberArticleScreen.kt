package com.gpo.pilipala.feature.member

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.FameSelf
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.PiliLargeTopBar
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.yoin.symbols.PiliSymbols

internal class MemberArticleViewModel(val mid: Long) : ViewModel() {
    val pager = Pager<MemberArticle>(viewModelScope, dedupKey = { it.id }) { cursor ->
        MemberRepo.articles(mid, cursor?.toIntOrNull() ?: 1)
    }

    init {
        pager.refresh()
    }
}

@Composable
fun MemberArticleScreen(route: Route.MemberArticle) {
    val vm = viewModel { MemberArticleViewModel(route.mid) }
    val pager = vm.pager
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { pager.loadMore() }
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0),
        topBar = { PiliLargeTopBar(title = "专栏", scrollBehavior = scrollBehavior) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            RefreshBox(isRefreshing = pager.isRefreshing && pager.items.isNotEmpty(), onRefresh = pager::refresh) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(400.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().seamViewport(gridState),
                    contentPadding = PaddingValues(top = 4.dp, bottom = navBottom + 16.dp),
                ) {
                    val items = pager.items
                    when {
                        items.isEmpty() && (pager.initialLoading || pager.isRefreshing) -> fullSpan("loading") {
                            LoadingState(Modifier.height(360.dp))
                        }
                        items.isEmpty() && pager.error != null -> fullSpan("error") {
                            StateBox(pager.error.orEmpty(), onRetry = pager::refresh)
                        }
                        items.isEmpty() -> fullSpan("empty") {
                            EmptyState("暂无专栏", Modifier.height(360.dp), icon = PiliSymbols.Article)
                        }
                        else -> {
                            // The user's own articles count as their own for 名声模式.
                            items(items, key = { it.id }) { FameSelf(route.mid) { ArticleRow(it) } }
                            pagerFooter(pager)
                        }
                    }
                }
            }
        }
    }
}
