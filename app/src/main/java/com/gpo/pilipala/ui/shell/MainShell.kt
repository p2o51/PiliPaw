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
import androidx.compose.runtime.DisposableEffect
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.widthClass
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
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.source.Sources
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.feature.dynamics.DynamicsScreen
import com.gpo.pilipala.feature.home.HomeScreen
import com.gpo.pilipala.feature.library.FavScreen
import com.gpo.pilipala.feature.mine.MineScreen
import com.gpo.pilipala.feature.rank.RankScreen
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.LocalBottomBarInset
import com.gpo.pilipala.ui.components.seam.ProvideSeamBarField
import com.gpo.pilipala.ui.launch.splashReveal
import com.gpo.pilipala.ui.theme.YoinMotion
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

/** Long press on the bar's 首页: 首页 shows and opens its search. */
val LocalHomeSearchRequest = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }

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
 * The shell's sections; [ShellRequests.select] and the saved selection use the ordinal, so new
 * ones go at the end. [fits] says which ticked sources a section belongs with.
 */
enum class ShellSection(val key: String, val tab: NavTab, val removable: Boolean) {
    Home("home", NavTab("首页", YoinSymbols.Home, YoinSymbols.HomeFilled), removable = false),
    Rank("rank", NavTab("排行", YoinSymbols.Trophy, YoinSymbols.TrophyFilled), removable = true),
    Dynamics("dynamics", NavTab("动态", YoinSymbols.Dynamic, YoinSymbols.DynamicFilled), removable = true),
    Fav("fav", NavTab("收藏", YoinSymbols.Star, YoinSymbols.StarFilled), removable = true),
    Mine("mine", NavTab("我的", YoinSymbols.Person, YoinSymbols.PersonFilled), removable = true),

    /** YouTube's subscriptions, in the bar while YouTube is ticked alone (else 动态 and 关注 cover follows). */
    Subs("yt_subs", NavTab("订阅", YoinSymbols.Library, YoinSymbols.LibraryFilled), removable = true),
    ;

    /** 排行 and 动态 are Bilibili's own; 订阅 only stands in for them while YouTube is ticked alone. */
    fun fits(enabled: Set<VideoSource>): Boolean = when (this) {
        Rank, Dynamics -> VideoSource.Bilibili in enabled
        Subs -> VideoSource.Bilibili !in enabled && VideoSource.YouTube in enabled
        else -> true
    }
}

/**
 * 底栏 (设置 › 外观): which sections the bar shows. 首页 always stays; 我的 is off by default
 * since it moved into the avatar menu at Home's top left (where 设置 lives too).
 */
object ShellSections {
    /** Keys of the hidden sections, comma separated. */
    val hiddenKey: AppSettings.Key<String> = AppSettings.register("shell_hidden_sections", "")

    /** [enabled]: the ticked sources, which drop the sections that do not [ShellSection.fits] them; null keeps all. */
    fun visible(hidden: String, enabled: Set<VideoSource>? = null): List<ShellSection> {
        val off = hidden.split(',').filter { it.isNotBlank() }.toSet()
        return ShellSection.entries.filter { (!it.removable || it.key !in off) && (enabled == null || it.fits(enabled)) }
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
    // YouTube ticked alone: the same shell, minus what only Bilibili has (no swap to another shell).
    val enabled by Sources.enabled.collectAsState()
    val sections = remember(hidden, enabled) { ShellSections.visible(hidden, enabled) }
    MineMovedToAvatar()
    FourTabsForMiniBar(sections)
    val tabs = remember(sections) { sections.map { it.tab } }
    var selectedOrdinal by rememberSaveable { mutableIntStateOf(ShellSection.Home.ordinal) }
    // A section switched off in settings while showing hands over to 首页.
    val current = ShellSection.entries[selectedOrdinal].takeIf { it in sections } ?: ShellSection.Home
    // 动态's dot: checked on open and every few minutes; opening 动态 clears it.
    LaunchedEffect(Unit) {
        while (true) {
            com.gpo.pilipala.feature.dynamics.DynUnread.check()
            kotlinx.coroutines.delay(5 * 60_000L)
        }
    }
    LaunchedEffect(current) {
        if (current == ShellSection.Dynamics) com.gpo.pilipala.feature.dynamics.DynUnread.clear()
    }
    val selected = sections.indexOf(current)
    val holder = rememberSaveableStateHolder()
    val reselects = remember { MutableSharedFlow<Int>(extraBufferCapacity = 1) }
    val homeSearch = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    // The four sections are peers: back never hops to 首页. On a section with a feed the first
    // back is a reselect (to the top, or a refresh when already there); on 我的 it only warns.
    // A second back within ExitWindowMs leaves the app.
    val activity = LocalActivity.current
    val toast = rememberToast()
    var armedAt by remember { mutableLongStateOf(0L) }
    // Never while the YouTube player is up: its own back collapses it.
    BackHandler(enabled = !com.gpo.pilipala.yt.feature.player.Player.expanded) {
        val now = SystemClock.uptimeMillis()
        if (now - armedAt < ExitWindowMs) {
            activity?.finish()
        } else {
            armedAt = now
            if (current != ShellSection.Mine && current != ShellSection.Subs) reselects.tryEmit(current.ordinal)
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
        // The nav pill's rounded rectangle (as the seams know it) anchors the mini bar.
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
                // Scrolling down folds the mini bar into its circle beside the nav bar.
                .nestedScroll(com.gpo.pilipala.feature.sources.KeepPlaying.scroll),
        ) {
            val barInset = floatingBarInset() + if (widthClass() == WidthClass.Compact) com.gpo.pilipala.feature.sources.miniBarInset() else 0.dp
            CompositionLocalProvider(LocalBottomBarInset provides barInset) {
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
                        CompositionLocalProvider(LocalTabReselect provides mine, LocalHomeSearchRequest provides homeSearch) {
                            when (section) {
                                ShellSection.Home -> HomeScreen()
                                ShellSection.Rank -> RankScreen()
                                ShellSection.Dynamics -> DynamicsScreen()
                                ShellSection.Fav -> FavScreen(Route.Fav(), asTab = true)
                                ShellSection.Mine -> MineScreen()
                                // YouTube's screens read their own inset.
                                ShellSection.Subs -> CompositionLocalProvider(
                                    com.gpo.pilipala.yt.ui.components.LocalBottomBarInset provides barInset,
                                ) { com.gpo.pilipala.yt.feature.subscriptions.SubscriptionsScreen() }
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
                hasLongPress = { i -> sections.getOrNull(i) == ShellSection.Home },
                badge = { i -> sections.getOrNull(i) == ShellSection.Dynamics && current != ShellSection.Dynamics && com.gpo.pilipala.feature.dynamics.DynUnread.hasNew },
                onLongPress = {
                    selectedOrdinal = ShellSection.Home.ordinal
                    homeSearch.tryEmit(Unit)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .splashReveal(lift = 22.dp),
            )
        }
    }
}

private const val ExitWindowMs = 2000L

/**
 * 继续播放 on a phone: the mini bar's circle joins the nav bar's row, which then has room for four
 * tabs. Once, the fifth (排行, else 动态) is switched off where the user can switch it back
 * (设置 › 外观 › 底栏), and a toast says so.
 */
@Composable
private fun FourTabsForMiniBar(sections: List<ShellSection>) {
    val keep by com.gpo.pilipala.feature.sources.KeepPlaying.mode.state.collectAsState()
    val compact = widthClass() == WidthClass.Compact
    val done by FourTabsDone.state.collectAsState()
    val toast = rememberToast()
    LaunchedEffect(keep, compact, sections.size, done) {
        if (keep != 2 || !compact || done || sections.size <= 4) return@LaunchedEffect
        val drop = listOf(ShellSection.Rank, ShellSection.Dynamics, ShellSection.Fav).firstOrNull { it in sections } ?: return@LaunchedEffect
        ShellSections.setShown(drop, false)
        com.gpo.pilipala.core.settings.AppSettings.set(FourTabsDone, true)
        toast("「${drop.tab.label}」已收起，可在 设置 › 外观 › 底栏 中打开")
    }
}

/**
 * Once, on the first run with the avatar menu: 我的 leaves the bar (it is in the menu now) and a
 * toast says where it went; 设置 › 外观 › 底栏 brings it back.
 */
@Composable
private fun MineMovedToAvatar() {
    val done by MineMovedDone.state.collectAsState()
    val toast = rememberToast()
    LaunchedEffect(done) {
        if (done) return@LaunchedEffect
        ShellSections.setShown(ShellSection.Mine, false)
        com.gpo.pilipala.core.settings.AppSettings.set(MineMovedDone, true)
        toast("「我的」在左上角头像里")
    }
}

private val MineMovedDone = com.gpo.pilipala.core.settings.AppSettings.register("mine_moved_to_avatar", false)

private val FourTabsDone = com.gpo.pilipala.core.settings.AppSettings.register("four_tabs_for_mini_bar", false)
