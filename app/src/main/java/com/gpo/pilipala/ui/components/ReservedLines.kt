package com.gpo.pilipala.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * A card's title block that always takes the height of a two-line title (so neighbouring
 * cards line up) while the lines under the title keep a fixed gap to it: with a one-line
 * title the spare room ends up at the bottom of the card, not between title and UP
 * (Spotify's arrangement). [content] is laid out twice: once invisible with `reserve = true`
 * (give the title `minLines = 2`), once for real with `reserve = false`.
 */
@Composable
fun ReservedTitleColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(reserve: Boolean) -> Unit,
) {
    Box(modifier) {
        Column(Modifier.alpha(0f).clearAndSetSemantics { }) { content(true) }
        Column { content(false) }
    }
}
