package com.gpo.pilipala.feature.reply

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asLong
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.has
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import kotlinx.serialization.json.JsonObject

@Immutable
internal data class ReplyMember(
    val mid: Long,
    val name: String,
    val face: String,
    val level: Int,
    val vip: Boolean,
    /** 0 = personal verified, 1 = organisation, -1 = none. */
    val officialType: Int,
)

@Immutable
internal data class ReplyPicture(val src: String, val width: Int, val height: Int)

/**
 * An inline emote. [size] 2+ = the big stickers some packages use, laid out
 * larger inline. [name] is its short label ("doge", "大笑"); [pack] the
 * package part of "[包_名]" codes, empty when the code has none.
 */
@Immutable
internal data class ReplyEmote(val url: String, val size: Int, val name: String = "", val pack: String = "")

@Immutable
internal data class ReplyJump(
    val title: String,
    val appUrl: String,
    val pcUrl: String,
    val prefixIcon: String,
)

@Immutable
internal data class ReplyContent(
    val message: String,
    val emotes: Map<String, ReplyEmote> = emptyMap(),
    val atNameToMid: Map<String, Long> = emptyMap(),
    val jumpUrls: Map<String, ReplyJump> = emptyMap(),
    val topics: Set<String> = emptySet(),
    val pictures: List<ReplyPicture> = emptyList(),
    val voteTitle: String = "",
    val voteUrl: String = "",
    val noteUrl: String = "",
) {
    /** Pure text (no @, vote, pictures) — safe to clamp in lists. */
    val isPlainText: Boolean get() = atNameToMid.isEmpty() && voteTitle.isEmpty() && pictures.isEmpty()
}

@Immutable
internal data class Reply(
    val rpid: Long,
    val oid: Long,
    val type: Int,
    val root: Long,
    val parent: Long,
    val member: ReplyMember,
    val content: ReplyContent,
    /** Unix seconds. */
    val ctime: Long,
    val like: Long,
    val liked: Boolean,
    /** 点踩 (action 2); the server keeps it apart from [liked]. */
    val disliked: Boolean = false,
    val rcount: Int,
    val replies: List<Reply>,
    val isUp: Boolean,
    val isTop: Boolean,
    val upLiked: Boolean,
    val hot: Boolean,
    val location: String,
    val entryText: String,
    val upReplied: Boolean,
    val invisible: Boolean,
    /**
     * The user's relation with the commenter when the payload carries it
     * (member.following / member.is_followed): 0 none, 2 关注, 6 互关; -1 = not in the
     * payload (the list asks /x/relation/relations instead).
     */
    val relation: Int = -1,
)

@Immutable
internal data class Emote(val text: String, val url: String, val size: Int, val alias: String = "") {
    /** Short label for the hold preview. */
    val label: String get() = alias.ifEmpty { text.removeSurrounding("[", "]").substringAfterLast('_') }
}

/** "[包_名]" → ("名", "包"); "[doge]" → ("doge", ""). [title] wins for the name when the API sends one. */
internal fun emoteLabel(code: String, title: String): Pair<String, String> {
    val inner = code.removeSurrounding("[", "]")
    val pack = if ('_' in inner) inner.substringBefore('_') else ""
    val name = title.ifEmpty { inner.substringAfterLast('_') }
    return name to pack.takeIf { it != name }.orEmpty()
}

@Immutable
internal data class EmotePackage(
    val id: Long,
    val title: String,
    val icon: String,
    /** 4 = kaomoji (text). */
    val type: Int,
    val emotes: List<Emote>,
)

// Older comments come escaped twice (`&amp;nbsp;`).
private fun String.unescapeHtml(): String = Fmt.decodeEntities(Fmt.decodeEntities(this))

internal fun parseContent(c: JsonObject?): ReplyContent {
    val emotes = c.obj("emote")?.mapNotNull { (k, v) ->
        val o = v as? JsonObject ?: return@mapNotNull null
        val (name, pack) = emoteLabel(k, o.str("jump_title").ifEmpty { o.obj("meta").str("alias") })
        k to ReplyEmote(o.str("url"), o.obj("meta").int("size", 1).coerceIn(1, 3), name, pack)
    }?.toMap().orEmpty()
    val ats = c.obj("at_name_to_mid")?.mapValues { it.value.asLong() }.orEmpty()
    val jumps = c.obj("jump_url")?.mapNotNull { (k, v) ->
        val o = v as? JsonObject ?: return@mapNotNull null
        k to ReplyJump(
            title = o.str("title").ifEmpty { k },
            appUrl = o.str("app_url_schema"),
            pcUrl = o.str("pc_url"),
            prefixIcon = o.str("prefix_icon"),
        )
    }?.toMap().orEmpty()
    val topics = c.obj("topics_meta")?.keys.orEmpty()
    val pictures = c.arr("pictures").objects().map {
        ReplyPicture(it.str("img_src"), it.int("img_width"), it.int("img_height"))
    }.filter { it.src.isNotEmpty() }
    val vote = c.obj("vote")
    return ReplyContent(
        message = c.str("message").unescapeHtml(),
        emotes = emotes,
        atNameToMid = ats,
        jumpUrls = jumps,
        topics = topics,
        pictures = pictures,
        voteTitle = vote.str("title"),
        voteUrl = vote.str("url"),
        noteUrl = c.obj("rich_text").obj("note").str("click_url"),
    )
}

internal fun parseReply(o: JsonObject, upperMid: Long, forceTop: Boolean = false): Reply {
    val m = o.obj("member")
    val ctrl = o.obj("reply_control")
    val mid = m.long("mid").takeIf { it != 0L } ?: o.long("mid")
    val labels = o.arr("card_label").objects().map { it.str("text_content") }
    return Reply(
        rpid = o.long("rpid"),
        oid = o.long("oid"),
        type = o.int("type", 1),
        root = o.long("root"),
        parent = o.long("parent"),
        member = ReplyMember(
            mid = mid,
            name = m.str("uname"),
            face = m.str("avatar"),
            level = m.obj("level_info").int("current_level"),
            vip = m.obj("vip").int("vipStatus") > 0,
            officialType = m.obj("official_verify").int("type", -1),
        ),
        content = parseContent(o.obj("content")),
        ctime = o.long("ctime"),
        like = o.long("like"),
        liked = o.int("action") == 1,
        disliked = o.int("action") == 2,
        rcount = o.int("rcount"),
        replies = o.arr("replies").objects().map { parseReply(it, upperMid) },
        isUp = upperMid != 0L && mid == upperMid,
        isTop = forceTop || ctrl.bool("is_up_top") || labels.any { it.contains("置顶") },
        upLiked = o.obj("up_action").bool("like"),
        hot = labels.any { it.contains("热评") },
        location = ctrl.str("location").substringAfter('：').substringAfter(':').trim(),
        entryText = ctrl.str("sub_reply_entry_text"),
        upReplied = ctrl.bool("up_reply"),
        invisible = o.bool("invisible"),
        relation = payloadRelation(m),
    )
}

/** member.following (the user follows them) + member.is_followed (they follow the user), when present. */
private fun payloadRelation(m: JsonObject?): Int {
    if (!m.has("following")) return -1
    return when {
        !m.bool("following") -> 0
        m.bool("is_followed") -> 6
        else -> 2
    }
}

internal fun parseEmotePackages(data: JsonObject?): List<EmotePackage> =
    data.arr("packages").objects().map { p ->
        val type = p.int("type")
        EmotePackage(
            id = p.long("id"),
            title = p.str("text"),
            icon = p.str("url"),
            type = type,
            emotes = p.arr("emote").objects().map {
                Emote(it.str("text"), it.str("url"), it.obj("meta").int("size", 1).coerceIn(1, 2), it.obj("meta").str("alias"))
            },
        )
    }.filter { it.emotes.isNotEmpty() }
