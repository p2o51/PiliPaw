package com.gpo.pilipala.ui.theme

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat

/**
 * The one owner of the system bar icon colour. Pages never write the window appearance
 * themselves: they declare a wish ([LightBarIcons]) while they are composed, and
 * [ApplySystemBars] applies the newest wish, or the app's own light/dark when there is none.
 * The old "save the previous value, restore it on dispose" pattern broke whenever two pages
 * overlapped in a transition, or the launch handoff let go late: the last writer won.
 */
object SystemBars {
    internal class Wish(val status: Boolean, val navigation: Boolean)

    internal val wishes = mutableStateListOf<Wish>()

    /** The launch handoff paints over this bar and owns its colour until it lets go. */
    var heldStatus by mutableStateOf(false)
    var heldNavigation by mutableStateOf(false)
}

/**
 * Light (white) icons on the status and/or navigation bar while this is composed and
 * [enabled], for content that puts something dark behind the bar (a black player strip,
 * the picture itself). The newest composed wish wins.
 */
@Composable
fun LightBarIcons(enabled: Boolean = true, status: Boolean = true, navigation: Boolean = false) {
    DisposableEffect(enabled, status, navigation) {
        if (!enabled) return@DisposableEffect onDispose { }
        val wish = SystemBars.Wish(status, navigation)
        SystemBars.wishes.add(wish)
        onDispose { SystemBars.wishes.remove(wish) }
    }
}

/** Placed once at the root: keeps the window's bar icons in step with the wishes. */
@Composable
fun ApplySystemBars(window: Window, appDark: Boolean) {
    val dark by rememberUpdatedState(appDark)
    LaunchedEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        snapshotFlow {
            val status = SystemBars.wishes.lastOrNull { it.status } != null || dark
            val navigation = SystemBars.wishes.lastOrNull { it.navigation } != null || dark
            listOf(status, navigation, SystemBars.heldStatus, SystemBars.heldNavigation)
        }.collect { (lightStatus, lightNav, holdStatus, holdNav) ->
            if (!holdStatus) controller.isAppearanceLightStatusBars = !lightStatus
            if (!holdNav) controller.isAppearanceLightNavigationBars = !lightNav
        }
    }
}
