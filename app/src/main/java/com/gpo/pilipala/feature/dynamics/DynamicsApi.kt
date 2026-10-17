package com.gpo.pilipala.feature.dynamics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.has
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.navigation.LinkRouter
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

// ── Endpoints (mirrors http/dynamics.dart + http/member.dart) ───────────────

internal object DynamicsApi {
    private const val FEATURES = "itemOpusStyle"

    /**
     * Followed feed. [type] = all | video | pgc | article. Returns the page + the update baseline.
     * Response parsing and item mapping run on [Dispatchers.Default], never on the main thread.
     */
    suspend fun feed(type: String, offset: String?, baseline: String, hostMid: Long?): Pair<Page<DynItem>, String> =
        withContext(Dispatchers.Default) {
            val params = mutableMapOf<String, Any?>(
                "type" to type,
                "offset" to (offset ?: ""),
                "features" to FEATURES,
                "timezone_offset" to "-480",
            )
            if (hostMid != null) {
                params["host_mid"] = hostMid
                params.remove("timezone_offset")
            }
            if (offset != null && baseline.isNotEmpty()) params["update_baseline"] = baseline
            val data = BiliClient.getData("/x/polymer/web-dynamic/v1/feed/all", params).asObj
            parsePage(data) to data.str("update_baseline")
        }

    /** A user's own dynamics (member page). */
    suspend fun space(mid: Long, offset: String?): Page<DynItem> = withContext(Dispatchers.Default) {
        val data = BiliClient.getData(
            "/x/polymer/web-dynamic/v1/feed/space",
            mapOf(
                "host_mid" to mid,
                "offset" to (offset ?: ""),
                "timezone_offset" to "-480",
                "features" to FEATURES,
            ),
            wbi = true,
        ).asObj
        parsePage(data)
    }

    suspend fun detail(id: String): DynItem = withContext(Dispatchers.Default) {
        val data = BiliClient.getData(
            "/x/polymer/web-dynamic/v1/detail",
            mapOf("id" to id, "timezone_offset" to "-480", "features" to FEATURES),
        ).asObj
        val item = data.obj("item") ?: throw com.gpo.pilipala.core.net.BiliException(-1, "动态不存在")
        item.toDynItem()
    }

    suspend fun portal(): Portal = withContext(Dispatchers.Default) {
        val data = BiliClient.getData("/x/polymer/web-dynamic/v1/portal").asObj
        val lives = data.obj("live_users").arr("items").objects().map {
            LiveUser(
                mid = it.long("mid"),
                name = it.str("uname"),
                face = it.str("face"),
                roomId = it.long("room_id"),
                title = it.str("title"),
            )
        }
        // `up_list` is an array on older responses and `{items: […]}` on newer ones.
        val upEl = data?.get("up_list")
        val upArr = upEl.asArr ?: upEl.asObj.arr("items")
        val ups = upArr.objects().map {
            UpUser(mid = it.long("mid"), name = it.str("uname"), face = it.str("face"), hasUpdate = it.bool("has_update"))
        }
        val me = data.obj("my_info")
        Portal(
            myMid = me.long("mid"),
            myName = me.str("name"),
            myFace = me.str("face"),
            lives = lives,
            ups = ups,
        )
    }

    /** Like / unlike. Newer JSON endpoint first, the legacy vc endpoint as fallback. */
    suspend fun like(dynId: String, up: Boolean) {
        val body = buildJsonObject {
            put("dyn_id_str", dynId)
            put("up", if (up) 1 else 2)
            put("spmid", "333.1365.0.0")
            put("from_spmid", "")
        }.toString()
        val res = runCatching {
            BiliClient.unwrap(
                BiliClient.postJson("/x/dynamic/feed/dyn/thumb", body, params = mapOf("csrf" to BiliClient.csrf)),
            )
        }
        if (res.isSuccess) return
        BiliClient.unwrap(
            BiliClient.postForm(
                "https://api.vc.bilibili.com/dynamic_like/v1/dynamic_like/thumb",
                mapOf("dynamic_id" to dynId, "up" to if (up) 1 else 2, "uid" to BiliClient.mid),
            ),
        )
    }

    /** Repost (scene 4) with optional text. */
    suspend fun forward(dynId: String, text: String) {
        val uploadId = "${BiliClient.mid}_${System.currentTimeMillis() / 1000}_${Random.nextInt(1000, 9999)}"
        val body = buildJsonObject {
            put(
                "dyn_req",
                buildJsonObject {
                    put(
                        "content",
                        buildJsonObject {
                            put(
                                "contents",
                                buildJsonArray {
                                    if (text.isNotBlank()) {
                                        add(
                                            buildJsonObject {
                                                put("raw_text", text)
                                                put("type", 1)
                                                put("biz_id", "")
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                    put("scene", 4)
                    put("upload_id", uploadId)
                    put(
                        "meta",
                        buildJsonObject {
                            put(
                                "app_meta",
                                buildJsonObject {
                                    put("from", "create.dynamic.web")
                                    put("mobi_app", "web")
                                },
                            )
                        },
                    )
                },
            )
            put("web_repost_src", buildJsonObject { put("dyn_id_str", dynId) })
        }.toString()
        BiliClient.unwrap(
            BiliClient.postJson(
                "/x/dynamic/feed/create/dyn",
                body,
                params = mapOf("platform" to "web", "csrf" to BiliClient.csrf),
            ),
        )
    }

    suspend fun watchLater(aid: Long, bvid: String) {
        BiliClient.postData(
            "/x/v2/history/toview/add",
            if (aid > 0) mapOf("aid" to aid) else mapOf("bvid" to bvid),
        )
    }

    private fun parsePage(data: JsonObject?): Page<DynItem> {
        val items = data.arr("items").objects()
            .filter { it.str("id_str").isNotEmpty() || it.has("modules") }
            .map { it.toDynItem() }
        val offset = data.str("offset")
        return Page(items, hasMore = data.bool("has_more") && offset.isNotEmpty(), nextCursor = offset)
    }
}

internal data class LiveUser(val mid: Long, val name: String, val face: String, val roomId: Long, val title: String)
internal data class UpUser(val mid: Long, val name: String, val face: String, val hasUpdate: Boolean)
internal data class Portal(
    val myMid: Long,
    val myName: String,
    val myFace: String,
    val lives: List<LiveUser>,
    val ups: List<UpUser>,
)

// ── UI model ────────────────────────────────────────────────────────────────

/**
 * One dynamic, resolved off the main thread when its page loads: the raw JSON the
 * card still renders from, plus everything that used to be derived during
 * composition (key, kind, time text, pictures, tap route, the forwarded original).
 */
@Immutable
internal class DynItem(
    val json: JsonObject,
    val id: String,
    /** Lazy-list / dedup key: the id, or a content hash computed once when the API omits it. */
    val key: String,
    /** `DYNAMIC_TYPE_*`; also the lazy-list content type. */
    val kind: String,
    val timeText: String,
    val pictures: List<DynPic>,
    val route: Route?,
    val orig: DynItem?,
)

internal fun JsonObject.toDynItem(): DynItem {
    val id = dynId
    return DynItem(
        json = this,
        id = id,
        key = id.ifEmpty { "h${hashCode()}" },
        kind = dynType,
        timeText = pubTimeText(),
        pictures = pictures(),
        route = dynamicRoute(this),
        orig = orig?.toDynItem(),
    )
}

// ── Item accessors ──────────────────────────────────────────────────────────

internal val JsonObject.dynId: String get() = str("id_str")
internal val JsonObject.dynType: String get() = str("type")
internal val JsonObject.author: JsonObject? get() = obj("modules").obj("module_author")
internal val JsonObject.dynModule: JsonObject? get() = obj("modules").obj("module_dynamic")
internal val JsonObject.statModule: JsonObject? get() = obj("modules").obj("module_stat")
internal val JsonObject.major: JsonObject? get() = dynModule.obj("major")
internal val JsonObject.orig: JsonObject? get() = obj("orig")

internal fun JsonObject.pubTimeText(): String {
    val a = author
    return a.str("pub_time").ifEmpty { Fmt.relativeTime(a.long("pub_ts")) }
}

/** Comment subject of a dynamic: (oid, type). type 0 is normalised to 11 like Flutter. */
internal fun JsonObject.commentSubject(): Pair<Long, Int> {
    val basic = obj("basic")
    val oid = basic.str("comment_id_str").toLongOrNull() ?: 0L
    val type = basic.int("comment_type").let { if (it == 0) 11 else it }
    return oid to type
}

internal fun normalizeUrl(url: String): String = when {
    url.startsWith("//") -> "https:$url"
    else -> url
}

internal data class LiveRcmd(
    val roomId: Long,
    val title: String,
    val cover: String,
    val area: String,
    val watched: String,
    val isLive: Boolean,
)

/** `major.live_rcmd.content` is a JSON string. */
internal fun parseLiveRcmd(major: JsonObject?): LiveRcmd? {
    val raw = major.obj("live_rcmd").str("content")
    if (raw.isEmpty()) return null
    val info = runCatching { BiliJson.parseToJsonElement(raw).asObj.obj("live_play_info") }.getOrNull() ?: return null
    return LiveRcmd(
        roomId = info.long("room_id"),
        title = info.str("title"),
        cover = info.str("cover"),
        area = info.str("area_name"),
        watched = info.obj("watched_show").str("text_large"),
        isLive = info.int("live_status") == 1,
    )
}

/** Where tapping a dynamic goes (Flutter `DynamicsController.pushDetail`). */
internal fun dynamicRoute(item: JsonObject): Route? {
    val major = item.major
    val id = item.dynId
    return when (item.dynType) {
        "DYNAMIC_TYPE_AV" -> major.obj("archive")?.let { a ->
            val aid = a.long("aid")
            val bvid = a.str("bvid").ifEmpty { if (aid > 0) IdUtils.av2bv(aid) else "" }
            if (bvid.isEmpty()) null else Route.Video(bvid = bvid, aid = aid, cover = a.str("cover"), title = a.str("title"))
        }
        "DYNAMIC_TYPE_UGC_SEASON" -> major.obj("ugc_season")?.let { s ->
            val aid = s.long("aid")
            if (aid <= 0) null else Route.Video(IdUtils.av2bv(aid), aid = aid, cover = s.str("cover"), title = s.str("title"))
        }
        "DYNAMIC_TYPE_PGC", "DYNAMIC_TYPE_PGC_UNION" -> major.obj("pgc")?.let { p ->
            Route.Bangumi(seasonId = p.long("season_id"), epId = p.long("epid"), cover = p.str("cover"))
        }
        "DYNAMIC_TYPE_LIVE_RCMD" -> parseLiveRcmd(major)?.let { Route.LiveRoom(it.roomId, it.cover, it.title) }
        "DYNAMIC_TYPE_LIVE" -> major.obj("live")?.let { Route.LiveRoom(it.long("id"), it.str("cover"), it.str("title")) }
        "DYNAMIC_TYPE_ARTICLE" -> articleRoute(major) ?: id.takeIf { it.isNotEmpty() }?.let { Route.DynamicDetail(it) }
        "DYNAMIC_TYPE_COURSES_SEASON", "DYNAMIC_TYPE_COURSES" ->
            major.obj("courses").str("jump_url").takeIf { it.isNotEmpty() }?.let { LinkRouter.route(normalizeUrl(it)) }
        "DYNAMIC_TYPE_MUSIC" ->
            major.obj("music").str("jump_url").takeIf { it.isNotEmpty() }?.let { LinkRouter.route(normalizeUrl(it)) }
        "DYNAMIC_TYPE_COMMON_SQUARE", "DYNAMIC_TYPE_COMMON_VERTICAL" ->
            major.obj("common").str("jump_url").takeIf { it.isNotEmpty() }?.let { LinkRouter.route(normalizeUrl(it)) }
        "DYNAMIC_TYPE_NONE" -> null
        else -> id.takeIf { it.isNotEmpty() }?.let { Route.DynamicDetail(it) }
    }
}

private val ReadIdRegex = Regex("""/read/(?:cv|mobile/?\?id=)(\d+)""")
private val OpusIdRegex = Regex("""/opus/(\d+)""")

internal fun articleRoute(major: JsonObject?): Route? {
    val opus = major.obj("opus")
    val article = major.obj("article")
    val url = opus.str("jump_url").ifEmpty { article.str("jump_url") }
    val title = opus.str("title").ifEmpty { article.str("title") }
    ReadIdRegex.find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return Route.Read(it, title) }
    OpusIdRegex.find(url)?.groupValues?.get(1)?.let { return Route.Opus(it, title) }
    article.long("id").takeIf { it > 0 }?.let { return Route.Read(it, title) }
    return if (url.isNotEmpty()) LinkRouter.route(normalizeUrl(url), title) else null
}

/** Pictures of a dynamic: opus pics (itemOpusStyle) or legacy draw items. */
internal data class DynPic(val url: String, val width: Int, val height: Int)

internal fun JsonObject.pictures(): List<DynPic> {
    val m = major ?: return emptyList()
    val opusPics = m.obj("opus").arr("pics").objects()
    if (opusPics.isNotEmpty()) {
        return opusPics.map { DynPic(it.str("url").ifEmpty { it.str("src") }, it.int("width"), it.int("height")) }
    }
    return m.obj("draw").arr("items").objects().map {
        DynPic(it.str("src").ifEmpty { it.str("url") }, it.int("width"), it.int("height"))
    }
}

// ── Shared like state (list and detail stay in sync) ────────────────────────

internal data class LikeState(val liked: Boolean, val count: Long)

internal object LikeCache {
    private val map = mutableStateMapOf<String, LikeState>()
    fun get(id: String, fallback: LikeState): LikeState = map[id] ?: fallback
    fun set(id: String, state: LikeState) {
        map[id] = state
    }
}

// ── Native helpers ──────────────────────────────────────────────────────────

internal fun dynamicShareUrl(item: JsonObject): String = "https://t.bilibili.com/${item.dynId}"

internal fun shareText(context: Context, text: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("link", text))
}
