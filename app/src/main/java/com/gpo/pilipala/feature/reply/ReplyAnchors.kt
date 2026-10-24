package com.gpo.pilipala.feature.reply

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A comment a notification points at (回复我的, @我的, 收到的赞): the comment list of
 * [oid] / [type] pins [root]'s thread above everything else, with [rpid] (when it is a
 * reply inside that thread) as its preview, and the host page opens on its comments.
 * [oid] / [type] are 0 when the notification does not say (a dynamic's comment subject).
 */
data class ReplyAnchor(val oid: Long, val type: Int, val root: Long, val rpid: Long) {
    fun matches(oid: Long, type: Int): Boolean =
        (this.oid == 0L || this.oid == oid) && (this.type == 0 || this.type == type)
}

/**
 * Hand-off between the message rows and the page they open: the row [put]s the anchor
 * and opens the content's own page; that page checks [has] to switch to its comments,
 * and its comment list [take]s it once. One at a time, and only for a short while, so an
 * anchor the page never used does not turn up in a later list. Snapshot state.
 *
 * A page may compose its comments before it asks (a pager keeps the 评论 tab composed next
 * to 简介, in the same frame the video's aid arrives): an anchor just taken still counts
 * for [has] a moment longer.
 */
object ReplyAnchors {
    private var pending by mutableStateOf<ReplyAnchor?>(null)
    private var putAt = 0L
    private var taken: ReplyAnchor? = null
    private var takenAt = 0L

    fun put(anchor: ReplyAnchor) {
        pending = anchor
        putAt = SystemClock.uptimeMillis()
        taken = null
    }

    private fun live(oid: Long, type: Int): ReplyAnchor? {
        val a = pending ?: return null
        if (SystemClock.uptimeMillis() - putAt > TtlMs) return null
        return a.takeIf { it.matches(oid, type) }
    }

    private fun justTaken(oid: Long, type: Int): Boolean {
        val a = taken ?: return false
        return SystemClock.uptimeMillis() - takenAt <= TakenGraceMs && a.matches(oid, type)
    }

    fun has(oid: Long, type: Int): Boolean = live(oid, type) != null || justTaken(oid, type)

    fun take(oid: Long, type: Int): ReplyAnchor? = live(oid, type)?.also {
        pending = null
        taken = it
        takenAt = SystemClock.uptimeMillis()
    }

    /**
     * The native link a comment notification carries, in either shape:
     * `bilibili://comment/detail/{type}/{oid}/{root}/?anchor={rpid}`, or the content's own
     * link with `comment_root_id` / `comment_secondary_id` (`bilibili://video/{aid}?…`).
     * [rpid] overrides the link's anchor when the caller knows better; [oid] / [type] fill
     * in what the link leaves out.
     */
    fun parse(nativeUri: String, rpid: Long = 0L, oid: Long = 0L, type: Int = 0): ReplyAnchor? {
        if (nativeUri.isBlank()) return null
        val uri = runCatching { Uri.parse(nativeUri) }.getOrNull() ?: return null
        if (uri.scheme != "bilibili") return null
        fun q(name: String) = runCatching { uri.getQueryParameter(name) }.getOrNull()?.toLongOrNull() ?: 0L
        val segs = uri.pathSegments
        if (uri.host == "comment") {
            if (segs.size < 4 || segs[0] != "detail") return null
            val t = segs[1].toIntOrNull() ?: return null
            val o = segs[2].toLongOrNull() ?: return null
            val root = segs[3].toLongOrNull() ?: return null
            if (o <= 0 || t <= 0 || root <= 0) return null
            return ReplyAnchor(o, t, root, rpid.takeIf { it > 0 } ?: q("anchor").takeIf { it > 0 } ?: root)
        }
        val root = q("comment_root_id").takeIf { it > 0 } ?: return null
        val second = q("comment_secondary_id")
        val linkOid = if (uri.host == "video") segs.firstOrNull()?.toLongOrNull() ?: 0L else 0L
        return ReplyAnchor(
            oid = oid.takeIf { it > 0 } ?: linkOid,
            type = type.takeIf { it > 0 } ?: if (linkOid > 0) 1 else 0,
            root = root,
            rpid = rpid.takeIf { it > 0 } ?: second.takeIf { it > 0 } ?: root,
        )
    }

    private const val TtlMs = 30_000L
    private const val TakenGraceMs = 3_000L
}
