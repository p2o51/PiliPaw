package com.gpo.pilipala.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapeTokens

internal data class SubtitleTrack(val lan: String, val label: String, val url: String)
internal data class SubtitleLine(val fromMs: Long, val toMs: Long, val text: String)

internal object SubtitleApi {
    /** Subtitle tracks and chapters (view points) from the player info, in one request. */
    suspend fun playerInfo(target: PlayTarget): Pair<List<SubtitleTrack>, List<Chapter>> {
        if (target.bvid.isBlank() && target.aid <= 0) return emptyList<SubtitleTrack>() to emptyList()
        val params = buildMap<String, Any?> {
            if (target.bvid.isNotBlank()) put("bvid", target.bvid) else put("aid", target.aid)
            put("cid", target.cid)
        }
        val data = BiliClient.getData("/x/player/wbi/v2", params, wbi = true).asObj
        val tracks = data.obj("subtitle").arr("subtitles").objects().mapNotNull { s ->
            // AI tracks sometimes only carry subtitle_url_v2; tracks without either need login.
            val url = s.str("subtitle_url").ifBlank { s.str("subtitle_url_v2") }
            if (url.isBlank()) null else SubtitleTrack(s.str("lan"), s.str("lan_doc").ifEmpty { s.str("lan") }, url)
        }
        return tracks to parseChapters(data)
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

/** Language code of the last subtitle the viewer picked; "" = off (the default). */
internal val SubtitlePreferredLan = AppSettings.register("subtitle_lan", "")

/**
 * 默认字幕: what a new video starts with. "off" 关闭, "auto" 自动 (the language picked
 * last, off when that was 关闭), "zh" 优先中文 (a Chinese track, AI ones too, else as 自动).
 */
val SubtitleDefaultMode = AppSettings.register("subtitle_default", "auto")

/** Picks a track (or -1 = off) and remembers the choice for the next video. */
internal fun chooseSubtitle(ctrl: PiliPlayerController, index: Int) {
    ctrl.lyricsBorrowedSubtitle = false
    ctrl.selectSubtitle(index)
    AppSettings.set(SubtitlePreferredLan, ctrl.subtitles.getOrNull(index)?.lan.orEmpty())
}

/** Exact language first, then the same language with / without the AI prefix (zh-CN <-> ai-zh). */
private fun preferredIndex(tracks: List<SubtitleTrack>, lan: String): Int {
    if (lan.isEmpty() || tracks.isEmpty()) return -1
    val exact = tracks.indexOfFirst { it.lan == lan }
    if (exact >= 0) return exact
    fun base(l: String) = l.removePrefix("ai-").substringBefore('-').lowercase()
    val want = base(lan)
    return tracks.indexOfFirst { base(it.lan) == want }
}

/** Simplified Chinese by people, then any Chinese by people, then the AI one (ai-zh). */
private fun chineseIndex(tracks: List<SubtitleTrack>): Int {
    val passes = listOf<(String) -> Boolean>(
        { it == "zh-CN" || it == "zh-Hans" },
        { it.startsWith("zh") },
        { it.startsWith("ai-zh") },
    )
    for (p in passes) {
        val i = tracks.indexOfFirst { p(it.lan) }
        if (i >= 0) return i
    }
    return -1
}

/** The track a new video starts with under 默认字幕, or -1. */
private fun defaultIndex(tracks: List<SubtitleTrack>): Int = when (SubtitleDefaultMode.value) {
    "off" -> -1
    "zh" -> chineseIndex(tracks).takeIf { it >= 0 } ?: preferredIndex(tracks, SubtitlePreferredLan.value)
    else -> preferredIndex(tracks, SubtitlePreferredLan.value)
}

/**
 * Turns on the default subtitle when a video's tracks arrive, and, while 仅听声音 is on
 * with subtitles off, borrows a track for the lyrics (handed back when the picture returns).
 * Lives with the player, not the overlay, so it works whatever is drawn.
 */
@Composable
internal fun SubtitleAutoSelect(ctrl: PiliPlayerController) {
    val tracks = ctrl.subtitles
    val listening = ctrl.audioOnly
    LaunchedEffect(tracks) {
        if (tracks.isEmpty() || ctrl.subtitleIndex >= 0) return@LaunchedEffect
        val idx = defaultIndex(tracks)
        if (idx >= 0) ctrl.selectSubtitle(idx)
    }
    LaunchedEffect(tracks, listening) {
        if (listening) {
            if (tracks.isNotEmpty() && ctrl.subtitleIndex < 0) {
                val idx = chineseIndex(tracks).takeIf { it >= 0 }
                    ?: preferredIndex(tracks, SubtitlePreferredLan.value).takeIf { it >= 0 }
                    ?: 0
                ctrl.selectSubtitle(idx)
                ctrl.lyricsBorrowedSubtitle = true
            }
        } else if (ctrl.lyricsBorrowedSubtitle) {
            ctrl.lyricsBorrowedSubtitle = false
            ctrl.selectSubtitle(-1)
        }
    }
}

/**
 * Bottom-centred subtitle line on a scrim plate; lifts above the controls when they show.
 * [minimal]: the portrait page draws its title and seek bar over the lower part of the
 * picture, so the line sits higher, clear of them.
 */
@Composable
internal fun SubtitleOverlay(
    ctrl: PiliPlayerController,
    controlsVisible: Boolean,
    big: Boolean,
    modifier: Modifier = Modifier,
    minimal: Boolean = false,
) {
    val lines = ctrl.subtitleLines
    if (lines.isEmpty()) return
    val line = SubtitleApi.lineAt(lines, ctrl.positionMs)?.text?.trim()
    val lift by animateDpAsState(
        targetValue = if (controlsVisible) (if (big) 120.dp else 60.dp) else (if (big) 28.dp else 12.dp),
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "subtitleLift",
    )
    val motion = MaterialTheme.motionScheme
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bottom = if (minimal) maxOf(lift, maxHeight * 0.32f) else lift
        Box(Modifier.fillMaxSize().padding(bottom = bottom), contentAlignment = Alignment.BottomCenter) {
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
                            .widthIn(max = if (big) 960.dp else 640.dp)
                            .padding(horizontal = if (big) 48.dp else 24.dp)
                            .background(Color.Black.copy(alpha = 0.58f), if (big) YoinShapeTokens.Medium else YoinShapeTokens.Small)
                            .padding(horizontal = if (big) 14.dp else 10.dp, vertical = if (big) 6.dp else 3.dp),
                    )
                }
            }
        }
    }
}
