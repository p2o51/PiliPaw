package com.gpo.pilipala.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

private val CenteredTrim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

/**
 * A label style set tight: the line box is trimmed to the font's own height with the glyphs
 * centred in it, so `Alignment.CenterVertically` in a Row centres on the text's visual centre
 * (not on a line box with more room on one side), and a tag's vertical padding reads equal.
 * For small text next to icons, avatars or other text: stat rows, tags, badges, meta rows.
 */
fun TextStyle.centered(): TextStyle = copy(lineHeightStyle = CenteredTrim)

/**
 * Size of an icon set inline with text in [this] style. The symbols draw in about 3/4 of
 * their box, so 1.2em draws a glyph as tall as the text's capitals and CJK; it follows the
 * user's font scale like the text does.
 */
@Composable
fun TextStyle.inlineIconSize(): Dp = with(LocalDensity.current) {
    val size = if (fontSize.isSpecified) fontSize else 14.sp
    (size * 1.2f).toDp()
}
