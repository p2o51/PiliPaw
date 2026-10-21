package com.gpo.pilipala.yt.ui.components

import com.gpo.pilipala.ui.components.*

import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Pull-to-refresh with the M3 Expressive loading indicator (Yoin). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorPadding: PaddingValues = PaddingValues(),
    content: @Composable BoxScope.() -> Unit,
) {
    val isRefreshingState = rememberUpdatedState(isRefreshing)
    val state = rememberPullToRefreshState()
    // A refresh started by a tap (tab reselect) often lands in a few hundred ms; hold the
    // indicator for a short beat so it reads as a refresh instead of a flicker.
    var shown by remember { mutableStateOf(isRefreshing) }
    var since by remember { mutableLongStateOf(0L) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        snapshotFlow { isRefreshingState.value }.collectLatest { refreshing ->
            if (refreshing) {
                shown = true
                return@collectLatest
            }
            if (!shown) return@collectLatest
            // Ended: keep it up until it has shown for MinRefreshMs since it appeared.
            val held = SystemClock.uptimeMillis() - since
            if (held < MinRefreshMs) delay(MinRefreshMs - held)
            shown = false
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    LaunchedEffect(shown) { if (shown) since = SystemClock.uptimeMillis() }
    PullToRefreshBox(
        isRefreshing = shown,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = state,
                isRefreshing = shown,
                modifier = Modifier.align(Alignment.TopCenter).padding(indicatorPadding),
            )
        },
        content = content,
    )
}

/** The shortest time the refresh indicator stays up once it appears. */
private const val MinRefreshMs = 700L
