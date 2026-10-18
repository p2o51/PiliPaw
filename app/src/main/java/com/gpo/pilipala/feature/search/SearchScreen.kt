package com.gpo.pilipala.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.BackButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * The search as a page (deep links, launcher shortcut): it opens straight into
 * the expanded native search, grown out of the page's own bar. Folding the
 * search away closes the page; a submit replaces it with what it opens.
 * [Route.Search.hint] is the placeholder an empty submit searches.
 */
@Composable
fun SearchScreen(route: Route.Search) {
    val nav = LocalNavigator.current
    val search = rememberPiliSearchState()

    LaunchedEffect(search) {
        if (search.bar.currentValue == SearchBarValue.Collapsed) {
            // The search grows out of the page's bar once that has a place.
            withFrameNanos { }
            try {
                search.bar.animateToExpanded()
            } catch (e: CancellationException) {
                // A back during the grow folds it again; only leaving the page stops here.
                ensureActive()
            }
        }
        // Folded away and at rest: without a submit, that closes the page.
        snapshotFlow { search.bar.currentValue == SearchBarValue.Collapsed && !search.bar.isAnimating }.first { it }
        if (!search.submitted) nav.pop()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton()
            Spacer(Modifier.width(8.dp))
            PiliSearchBar(
                state = search,
                hint = route.hint,
                onSubmit = { nav.openSearch(it, replace = true) },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(),
            )
        }
    }
}
