package com.gpo.pilipala.feature.dynamics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.yoin.symbols.PiliSymbols
import kotlinx.serialization.json.JsonObject

internal class MemberDynamicsViewModel(mid: Long) : ViewModel() {
    val pager = Pager<JsonObject>(viewModelScope, dedupKey = { it.dynId.ifEmpty { it.hashCode() } }) { cursor ->
        DynamicsApi.space(mid, cursor)
    }

    init {
        pager.refresh()
    }
}

@Composable
internal fun memberDynamicsViewModel(mid: Long): MemberDynamicsViewModel =
    viewModel(key = "member_dynamics_$mid") { MemberDynamicsViewModel(mid) }

/** A user's dynamics feed, embeddable in the member page tab. CONTRACT — signature is fixed. */
@Composable
fun MemberDynamicsList(
    mid: Long,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val vm = memberDynamicsViewModel(mid)
    val pager = vm.pager
    val state = rememberLazyListState()
    LoadMoreEffect(state) { pager.loadMore() }

    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            pager.items.isEmpty() && (pager.initialLoading || pager.isRefreshing) -> item(key = "loading") {
                LoadingState(Modifier.height(320.dp))
            }
            pager.items.isEmpty() && pager.error != null -> item(key = "error") {
                ErrorState(pager.error ?: "", Modifier.height(320.dp), onRetry = pager::refresh)
            }
            pager.items.isEmpty() -> item(key = "empty") {
                EmptyState("暂无动态", Modifier.height(320.dp), icon = PiliSymbols.Dynamic)
            }
        }
        items(pager.items, key = { it.dynId.ifEmpty { it.hashCode().toString() } }) { item ->
            DynamicCard(item, Modifier.readingWidth(720.dp).padding(horizontal = 16.dp))
        }
        if (pager.isLoadingMore) {
            item(key = "more") { LoadingFooter() }
        } else if (pager.error != null && pager.items.isNotEmpty()) {
            item(key = "retry") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = pager::loadMore) { Text("重试") }
                }
            }
        }
    }
}
