package com.gpo.pilipala.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * What to play. UGC: bvid/aid + cid. PGC (bangumi): epId + cid with isPgc = true.
 */
data class PlayTarget(
    val bvid: String = "",
    val aid: Long = 0,
    val cid: Long,
    val epId: Long = 0,
    val seasonId: Long = 0,
    val isPgc: Boolean = false,
    val title: String = "",
    val cover: String = "",
    val startPositionMs: Long = 0,
)

/**
 * Hoisted player handle shared with the detail page (seek from a reply
 * timestamp, pause when leaving, read progress). CONTRACT — the player agent
 * may add members but must keep these.
 */
@Stable
open class BiliPlayerState {
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    open fun play() {}
    open fun pause() {}
    open fun seekTo(ms: Long) {}
    open fun release() {}
}

@Composable
fun rememberBiliPlayerState(): BiliPlayerState = remember { BiliPlayerState() }

/**
 * The video surface + controls + danmaku. CONTRACT — signature is fixed:
 * - [target] null while the page is still resolving cid → show cover/loader.
 * - [fullscreen] is owned by the caller; the player calls [onFullscreenChange]
 *   from its button/gesture and applies orientation + system-bar changes itself.
 * - [onCompleted] fires at the end (auto-play-next lives in the detail page).
 */
@Composable
fun BiliVideoPlayer(
    target: PlayTarget?,
    state: BiliPlayerState,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCompleted: () -> Unit = {},
) {
    Box(modifier.background(Color.Black))
}
