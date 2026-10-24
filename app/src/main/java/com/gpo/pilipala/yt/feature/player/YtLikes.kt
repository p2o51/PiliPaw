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

    /** 点踩 this run; YouTube keeps one rating per video, so it and [liked] never both hold. */
    private val disliked = mutableStateMapOf<String, Boolean>()

    fun liked(url: String): Boolean = liked[Library.keyOf(url)] == true

    fun disliked(url: String): Boolean = disliked[Library.keyOf(url)] == true

    /** 点踩 [videoId] or takes it back (a 点踩 replaces the 赞); returns the line to show, or null. */
    suspend fun setDislike(url: String, videoId: String, to: Boolean): String? {
        val key = Library.keyOf(url)
        val wasLiked = liked[key] == true
        disliked[key] = to
        if (to) liked[key] = false
        val line = runCatching { AccountWrites.run(if (to) "点踩" else "取消点踩") { YtAccount.dislike(videoId, to) } }
            .getOrElse { e ->
                disliked[key] = !to
                if (to) liked[key] = wasLiked
                e.friendlyMessage()
            }
        return line.takeIf { AccountWrites.dryRun || disliked[key] != to }
    }

    /** Likes [videoId] or takes it back; returns the line to show, or null when there is none. */
    suspend fun set(url: String, videoId: String, to: Boolean): String? {
        val key = Library.keyOf(url)
        liked[key] = to
        val wasDisliked = disliked[key] == true
        if (to) disliked[key] = false
        val line = runCatching { AccountWrites.run(if (to) "赞" else "取消赞") { YtAccount.like(videoId, to) } }
            .getOrElse { e ->
                liked[key] = !to
                if (to) disliked[key] = wasDisliked
                e.friendlyMessage()
            }
        return line.takeIf { AccountWrites.dryRun || liked[key] != to }
    }
}
