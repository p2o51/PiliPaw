package com.gpo.pilipala.feature.reply

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.components.SheetButtonLabel
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.rememberSheetFinisher
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.components.sheetStagger
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion

/**
 * 举报 reasons for a comment, grouped as the comment H5 page groups them (its ids are the
 * `reason` the report sends). From the official comment-h5 bundle, as PiliPlus lists it.
 */
internal object ReplyReportReasons {
    val groups: List<Pair<String, List<Pair<Int, String>>>> = listOf(
        "违反法律法规" to listOf(9 to "违法违规", 2 to "色情", 10 to "低俗", 12 to "赌博诈骗", 23 to "违法信息外链"),
        "谣言类不实信息" to listOf(19 to "涉政谣言", 22 to "虚假不实信息", 20 to "涉社会事件谣言"),
        "侵犯个人权益" to listOf(7 to "人身攻击", 15 to "侵犯隐私"),
        "有害社区环境" to listOf(
            1 to "垃圾广告",
            4 to "引战",
            5 to "剧透",
            3 to "刷屏",
            8 to "视频不相关",
            18 to "违规抽奖",
            17 to "青少年不良信息",
        ),
        "其他" to listOf(0 to "其他"),
    )

    /** These need a few words of why. */
    fun needsDetail(reason: Int): Boolean = reason == 0 || reason == 22
}

private val FieldShape = ContinuousRoundedCornerShape(16.dp)
private val ToggleShape = ContinuousRoundedCornerShape(20.dp)

/**
 * 举报 one comment: the comment it is about, the reasons as pills under their group, a
 * few words (required for 其他 / 虚假不实信息), and whether to 拉黑 the commenter too. The
 * submit button answers with a tick and 已举报 before the sheet goes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReplyReportSheet(
    reply: Reply,
    onDismiss: () -> Unit,
    onSubmit: (reason: Int, detail: String, block: Boolean, onDone: () -> Unit, onFail: () -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val finisher = rememberSheetFinisher(state, onDismiss)
    var reason by remember { mutableIntStateOf(-1) }
    var detail by remember { mutableStateOf("") }
    var block by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val needsDetail = reason >= 0 && ReplyReportReasons.needsDetail(reason)
    val ready = reason >= 0 && (!needsDetail || detail.isNotBlank()) && !sending && !finisher.finishing

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = sheetBottomPadding(24.dp)),
        ) {
            // What is being reported.
            Column(Modifier.padding(horizontal = 4.dp).sheetStagger(0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("举报评论", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${reply.member.name}：${reply.content.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(24.dp))

            // The reasons, group by group.
            Column(Modifier.sheetStagger(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ReplyReportReasons.groups.forEach { (title, reasons) ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            reasons.forEach { (id, label) ->
                                ReasonPill(label, selected = reason == id, enabled = !sending && !finisher.finishing) { reason = id }
                            }
                        }
                    }
                }
            }

            // A few words, once a reason is picked.
            AnimatedVisibility(
                visible = reason >= 0,
                enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
                exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.fastSpatialSpec()),
            ) {
                TextField(
                    value = detail,
                    onValueChange = { detail = it.take(200) },
                    placeholder = { Text(if (needsDetail) "说说原因（必填）" else "补充说明（选填）") },
                    minLines = 2,
                    maxLines = 4,
                    shape = FieldShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = scheme.surfaceContainerHigh,
                        unfocusedContainerColor = scheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }

            // 拉黑 too: its own block, apart from the reasons.
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(ToggleShape)
                    .background(scheme.surfaceContainerHigh)
                    .clickable(enabled = !sending) { block = !block }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .sheetStagger(2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("同时拉黑", style = MaterialTheme.typography.bodyLarge)
                    Text(reply.member.name, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = block, onCheckedChange = { block = it }, enabled = !sending)
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    sending = true
                    // A failure toasts from the ViewModel; the sheet stays to try again.
                    onSubmit(reason, detail, block, { finisher.finish("report", "已举报") }, { sending = false })
                },
                enabled = ready || finisher.finishing,
                modifier = Modifier.fillMaxWidth().height(56.dp).sheetStagger(3),
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
            ) { SheetButtonLabel("提交举报", finisher.doneLabel("report")) }
        }
    }
}

@Composable
private fun ReasonPill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) scheme.errorContainer else scheme.surfaceContainerHigh,
        YoinMotion.defaultEffectsSpec(),
        label = "reasonPill",
    )
    val content by animateColorAsState(
        if (selected) scheme.onErrorContainer else scheme.onSurface,
        YoinMotion.defaultEffectsSpec(),
        label = "reasonPillText",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.height(40.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
