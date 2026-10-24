package com.gpo.pilipala.feature.dynamics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.settings.AppSettings
import kotlinx.coroutines.CancellationException

/**
 * 动态 unread: the bar's 动态 button wears a dot while people you follow have posted since the
 * feed was last opened. The feed's update_baseline marks what was seen; the dot asks Bilibili
 * how many came after it. No count is shown, only that there is something.
 */
object DynUnread {
    private val baseline = AppSettings.register("dyn_seen_baseline", "")

    var hasNew by mutableStateOf(false)
        private set

    /** The 全部 feed's first page came in: everything up to [b] is seen. */
    fun seen(b: String) {
        if (b.isNotEmpty()) AppSettings.set(baseline, b)
        hasNew = false
    }

    fun clear() {
        hasNew = false
    }

    suspend fun check() {
        if (!BiliClient.isLoggedIn) {
            hasNew = false
            return
        }
        val b = baseline.value
        if (b.isEmpty()) return
        val n = try {
            BiliClient.getData("/x/polymer/web-dynamic/v1/feed/all/update", mapOf("type" to "all", "update_baseline" to b)).asObj.int("update_num")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return
        }
        hasNew = n > 0
    }
}
