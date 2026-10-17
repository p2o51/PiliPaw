package com.gpo.pilipala.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/** App-root scope so a toast survives the screen that fired it (e.g. right before nav.pop()). */
val LocalToastScope = staticCompositionLocalOf<CoroutineScope?> { null }

/** `val toast = rememberToast(); toast("已收藏")` — one-line snackbar at the app root. */
@Composable
fun rememberToast(): (String) -> Unit {
    val host = LocalSnackbarHost.current
    val local = rememberCoroutineScope()
    val scope = LocalToastScope.current ?: local
    return remember(host, scope) {
        { msg: String ->
            scope.launch {
                host.currentSnackbarData?.dismiss()
                host.showSnackbar(msg)
            }
            Unit
        }
    }
}
