package com.gpo.pilipala.feature.reply

import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.feature.dynamics.UploadedPic
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Comment endpoints (mirrors Flutter `ReplyHttp` + `VideoHttp.replyAdd`). */
internal object ReplyApi {
    const val PAGE_SIZE = 20

    class MainPage(
        val replies: List<Reply>,
        val top: List<Reply>,
        val count: Long,
        val next: Long,
        val isEnd: Boolean,
        val upperMid: Long,
        val readOnly: Boolean,
    )

    class ThreadPage(
        val root: Reply?,
        val replies: List<Reply>,
        val count: Long,
        val upperMid: Long,
    )

    /** mode: 3 = hot, 2 = newest. [next] is the cursor returned by the previous page (0 = first). */
    suspend fun main(oid: Long, type: Int, mode: Int, next: Long): MainPage {
        val params = mapOf("oid" to oid, "type" to type, "mode" to mode, "next" to next, "ps" to PAGE_SIZE)
        val data = try {
            try {
                BiliClient.getData("/x/v2/reply/main", params).asObj
            } catch (e: BiliException) {
                // Risk control on the plain endpoint: fall back to the signed one.
                if (e.code == -352 || e.code == -412 || e.code == -403) {
                    BiliClient.getData("/x/v2/reply/wbi/main", params, wbi = true).asObj
                } else {
                    throw e
                }
            }
        } catch (e: BiliException) {
            throw BiliException(e.code, errorLabel(e.code) ?: e.message)
        }
        val upperMid = data.obj("upper").long("mid")
        val cursor = data.obj("cursor")
        val tops = buildList {
            data.arr("top_replies").objects().forEach { add(parseReply(it, upperMid, forceTop = true)) }
            data.obj("upper").obj("top")?.let { add(parseReply(it, upperMid, forceTop = true)) }
            data.obj("top").obj("upper")?.let { add(parseReply(it, upperMid, forceTop = true)) }
            data.obj("top").obj("admin")?.let { add(parseReply(it, upperMid, forceTop = true)) }
        }.filter { it.rpid != 0L }.distinctBy { it.rpid }
        val topIds = tops.map { it.rpid }.toSet()
        val replies = data.arr("replies").objects().map { parseReply(it, upperMid) }.filter { it.rpid !in topIds }
        return MainPage(
            replies = replies,
            top = tops,
            count = cursor.long("all_count"),
            next = cursor.long("next"),
            isEnd = cursor.bool("is_end"),
            upperMid = upperMid,
            readOnly = data.obj("config").bool("read_only") || data.obj("control").bool("input_disable"),
        )
    }

    suspend fun thread(oid: Long, type: Int, root: Long, pn: Int): ThreadPage {
        val data = try {
            BiliClient.getData(
                "/x/v2/reply/reply",
                mapOf("oid" to oid, "type" to type, "root" to root, "pn" to pn, "ps" to PAGE_SIZE, "sort" to 1),
            ).asObj
        } catch (e: BiliException) {
            throw BiliException(e.code, errorLabel(e.code) ?: e.message)
        }
        val upperMid = data.obj("upper").long("mid")
        return ThreadPage(
            root = data.obj("root")?.let { parseReply(it, upperMid) },
            replies = data.arr("replies").objects().map { parseReply(it, upperMid) },
            count = data.obj("page").long("count"),
            upperMid = upperMid,
        )
    }

    /** action: 1 = like, 0 = cancel. */
    suspend fun like(oid: Long, type: Int, rpid: Long, like: Boolean) {
        BiliClient.postData(
            "/x/v2/reply/action",
            mapOf("oid" to oid, "type" to type, "rpid" to rpid, "action" to if (like) 1 else 0),
        )
    }

    /** 点踩: action 1 = dislike, 0 = cancel. */
    suspend fun hate(oid: Long, type: Int, rpid: Long, hate: Boolean) {
        BiliClient.postData(
            "/x/v2/reply/hate",
            mapOf("oid" to oid, "type" to type, "rpid" to rpid, "action" to if (hate) 1 else 0),
        )
    }

    /**
     * 举报 a comment: [reason] is one of [ReplyReportReasons]' ids; [detail] is the free text
     * (required for 其他 and 虚假不实信息); [block] also puts the commenter on the blacklist.
     */
    suspend fun report(oid: Long, type: Int, rpid: Long, reason: Int, detail: String, block: Boolean) {
        BiliClient.postData(
            "/x/v2/reply/report",
            mapOf(
                "oid" to oid,
                "type" to type,
                "rpid" to rpid,
                "reason" to reason,
                "content" to detail.trim().takeIf { it.isNotEmpty() },
                "add_blacklist" to block,
                "gaia_source" to "main_h5",
                "platform" to "android",
                "scene" to "main",
            ),
        )
    }

    class AddResult(val reply: Reply?, val toast: String)

    /**
     * [pictures] are already on bilibili's image host (the dynamic composer's
     * bfs upload); they go up as the web client's `pictures` field, a JSON array
     * of {img_src, img_width, img_height, img_size (KB)}.
     */
    suspend fun add(
        oid: Long,
        type: Int,
        message: String,
        root: Long,
        parent: Long,
        upperMid: Long,
        pictures: List<UploadedPic> = emptyList(),
    ): AddResult {
        val data = BiliClient.postData(
            "/x/v2/reply/add",
            mapOf(
                "oid" to oid,
                "type" to type,
                "message" to message,
                "root" to root.takeIf { it != 0L },
                "parent" to parent.takeIf { it != 0L },
                "plat" to 1,
                "pictures" to pictures.takeIf { it.isNotEmpty() }?.let(::picturesJson),
            ),
        ).asObj
        return AddResult(
            reply = data.obj("reply")?.let { parseReply(it, upperMid) },
            toast = data.str("success_toast").ifEmpty { "发送成功" },
        )
    }

    suspend fun delete(oid: Long, type: Int, rpid: Long) {
        BiliClient.postData("/x/v2/reply/del", mapOf("oid" to oid, "type" to type, "rpid" to rpid))
    }

    /**
     * Read-only batch lookup of the user's relation with [mids] (GET /x/relation/relations):
     * mid → attribute (1 悄悄关注, 2 关注, 6 互关, 128 拉黑). Mids it leaves out have none.
     */
    suspend fun relations(mids: List<Long>): Map<Long, Int> {
        if (mids.isEmpty()) return emptyMap()
        val data = BiliClient.getData("/x/relation/relations", mapOf("fids" to mids.joinToString(","))).asObj
            ?: return emptyMap()
        return data.mapNotNull { (k, v) ->
            val mid = k.toLongOrNull() ?: return@mapNotNull null
            mid to v.asObj.int("attribute")
        }.toMap()
    }

    suspend fun emotePanel(): List<EmotePackage> {
        val data = BiliClient.getData(
            "/x/emote/user/panel/web",
            mapOf("business" to "reply", "web_location" to "333.1245"),
        ).asObj
        return parseEmotePackages(data)
    }

    private fun picturesJson(pictures: List<UploadedPic>): String = BiliJson.encodeToString(
        kotlinx.serialization.json.JsonArray.serializer(),
        buildJsonArray {
            pictures.forEach { p ->
                add(
                    buildJsonObject {
                        put("img_src", p.url)
                        put("img_width", p.width)
                        put("img_height", p.height)
                        put("img_size", p.sizeKb)
                    },
                )
            }
        },
    )

    private fun errorLabel(code: Int): String? = when (code) {
        12002 -> "评论区已关闭"
        12061 -> "UP主已关闭评论区"
        12009 -> "评论主体类型不合法"
        -404 -> "无此项"
        else -> null
    }
}

/** Shared emote panel cache (one request per process). */
internal object EmoteCache {
    @Volatile var packages: List<EmotePackage>? = null
}
