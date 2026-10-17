package com.gpo.pilipala

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.rememberNavBackStack
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.feature.player.PlaybackService
import com.gpo.pilipala.navigation.AppNavHost
import com.gpo.pilipala.navigation.LinkRouter
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.LocalSnackbarHost
import com.gpo.pilipala.ui.theme.PiliTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** [resume]: from the media notification — the playing page may already be on top. */
    private class DeepLink(val route: Route, val resume: Boolean)

    private val deepLinks = Channel<DeepLink>(Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        lifecycleScope.launch {
            BiliClient.ensureBuvid()
            runCatching { Account.refresh() }
        }
        handleIntent(intent)
        setContent {
            PiliTheme {
                val snackbar = remember { SnackbarHostState() }
                val rootScope = androidx.compose.runtime.rememberCoroutineScope()
                val backStack = rememberNavBackStack(Route.Main)
                val navigator = remember(backStack) { Navigator(backStack) }
                LaunchedEffect(Unit) {
                    for (link in deepLinks) {
                        val top = backStack.lastOrNull()
                        if (link.resume && (top is Route.Video || top is Route.Bangumi)) continue
                        // Through the navigator, so the covered page is frozen for its later reveal.
                        navigator.push(link.route)
                    }
                }
                CompositionLocalProvider(LocalSnackbarHost provides snackbar, com.gpo.pilipala.ui.components.LocalToastScope provides rootScope) {
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        val resume = intent.getBooleanExtra(PlaybackService.EXTRA_RESUME, false)
        deepLinks.trySend(DeepLink(LinkRouter.route(data), resume))
    }
}
