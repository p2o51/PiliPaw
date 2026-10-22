package com.gpo.pilipala.feature.video

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PictureSwipeDown
import com.gpo.pilipala.feature.sources.BiliKeeper
import com.gpo.pilipala.feature.sources.KeepPlaying
import com.gpo.pilipala.feature.sources.PlayerHost
import com.gpo.pilipala.feature.sources.PlayerSlot
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.transition.HeroDock
import com.gpo.pilipala.navigation.transition.LocalPageTransitions
import com.gpo.pilipala.navigation.transition.SwipeBack
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.widthClass

/**
 * The video page's swipe down on its picture: the page closes under the finger as a back gesture
 * does (it scales down over the page beneath), and on release goes or settles. With 继续播放
 * keeping the video, every back of the page flies the picture's last frame into the mini bar's
 * tile, where the bar takes over; otherwise it flies home to its card.
 */
@Composable
internal fun rememberVideoSwipeDown(): PictureSwipeDown? {
    val transitions = LocalPageTransitions.current ?: return null
    val nav = LocalNavigator.current
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val tablet = widthClass() != WidthClass.Compact
    val metrics by rememberUpdatedState(Metrics(window.width.toFloat(), (window.height - navBottom).toFloat(), tablet))
    // Any back of this page (gesture, button, the picture's swipe) whose video 继续播放 keeps: the
    // picture's last frame flies into the bar's tile, where the bar takes over in place.
    val key = remember { nav.backStack.lastOrNull()?.toString() }
    if (key != null) {
        androidx.compose.runtime.DisposableEffect(transitions, key) {
            val stack = nav.backStack
            fun shellBeneath(): Boolean {
                val i = stack.indexOfLast { it.toString() == key }
                val beneath = stack.getOrNull(i - 1)
                return beneath == com.gpo.pilipala.navigation.Route.Main || beneath == com.gpo.pilipala.yt.navigation.Route.Main
            }
            val dock = HeroDock(
                key = key,
                enabled = {
                    val c = (PlayerHost.front as? PlayerSlot.Bili)?.player as? PiliPlayerController
                    c != null && BiliKeeper.wouldKeep(c)
                },
                target = {
                    val shell = shellBeneath()
                    // Opened from the floating window (or circle): back to it, else the bar's tile.
                    val form = PlayerHost.front?.let(PlayerHost::remembered) as? com.gpo.pilipala.feature.sources.DockForm
                    form?.rect ?: metrics.let { m -> KeepPlaying.dockTile(shell, m.w, m.h, m.tablet, density) }
                },
                landing = { KeepPlaying.arrive(it, shellBeneath()) },
            )
            transitions.registerHeroDock(dock)
            onDispose { transitions.unregisterHeroDock(dock) }
        }
    }
    return remember(transitions, nav, density) {
        object : PictureSwipeDown {
            private var back: SwipeBack? = null

            override fun start(ctrl: PiliPlayerController): Boolean {
                back = transitions.startSwipeBack() ?: return false
                return true
            }

            override fun drag(dy: Float) {
                // Half the window's height is the whole back; the page also follows the finger down.
                back?.drag(dy / (metrics.h * 0.5f), dy)
            }

            override fun end(dy: Float, vy: Float) {
                val b = back ?: return
                back = null
                val goes = with(density) { dy > 96.dp.toPx() || (dy > 24.dp.toPx() && vy > 800.dp.toPx()) }
                if (goes) {
                    b.commit()
                } else {
                    b.cancel()
                }
            }
        }
    }
}

private class Metrics(val w: Float, val h: Float, val tablet: Boolean)
