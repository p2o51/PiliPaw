package com.gpo.pilipala.feature.reply

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

/** Transient UI for one reply surface (menu, composer, viewer, dialogs). */
@Stable
internal class ReplyUiState {
    var menuFor by mutableStateOf<Reply?>(null)
    var confirmDelete by mutableStateOf<Reply?>(null)
    var freeCopy by mutableStateOf<String?>(null)
    var composer by mutableStateOf<ComposeTarget?>(null)
    var viewer by mutableStateOf<Pair<List<String>, Int>?>(null)

    /** Rows resizing right now (text expanding, a reply expanding in). */
    val layout = ReplyLayoutMotion()
}

@Composable
internal fun rememberReplyUiState(): ReplyUiState = remember { ReplyUiState() }

/** Guests get a toast and the login page. */
internal fun requireLogin(nav: Navigator, toast: (String) -> Unit): Boolean {
    if (BiliClient.isLoggedIn) return true
    toast("未登录")
    nav.push(Route.Login)
    return false
}

internal fun copyToClipboard(context: Context, text: String): Boolean {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
    cm.setPrimaryClip(ClipData.newPlainText("评论", text))
    // Android 13+ shows its own clipboard confirmation.
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}

/** Builds the row actions for a ViewModel + UI state pair. */
@Composable
internal fun rememberReplyActions(
    vm: BaseReplyViewModel,
    ui: ReplyUiState,
    onReply: (Reply) -> ComposeTarget,
    onOpenThread: ((Reply) -> Unit)?,
    onTimestampClick: ((Long) -> Unit)?,
    threadSource: Boolean = true,
): ReplyActions {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val replyTarget by rememberUpdatedState(onReply)
    val openThread by rememberUpdatedState(onOpenThread)
    val timestamp by rememberUpdatedState(onTimestampClick)
    val hasThread = onOpenThread != null
    val hasTimestamp = onTimestampClick != null
    // One instance per surface: rows skip when their host recomposes.
    return remember(vm, ui, nav, toast, hasThread, hasTimestamp, threadSource) {
        ReplyActions(
            likeState = { vm.likeState(it) },
            onLike = { r -> if (!vm.toggleLike(r)) requireLogin(nav, toast) },
            onReply = { r -> if (requireLogin(nav, toast)) ui.composer = replyTarget(r) },
            onOpenThread = if (hasThread) { r -> openThread?.invoke(r) } else null,
            onLongPress = { ui.menuFor = it },
            onImage = { urls, i -> ui.viewer = urls to i },
            onTimestampClick = if (hasTimestamp) { s -> timestamp?.invoke(s) } else null,
            entrance = vm.entrance,
            layout = ui.layout,
            threadSource = threadSource,
            relationOf = { vm.relationOf(it) },
        )
    }
}

@Composable
internal fun ReplyOverlays(
    ui: ReplyUiState,
    vm: BaseReplyViewModel,
    onDeleted: (Reply) -> Unit = {},
) {
    val toast = rememberToast()
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.messages.collect { toast(it) } }

    ui.menuFor?.let { reply ->
        ReplyMenuSheet(
            reply = reply,
            isOwner = BiliClient.isLoggedIn && reply.member.mid == BiliClient.mid,
            onDismiss = { ui.menuFor = null },
            onCopy = {
                if (copyToClipboard(context, reply.content.message)) toast("已复制")
            },
            onFreeCopy = { ui.freeCopy = reply.content.message },
            onDelete = { ui.confirmDelete = reply },
        )
    }

    ui.confirmDelete?.let { reply ->
        AlertDialog(
            onDismissRequest = { ui.confirmDelete = null },
            title = { Text("删除评论") },
            text = if (reply.root == 0L) {
                { Text("楼中楼回复会一并删除") }
            } else {
                null
            },
            confirmButton = {
                TextButton(onClick = {
                    ui.confirmDelete = null
                    vm.delete(reply) { onDeleted(reply) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { ui.confirmDelete = null }) { Text("取消") } },
        )
    }

    ui.freeCopy?.let { text ->
        AlertDialog(
            onDismissRequest = { ui.freeCopy = null },
            title = { Text("自由复制") },
            text = {
                SelectionContainer(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = { TextButton(onClick = { ui.freeCopy = null }) { Text("完成") } },
        )
    }

    ui.composer?.let { target ->
        ReplyComposerSheet(
            target = target,
            onDismiss = { ui.composer = null },
            onSend = { text -> vm.send(target, text) },
        )
    }

    ui.viewer?.let { (urls, index) ->
        ImageViewerDialog(urls, index) { ui.viewer = null }
    }
}

@Composable
private fun ReplyMenuSheet(
    reply: Reply,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onFreeCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun run(action: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(reply.member.face, 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        reply.member.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        reply.content.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .clip(ContinuousRoundedCornerShape(20.dp)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MenuRow(YoinSymbols.Copy, "复制") { run(onCopy) }
                MenuRow(YoinSymbols.EditNote, "自由复制") { run(onFreeCopy) }
                if (isOwner) {
                    MenuRow(YoinSymbols.Delete, "删除", tint = MaterialTheme.colorScheme.error) { run(onDelete) }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
