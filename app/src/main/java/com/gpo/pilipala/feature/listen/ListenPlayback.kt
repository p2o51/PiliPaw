package com.gpo.pilipala.feature.listen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import com.gpo.yoin.symbols.SymbolPlayMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * What the listen page's transport plays, whichever part it is: Bilibili's controller or the
 * YouTube player behind the same keys.
 */
@Stable
internal interface ListenPlayback {
    /** Playing, or about to (a pause shows the play key). */
    val playing: Boolean
    val hasNext: Boolean
    val durationMs: Long
    val bufferedMs: Long get() = 0L

    /** The playhead now, read straight from the player (not a snapshot state). */
    fun positionMs(): Long

    fun togglePlay()
    fun skipNext()
    fun skipPrevious()
    fun seekTo(ms: Long)

    /** 上一个 for a list, 回到开头 for a single video. */
    val previousLabel: String get() = "上一个"

    /** The play-mode key as it shows now. */
    @Composable
    fun mode(): ListenMode

    fun stepMode()
}

/** The play-mode key: its symbol, its name, and lit (primary) unless it is the resting mode. */
@Immutable
internal data class ListenMode(val symbol: SymbolPlayMode, val label: String, val lit: Boolean)

/** The playhead read every frame while playing (the lyrics and the wave glide), polled while paused. */
@Composable
internal fun rememberListenPosition(playback: ListenPlayback): State<Long> {
    val live: MutableLongState = remember(playback) { mutableLongStateOf(playback.positionMs()) }
    val playing = playback.playing
    LaunchedEffect(playback, playing) {
        if (playing) {
            while (isActive) withFrameMillis { live.longValue = playback.positionMs() }
        } else {
            while (isActive) {
                live.longValue = playback.positionMs()
                delay(250)
            }
        }
    }
    return live
}
