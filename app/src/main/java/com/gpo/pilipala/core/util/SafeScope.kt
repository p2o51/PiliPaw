package com.gpo.pilipala.core.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * For the app's own long-lived scopes (`CoroutineScope(SupervisorJob() + …)`): a coroutine
 * there that throws has no parent to hand the error to, so without a handler it goes to the
 * thread's default handler and the process dies. With this it is logged and dropped; the
 * work it was doing simply does not happen.
 */
val ScopeErrors = CoroutineExceptionHandler { context, error ->
    Log.w("PiliPaw", "uncaught in ${context[kotlinx.coroutines.CoroutineName]?.name ?: "scope"}", error)
}
