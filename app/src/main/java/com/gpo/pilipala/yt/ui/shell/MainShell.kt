package com.gpo.pilipala.yt.ui.shell

import com.gpo.pilipala.ui.shell.*

import com.gpo.pilipala.ui.components.rememberToast
import androidx.compose.runtime.mutableLongStateOf
import androidx.activity.compose.LocalActivity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.yt.feature.home.HomeScreen
import com.gpo.pilipala.yt.feature.mine.MineScreen
import com.gpo.pilipala.yt.feature.player.miniPlayerInset
import com.gpo.pilipala.yt.feature.subscriptions.SubscriptionsScreen
import com.gpo.pilipala.yt.ui.components.LocalBottomBarInset
import com.gpo.pilipala.ui.components.seam.ProvideSeamBarField
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Fires when the user taps the bottom-bar tab of the section that is already
 * showing (scroll to top / refresh). Each section sees only its own taps.
 */
val LocalTabReselect = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }

/** Requests from outside the composition (a widget tap) to show one of the shell's sections. */
object ShellRequests {
    private val _section = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    internal val section: kotlinx.coroutines.flow.StateFlow<Int?> = _section

    fun select(index: Int) {
        _section.value = index
    }

    internal fun consume() {
        _section.value = null
    }
}

/**
 * The shell: three sections under Yoin's floating button group. Sections keep
 * their state (scroll, tabs) while hidden; switching cross-fades with the
 * effects spring and a hint of scale (spatial spring).
 */
@Composable
fun MainShell() {
    val nav = LocalNavigator.current
    val tabs = listOf(
        NavTab("首页", YoinSymbols.Home, YoinSymbols.HomeFilled),
        NavTab("订阅", YoinSymbols.Library, YoinSymbols.LibraryFilled),
        NavTab("我的", YoinSymbols.Person, PiliSymbols.PersonFilled),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    val reselects = remember { MutableSharedFlow<Int>(extraBufferCapacity = 1) }
    // The sections are peers: back never hops to 首页. On a section with a feed the first
    // back is a reselect (to the top, or a refresh when already there); on 我的 it only warns.
    // A second back within ExitWindowMs leaves the app.
    val activity = LocalActivity.current
    val toast = rememberToast()
    var armedAt by remember { mutableLongStateOf(0L) }
    // Never while the player is up: its own back collapses it, even when a link opened the player
    // before this handler was registered (the later one would win).
    BackHandler(enabled = !com.gpo.pilipala.yt.feature.player.Player.expanded) {
        val now = SystemClock.uptimeMillis()
        if (now - armedAt < ExitWindowMs) {
            activity?.finish()
        } else {
            armedAt = now
            if (selected != MineIndex) reselects.tryEmit(selected)
            toast("再按一次退出")
        }
    }
    val requested by ShellRequests.section.collectAsState()
    LaunchedEffect(requested) {
        val index = requested ?: return@LaunchedEffect
        if (index in tabs.indices) selected = index
        ShellRequests.consume()
    }

    // The floating bar reports where it sits; main-tab lists marked with seamViewport
    // break their graphics into the halftone field around it (Yoin's dissolve).
    ProvideSeamBarField {
        // The nav pill anchors the app's mini bar (as in the Bilibili shell).
        val field = com.gpo.pilipala.ui.components.seam.LocalSeamBarField.current
        val spotToken = remember { Any() }
        LaunchedEffect(field) {
            if (field == null) return@LaunchedEffect
            androidx.compose.runtime.snapshotFlow { field.bounds.takeIf { field.attached && it.width > 0f } }
                .collect { com.gpo.pilipala.feature.sources.NavSpot.place(spotToken, it) }
        }
        androidx.compose.runtime.DisposableEffect(spotToken) {
            onDispose { com.gpo.pilipala.feature.sources.NavSpot.clear(spotToken) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .nestedScroll(com.gpo.pilipala.feature.sources.KeepPlaying.scroll),
        ) {
            // Lists clear the floating bar and, while a video is open, the mini bar above it.
            CompositionLocalProvider(
                LocalBottomBarInset provides floatingBarInset() + com.gpo.pilipala.feature.sources.miniBarInset(),
            ) {
                val enterFade = YoinMotion.defaultEffectsSpec<Float>()
                val enterScale = YoinMotion.defaultSpatialSpec<Float>()
                val exitFade = YoinMotion.fastEffectsSpec<Float>()
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        (fadeIn(enterFade) + scaleIn(enterScale, initialScale = 0.98f)).togetherWith(fadeOut(exitFade))
                    },
                    label = "shellSection",
                ) { index ->
                    holder.SaveableStateProvider(index) {
                        val mine = remember(index) { reselects.filter { it == index }.map { } }
                        CompositionLocalProvider(LocalTabReselect provides mine) {
                            when (index) {
                                0 -> HomeScreen()
                                1 -> SubscriptionsScreen()
                                else -> MineScreen()
                            }
                        }
                    }
                }
            }
            FloatingNavBar(
                tabs = tabs,
                selected = selected,
                onSelect = { if (it == selected) reselects.tryEmit(it) else selected = it },
                modifier = Modifier.align(Alignment.BottomCenter),
                // Long press on 首页 opens search, as in PiliPaw.
                onLongPress = { nav.push(Route.Search()) },
                hasLongPress = { it == 0 },
            )
        }
    }
}

private const val MineIndex = 2
private const val ExitWindowMs = 2000L
