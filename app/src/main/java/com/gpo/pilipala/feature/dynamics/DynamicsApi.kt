package com.gpo.pilipala.feature.dynamics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.double
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
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

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

    /**
     * Who liked (or forwarded) a dynamic: GET detail/reaction, read-only.
     * `data { items: [{mid, name, face, action: "赞了" | "转发了", attend}], offset, has_more, total }`.
     * Visitors get an empty page. Fields are read loosely; a row without a mid is dropped.
     */
    suspend fun reactions(id: String, offset: String?): ReactionPage = withContext(Dispatchers.Default) {
        val data = BiliClient.getData(
            "/x/polymer/web-dynamic/v1/detail/reaction",
            mapOf("id" to id, "offset" to (offset ?: "")),
        ).asObj
        val items = data.arr("items").objects().mapNotNull {
            val mid = it.long("mid")
            if (mid <= 0) return@mapNotNull null
            Reactor(
                mid = mid,
                name = it.str("name").ifEmpty { it.str("uname") },
                face = normalizeUrl(it.str("face")),
                action = it.str("action"),
                following = it.int("attend") > 0,
            )
        }
        val next = data.str("offset")
        ReactionPage(
            page = Page(items, hasMore = data.bool("has_more") && next.isNotEmpty() && next != offset, nextCursor = next),
            total = data.long("total"),
        )
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

    /**
     * Uploads one picture for a new dynamic (multipart `file_up`, biz new_dyn).
     * A JPEG/PNG/GIF/WebP within the size limit streams straight from the
     * picker's URI; anything else (HEIF, AVIF, oversized) is re-encoded to a
     * JPEG temp file first. Cancelling the caller cancels the request.
     */
    suspend fun uploadImage(resolver: ContentResolver, cacheDir: File, uri: Uri): UploadedPic =
        withContext(Dispatchers.IO) {
            val file = prepareUpload(resolver, cacheDir, uri)
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file_up", file.name, file.body)
                    .addFormDataPart("category", "daily")
                    .addFormDataPart("biz", "new_dyn")
                    .addFormDataPart("csrf", BiliClient.csrf)
                    .build()
                val req = Request.Builder().url(UPLOAD_URL).post(body).build()
                val (status, text) = uploadClient.newCall(req).await().use { it.code to it.body.string() }
                val root = runCatching { BiliJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
                if (status !in 200..299) {
                    // An error page (413, 5xx …) is not a risk-control block: say what failed.
                    val server = root?.takeIf { it.int("code", 0) != 0 }?.str("message")?.takeIf { it.isNotBlank() }
                    throw BiliException(status, server ?: if (status == 413) "图片过大" else "图片上传失败")
                }
                if (root == null) throw BiliException(-1, "图片上传失败")
                val data = BiliClient.unwrap(root).asObj
                val url = data.str("image_url")
                if (url.isEmpty()) throw BiliException(-1, "图片上传失败")
                val kb = data.double("img_size").takeIf { it > 0 }
                    ?: data.double("size").takeIf { it > 0 }
                    ?: (file.length / 1024.0)
                UploadedPic(url = url, width = data.int("image_width"), height = data.int("image_height"), sizeKb = kb)
            } finally {
                file.temp?.delete()
            }
        }

    /**
     * Publishes a new dynamic: scene 1 (text) or 2 (with [pics], uploaded first by
     * [uploadImage]). [uploadId] is the draft's own, reused by every retry of the
     * same content. Returns the new dynamic's id. Only ever called from the
     * composer's send.
     *
     * Throws [BiliException] when the server answered with an error (nothing was
     * posted) and [PublishUnknownException] when no answer came back (timeout,
     * dropped connection, an error page): the post may or may not exist.
     */
    suspend fun createDynamic(text: String, pics: List<UploadedPic>, uploadId: String): String =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put(
                    "dyn_req",
                    buildJsonObject {
                        // One plain text segment, like the Flutter client: the server
                        // parses the [emote] codes in it itself.
                        put(
                            "content",
                            buildJsonObject {
                                put(
                                    "contents",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("raw_text", text)
                                                put("type", 1)
                                                put("biz_id", "")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        put("scene", if (pics.isEmpty()) 1 else 2)
                        if (pics.isNotEmpty()) {
                            put(
                                "pics",
                                buildJsonArray {
                                    pics.forEach { p ->
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
                        }
                        put("attach_card", JsonNull)
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
            }.toString()
            val url = BiliClient.resolve("/x/dynamic/feed/create/dyn").toHttpUrl().newBuilder()
                .addQueryParameter("platform", "web")
                .addQueryParameter("csrf", BiliClient.csrf)
                .addQueryParameter("x-bili-device-req-json", """{"platform":"web","device":"pc"}""")
                .addQueryParameter("x-bili-web-req-json", """{"spm_id":"333.999"}""")
                .build()
            val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
            val (status, raw) = try {
                publishClient.newCall(req).await().use { it.code to it.body.string() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw PublishUnknownException(e)
            }
            val root = runCatching { BiliJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            if (root == null || !root.has("code")) {
                // The risk-control page answers before anything is created; any other page is a guess.
                if (status == 412) throw BiliException(-412, "请求被拦截，稍后再试")
                throw PublishUnknownException(null)
            }
            val data = BiliClient.unwrap(root).asObj
            data.str("dyn_id_str").ifEmpty { data.long("dyn_id").takeIf { it > 0 }?.toString().orEmpty() }
        }

    /**
     * The account's own latest dynamics (read only), to tell whether a create
     * that never answered went through: its space feed, else the followed feed
     * (which carries the account's own posts) filtered to it. Throws when
     * neither could be read.
     */
    suspend fun ownRecent(mid: Long): List<DynItem> {
        val fromSpace = try {
            space(mid, null).items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (!fromSpace.isNullOrEmpty()) return fromSpace.take(OwnRecentCount)
        val fromFeed = feed("all", null, "", null).first.items
        return fromFeed.filter { it.json.author.long("mid") == mid }.take(OwnRecentCount)
    }

    private const val OwnRecentCount = 6

    private const val UPLOAD_URL = "https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs"

    /**
     * The shared client (cookies, UA, referer) with room for a multi-MB body on
     * a slow link, and a whole-call cap so a half-dead link can't hold one
     * picture for minutes.
     */
    private val uploadClient by lazy {
        BiliClient.http.newBuilder()
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /** The create call: longer than the shared 15s read, capped as a whole. */
    private val publishClient by lazy {
        BiliClient.http.newBuilder()
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * One vote: `/x/vote/vote_info` (`vote_info` + `my_votes`, option images), the
     * legacy vc endpoint (`info`) only when the new one is unreachable (a transport
     * failure or HTTP 5xx). Any code bilibili answers with (-412 风控, -400, 260010
     * 投票不可展示 …) is the answer and surfaces as is; so does the original error
     * when the legacy endpoint has no such vote either.
     */
    suspend fun voteInfo(voteId: Long): VoteInfo = withContext(Dispatchers.Default) {
        val data = try {
            BiliClient.getData("/x/vote/vote_info", mapOf("vote_id" to voteId)).asObj
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val unreachable = e is java.io.IOException && (e !is BiliException || e.code in 500..599)
            if (!unreachable) throw e
            val legacy = try {
                BiliClient.getData("${BiliHosts.VC}/vote_svr/v1/vote_svr/vote_info", mapOf("vote_id" to voteId)).asObj
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                throw e
            }
            return@withContext parseVoteInfo(legacy) ?: throw e
        }
        parseVoteInfo(data) ?: throw BiliException(-404, "投票不存在")
    }

    /**
     * Casts a vote. Only ever called from the 投票 button. JSON body like the web
     * client (`/x/vote/do_vote`, csrf in query and body); the response carries the
     * updated `vote_info` with `my_votes`, null when it does not. `dynamic_id` goes
     * out only when there is a numeric one (like PiliPlus); never a stand-in 0.
     */
    suspend fun doVote(voteId: Long, votes: List<Int>, dynamicId: String): VoteInfo? = withContext(Dispatchers.Default) {
        val csrf = BiliClient.csrf
        val body = buildJsonObject {
            put("vote_id", voteId)
            put("votes", buildJsonArray { votes.forEach { add(it) } })
            put("voter_uid", BiliClient.mid)
            put("status", 0)
            put("op_bit", 0)
            dynamicId.toLongOrNull()?.takeIf { it > 0 }?.let { put("dynamic_id", it) }
            put("csrf_token", csrf)
            put("csrf", csrf)
        }.toString()
        val data = BiliClient.unwrap(BiliClient.postJson("/x/vote/do_vote", body, params = mapOf("csrf" to csrf)))
        parseVoteInfo(data.asObj)
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

// ── Publishing ──────────────────────────────────────────────────────────────

/** A picture on bilibili's image host, ready for `dyn_req.pics`. */
@Serializable
internal data class UploadedPic(val url: String, val width: Int, val height: Int, val sizeKb: Double)

/** The create request got no answer: the dynamic may exist. */
internal class PublishUnknownException(cause: Throwable?) : IOException("发布结果未知", cause)

/** Sent as they are; anything else (HEIF, AVIF …) is re-encoded as JPEG. */
private val DirectUploadTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
private const val MaxDirectUploadBytes = 20L * 1024 * 1024

/** Re-encoded pictures keep at most this many pixels (a long screenshot keeps its width). */
private const val MaxEncodePixels = 16_000_000L

private class UploadFile(val body: RequestBody, val length: Long, val name: String, val temp: File?)

/**
 * What goes up for [uri]: the file itself, streamed from the resolver, when
 * its type is accepted and its size is known to be within the limit; else a
 * re-encoded JPEG in [cacheDir]. Nothing larger than the limit is read into
 * memory whole.
 */
private fun prepareUpload(resolver: ContentResolver, cacheDir: File, uri: Uri): UploadFile {
    val mime = resolver.getType(uri)?.lowercase().orEmpty().ifEmpty { "image/jpeg" }
    // Fails here, on the caller's thread, when the grant is gone.
    (resolver.openInputStream(uri) ?: throw BiliException(-1, "图片读取失败")).close()
    if (mime in DirectUploadTypes) {
        val name = "image." + mime.substringAfter('/').replace("jpeg", "jpg")
        val size = sizeOf(resolver, uri)
        if (size in 1..MaxDirectUploadBytes) {
            return UploadFile(UriRequestBody(resolver, uri, mime.toMediaType(), size), size, name, null)
        }
        if (size <= 0) {
            // Size unknown: read up to the limit, re-encode past it.
            val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, MaxDirectUploadBytes) }
            if (bytes != null) return UploadFile(bytes.toRequestBody(mime.toMediaType()), bytes.size.toLong(), name, null)
        }
    }
    val jpeg = reencode(resolver, cacheDir, uri)
    return UploadFile(jpeg.asRequestBody("image/jpeg".toMediaType()), jpeg.length(), "image.jpg", jpeg)
}

/** The content's size in bytes, or -1 when the provider doesn't say. */
private fun sizeOf(resolver: ContentResolver, uri: Uri): Long {
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                val size = c.getLong(0)
                if (size > 0) return size
            }
        }
    }
    runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
            if (fd.length > 0) return fd.length
        }
    }
    return -1
}

/** The whole stream when it is at most [limit] bytes, else null. */
private fun readAtMost(input: java.io.InputStream, limit: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > limit) return null
        out.write(buf, 0, n)
    }
}

/**
 * Streams the picture from the resolver on every write (OkHttp may write a
 * body twice on a retried connection). Any failure surfaces as an
 * IOException: anything else thrown on OkHttp's thread would crash the app.
 */
private class UriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val type: MediaType,
    private val length: Long,
) : RequestBody() {
    override fun contentType(): MediaType = type

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("图片读取失败")
            input.source().use { sink.writeAll(it) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("图片读取失败", e)
        }
    }
}

/** Decodes [uri] within [MaxEncodePixels] and writes it as a JPEG temp file. */
private fun reencode(resolver: ContentResolver, cacheDir: File, uri: Uri): File {
    val bitmap = try {
        decodeForUpload(resolver, uri)
    } catch (e: OutOfMemoryError) {
        throw BiliException(-1, "图片过大")
    } catch (e: FileNotFoundException) {
        throw BiliException(-1, "图片读取失败")
    } catch (e: IOException) {
        throw BiliException(-1, "图片格式不支持")
    } ?: throw BiliException(-1, "图片格式不支持")
    val out = File.createTempFile("dyn_upload_", ".jpg", cacheDir)
    try {
        val written = out.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (!written) throw BiliException(-1, "图片格式不支持")
    } catch (e: Throwable) {
        out.delete()
        throw e
    } finally {
        bitmap.recycle()
    }
    return out
}

/**
 * Streams [uri] into a bitmap of at most [MaxEncodePixels], by total pixels
 * rather than the longest edge. ImageDecoder (API 28+) applies EXIF rotation.
 */
private fun decodeForUpload(resolver: ContentResolver, uri: Uri): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val pixels = w.toLong() * h
            if (w > 0 && h > 0 && pixels > MaxEncodePixels) {
                val scale = sqrt(MaxEncodePixels.toDouble() / pixels)
                decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MaxEncodePixels) sample *= 2
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

/** The call, cancelled with the coroutine (a blocking execute() ignores cancellation). */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

/** The text a published dynamic shows (rich nodes joined back to what was typed). */
internal fun DynItem.postedText(): String {
    val nodes = richSource(json).second
    if (!nodes.isNullOrEmpty()) {
        return nodes.objects().joinToString("") { it.str("orig_text").ifEmpty { it.str("text") } }
    }
    return json.dynModule.obj("desc").str("text").ifEmpty { json.major.obj("opus").obj("summary").str("text") }
}

/** Publish time, server seconds (0 when absent). */
internal val DynItem.pubTs: Long get() = json.author.long("pub_ts")

/** Feed-wide signals: the composer emits the new dynamic's id after a successful post. */
internal object DynamicsEvents {
    val posted = MutableSharedFlow<String>(extraBufferCapacity = 1)
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

/** One row of a dynamic's 点赞 list. */
@Immutable
internal data class Reactor(
    val mid: Long,
    val name: String,
    val face: String,
    /** "赞了", "转发了"… as the server words it. */
    val action: String,
    val following: Boolean,
)

internal class ReactionPage(val page: Page<Reactor>, val total: Long)

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
