package com.gpo.pilipala.core.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Base hosts (mirrors the Flutter `HttpString`). */
object BiliHosts {
    const val WWW = "https://www.bilibili.com"
    const val API = "https://api.bilibili.com"
    const val VC = "https://api.vc.bilibili.com"
    const val APP = "https://app.bilibili.com"
    const val LIVE = "https://api.live.bilibili.com"
    const val PASSPORT = "https://passport.bilibili.com"
    const val MESSAGE = "https://message.bilibili.com"
    const val SEARCH = "https://s.search.bilibili.com"
}

/** Non-zero `code` from a bilibili JSON envelope, or a transport failure. */
class BiliException(val code: Int, override val message: String) : IOException(message)

/**
 * The one HTTP entry point. Every request carries the persistent cookie jar,
 * a desktop UA and the www referer. Paths starting with `/` resolve against
 * [BiliHosts.API]; pass a full URL for other hosts.
 *
 * - [get]/[postForm] return the whole envelope (`code`, `message`, `data`, …).
 * - [getData]/[postData] throw [BiliException] when `code != 0` and return `data`
 *   (or `result` for PGC endpoints).
 * - `wbi = true` signs the query with w_rid/wts.
 */
object BiliClient {
    const val UA_PC =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Pixel Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val REFERER = "https://www.bilibili.com/"

    private const val TAG = "BiliClient"

    lateinit var cookies: PersistentCookieJar
        private set
    lateinit var http: OkHttpClient
        private set
    private lateinit var prefs: SharedPreferences
    private val wbiMutex = Mutex()

    fun init(context: Context) {
        if (::http.isInitialized) return
        prefs = context.getSharedPreferences("pili_net", Context.MODE_PRIVATE)
        cookies = PersistentCookieJar(context)
        http = OkHttpClient.Builder()
            .cookieJar(cookies)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder()
                if (req.header("User-Agent") == null) b.header("User-Agent", UA_PC)
                if (req.header("Referer") == null) b.header("Referer", REFERER)
                chain.proceed(b.build())
            }
            .build()
    }

    // ── Account-derived values ──────────────────────────────────────────────
    val csrf: String get() = cookies.get("bili_jct").orEmpty()
    val isLoggedIn: Boolean get() = cookies.get("SESSDATA").isNullOrEmpty().not()
    val mid: Long get() = cookies.get("DedeUserID")?.toLongOrNull() ?: 0L
    val buvid3: String get() = cookies.get("buvid3").orEmpty()

    /** Headers for media (video/audio/danmaku) requests to the CDN. */
    fun mediaHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA_PC,
        "Referer" to REFERER,
        "Origin" to "https://www.bilibili.com",
    )

    // ── Requests ─────────────────────────────────────────────────────────────

    fun resolve(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> BiliHosts.API + url
    }

    suspend fun get(
        url: String,
        params: Map<String, Any?> = emptyMap(),
        wbi: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JsonObject {
        val text = getText(url, params, wbi, headers)
        return parseEnvelope(text)
    }

    suspend fun getData(
        url: String,
        params: Map<String, Any?> = emptyMap(),
        wbi: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = unwrap(get(url, params, wbi, headers))

    suspend fun getText(
        url: String,
        params: Map<String, Any?> = emptyMap(),
        wbi: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        var query = params.filterValues { it != null }.mapValues { it.value.toString() }
        if (wbi) query = signWbi(query)
        val httpUrl = resolve(url).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(httpUrl).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        execute(req).use { it.body.string() }
    }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(resolve(url)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            execute(req).use { it.body.bytes() }
        }

    /**
     * Form POST. `csrf = true` adds `csrf` (and `csrf_token`) from the bili_jct cookie.
     */
    suspend fun postForm(
        url: String,
        form: Map<String, Any?> = emptyMap(),
        params: Map<String, Any?> = emptyMap(),
        csrf: Boolean = true,
        wbi: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JsonObject = withContext(Dispatchers.IO) {
        var query = params.filterValues { it != null }.mapValues { it.value.toString() }
        if (wbi) query = signWbi(query)
        val httpUrl = resolve(url).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> if (v != null) add(k, v.toString()) }
            if (csrf && !form.containsKey("csrf")) {
                add("csrf", this@BiliClient.csrf)
                if (!form.containsKey("csrf_token")) add("csrf_token", this@BiliClient.csrf)
            }
        }.build()
        val req = Request.Builder().url(httpUrl).apply { headers.forEach { (k, v) -> header(k, v) } }.post(body).build()
        parseEnvelope(execute(req).use { it.body.string() })
    }

    suspend fun postData(
        url: String,
        form: Map<String, Any?> = emptyMap(),
        params: Map<String, Any?> = emptyMap(),
        csrf: Boolean = true,
    ): JsonElement = unwrap(postForm(url, form, params, csrf))

    suspend fun postJson(
        url: String,
        json: String,
        params: Map<String, Any?> = emptyMap(),
    ): JsonObject = withContext(Dispatchers.IO) {
        val httpUrl = resolve(url).toHttpUrl().newBuilder().apply {
            params.filterValues { it != null }.forEach { (k, v) -> addQueryParameter(k, v.toString()) }
        }.build()
        val req = Request.Builder().url(httpUrl)
            .post(json.toRequestBody("application/json".toMediaType())).build()
        parseEnvelope(execute(req).use { it.body.string() })
    }

    private fun execute(req: Request): Response {
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful && resp.code !in setOf(400, 403, 404, 412)) {
            resp.close()
            throw BiliException(resp.code, "HTTP ${resp.code}")
        }
        return resp
    }

    fun parseEnvelope(text: String): JsonObject {
        val el = runCatching { BiliJson.parseToJsonElement(text) }.getOrNull()
        if (el == null && text.trimStart().startsWith("<")) throw BiliException(-412, "请求被拦截，稍后再试")
        return el as? JsonObject ?: throw BiliException(-1, "响应解析失败")
    }

    fun unwrap(root: JsonObject): JsonElement {
        val code = root.int("code", 0)
        if (code != 0) {
            val msg = root.str("message").ifEmpty { root.str("msg") }.ifEmpty { "请求失败 ($code)" }
            throw BiliException(code, msg)
        }
        return root["data"]?.takeIf { it !is JsonNull } ?: root["result"] ?: JsonNull
    }

    // ── WBI ─────────────────────────────────────────────────────────────────

    suspend fun signWbi(params: Map<String, String>): Map<String, String> {
        val (img, sub) = wbiKeys()
        return Wbi.sign(params, img, sub)
    }

    private suspend fun wbiKeys(): Pair<String, String> = wbiMutex.withLock {
        val day = System.currentTimeMillis() / 86_400_000L
        val cached = prefs.getString("wbi", null)?.split('|')
        if (cached != null && cached.size == 3 && cached[0].toLongOrNull() == day) {
            return@withLock cached[1] to cached[2]
        }
        val nav = get("/x/web-interface/nav")
        val wbiImg = nav.obj("data").obj("wbi_img")
        fun key(u: String) = u.substringAfterLast('/').substringBefore('.')
        val img = key(wbiImg.str("img_url"))
        val sub = key(wbiImg.str("sub_url"))
        prefs.edit().putString("wbi", "$day|$img|$sub").apply()
        img to sub
    }

    // ── Device identity (buvid) ───────────────────────────────────────────────

    /** Ensures buvid3/buvid4/b_nut cookies exist; search & feeds return -412 without them. */
    suspend fun ensureBuvid() {
        if (buvid3.isNotEmpty()) return
        runCatching {
            val data = getData("/x/frontend/finger/spi").asObj
            val b3 = data.str("b_3")
            val b4 = data.str("b_4")
            if (b3.isNotEmpty()) cookies.put("buvid3", b3)
            if (b4.isNotEmpty()) cookies.put("buvid4", b4)
            cookies.put("b_nut", (System.currentTimeMillis() / 1000).toString())
            cookies.put("_uuid", genUuid())
            activateBuvid()
        }.onFailure { Log.w(TAG, "ensureBuvid", it) }
    }

    private suspend fun activateBuvid() {
        runCatching {
            val html = getText("https://space.bilibili.com/1/dynamic")
            val spm = Regex("""<meta name="spm_prefix" content="([^"]+?)">""").find(html)?.groupValues?.get(1)
                ?: "333.999"
            val rnd = ByteArray(32) { Random.nextInt(256).toByte() } + ByteArray(4) + byteArrayOf(73, 69, 78, 68) +
                ByteArray(4) { Random.nextInt(256).toByte() }
            val b64 = android.util.Base64.encodeToString(rnd, android.util.Base64.NO_WRAP)
            val payload = """{"3064":1,"39c8":"$spm.fp.risk","3c43":{"adca":"Linux","bfe9":"${b64.takeLast(50)}"}}"""
            postJson(
                "/x/internal/gaia-gateway/ExClimbWuzhi",
                """{"payload":${BiliJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(payload))}}""",
            )
        }
    }

    private fun genUuid(): String {
        fun hex(n: Int) = (1..n).joinToString("") { "0123456789ABCDEF"[Random.nextInt(16)].toString() }
        val t = (System.currentTimeMillis() % 100000).toString().padStart(5, '0')
        return "${hex(8)}-${hex(4)}-${hex(4)}-${hex(4)}-${hex(12)}${t}infoc"
    }
}
