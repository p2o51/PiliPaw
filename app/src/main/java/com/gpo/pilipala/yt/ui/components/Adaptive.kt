package com.gpo.pilipala.yt.ui.components

import com.gpo.pilipala.ui.components.*

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Yoin's three layout tiers (Compact < 600 ≤ Medium < 840 ≤ Expanded). */
enum class WidthClass { Compact, Medium, Expanded }

@Composable
fun widthClass(): WidthClass {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w < 600 -> WidthClass.Compact
        w < 840 -> WidthClass.Medium
        else -> WidthClass.Expanded
    }
}

/** Grid columns for video cards at the current width (min card ~180dp, phone = 2). */
@Composable
fun videoGridColumns(): Int {
    val w = LocalConfiguration.current.screenWidthDp
    return (w / 200).coerceIn(2, 6)
}

/** Centers reading-width content (lists, text pages) on wide windows. */
fun Modifier.readingWidth(max: Dp = 840.dp): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

/**
 * Space the shell's floating bar occupies at the bottom (0 on pushed pages).
 * Lists add it to their bottom content padding.
 */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }
