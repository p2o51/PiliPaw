package com.gpo.pilipala.feature.download

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayInfo
import com.gpo.pilipala.feature.player.PlayUrlApi
import com.gpo.pilipala.feature.player.QualityOption
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The player sheet's 缓存 page: this part's qualities (as the quality sheet offers them),
 * each with its size; picking one queues the cid. Locked qualities (登录 / 大会员) are dimmed.
 */
@Composable
internal fun CachePage(ctrl: PiliPlayerController, close: () -> Unit) {
    val context = LocalContext.current
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val target = ctrl.target ?: return
    // The player's own info when it streams DASH; a cached / single-file play asks afresh.
    var info by remember(target.cid) { mutableStateOf(ctrl.info?.takeIf { it.isDash }) }
    var error by remember(target.cid) { mutableStateOf<String?>(null) }
    LaunchedEffect(target.cid) {
        if (info != null) return@LaunchedEffect
        try {
            info = PlayUrlApi.fetch(target, 127)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message?.takeIf { it.isNotBlank() } ?: "加载失败"
        }
    }
    LaunchedEffect(Unit) { DownloadStore.init(context) }
    val items by DownloadStore.items.collectAsState()
    val existing = items.firstOrNull { it.id == DownloadStore.idFor(target.cid) }

    var pending by remember { mutableStateOf<QualityOption?>(null) }
    val enqueue: (QualityOption) -> Unit = { q ->
        val pi = info
        val e = existing
        when {
            e != null && e.qn == q.qn && (e.state == DownloadState.Done || e.active) -> toast("已在离线缓存")
            e != null && e.qn == q.qn -> {
                DownloadStore.scope.launch { DownloadStore.resume(context.applicationContext, e.id) }
                toast("继续缓存")
            }
            else -> {
                val item = DownloadItem(
                    id = DownloadStore.idFor(target.cid),
                    bvid = target.bvid,
                    aid = target.aid,
                    cid = target.cid,
                    epId = target.epId,
                    seasonId = target.seasonId,
                    isPgc = target.isPgc,
                    title = target.title,
                    cover = target.cover,
                    owner = ctrl.artist,
                    qn = q.qn,
                    qualityLabel = q.label,
                    durationMs = pi?.timelengthMs ?: 0,
                    totalBytes = pi?.let { estimate(it, q.qn) } ?: 0,
                )
                DownloadStore.scope.launch { DownloadStore.add(context.applicationContext, item) }
                toast("已加入离线缓存")
            }
        }
        close()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let(enqueue)
        pending = null
    }

    val motion = MaterialTheme.motionScheme
    val enter = motion.defaultEffectsSpec<Float>()
    val exit = motion.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = when {
            info != null -> 2
            error != null -> 1
            else -> 0
        },
        transitionSpec = {
            fadeIn(enter) togetherWith fadeOut(exit)
        },
        label = "cachePage",
    ) { s ->
        when (s) {
            0 -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                ContainedLoadingIndicator()
            }
            1 -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val pi = info ?: return@AnimatedContent
                val options = pi.qualities.sortedByDescending { it.qn }
                    .ifEmpty { listOf(QualityOption(pi.quality, com.gpo.pilipala.feature.player.Qualities.videoName(pi.quality), true)) }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    options.forEachIndexed { i, q ->
                        val cached = existing?.qn == q.qn
                        QualityRow(
                            label = q.label,
                            size = estimate(pi, q.qn).takeIf { it > 0 }?.let(::formatBytes),
                            tag = when {
                                !cached -> null
                                existing?.state == DownloadState.Done -> "已缓存"
                                else -> "缓存中"
                            },
                            enabled = q.available,
                            shape = segmentShape(i, options.size),
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            val needAsk = Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            if (needAsk) {
                                pending = q
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                enqueue(q)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Video (AVC first, as the worker picks) + best AAC audio, from the streams' bandwidth. */
internal fun estimate(pi: PlayInfo, qn: Int): Long {
    val seconds = pi.timelengthMs / 1000.0
    if (seconds <= 0) return 0
    val same = pi.videos.filter { it.id == qn }
    val v = same.firstOrNull { it.codecId == 7 } ?: same.firstOrNull { it.codecId == 12 } ?: same.firstOrNull() ?: return 0
    val a = pi.audios.filter { it.id in setOf(30216, 30232, 30280) }.maxByOrNull { it.bandwidth }
    return ((v.bandwidth + (a?.bandwidth ?: 0)) * seconds / 8).toLong()
}

private fun segmentShape(index: Int, count: Int): Shape {
    val big = 20.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
}

@Composable
private fun QualityRow(
    label: String,
    size: String?,
    tag: String?,
    enabled: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = if (tag != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (tag != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (tag == "已缓存") YoinSymbols.DownloadDone else YoinSymbols.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tag != null) {
                Surface(
                    shape = YoinShapeTokens.Full,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(tag, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            if (size != null && enabled) {
                Text(size, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
    }
}
