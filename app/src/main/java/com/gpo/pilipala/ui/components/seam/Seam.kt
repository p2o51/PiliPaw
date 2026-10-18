package com.gpo.pilipala.ui.components.seam

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/*
 * PiliPala's entry points to Yoin's seam dissolve (docs/design.md "溶解"):
 *
 *   LazyVerticalGrid(state = grid, modifier = Modifier.fillMaxSize().seamViewport(grid)) { … }
 *   NetImage(…)                                  // a graphic already: covers / avatars carry seamGraphic()
 *   Column(Modifier.seamText()) { Text(title); Text(owner) }   // text blocks fade, never dot
 *
 * - [seamViewport] marks the scroll container whose top edge meets pinned
 *   chrome (a tab / filter row, a top bar, the status bar). Under the main
 *   tabs' shell it also takes the halftone field under the floating bar.
 * - [seamGraphic]: covers, avatars, tinted cards. NetImage (and so Avatar)
 *   carries it; put it on a whole card / cover box to make it one print —
 *   the images inside defer to it.
 * - [seamText]: text, icons, numbers. Fades below the top seam's line, lifts
 *   whole out of a breaking print, passes under the bar untouched. Nested
 *   seamText blocks fade once (the outermost wins).
 * All three are no-ops outside a viewport, so shared components carry them
 * everywhere at no cost; at rest nothing redraws.
 */

/** Followers (scroll speed, afterglow, travel) shared by a viewport and a painted [SeamStatusBarTide]. */
@Composable
fun rememberSeamFlow(): SeamFlow = remember { SeamFlow() }

/**
 * Marks a lazy list as a seam viewport (put it on the list itself).
 *
 * @param topChrome how far below this element's top edge the pinned chrome
 *   ends: 0 when the list starts right under its tab / filter row; the
 *   Scaffold's top padding when the list runs under a top bar.
 * @param underStatusBar the list runs up under the status bar (nothing else
 *   pinned): the seam is the status bar's lower edge (+ [topChrome]) and is
 *   always the tide line, whatever top style the user picked (as Yoin's
 *   status bar).
 * @param pageColor the page colour behind the list (the bottom field eases
 *   its dots toward it). The field joins only under the main tabs' shell.
 * @param flow hoisted followers, to drive a painted [SeamStatusBarTide] on the same beat.
 */
@Composable
fun Modifier.seamViewport(
    state: LazyListState,
    topChrome: Dp = 0.dp,
    underStatusBar: Boolean = false,
    pageColor: Color = MaterialTheme.colorScheme.surface,
    flow: SeamFlow? = null,
    top: SeamTop = SeamTop.Chrome,
): Modifier = seamViewportOf(
    scrolledPx = remember(state) { { state.seamScrolledPx() } },
    remainingPx = remember(state) { { state.seamRemainingPx() } },
    topChrome = topChrome,
    underStatusBar = underStatusBar,
    pageColor = pageColor,
    flow = flow,
    top = top,
)

/** [seamViewport] for a lazy grid. */
@Composable
fun Modifier.seamViewport(
    state: LazyGridState,
    topChrome: Dp = 0.dp,
    underStatusBar: Boolean = false,
    pageColor: Color = MaterialTheme.colorScheme.surface,
    flow: SeamFlow? = null,
    top: SeamTop = SeamTop.Chrome,
): Modifier = seamViewportOf(
    scrolledPx = remember(state) { { state.seamScrolledPx() } },
    remainingPx = remember(state) { { state.seamRemainingPx() } },
    topChrome = topChrome,
    underStatusBar = underStatusBar,
    pageColor = pageColor,
    flow = flow,
    top = top,
)

/** [seamViewport] for a staggered grid. */
@Composable
fun Modifier.seamViewport(
    state: LazyStaggeredGridState,
    topChrome: Dp = 0.dp,
    underStatusBar: Boolean = false,
    pageColor: Color = MaterialTheme.colorScheme.surface,
    flow: SeamFlow? = null,
    top: SeamTop = SeamTop.Chrome,
): Modifier = seamViewportOf(
    scrolledPx = remember(state) { { state.seamScrolledPx() } },
    remainingPx = remember(state) { { state.seamRemainingPx() } },
    topChrome = topChrome,
    underStatusBar = underStatusBar,
    pageColor = pageColor,
    flow = flow,
    top = top,
)

/**
 * [seamViewport] for a `verticalScroll` column: put it BEFORE
 * `.verticalScroll(state)` in the chain (it must sit on the scroll
 * container's own node or an ancestor of it).
 */
@Composable
fun Modifier.seamViewport(
    state: ScrollState,
    topChrome: Dp = 0.dp,
    underStatusBar: Boolean = false,
    pageColor: Color = MaterialTheme.colorScheme.surface,
    flow: SeamFlow? = null,
    top: SeamTop = SeamTop.Chrome,
): Modifier = seamViewportOf(
    scrolledPx = remember(state) { { state.value.toFloat() } },
    remainingPx = remember(state) { { state.seamRemainingPx() } },
    topChrome = topChrome,
    underStatusBar = underStatusBar,
    pageColor = pageColor,
    flow = flow,
    top = top,
)

/**
 * [seamViewport] around a scroll container whose state is out of reach (a
 * list another feature owns, e.g. ReplyList): put it on any ancestor whose
 * top edge is the seam. The scroll is followed through nested scroll — exact
 * for touch scrolling; a programmatic jump shows once the next drag moves it.
 * No bottom field (it needs to know where the content ends).
 */
@Composable
fun Modifier.seamViewport(
    topChrome: Dp = 0.dp,
    underStatusBar: Boolean = false,
    flow: SeamFlow? = null,
    top: SeamTop = SeamTop.Chrome,
): Modifier = seamViewportOf(
    scrolledPx = null,
    remainingPx = null,
    topChrome = topChrome,
    underStatusBar = underStatusBar,
    pageColor = Color.Unspecified,
    flow = flow,
    top = top,
)

/**
 * The general form: [scrolledPx] is how far the content has scrolled from rest
 * (exact up to 40dp; saturate after), [remainingPx] how far it can still
 * scroll toward its end (null: no bottom field).
 */
@Composable
fun Modifier.seamViewport(
    scrolledPx: () -> Float,
    remainingPx: (() -> Float)?,
    topChrome: Dp = 0.dp,
    underStatusBar: Boolean = false,
    pageColor: Color = MaterialTheme.colorScheme.surface,
    flow: SeamFlow? = null,
    top: SeamTop = SeamTop.Chrome,
): Modifier = seamViewportOf(scrolledPx, remainingPx, topChrome, underStatusBar, pageColor, flow, top)

@Composable
private fun Modifier.seamViewportOf(
    scrolledPx: (() -> Float)?,
    remainingPx: (() -> Float)?,
    topChrome: Dp,
    underStatusBar: Boolean,
    pageColor: Color,
    flow: SeamFlow?,
    top: SeamTop,
): Modifier {
    val statusBar = if (underStatusBar) {
        with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    } else {
        0.dp
    }
    val background = remember(pageColor) { if (pageColor.isSpecified()) SeamBackground(listOf(pageColor)) else null }
    return seamDissolveViewport(
        top = top,
        topInset = statusBar + topChrome,
        flow = flow,
        background = background,
        remainingPx = remainingPx.takeIf { background != null },
        // Yoin's status bar is always the tide line; the user's style is for chrome seams.
        style = if (underStatusBar && top == SeamTop.Chrome) SeamTopStyle.Tide else null,
        scrolledPx = scrolledPx,
    )
}

private fun Color.isSpecified(): Boolean = this != Color.Unspecified

/**
 * A graphic for the seams (covers, avatars, thumbnails, tinted cards): it
 * sinks under the tide line, breaks into the user's top style, and turns into
 * the halftone print in the floating bar's field. NetImage carries it; on a
 * card or a cover box with overlays it makes the whole box one print (images
 * inside defer to it, [seamText] inside is lifted out whole).
 */
fun Modifier.seamGraphic(): Modifier = seamDissolve()

/**
 * Text (also icons and numbers): fades out below the top seam's line over
 * max(10dp, 0.75 × [fontSize]), never breaks into dots, passes under the
 * floating bar untouched. Put it on a text block (a Column of title + meta)
 * rather than on each Text; nested blocks fade once.
 */
fun Modifier.seamText(fontSize: TextUnit = TextUnit.Unspecified): Modifier = seamFade(fontSize)

/**
 * A painted tide over the status bar, for a page whose content runs under the
 * status bar when the scroll container cannot mask it itself (prefer
 * `seamViewport(state, underStatusBar = true)`, which reveals the real
 * background). Place it last in a Box over the page; pair the list with
 * `seamViewport(state, flow = flow, top = SeamTop.FadeText, topChrome = 2.dp, underStatusBar = true)`
 * so its text fades at the same line. [color] is the page colour at the top
 * (flat pages only — a cover-tinted wash needs the masking viewport).
 */
@Composable
fun SeamStatusBarTide(
    flow: SeamFlow,
    scrolledPx: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
) {
    val statusBarPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    Spacer(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .seamTide(flow = flow, color = color, statusBarPx = statusBarPx, scrolledPx = scrolledPx),
    )
}

/** [SeamStatusBarTide] for a lazy list. */
@Composable
fun SeamStatusBarTide(
    state: LazyListState,
    flow: SeamFlow,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
) = SeamStatusBarTide(flow, remember(state) { { state.seamScrolledPx() } }, modifier, color)

/** [SeamStatusBarTide] for a lazy grid. */
@Composable
fun SeamStatusBarTide(
    state: LazyGridState,
    flow: SeamFlow,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
) = SeamStatusBarTide(flow, remember(state) { { state.seamScrolledPx() } }, modifier, color)

/** [SeamStatusBarTide] for a staggered grid. */
@Composable
fun SeamStatusBarTide(
    state: LazyStaggeredGridState,
    flow: SeamFlow,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
) = SeamStatusBarTide(flow, remember(state) { { state.seamScrolledPx() } }, modifier, color)
