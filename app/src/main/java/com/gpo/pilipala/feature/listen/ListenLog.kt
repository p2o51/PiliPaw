package com.gpo.pilipala.feature.listen

import android.os.SystemClock

/**
 * Debug builds: how long 显示画面 takes to put a picture back (tag PiliListen), so the switch
 * between 仅听声音 and the video can be measured.
 */
internal object ListenLog {
    private var shownAt = 0L

    fun event(what: String) {
        if (!com.gpo.pilipala.BuildConfig.DEBUG) return
        android.util.Log.d("PiliListen", what)
    }

    /** The picture was asked back ([source]: B站 / YouTube, and how). */
    fun videoAsked(source: String) {
        if (!com.gpo.pilipala.BuildConfig.DEBUG) return
        shownAt = SystemClock.elapsedRealtime()
        event("show video ($source)")
    }

    /** Its first frame drew. */
    fun firstFrame() {
        if (!com.gpo.pilipala.BuildConfig.DEBUG || shownAt == 0L) return
        event("first frame after ${SystemClock.elapsedRealtime() - shownAt} ms")
        shownAt = 0L
    }
}
