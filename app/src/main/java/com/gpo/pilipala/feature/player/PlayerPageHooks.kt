package com.gpo.pilipala.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable

/**
 * What the page behind the player offers beyond one video: its neighbouring items
 * (分P / 合集) for 上一个 / 下一个 in the overlay and the system media controls, the
 * like toggle for the media controls, and the page's current [target].
 *
 * Every member is read through snapshots on the player's own scope, not in
 * composition, so the hooks keep working while the page sits in the background
 * (the page does not recompose then): a target the page switches to from
 * [next] or from auto-play-next still loads.
 */
@Stable
class PlayerPageHooks(
    val target: () -> PlayTarget? = { null },
    val hasNext: () -> Boolean = { false },
    val next: () -> Unit = {},
    val hasPrevious: () -> Boolean = { false },
    val previous: () -> Unit = {},
    /** Current like state; null when liking is not offered (signed out, nothing loaded yet). */
    val liked: () -> Boolean? = { null },
    val toggleLike: () -> Unit = {},
)

/** Binds [hooks] to the player behind this state for as long as the caller stays composed. */
@Composable
fun BindPlayerPage(state: BiliPlayerState, hooks: PlayerPageHooks) {
    DisposableEffect(state, hooks) {
        val ctrl = state as? PiliPlayerController
        ctrl?.bindPage(hooks)
        onDispose { ctrl?.bindPage(null) }
    }
}
