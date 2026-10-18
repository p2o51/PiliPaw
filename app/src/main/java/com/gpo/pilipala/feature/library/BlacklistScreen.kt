package com.gpo.pilipala.feature.library

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

internal class BlacklistViewModel : ViewModel() {
    var total by mutableStateOf(0)
        private set
    val pager = Pager(viewModelScope, dedupKey = { it.mid }) { cursor ->
        val pn = cursor?.toIntOrNull() ?: 1
        val ps = 50
        val data = BiliClient.getData(
            "/x/relation/blacks",
            mapOf("pn" to pn, "ps" to ps, "re_version" to 0, "jsonp" to "jsonp"),
        ).asObj
        total = data.int("total")
        val list = data.arr("list").objects().map { it.toLibUser() }
        Page(list, hasMore = list.size >= ps && pn * ps < total, nextCursor = (pn + 1).toString())
    }

    init {
        pager.refresh()
    }

    suspend fun remove(u: LibUser) {
        relationModify(u.mid, act = 6, reSrc = 116)
        pager.update { l -> l.filterNot { it.mid == u.mid } }
        total = (total - 1).coerceAtLeast(0)
    }
}

@Composable
fun BlacklistScreen() {
    val vm = viewModel { BlacklistViewModel() }
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<LibUser?>(null) }
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { vm.pager.loadMore() }

    LibraryScaffold(title = "黑名单", subtitle = if (vm.total > 0) "${vm.total}人" else null) { padding ->
        PagerBody(vm.pager, padding, emptyLabel = "黑名单为空", emptyIcon = YoinSymbols.Person) { listPadding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(RowGridMinCell),
                state = gridState,
                modifier = Modifier.librarySeam(gridState, listPadding),
                contentPadding = listPadding,
            ) {
                items(vm.pager.items, key = { it.mid }) { u ->
                    UserRow(
                        u,
                        modifier = Modifier.animateItem(),
                        trailing = {
                            FilledTonalButton(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                confirm = u
                            }) { Text("移除") }
                        },
                    )
                }
                pagerFooter(vm.pager)
            }
        }
    }

    confirm?.let { u ->
        ConfirmDialog(
            title = "移出黑名单",
            text = u.name,
            confirmLabel = "移除",
            onConfirm = {
                scope.launch {
                    runCatching { vm.remove(u) }
                        .onSuccess { toast("已移除") }
                        .onFailure { toast(it.friendlyMessage()) }
                }
            },
            onDismiss = { confirm = null },
        )
    }
}
