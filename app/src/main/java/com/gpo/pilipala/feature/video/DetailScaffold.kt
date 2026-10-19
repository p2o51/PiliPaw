package com.gpo.pilipala.feature.video

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import com.gpo.pilipala.ui.theme.LightBarIcons
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import com.gpo.pilipala.feature.player.LocalPlayerPictureShape
import androidx.compose.runtime.CompositionLocalProvider
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.ExpressivePageBackground
import com.gpo.pilipala.ui.components.LocalSnackbarHost
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import kotlinx.coroutines.launch

/** A tab label with an optional quiet count beside it ("评论" + "1234"). */
data class DetailTab(val label: String, val count: String? = null)

/** Two panes (player + intro | side tabs) on wide windows; stacked otherwise. */
@Composable
internal fun isTwoPane(): Boolean {
    val cfg = LocalConfiguration.current
    val w = cfg.screenWidthDp
    val h = cfg.screenHeightDp
    return w >= 840 || (w > h && w >= 600)
}

/**
 * Shared adaptive frame for the video and bangumi pages.
 *
 * - Fullscreen: only the player, on black.
 * - Two-pane (Expanded / landscape): left = player + [expandedMain], right = [sideTabs].
 * - Stacked (Compact / Medium portrait): black status-bar strip + 16:9 player pinned
 *   on top, tabs below, pages swipe horizontally.
 *
 * [player] must be a `movableContentOf` so the surface survives layout changes.
 */
@Composable
internal fun DetailScaffold(
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit,
    player: @Composable (Modifier) -> Unit,
    compactTabs: List<DetailTab>,
    compactPagerState: PagerState,
    compactPage: @Composable (page: Int, contentPadding: PaddingValues) -> Unit,
    expandedMain: @Composable (contentPadding: PaddingValues) -> Unit,
    sideTabs: List<DetailTab>,
    sidePage: @Composable (page: Int, contentPadding: PaddingValues) -> Unit,
) {
    val twoPane = isTwoPane()
    BackHandler(enabled = fullscreen) { onExitFullscreen() }
    val dark = LocalPiliDark.current
    StatusBarIcons(light = fullscreen || !twoPane || dark)

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ExpressivePageBackground(Modifier.fillMaxSize(), belowPlayer = !twoPane, animated = !fullscreen) {
        when {
            fullscreen -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                player(Modifier.fillMaxSize())
            }

            twoPane -> {
                val cfg = LocalConfiguration.current
                val w = cfg.screenWidthDp
                // Wide windows (tablet landscape) give the side pane a little more, so the
                // player column is not one huge 16:9 block over a sliver of intro.
                val sideWidth: Dp = minOf(480.dp, (w * if (w >= 1200) 0.36f else 0.4f).dp)
                Row(
                    Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        // The player takes at most ~58% of the height (16:9 width derived from
                        // it, centered), so the title, UP and actions stay above the fold.
                        val playerWidth = minOf(maxWidth, maxHeight * 0.58f * (16f / 9f))
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .width(playerWidth),
                            ) {
                                // The player owns its height (16:9 picture + its control strip). Only
                                // the picture is a black card; the strip under it sits on the page.
                                CompositionLocalProvider(LocalPlayerPictureShape provides YoinContainerShapes.Card) {
                                    player(Modifier.fillMaxWidth())
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                expandedMain(PaddingValues(top = 4.dp, bottom = navBottom + 16.dp))
                            }
                        }
                    }
                    val sideState = rememberPagerState { sideTabs.size }
                    Column(
                        Modifier
                            .width(sideWidth)
                            .fillMaxHeight()
                            .padding(top = 8.dp)
                            .clip(YoinContainerShapes.Panel)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        DetailTabRow(sideTabs, sideState, MaterialTheme.colorScheme.surfaceContainerLow)
                        HorizontalPager(
                            state = sideState,
                            modifier = Modifier.weight(1f),
                            beyondViewportPageCount = 1,
                            key = { sideTabs.getOrNull(it)?.label ?: it },
                        ) { page ->
                            // Each tab's list sinks under the tide line at the tab row (ui/components/seam).
                            Box(Modifier.fillMaxSize().seamViewport()) {
                                sidePage(page, PaddingValues(bottom = navBottom + 16.dp))
                            }
                        }
                    }
                }
            }

            else -> Column(Modifier.fillMaxSize()) {
                // Black only under the status bar and the picture: the control strip that
                // slides out below the picture sits on the page itself.
                // Under the status bar: a deep tone of the page's colour easing into the picture's
                // black, rather than a flat black band.
                val tint = MaterialTheme.colorScheme.primary
                val barBrush = remember(tint) {
                    Brush.verticalGradient(listOf(lerp(Color.Black, tint, 0.32f), Color.Black))
                }
                Spacer(Modifier.fillMaxWidth().background(barBrush).windowInsetsTopHeight(WindowInsets.statusBars))
                // The player owns its height (16:9 picture + its control strip).
                player(Modifier.fillMaxWidth())
                DetailTabRow(compactTabs, compactPagerState, Color.Transparent)
                HorizontalPager(
                    state = compactPagerState,
                    modifier = Modifier.weight(1f),
                    beyondViewportPageCount = 1,
                ) { page ->
                    // Intro and comments sink under the tide line at the tab row instead of being
                    // cut there; the mask shows the page's own wash beneath (ui/components/seam).
                    Box(Modifier.fillMaxSize().seamViewport()) {
                        compactPage(page, PaddingValues(bottom = navBottom + 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTabRow(tabs: List<DetailTab>, state: PagerState, container: Color) {
    val scope = rememberCoroutineScope()
    PrimaryTabRow(
        selectedTabIndex = state.currentPage.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
        containerColor = container,
        divider = {},
    ) {
        tabs.forEachIndexed { i, tab ->
            val selected = state.currentPage == i
            Tab(
                selected = selected,
                onClick = { scope.launch { state.animateScrollToPage(i) } },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.label, style = MaterialTheme.typography.titleSmall)
                        if (!tab.count.isNullOrEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                tab.count,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
    }
}

/** Light status-bar icons over the black player strip; restored when the page leaves. */
@Composable
private fun StatusBarIcons(light: Boolean) {
    LightBarIcons(enabled = light, status = true)
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Runs the action when signed in; otherwise a short snackbar with a 登录 action.
 * `val gate = rememberLoginGate(); gate { vm.like() }`
 */
@Composable
internal fun rememberLoginGate(): (() -> Unit) -> Unit {
    val host = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val nav = LocalNavigator.current
    return remember(host, scope, nav) {
        { block: () -> Unit ->
            if (BiliClient.isLoggedIn) {
                block()
            } else {
                scope.launch {
                    host.currentSnackbarData?.dismiss()
                    val r = host.showSnackbar("登录后可用", actionLabel = "登录", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) nav.push(Route.Login)
                }
            }
            Unit
        }
    }
}

/** System share sheet. */
internal fun shareText(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_TEXT, if (title.isNotEmpty()) "$title $url" else url)
    }
    context.startActivity(Intent.createChooser(send, title.ifEmpty { null }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
