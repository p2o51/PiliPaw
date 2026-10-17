package com.gpo.pilipala.feature.dynamics

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A user's dynamics feed, embeddable in the member page tab. CONTRACT — signature is fixed. */
@Composable
fun MemberDynamicsList(
    mid: Long,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {}
}
