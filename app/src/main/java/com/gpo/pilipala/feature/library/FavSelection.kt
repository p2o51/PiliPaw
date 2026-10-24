package com.gpo.pilipala.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.yoin.symbols.YoinSymbols

@Immutable
internal class MyFolder(val id: Long, val title: String, val count: Int)

internal object FavBatchApi {
    suspend fun myFolders(): List<MyFolder> =
        BiliClient.getData("/x/v3/fav/folder/created/list-all", mapOf("up_mid" to BiliClient.mid, "type" to 2)).asObj
            .arr("list").objects().map { MyFolder(it.long("id"), it.str("title"), it.int("media_count")) }

    /** 移动到 / 复制到: [resources] as "id:type" from [src] into [dst]. */
    suspend fun transfer(resources: List<String>, src: Long, dst: Long, move: Boolean) {
        BiliClient.postData(
            if (move) "/x/v3/fav/resource/move" else "/x/v3/fav/resource/copy",
            mapOf(
                "resources" to resources.joinToString(","),
                "src_media_id" to src,
                "tar_media_id" to dst,
                "mid" to BiliClient.mid,
                "platform" to "web",
            ),
        )
    }

    suspend fun remove(resources: List<String>, mediaId: Long) {
        BiliClient.postData("/x/v3/fav/resource/batch-del", mapOf("resources" to resources.joinToString(","), "media_id" to mediaId, "platform" to "web"))
    }

    /** 清除失效内容. */
    suspend fun clean(mediaId: Long) {
        BiliClient.postData("/x/v3/fav/resource/clean", mapOf("media_id" to mediaId))
    }
}

/**
 * The selection's actions along the bottom: 移动到 and 复制到 as one tonal group, then 删除
 * apart from them, outlined (it removes from this folder).
 */
@Composable
internal fun FavSelectionBar(count: Int, onMove: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = count > 0
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onMove, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Icon(YoinSymbols.Folder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("移动到")
                }
                FilledTonalButton(onClick = onCopy, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Icon(YoinSymbols.Copy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制到")
                }
            }
            Spacer(Modifier.width(24.dp))
            OutlinedButton(onClick = onDelete, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                Icon(YoinSymbols.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("删除")
            }
        }
    }
}

/** A tick over a selected card's cover; a hollow ring over the others while selecting. */
@Composable
internal fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(24.dp)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(YoinSymbols.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** 移动到 / 复制到's folder list: my folders but this one; a tap picks. */
@Composable
internal fun FolderPickerSheet(title: String, exclude: Long, onPick: (MyFolder) -> Unit, onDismiss: () -> Unit) {
    val folders by produceState<List<MyFolder>?>(null) {
        value = runCatching { FavBatchApi.myFolders() }.getOrDefault(emptyList()).filter { it.id != exclude }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            val list = folders
            when {
                list == null -> LoadingState(Modifier.heightIn(min = 120.dp))
                list.isEmpty() -> Text("没有别的收藏夹", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    list.forEachIndexed { i, f ->
                        val big = 20.dp
                        val small = 6.dp
                        Surface(
                            onClick = { onPick(f) },
                            shape = RoundedCornerShape(
                                topStart = if (i == 0) big else small,
                                topEnd = if (i == 0) big else small,
                                bottomStart = if (i == list.lastIndex) big else small,
                                bottomEnd = if (i == list.lastIndex) big else small,
                            ),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(YoinSymbols.Folder, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Text(f.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${f.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
