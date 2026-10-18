package com.gpo.pilipala.ui.shell

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
import com.gpo.pilipala.feature.dynamics.DynamicsScreen
import com.gpo.pilipala.feature.home.HomeScreen
import com.gpo.pilipala.feature.mine.MineScreen
import com.gpo.pilipala.feature.rank.RankScreen
import com.gpo.pilipala.ui.components.LocalBottomBarInset
import com.gpo.pilipala.ui.components.seam.ProvideSeamBarField
import com.gpo.pilipala.ui.launch.splashReveal
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
 * The shell: four sections under Yoin's floating button group. Sections keep
 * their state (scroll, tabs) while hidden; switching cross-fades with the
 * effects spring and a hint of scale (spatial spring).
 */
@Composable
fun MainShell() {
    val tabs = listOf(
        NavTab("首页", YoinSymbols.Home, YoinSymbols.HomeFilled),
        NavTab("排行", PiliSymbols.Trophy, PiliSymbols.TrophyFilled),
        NavTab("动态", PiliSymbols.Dynamic, PiliSymbols.DynamicFilled),
        NavTab("我的", YoinSymbols.Person, PiliSymbols.PersonFilled),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    val reselects = remember { MutableSharedFlow<Int>(extraBufferCapacity = 1) }
    BackHandler(enabled = selected != 0) { selected = 0 }
    val requested by ShellRequests.section.collectAsState()
    LaunchedEffect(requested) {
        val index = requested ?: return@LaunchedEffect
        if (index in tabs.indices) selected = index
        ShellRequests.consume()
    }

    // The floating bar reports where it sits; main-tab lists marked with seamViewport
    // break their graphics into the halftone field around it (Yoin's dissolve).
    ProvideSeamBarField {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            CompositionLocalProvider(LocalBottomBarInset provides floatingBarInset()) {
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
                                1 -> RankScreen()
                                2 -> DynamicsScreen()
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
                modifier = Modifier.align(Alignment.BottomCenter).splashReveal(lift = 22.dp),
            )
        }
    }
}
