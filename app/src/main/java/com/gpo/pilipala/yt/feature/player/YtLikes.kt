package com.gpo.pilipala.yt.feature.player

import androidx.compose.runtime.mutableStateMapOf
import com.gpo.pilipala.yt.core.account.AccountWrites
import com.gpo.pilipala.yt.core.extractor.YtAccount
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.ui.components.friendlyMessage

/**
 * The account's 赞 on the videos liked this run, one value for the video page and the listen
 * page (YouTube does not say up front whether a video is liked, so it starts off). Writes go
 * through AccountWrites (dry runs in debug builds).
 */
object YtLikes {
    private val liked = mutableStateMapOf<String, Boolean>()

    fun liked(url: String): Boolean = liked[Library.keyOf(url)] == true

    /** Likes [videoId] or takes it back; returns the line to show, or null when there is none. */
    suspend fun set(url: String, videoId: String, to: Boolean): String? {
        val key = Library.keyOf(url)
        liked[key] = to
        val line = runCatching { AccountWrites.run(if (to) "赞" else "取消赞") { YtAccount.like(videoId, to) } }
            .getOrElse { e ->
                liked[key] = !to
                e.friendlyMessage()
            }
        return line.takeIf { AccountWrites.dryRun || liked[key] != to }
    }
}
