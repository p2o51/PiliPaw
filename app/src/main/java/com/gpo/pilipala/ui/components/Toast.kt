package com.gpo.pilipala.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch

val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/** `val toast = rememberToast(); toast("已收藏")` — one-line snackbar at the app root. */
@Composable
fun rememberToast(): (String) -> Unit {
    val host = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
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
