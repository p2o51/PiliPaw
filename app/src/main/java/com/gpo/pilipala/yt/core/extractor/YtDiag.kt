package com.gpo.pilipala.yt.core.extractor

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * The account's youtubei calls in one place, so a failure says what YouTube answered instead of
 * reading as an empty list (an error body parses to no videos), and a report of what each list
 * returns that the user can copy from 设置 and send back. The report names cookies, never values.
 */
object YtDiag {
    /** youtubei rejected the call: its status and message. */
    class YtApiException(val code: Int, message: String) : Exception("YouTube 返回 $code：$message")

    /** A signed-in POST to youtubei [endpoint]; throws [YtApiException] when the body is an error. */
    fun post(endpoint: String, body: ByteArray): JsonObject = request(endpoint, body).let { (code, json) ->
        val err = json.getObject("error")
        if (code >= 400 || err.isNotEmpty()) throw YtApiException(code, err.getString("message") ?: err.getString("status") ?: "HTTP $code")
        // YouTube answers a session it does not accept with a signed-out page (200, logged_in 0), not an error.
        if (ServiceList.YouTube.hasTokens() && loggedIn(json) == "0") throw YtApiException(code, "没有认出这次登录，试试重新登录 YouTube")
        // A list it will not show (private, gone) comes back as alerts in place of contents.
        if (json.has("alerts") && !json.has("contents") && !json.has("onResponseReceivedActions") && !json.has("actions")) {
            val alert = ArrayList<String>()
            YtFeeds.walk(json.getArray("alerts")) { key, value -> if (key == "alertRenderer") alert += texts(value) }
            throw YtApiException(code, alert.firstOrNull() ?: "这个列表打不开")
        }
        json
    }

    private fun loggedIn(json: JsonObject): String? = json.getObject("responseContext").getArray("serviceTrackingParams")
        .mapNotNull { it as? JsonObject }.flatMap { p -> p.getArray("params").mapNotNull { it as? JsonObject } }
        .firstOrNull { it.getString("key") == "logged_in" }?.getString("value")

    private fun request(endpoint: String, body: ByteArray): Pair<Int, JsonObject> {
        val headers = YoutubeParsingHelper.getYouTubeHeaders()
        headers["Content-Type"] = listOf("application/json")
        YoutubeParsingHelper.addLoggedInHeaders(headers)
        // What youtube.com itself sends with a signed-in call: which of the signed-in accounts.
        if (ServiceList.YouTube.hasTokens()) headers["X-Goog-AuthUser"] = listOf("0")
        val url = "${YoutubeParsingHelper.YOUTUBEI_V1_URL}$endpoint?${YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER}"
        val response = NewPipe.getDownloader().post(url, headers, body, Localization.DEFAULT)
        val json = runCatching { JsonParser.`object`().from(response.responseBody()) }.getOrDefault(JsonObject())
        return response.responseCode() to json
    }

    private fun browseBody(browseId: String): ByteArray {
        val builder = YoutubeParsingHelper.prepareDesktopJsonBuilder(
            NewPipe.getPreferredLocalization() ?: Localization.DEFAULT,
            NewPipe.getPreferredContentCountry() ?: ContentCountry.DEFAULT,
        )
        builder.value("browseId", browseId)
        return JsonWriter.string(builder.done()).toByteArray()
    }

    /** What each signed-in list returns now, as plain text to paste back. */
    suspend fun report(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder("PiliPaw YouTube 诊断\n")
        val tokens = if (ServiceList.YouTube.hasTokens()) ServiceList.YouTube.tokens.orEmpty() else ""
        val names = tokens.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        sb.append("tokens: ${if (tokens.isEmpty()) "无" else "${names.size} 个 cookie"}\n")
        sb.append("cookies: ${names.joinToString(",")}\n")
        sb.append("client: ${runCatching { YoutubeParsingHelper.getClientVersion() }.getOrElse { "? ${it.message}" }}\n")
        val probes = listOf(
            "account_menu" to ("account/account_menu" to JsonWriter.string(YoutubeParsingHelper.prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT).done()).toByteArray()),
            "首页 FEwhat_to_watch" to ("browse" to browseBody("FEwhat_to_watch")),
            "订阅 FEsubscriptions" to ("browse" to browseBody("FEsubscriptions")),
            "赞过 VLLL" to ("browse" to browseBody("VLLL")),
            "稍后看 VLWL" to ("browse" to browseBody("VLWL")),
        )
        for ((label, call) in probes) {
            sb.append("\n[$label]\n")
            runCatching { request(call.first, call.second) }
                .onSuccess { (code, json) -> describe(sb, code, json) }
                .onFailure { sb.append("请求失败: ${it.javaClass.simpleName}: ${it.message}\n") }
        }
        sb.toString()
    }

    private fun describe(sb: StringBuilder, code: Int, json: JsonObject) {
        sb.append("HTTP $code, ${JsonWriter.string(json).length} 字节\n")
        json.getObject("error").takeIf { it.isNotEmpty() }?.let { sb.append("error: ${it.getInt("code")} ${it.getString("status")} ${it.getString("message")}\n") }
        sb.append("顶层: ${json.keys.joinToString(",")}\n")
        sb.append("logged_in: ${loggedIn(json) ?: "?"}\n")
        // Alerts and empty-page messages say why a list is empty (history off, private, ...).
        val messages = ArrayList<String>()
        YtFeeds.walk(json) { key, value ->
            if (key == "alertRenderer" || key == "alertWithButtonRenderer" || key == "feedNudgeRenderer" || key == "messageRenderer" || key == "backgroundPromoRenderer") {
                messages += "$key: " + texts(value).take(3).joinToString(" / ")
            }
        }
        messages.distinct().take(6).forEach { sb.append("$it\n") }
        val counts = HashMap<String, Int>()
        YtFeeds.walk(json) { key, _ -> if (key.endsWith("Renderer") || key.endsWith("ViewModel")) counts[key] = (counts[key] ?: 0) + 1 }
        sb.append("renderers: ${counts.entries.sortedByDescending { it.value }.take(14).joinToString(", ") { "${it.key}×${it.value}" }}\n")
        val lockups = HashMap<String, Int>()
        YtFeeds.walk(json) { key, value -> if (key == "lockupViewModel") value.getString("contentType")?.let { lockups[it] = (lockups[it] ?: 0) + 1 } }
        if (lockups.isNotEmpty()) sb.append("lockups: $lockups\n")
    }

    /** The first few strings under simpleText / runs.text / content anywhere in [o]. */
    private fun texts(o: JsonObject): List<String> {
        val out = ArrayList<String>()
        fun go(x: Any?) {
            when (x) {
                is JsonObject -> for ((k, v) in x) {
                    if ((k == "simpleText" || k == "text" || k == "content") && v is String && v.isNotBlank()) out += v.take(80) else go(v)
                }
                is JsonArray -> x.forEach { go(it) }
            }
        }
        go(o)
        return out
    }
}
