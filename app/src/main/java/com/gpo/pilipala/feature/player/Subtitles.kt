package com.gpo.pilipala.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.double
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapeTokens

internal data class SubtitleTrack(val lan: String, val label: String, val url: String)
internal data class SubtitleLine(val fromMs: Long, val toMs: Long, val text: String)

internal object SubtitleApi {
    suspend fun tracks(target: PlayTarget): List<SubtitleTrack> {
        if (target.bvid.isBlank() && target.aid <= 0) return emptyList()
        val params = buildMap<String, Any?> {
            if (target.bvid.isNotBlank()) put("bvid", target.bvid) else put("aid", target.aid)
            put("cid", target.cid)
        }
        val data = BiliClient.getData("/x/player/wbi/v2", params, wbi = true).asObj
        return data.obj("subtitle").arr("subtitles").objects().mapNotNull { s ->
            val url = s.str("subtitle_url")
            if (url.isBlank()) null else SubtitleTrack(s.str("lan"), s.str("lan_doc").ifEmpty { s.str("lan") }, url)
        }
    }

    suspend fun lines(track: SubtitleTrack): List<SubtitleLine> {
        val text = BiliClient.getText(BiliClient.resolve(track.url), headers = BiliClient.mediaHeaders())
        val root = runCatching { BiliJson.parseToJsonElement(text) }.getOrNull().asObj
        return root.arr("body").objects().map { b ->
            SubtitleLine(
                fromMs = (b.double("from") * 1000).toLong(),
                toMs = (b.double("to") * 1000).toLong(),
                text = b.str("content"),
            )
        }.sortedBy { it.fromMs }
    }

    fun lineAt(lines: List<SubtitleLine>, pos: Long): SubtitleLine? {
        var lo = 0
        var hi = lines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val l = lines[mid]
            when {
                pos < l.fromMs -> hi = mid - 1
                pos > l.toMs -> lo = mid + 1
                else -> return l
            }
        }
        return null
    }
}

/** Bottom-centred subtitle line on a scrim pill; lifts above the controls when they show. */
@Composable
internal fun SubtitleOverlay(
    ctrl: PiliPlayerController,
    controlsVisible: Boolean,
    big: Boolean,
    modifier: Modifier = Modifier,
) {
    val lines = ctrl.subtitleLines
    if (lines.isEmpty()) return
    val line = SubtitleApi.lineAt(lines, ctrl.positionMs)?.text
    val lift by animateDpAsState(
        targetValue = if (controlsVisible) (if (big) 112.dp else 64.dp) else 16.dp,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "subtitleLift",
    )
    val motion = MaterialTheme.motionScheme
    Box(modifier.fillMaxSize().padding(bottom = lift), contentAlignment = Alignment.BottomCenter) {
        AnimatedContent(
            targetState = line,
            transitionSpec = {
                fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
            },
            label = "subtitleLine",
        ) { text ->
            if (!text.isNullOrBlank()) {
                Text(
                    text = text,
                    style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .padding(horizontal = 24.dp)
                        .background(Color.Black.copy(alpha = 0.55f), YoinShapeTokens.Small)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
