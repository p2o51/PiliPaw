package com.gpo.pilipala.feature.videomenu

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.feature.dynamics.DynamicsApi
import com.gpo.pilipala.feature.video.shareText
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.SheetButtonLabel
import com.gpo.pilipala.ui.components.SheetFinisher
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.SheetGroupButton
import com.gpo.pilipala.ui.components.SheetGroupSpacing
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberSheetFinisher
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What is being shared: a B站 video; [cid] is the part on screen (0 = the first). */
internal class ShareTarget(val aid: Long, val title: String, val cover: String, val url: String, val cid: Long = 0)

/**
 * 分享's choices: the recent 私信 contacts ([ShareContactsRow]), then a standard button group of
 * equal tonal buttons: 转发到动态 (signed in, opens [onForward]), 复制链接 (a tick, then the
 * sheet goes), 复制字幕 (标题, 链接, UP主, 标签, 简介 and the subtitles, see
 * [loadVideoTranscript]) and 更多方式 (the system share sheet).
 */
@Composable
internal fun ShareChoices(target: ShareTarget, finisher: SheetFinisher, enabled: Boolean, onForward: () -> Unit) {
    val context = LocalContext.current
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var fetching by remember { mutableStateOf(false) }
    // Two groups: the people (one tap sends it), then the ways to share; loose between them.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        ShareContactsRow(target, finisher)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SheetGroupSpacing)) {
            if (BiliClient.isLoggedIn && target.aid > 0) {
                SheetGroupButton(PiliSymbols.Forward, "转发到动态", onForward, Modifier.weight(1f), enabled = enabled)
            }
            SheetGroupButton(
                PiliSymbols.Link,
                "复制链接",
                {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(target.title, target.url))
                    finisher.finish("复制链接", "已复制")
                },
                Modifier.weight(1f),
                done = finisher.doneLabel("复制链接"),
                enabled = enabled,
            )
            if (target.aid > 0) {
                SheetGroupButton(
                    PiliSymbols.Subtitles,
                    if (fetching) "获取中…" else "复制字幕",
                    {
                        if (!fetching) {
                            fetching = true
                            scope.launch {
                                try {
                                    val t = loadVideoTranscript(target)
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText(target.title, t.text))
                                    if (!t.hasSubtitles) toast("这个视频没有字幕，已复制标题、简介等信息")
                                    finisher.finish("复制字幕", "已复制")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    toast(e.friendlyMessage())
                                }
                                fetching = false
                            }
                        }
                    },
                    Modifier.weight(1f),
                    done = finisher.doneLabel("复制字幕"),
                    enabled = enabled,
                )
            }
            SheetGroupButton(
                YoinSymbols.Share,
                "更多方式",
                { finisher.close { shareText(context, target.title, target.url) } },
                Modifier.weight(1f),
                enabled = enabled,
            )
        }
    }
}

/** 转发到动态: the video, a 附言 field, and 转发, which turns into 已转发 before the sheet goes. */
@Composable
internal fun ForwardStep(target: ShareTarget, finisher: SheetFinisher, onBack: () -> Unit) {
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetImage(target.cover, Modifier.width(72.dp).aspectRatio(16f / 10f), shape = ContinuousRoundedCornerShape(8.dp), thumbWidth = 144.dp)
            Text(
                target.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("说点什么（可不填）") },
            shape = YoinContainerShapes.Card,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onBack,
                enabled = !sending && !finisher.finishing,
                modifier = Modifier.weight(1f).height(52.dp),
                shapes = ButtonDefaults.shapes(),
            ) { Text("返回", style = MaterialTheme.typography.titleSmall) }
            Button(
                onClick = {
                    sending = true
                    scope.launch {
                        try {
                            DynamicsApi.forwardVideo(target.aid, text.trim())
                            finisher.finish("转发", "已转发")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            toast(e.friendlyMessage())
                        }
                        sending = false
                    }
                },
                enabled = !sending && !finisher.finishing,
                modifier = Modifier.weight(2f).height(52.dp),
                shapes = ButtonDefaults.shapes(),
            ) { SheetButtonLabel("转发", finisher.doneLabel("转发")) }
        }
    }
}

private enum class SharePage { Choose, Forward }

/** The video page's 分享: [ShareChoices], with 转发到动态 opening its composer in the same sheet. */
@Composable
internal fun VideoShareSheet(target: ShareTarget, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val finisher = rememberSheetFinisher(state, onDismiss)
    var page by remember { mutableStateOf(SharePage.Choose) }
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                if (page == SharePage.Forward) "转发到动态" else "分享",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
            )
            AnimatedContent(page, transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) }, label = "sharePage") { p ->
                when (p) {
                    SharePage.Choose -> ShareChoices(target, finisher, enabled = !finisher.finishing) { page = SharePage.Forward }
                    SharePage.Forward -> ForwardStep(target, finisher) { page = SharePage.Choose }
                }
            }
        }
    }
}
