package com.gpo.pilipala.yt

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.rememberNavBackStack
import com.gpo.pilipala.ui.components.LocalSnackbarHost
import com.gpo.pilipala.ui.components.LocalToastScope
import com.gpo.pilipala.ui.theme.ApplySystemBars
import com.gpo.pilipala.yt.feature.player.PlaybackService
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.pilipala.yt.navigation.AppNavHost
import com.gpo.pilipala.yt.navigation.LinkRouter
import com.gpo.pilipala.yt.navigation.Navigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.yt.ui.theme.LocalPawDark
import com.gpo.pilipala.yt.ui.theme.PawTheme
import kotlinx.coroutines.channels.Channel

/** YouTube links from outside (a share, a tapped link), opened once the YouTube part is on screen. */
object YtLinks {
    val pending = Channel<String>(Channel.UNLIMITED)

    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")

    fun isYoutube(url: String): Boolean =
        runCatching { android.net.Uri.parse(url).host?.lowercase() in hosts }.getOrDefault(false)

    /** The first YouTube link in shared text, or null. */
    fun firstLink(text: String): String? = LinkRouter.firstLink(text)?.takeIf(::isYoutube)

    /**
     * The media notification of the YouTube player: bring the player up. True when [intent] was
     * that notification.
     */
    fun handleResume(intent: Intent): Boolean {
        if (!intent.getBooleanExtra(PlaybackService.EXTRA_RESUME, false)) return false
        if (Player.session != null) Player.expanded = true
        return true
    }
}

/** The YouTube part of the app: PipePaw's theme, navigation and player over the shared shell. */
@Composable
fun YoutubeRoot(activity: Activity) {
    PawTheme {
        val snackbar = remember { SnackbarHostState() }
        val rootScope = rememberCoroutineScope()
        val backStack = rememberNavBackStack(Route.Main)
        val navigator = remember(backStack) { Navigator(backStack, activity) }
        LaunchedEffect(Unit) { for (url in YtLinks.pending) navigator.openUrl(url) }
        // The single owner of the bar icon colour: pages' wishes, else the app's light/dark.
        ApplySystemBars(activity.window, LocalPawDark.current)
        CompositionLocalProvider(
            LocalSnackbarHost provides snackbar,
            LocalToastScope provides rootScope,
        ) {
            Box(Modifier.fillMaxSize()) {
                AppNavHost(navigator)
                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
                )
            }
        }
    }
}
