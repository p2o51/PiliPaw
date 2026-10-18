package com.gpo.pilipala

import android.animation.ValueAnimator
import android.app.PictureInPictureUiState
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.rememberNavBackStack
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.feature.player.PipSignals
import com.gpo.pilipala.feature.video.AppShortcuts
import com.gpo.pilipala.feature.player.PlaybackService
import com.gpo.pilipala.feature.widget.WatchWidget
import com.gpo.pilipala.navigation.AppNavHost
import com.gpo.pilipala.navigation.LinkRouter
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.LocalSnackbarHost
import com.gpo.pilipala.ui.launch.LocalSplashHandoff
import com.gpo.pilipala.ui.launch.SplashHandoff
import com.gpo.pilipala.ui.launch.SplashHandoffOverlay
import com.gpo.pilipala.ui.theme.PiliTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    /** [resume]: from the media notification — the playing page may already be on top. */
    private class DeepLink(val route: Route, val resume: Boolean, val shortcut: Boolean = false)

    private val deepLinks = Channel<DeepLink>(Channel.UNLIMITED)

    /** Compose has laid out its first frame: the splash may go. */
    private var firstLayout = false

    private var handoff: SplashHandoff? = null
    private var giveUp: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        val handoff = SplashHandoff(window).also { this.handoff = it }
        installHandoff(splash, handoff, savedInstanceState)
        lifecycleScope.launch {
            BiliClient.ensureBuvid()
            runCatching { Account.refresh() }
        }
        AppShortcuts.publishFixed(this)
        lifecycleScope.launch {
            // Signed in, out or into another account: a placed widget swaps its 稍后再看 at once.
            Account.user.map { it?.mid }.distinctUntilChanged().drop(1).collect {
                WatchWidget.refresh(applicationContext)
            }
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
                        // A launcher shortcut for the page that is already open (the same video, 稍后再看 …).
                        if (link.shortcut && (top == link.route || (top is Route.Video && top.bvid == (link.route as? Route.Video)?.bvid))) continue
                        // Through the navigator, so the covered page is frozen for its later reveal.
                        navigator.push(link.route)
                    }
                }
                CompositionLocalProvider(
                    LocalSnackbarHost provides snackbar,
                    com.gpo.pilipala.ui.components.LocalToastScope provides rootScope,
                    LocalSplashHandoff provides handoff,
                ) {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { firstLayout = true }) {
                        AppNavHost(navigator)
                        SnackbarHost(
                            snackbar,
                            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
                        )
                        SplashHandoffOverlay(handoff)
                    }
                }
            }
        }
    }

    /**
     * The splash (霹雳啵, drawable/splash_icon) stays only until Compose has laid out its first
     * frame, and never past the icon's own length. On exit it waits for the icon to finish (the
     * paw stamp lands at 770 ms), then hands over to [SplashHandoff]: from the launcher the mark
     * flies into the home header's avatar; off a link, shortcut, widget or notification it just
     * fades; with animations removed, or on a recreated activity, it is simply gone.
     */
    private fun installHandoff(splash: SplashScreen, handoff: SplashHandoff, savedInstanceState: Bundle?) {
        val created = SystemClock.uptimeMillis()
        val maxHold = resources.getInteger(R.integer.splash_icon_duration).toLong()
        splash.setKeepOnScreenCondition { !firstLayout && SystemClock.uptimeMillis() - created < maxHold }
        val launched = intent
        val fromLauncher = savedInstanceState == null &&
            launched?.action == Intent.ACTION_MAIN &&
            launched.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            launched.data == null
        // Below 12 the compat splash draws inside this window, under the app's bar appearance.
        if (savedInstanceState == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) handoff.coverBars()
        // Android 12+ calls the exit listener only for a splash it actually showed.
        giveUp = Runnable(handoff::abandonIfWaiting).also { window.decorView.postDelayed(it, SplashGiveUpMs) }
        splash.setOnExitAnimationListener { provider ->
            if (savedInstanceState != null || !ValueAnimator.areAnimatorsEnabled()) {
                handoff.skip(provider::remove)
                return@setOnExitAnimationListener
            }
            val decor = window.decorView
            decor.postDelayed({
                val icon = provider.iconView
                val hasIcon = icon.isAttachedToWindow && icon.width > 0
                val center = if (hasIcon) {
                    val at = IntArray(2).also(icon::getLocationInWindow)
                    Offset(at[0] + icon.width / 2f, at[1] + icon.height / 2f)
                } else {
                    Offset(decor.width / 2f, decor.height / 2f)
                }
                handoff.begin(
                    fly = fromLauncher,
                    iconCenter = center,
                    iconUnit = if (hasIcon) splashIconUnit(icon.width) else 0f,
                    windowSize = Size(decor.width.toFloat(), decor.height.toFloat()),
                    density = resources.displayMetrics.density,
                    remove = provider::remove,
                )
            }, remainingIconMs(provider))
        }
    }

    /**
     * px per unit of the icon's 108 viewport. Android 12+ sizes the icon view to the visible
     * 192dp circle (72 units) and draws the foreground 1.5x around it; the compat splash's
     * image view spans the whole 288dp foreground.
     */
    private fun splashIconUnit(iconPx: Int): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) iconPx / 72f else iconPx / 108f

    /** What is left of the icon's animation (0 below 12, where the icon is static). */
    private fun remainingIconMs(provider: SplashScreenViewProvider): Long {
        val start = provider.iconAnimationStartMillis
        val duration = provider.iconAnimationDurationMillis
        if (start <= 0L || duration <= 0L) return 0L
        // The platform stamps the start as an Instant; read it on the clock it came from.
        val wall = System.currentTimeMillis()
        val now = if (abs(wall - start) < 60_000L) wall else SystemClock.uptimeMillis()
        return (start + duration - now).coerceIn(0L, duration)
    }

    /** Android 15+: the PiP flight has started (or was called off); the player drops its chrome at once. */
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            if (pipState.isTransitioningToPip) {
                PipSignals.entering = true
            } else if (!isInPictureInPictureMode) {
                PipSignals.entering = false
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipSignals.entering = false
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) PipSignals.entering = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        giveUp?.let(window.decorView::removeCallbacks)
        giveUp = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        // The launcher, the live notification and the widget's open-the-app tap just bring the
        // app forward. Glance stamps a data-less intent with a glance-action: URI, which is no link.
        if (intent == null || intent.action == Intent.ACTION_MAIN) return
        if (intent.data?.scheme == GlanceActionScheme) return
        val data = intent.dataString ?: return
        // A page is about to cover the home: the handoff fades instead of flying to its avatar.
        handoff?.divert()
        AppShortcuts.route(this, intent)?.let { route ->
            deepLinks.trySend(DeepLink(route, resume = false, shortcut = true))
            return
        }
        val resume = intent.getBooleanExtra(PlaybackService.EXTRA_RESUME, false)
        deepLinks.trySend(DeepLink(LinkRouter.route(data), resume))
    }

    private companion object {
        const val GlanceActionScheme = "glance-action"

        /** No splash handed over by then (none was shown): the home stops waiting for one. */
        const val SplashGiveUpMs = 5_000L
    }
}
