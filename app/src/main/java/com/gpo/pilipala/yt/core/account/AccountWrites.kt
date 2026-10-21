package com.gpo.pilipala.yt.core.account

import android.util.Log
import com.gpo.pilipala.BuildConfig

/**
 * Every action that changes the signed-in account (subscribe, like, add to a playlist) goes
 * through [run]. A debug build only says what it would have done (log and the returned
 * message), so testing on the owner's real account never touches it; a release build sends.
 */
object AccountWrites {
    /** Debug builds never write to the account. */
    val dryRun: Boolean = BuildConfig.DEBUG

    /**
     * Runs [send] for [what] (a short label, e.g. "订阅 Blender"), or in a dry run only logs
     * it. Returns the line to show the person ("调试版：未发送 …" in a dry run).
     */
    suspend fun run(what: String, send: suspend () -> Unit): String {
        if (dryRun) {
            Log.i("AccountWrites", "dry run: $what")
            return "调试版，未发送：$what"
        }
        send()
        return what
    }
}
