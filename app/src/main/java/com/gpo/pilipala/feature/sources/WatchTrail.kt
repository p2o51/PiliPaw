package com.gpo.pilipala.feature.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gpo.pilipala.core.source.VideoSource

/**
 * A video left in place for another (继续播放 only): the related video or link that replaced it on
 * the same page. [key] is the video's identity (B站 bvid, YouTube url); [target] what reopens it
 * (a B站 Route.Video, a YouTube url and card).
 */
data class TrailEntry(
    val key: String,
    val source: VideoSource,
    val title: String,
    val cover: String,
    val positionMs: Long,
    val durationMs: Long,
    val target: Any,
)

/**
 * The videos one watch went through, newest first, at most [cap]. A video watched again moves to
 * the top (never twice in the list). Pure: [WatchTrail] holds the app's one.
 */
class TrailList(private val cap: Int = 20) {
    var entries: List<TrailEntry> = emptyList()
        private set

    /** [e] was left for another video. */
    fun push(e: TrailEntry) {
        entries = (listOf(e) + entries.filter { it.key != e.key }).take(cap)
    }

    /** [key] is being watched again: it leaves the list (the one it replaces is pushed by the caller). */
    fun take(key: String): TrailEntry? {
        val e = entries.firstOrNull { it.key == key } ?: return null
        entries = entries.filter { it.key != key }
        return e
    }

    /** The video now playing is not one to go back to. */
    fun forget(key: String) {
        entries = entries.filter { it.key != key }
    }

    fun clear() {
        entries = emptyList()
    }
}

/**
 * The 继续播放 watch's way back (the video page's back menu): videos a related tap or a link
 * replaced in place. It outlives the page while the video sits in the bar or the window; swiping
 * the bar away, closing the window, or opening a video from outside the page starts it afresh.
 */
object WatchTrail {
    private val list = TrailList(MaxEntries)

    /** Newest first; snapshot state (the back button and menu follow it). */
    var entries by mutableStateOf<List<TrailEntry>>(emptyList())
        private set

    val isEmpty: Boolean get() = entries.isEmpty()

    fun push(e: TrailEntry) = update { list.push(e) }

    fun take(key: String): TrailEntry? {
        var e: TrailEntry? = null
        update { e = list.take(key) }
        return e
    }

    fun forget(key: String) = update { list.forget(key) }

    fun clear() {
        if (entries.isEmpty()) return
        MiniLog.event("trail cleared", "")
        update { list.clear() }
    }

    private inline fun update(change: () -> Unit) {
        change()
        entries = list.entries
    }

    /**
     * The video page that takes a video in place, while it is the page on top (B站): returns true
     * when it took [route] (a Route.Video), so the navigator does not push it.
     */
    internal var inPlace: ((Any) -> Boolean)? = null

    const val MaxEntries = 20

    /** The most the back menu lists. */
    const val MenuEntries = 5
}
