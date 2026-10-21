package com.gpo.pilipala.yt.ui.theme

import androidx.compose.runtime.Composable
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.PiliTheme

/** One theme for the whole app: the YouTube part wears PiliPaw's (same colours, same 外观 settings). */
val LocalPawDark = LocalPiliDark

@Composable
fun PawTheme(
    seedOverride: Int? = null,
    accentOverride: Int? = null,
    content: @Composable () -> Unit,
) = PiliTheme(seedOverride, accentOverride, content)
