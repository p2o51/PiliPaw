package com.gpo.pilipala.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

internal class FavViewModel(val mid: Long) : ViewModel() {
    val isOwner = mid == BiliClient.mid
    val created = Pager(viewModelScope, dedupKey = { it.id }) { cursor ->
        val pn = cursor?.toIntOrNull() ?: 1
        val data = BiliClient.getData(
            "/x/v3/fav/folder/created/list",
            mapOf("up_mid" to mid, "pn" to pn, "ps" to 20),
        ).asObj
        val list = data.arr("list").objects().map { it.toFavFolder() }
        val more = data.bool("has_more")
        Page(list, more, (pn + 1).toString())
    }
    var collected by mutableStateOf<List<FavFolder>>(emptyList())
        private set
    var collectedCount by mutableStateOf(0)
        private set

    init {
        refresh()
    }

    fun refresh() {
        created.refresh()
        viewModelScope.launch {
            runCatching {
                BiliClient.getData(
                    "/x/v3/fav/folder/collected/list",
                    mapOf("up_mid" to mid, "pn" to 1, "ps" to 20, "platform" to "web"),
                ).asObj
            }.onSuccess { d ->
                collected = d.arr("list").objects().map { it.toFavFolder() }
                collectedCount = d.int("count")
            }
        }
    }

    suspend fun save(editing: FavFolder?, title: String, intro: String, private: Boolean) {
        val form = mutableMapOf<String, Any?>(
            "title" to title,
            "intro" to intro,
            "privacy" to if (private) 1 else 0,
            "cover" to (editing?.cover ?: ""),
        )
        if (editing != null) {
            form["media_id"] = editing.id
            BiliClient.postData("/x/v3/fav/folder/edit", form)
        } else {
            BiliClient.postData("/x/v3/fav/folder/add", form)
        }
        created.refresh()
    }

    suspend fun delete(folder: FavFolder) {
        BiliClient.postData("/x/v3/fav/folder/del", mapOf("media_ids" to folder.id, "platform" to "web"))
        created.update { list -> list.filterNot { it.id == folder.id } }
    }
}

@Composable
fun FavScreen(route: Route.Fav) {
    val mid = if (route.mid > 0) route.mid else BiliClient.mid
    val vm = viewModel { FavViewModel(mid) }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    var sheetFolder by remember { mutableStateOf<FavFolder?>(null) }
    var editor by remember { mutableStateOf<FolderEditorState?>(null) }
    var confirmDelete by remember { mutableStateOf<FavFolder?>(null) }
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { vm.created.loadMore() }

    LibraryScaffold(
        title = if (vm.isOwner) "我的收藏" else "收藏",
        floatingActionButton = {
            if (vm.isOwner && BiliClient.isLoggedIn) {
                ExtendedFloatingActionButton(
                    onClick = { editor = FolderEditorState(null) },
                    expanded = !gridState.isScrollInProgress,
                    icon = { Icon(YoinSymbols.Add, null) },
                    text = { Text("新建") },
                )
            }
        },
    ) { padding ->
        PagerBody(vm.created, padding, emptyLabel = "暂无收藏夹", emptyIcon = YoinSymbols.Folder) { listPadding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(168.dp),
                state = gridState,
                modifier = Modifier.librarySeam(gridState, listPadding),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = listPadding.calculateTopPadding(),
                    bottom = listPadding.calculateBottomPadding() + 72.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sectionHeader("h_created", if (vm.isOwner) "我创建的" else "创建的收藏夹")
                items(vm.created.items, key = { "c${it.id}" }) { folder ->
                    FolderCard(
                        folder,
                        modifier = Modifier.animateItem(),
                        onLongClick = if (vm.isOwner) {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                sheetFolder = folder
                            }
                        } else null,
                        onClick = { nav.push(Route.FavDetail(folder.id, folder.title, folder.cover)) },
                    )
                }
                pagerFooter(vm.created)
                if (vm.collected.isNotEmpty()) {
                    sectionHeader("h_collected", if (vm.isOwner) "我收藏的" else "收藏的合集") {
                        TextButton(onClick = { nav.push(Route.Subscription(mid)) }) { Text("全部") }
                    }
                    items(vm.collected, key = { "s${it.id}" }) { folder ->
                        FolderCard(
                            folder,
                            modifier = Modifier.animateItem(),
                            onClick = { nav.push(Route.SubDetail(folder.id, folder.type, folder.title, folder.cover)) },
                        )
                    }
                }
            }
        }
    }

    sheetFolder?.let { folder ->
        ItemActionSheet(
            title = folder.title,
            cover = folder.cover,
            actions = buildList {
                add(SheetAction("编辑", YoinSymbols.Edit) { editor = FolderEditorState(folder) })
                if (!folder.isDefault) {
                    add(SheetAction("删除", YoinSymbols.Delete, destructive = true) { confirmDelete = folder })
                }
            },
            onDismiss = { sheetFolder = null },
        )
    }
    confirmDelete?.let { folder ->
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        ConfirmDialog(
            title = "删除收藏夹",
            text = folder.title,
            confirmLabel = "删除",
            onConfirm = {
                scope.launch {
                    runCatching { vm.delete(folder) }
                        .onSuccess { toast("已删除") }
                        .onFailure { toast(it.friendlyMessage()) }
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
    editor?.let { st ->
        FolderEditorSheet(
            editing = st.folder,
            onDismiss = { editor = null },
            onSave = { title, intro, private ->
                runCatching { vm.save(st.folder, title, intro, private) }
                    .onSuccess { toast(if (st.folder == null) "已创建" else "已保存") }
                    .onFailure { toast(it.friendlyMessage()) }
                    .isSuccess
            },
        )
    }
}

internal data class FolderEditorState(val folder: FavFolder?)

/** Create / edit a folder: title, intro, privacy. */
@Composable
internal fun FolderEditorSheet(
    editing: FavFolder?,
    onDismiss: () -> Unit,
    onSave: suspend (title: String, intro: String, private: Boolean) -> Boolean,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf(editing?.title.orEmpty()) }
    var intro by rememberSaveable { mutableStateOf(editing?.intro.orEmpty()) }
    var private by rememberSaveable { mutableStateOf(editing?.isPrivate ?: false) }
    var saving by remember { mutableStateOf(false) }
    val fieldColors = TextFieldDefaults.colors(
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (editing == null) "新建收藏夹" else "编辑收藏夹",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            TextField(
                value = title,
                onValueChange = { title = it.take(20) },
                label = { Text("名称") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = intro,
                onValueChange = { intro = it.take(200) },
                label = { Text("简介") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PiliSymbols.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("私密", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 16.dp))
                Switch(checked = private, onCheckedChange = { private = it })
            }
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val ok = onSave(title.trim(), intro.trim(), private)
                        saving = false
                        if (ok) {
                            state.hide()
                            onDismiss()
                        }
                    }
                },
                enabled = title.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (editing == null) "创建" else "保存") }
        }
    }
}
