package com.gpo.pilipala.feature.video

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Immutable
internal class AiPoint(val ms: Long, val text: String)

@Immutable
internal class AiSection(val title: String, val ms: Long, val points: List<AiPoint>)

/** Bilibili's AI 总结 of a video: a paragraph and an outline with times. */
@Immutable
internal class AiSummary(val summary: String, val outline: List<AiSection>)

internal object AiSummaryApi {
    private val cache = android.util.LruCache<Long, AiSummary>(16)

    /** The summary of [cid], or null when Bilibili has none for it (most short or new videos). */
    suspend fun get(bvid: String, cid: Long, upMid: Long): AiSummary? {
        cache.get(cid)?.let { return it }
        val data = try {
            BiliClient.getData(
                "/x/web-interface/view/conclusion/get",
                mapOf("bvid" to bvid, "cid" to cid, "up_mid" to upMid.takeIf { it > 0 }),
                wbi = true,
            ).asObj
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        }
        if (data.int("code") != 0) return null
        val m = data.obj("model_result") ?: return null
        val outline = m.arr("outline").objects().map { o ->
            AiSection(
                title = o.str("title"),
                ms = o.long("timestamp") * 1000,
                points = o.arr("part_outline").objects().map { p -> AiPoint(p.long("timestamp") * 1000, p.str("content")) }
                    .filter { it.text.isNotBlank() },
            )
        }.filter { it.title.isNotBlank() }
        val s = AiSummary(m.str("summary").trim(), outline)
        if (s.summary.isBlank() && s.outline.isEmpty()) return null
        cache.put(cid, s)
        return s
    }
}

/**
 * AI 总结 under the stats line: a quiet block in the description's colour with the first line of
 * the summary; it appears only when Bilibili has one. Tapping opens [AiSummarySheet].
 */
@Composable
internal fun AiSummaryRow(d: VideoDetail, onSeek: ((Long) -> Unit)?, modifier: Modifier = Modifier) {
    val summary by produceState<AiSummary?>(null, d.bvid, d.cid) {
        value = AiSummaryApi.get(d.bvid, d.cid, d.ownerMid)
    }
    val s = summary ?: return
    var open by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { open = true }
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(YoinSymbols.Sparkle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("AI 总结", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            val line = s.summary.ifBlank { s.outline.joinToString("、") { it.title } }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(YoinSymbols.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
    }
    if (open) AiSummarySheet(s, onSeek, onDismiss = { open = false })
}

/** The paragraph, then the outline: each section a group of rows with its time; a time seeks and closes. */
@Composable
internal fun AiSummarySheet(s: AiSummary, onSeek: ((Long) -> Unit)?, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val seek: ((Long) -> Unit)? = onSeek?.let { f ->
        { ms: Long ->
            f(ms)
            scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
        }
    }
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
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI 总结", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
                if (s.summary.isNotBlank()) {
                    Text(
                        s.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            s.outline.forEach { sec ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        sec.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    val rows = sec.points.ifEmpty { listOf(AiPoint(sec.ms, sec.title)) }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows.forEachIndexed { i, p -> OutlineRow(p, i, rows.size, seek) }
                    }
                }
            }
            Text(
                "由 B 站 AI 生成，可能不准确",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun OutlineRow(p: AiPoint, index: Int, count: Int, seek: ((Long) -> Unit)?) {
    val big = 20.dp
    val small = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
    Surface(
        onClick = { seek?.invoke(p.ms) },
        enabled = seek != null,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                Fmt.duration(p.ms / 1000),
                style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(52.dp).padding(top = 1.dp),
            )
            Text(p.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
