package com.gpo.pilipala.ui.shell

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
import com.gpo.pilipala.core.settings.AppSettings
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

/** The shell's sections; [ShellRequests.select] and the saved selection use the ordinal. */
enum class ShellSection(val key: String, val tab: NavTab, val removable: Boolean) {
    Home("home", NavTab("首页", YoinSymbols.Home, YoinSymbols.HomeFilled), removable = false),
    Rank("rank", NavTab("排行", PiliSymbols.Trophy, PiliSymbols.TrophyFilled), removable = true),
    Dynamics("dynamics", NavTab("动态", PiliSymbols.Dynamic, PiliSymbols.DynamicFilled), removable = true),
    Mine("mine", NavTab("我的", YoinSymbols.Person, PiliSymbols.PersonFilled), removable = false),
}

/**
 * 底栏 (设置 › 外观): which sections the bar shows. 首页 and 我的 always stay (我的 is where
 * settings live); 排行 and 动态 can go.
 */
object ShellSections {
    /** Keys of the hidden sections, comma separated. */
    val hiddenKey: AppSettings.Key<String> = AppSettings.register("shell_hidden_sections", "")

    fun visible(hidden: String): List<ShellSection> {
        val off = hidden.split(',').filter { it.isNotBlank() }.toSet()
        return ShellSection.entries.filter { !it.removable || it.key !in off }
    }

    fun isShown(hidden: String, section: ShellSection): Boolean = section in visible(hidden)

    fun setShown(section: ShellSection, shown: Boolean) {
        if (!section.removable) return
        val off = hiddenKey.value.split(',').filter { it.isNotBlank() }.toMutableSet()
        if (shown) off -= section.key else off += section.key
        AppSettings.set(hiddenKey, off.sorted().joinToString(","))
    }
}

/**
 * The shell: its sections ([ShellSections]) under Yoin's floating button group. Sections
 * keep their state (scroll, tabs) while hidden; switching cross-fades with the effects
 * spring and a hint of scale (spatial spring).
 */
@Composable
fun MainShell() {
    val hidden by ShellSections.hiddenKey.state.collectAsState()
    val sections = remember(hidden) { ShellSections.visible(hidden) }
    val tabs = remember(sections) { sections.map { it.tab } }
    var selectedOrdinal by rememberSaveable { mutableIntStateOf(ShellSection.Home.ordinal) }
    // A section switched off in settings while showing hands over to 首页.
    val current = ShellSection.entries[selectedOrdinal].takeIf { it in sections } ?: ShellSection.Home
    val selected = sections.indexOf(current)
    val holder = rememberSaveableStateHolder()
    val reselects = remember { MutableSharedFlow<Int>(extraBufferCapacity = 1) }
    // The four sections are peers: back never hops to 首页. On a section with a feed the first
    // back is a reselect (to the top, or a refresh when already there); on 我的 it only warns.
    // A second back within ExitWindowMs leaves the app.
    val activity = LocalActivity.current
    val toast = rememberToast()
    var armedAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = SystemClock.uptimeMillis()
        if (now - armedAt < ExitWindowMs) {
            activity?.finish()
        } else {
            armedAt = now
            if (current != ShellSection.Mine) reselects.tryEmit(current.ordinal)
            toast("再按一次退出")
        }
    }
    val requested by ShellRequests.section.collectAsState()
    LaunchedEffect(requested) {
        val index = requested ?: return@LaunchedEffect
        ShellSection.entries.getOrNull(index)?.takeIf { it in sections }?.let { selectedOrdinal = it.ordinal }
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
                    targetState = current,
                    transitionSpec = {
                        (fadeIn(enterFade) + scaleIn(enterScale, initialScale = 0.98f)).togetherWith(fadeOut(exitFade))
                    },
                    label = "shellSection",
                ) { section ->
                    holder.SaveableStateProvider(section.ordinal) {
                        val mine = remember(section) { reselects.filter { it == section.ordinal }.map { } }
                        CompositionLocalProvider(LocalTabReselect provides mine) {
                            when (section) {
                                ShellSection.Home -> HomeScreen()
                                ShellSection.Rank -> RankScreen()
                                ShellSection.Dynamics -> DynamicsScreen()
                                ShellSection.Mine -> MineScreen()
                            }
                        }
                    }
                }
            }
            FloatingNavBar(
                tabs = tabs,
                selected = selected,
                onSelect = { i ->
                    val tapped = sections[i]
                    if (tapped == current) reselects.tryEmit(tapped.ordinal) else selectedOrdinal = tapped.ordinal
                },
                modifier = Modifier.align(Alignment.BottomCenter).splashReveal(lift = 22.dp),
            )
        }
    }
}

private const val ExitWindowMs = 2000L
