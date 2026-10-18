package com.gpo.pilipala.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Edge-to-edge bottom sheets. Every `ModalBottomSheet` in the app runs its surface under
 * the navigation bar: pass `contentWindowInsets = SheetInsets` and
 * `properties = rememberSheetProperties()`, and pad the scrolling content's bottom with
 * [sheetBottomPadding] / [sheetContentPadding] (the last row clears the bar, the list
 * scrolls beneath it). Sheets with a text field keep `navigationBarsPadding().imePadding()`
 * on their column instead.
 */
val SheetInsets: @Composable () -> WindowInsets = { WindowInsets(0) }

/** The navigation bar's height. */
@Composable
fun sheetNavBottom(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Bottom padding for a sheet's content: [extra] plus the navigation bar. */
@Composable
fun sheetBottomPadding(extra: Dp = 24.dp): Dp = extra + sheetNavBottom()

/** [PaddingValues] for a sheet's list: horizontal / top as given, bottom clears the navigation bar. */
@Composable
fun sheetContentPadding(horizontal: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 24.dp): PaddingValues =
    PaddingValues(start = horizontal, end = horizontal, top = top, bottom = sheetBottomPadding(bottom))

/**
 * Sheet window properties that match the page under it: status-bar icons as the page has
 * them (light over a black player strip or a dark cover), navigation-bar icons for the
 * sheet's own surface ([container], the sheet's default container unless overridden).
 */
@Composable
fun rememberSheetProperties(
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
): ModalBottomSheetProperties {
    val activity = LocalActivity.current
    val view = LocalView.current
    val sheetIsLight = container.luminance() > 0.5f
    return remember(activity, view, sheetIsLight) {
        val window = activity?.window
        val lightStatus = window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars } ?: !sheetIsLight
        ModalBottomSheetProperties(
            isAppearanceLightStatusBars = lightStatus,
            isAppearanceLightNavigationBars = sheetIsLight,
        )
    }
}
