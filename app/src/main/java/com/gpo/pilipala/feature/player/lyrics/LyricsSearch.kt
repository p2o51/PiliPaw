package com.gpo.pilipala.feature.player.lyrics

import android.util.Base64
import com.gpo.pilipala.feature.player.SubtitleLine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One song a lyrics site knows, offered for a manual pick. */
internal data class LyricCandidate(
    val source: LyricSource,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal enum class LyricSource(val label: String) { NetEase("网易云"), QQ("QQ 音乐") }

/**
 * Lyrics from 网易云 and QQ 音乐 for a video that has none of its own, the way Yoin finds
 * a song's (Yoin's NetEaseLyricsProvider / QQLyricsProvider, trimmed to a manual search:
 * the person picks the result, so there is no matcher). 网易云 goes through the Spotoolfy
 * proxy Yoin uses; QQ 音乐 needs no login (web search, gateway lyric, web lyric as fallback).
 */
internal object LyricsSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val NetEaseBase = "https://api.spotoolfy.gojyuplus.com"
    private const val QQWeb = "https://c.y.qq.com"
    private const val QQGateway = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val QQReferer = "https://y.qq.com/"

    /** Both sites at once, interleaved (网易云 first), each in its own order. */
    suspend fun search(query: String): List<LyricCandidate> = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()
        val ne = async { runCatching { netEaseSearch(q) }.getOrDefault(emptyList()) }
        val qq = async { runCatching { qqSearch(q) }.getOrDefault(emptyList()) }
        val a = ne.await()
        val b = qq.await()
        buildList {
            for (i in 0 until maxOf(a.size, b.size)) {
                a.getOrNull(i)?.let(::add)
                b.getOrNull(i)?.let(::add)
            }
        }
    }

    /** The picked song's timed lines; null when it has none (or no timing). */
    suspend fun fetch(c: LyricCandidate): List<SubtitleLine>? = runCatching {
        val lrc = when (c.source) {
            LyricSource.NetEase -> netEaseLyric(c.id)
            LyricSource.QQ -> qqLyric(c.id)
        } ?: return@runCatching null
        Lrc.parse(lrc).takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ── 网易云 ──────────────────────────────────────────────────────────────

    private suspend fun netEaseSearch(q: String): List<LyricCandidate> {
        val url = "$NetEaseBase/cloudsearch".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", q)
            .addQueryParameter("limit", "15")
            .build()
        val root = getJson(Request.Builder().url(url).get().build()) ?: return emptyList()
        val songs = root.obj("result")?.array("songs") ?: return emptyList()
        return songs.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.string("id") ?: return@mapNotNull null
            LyricCandidate(
                source = LyricSource.NetEase,
                id = id,
                title = o.string("name").orEmpty(),
                artist = o.array("ar").orEmpty().mapNotNull { (it as? JsonObject)?.string("name") }.joinToString(" / "),
                album = o.obj("al")?.string("name").orEmpty(),
                durationMs = o.string("dt")?.toLongOrNull() ?: 0L,
            )
        }
    }

    private suspend fun netEaseLyric(id: String): String? {
        val url = "$NetEaseBase/lyric/new".toHttpUrl().newBuilder().addQueryParameter("id", id).build()
        val root = getJson(Request.Builder().url(url).get().build()) ?: return null
        val raw = root.obj("lrc")?.string("lyric")?.takeIf { it.isNotBlank() } ?: return null
        return if (raw.trimStart().startsWith('{')) netEaseJsonToLrc(raw) else raw
    }

    /** 网易's per-word JSON lines (`{"t":12345,"c":[{"tx":"歌"},…]}`) → LRC. */
    private fun netEaseJsonToLrc(raw: String): String? {
        val out = raw.lines().mapNotNull { line ->
            val t = line.trim()
            if (!t.startsWith('{')) return@mapNotNull t.takeIf { it.isNotEmpty() }
            runCatching {
                val o = json.parseToJsonElement(t) as JsonObject
                val ms = o.string("t")?.toLongOrNull() ?: 0L
                val text = o.array("c").orEmpty().joinToString("") { (it as? JsonObject)?.string("tx").orEmpty() }
                "[${Lrc.stamp(ms)}]$text"
            }.getOrNull()
        }
        return out.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    // ── QQ 音乐 ─────────────────────────────────────────────────────────────

    private suspend fun qqSearch(q: String): List<LyricCandidate> {
        val url = "$QQWeb/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
            .addQueryParameter("w", q)
            .addQueryParameter("format", "json")
            .addQueryParameter("n", "15")
            .addQueryParameter("p", "1")
            .addQueryParameter("t", "0")
            .addQueryParameter("cr", "1")
            .addQueryParameter("new_json", "1")
            .build()
        val root = getJson(Request.Builder().url(url).get().header("Referer", QQReferer).build()) ?: return emptyList()
        val list = root.obj("data")?.obj("song")?.array("list") ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.string("id")?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val mid = o.string("mid").orEmpty()
            LyricCandidate(
                source = LyricSource.QQ,
                // "id:mid": the gateway takes the number, the web fallback the mid.
                id = "$id:$mid",
                title = o.string("title") ?: o.string("name").orEmpty(),
                artist = o.array("singer").orEmpty().mapNotNull { (it as? JsonObject)?.string("name") }.joinToString(" / "),
                album = o.obj("album")?.string("name").orEmpty(),
                durationMs = (o.string("interval")?.toLongOrNull() ?: 0L) * 1000,
            )
        }
    }

    private suspend fun qqLyric(key: String): String? {
        val id = key.substringBefore(':').toLongOrNull() ?: return null
        val mid = key.substringAfter(':', "")
        qqGatewayLyric(id)?.let { return it }
        if (mid.isEmpty()) return null
        val url = "$QQWeb/lyric/fcgi-bin/fcg_query_lyric_new.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("songmid", mid)
            .addQueryParameter("format", "json")
            .addQueryParameter("nobase64", "1")
            .build()
        val root = getJson(Request.Builder().url(url).get().header("Referer", QQReferer).build()) ?: return null
        return decodeLyricField(root.string("lyric"))
    }

    private suspend fun qqGatewayLyric(id: Long): String? {
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("ct", "19")
                put("cv", "1859")
                put("uin", "0")
            }
            putJsonObject("req_1") {
                put("module", "music.musichallSong.PlayLyricInfo")
                put("method", "GetPlayLyricInfo")
                putJsonObject("param") {
                    put("songID", id)
                    put("format", "json")
                    put("crypt", 0)
                    put("qrc", 0)
                    put("roma", 0)
                    put("trans", 0)
                }
            }
        }
        val request = Request.Builder()
            .url(QQGateway)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val data = getJson(request)?.obj("req_1")?.obj("data") ?: return null
        return decodeLyricField(data.string("lyric"))
    }

    /** Plain LRC, base64 LRC, or (QRC-only songs) hex ciphertext, which we cannot read. */
    private fun decodeLyricField(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.startsWith('[')) return t
        val compact = t.filterNot(Char::isWhitespace)
        if (compact.length % 2 == 0 && compact.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return runCatching { String(Base64.decode(compact, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    // ── HTTP / JSON ─────────────────────────────────────────────────────────

    private suspend fun getJson(request: Request): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val text = r.body.string().trim()
                // QQ's web endpoints may wrap the JSON in a JSONP callback.
                val bare = if (text.startsWith('{')) text else text.substringAfter('(', "").substringBeforeLast(')', "")
                json.parseToJsonElement(bare) as? JsonObject
            }
        }.getOrNull()
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = this[key] as? JsonArray
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** LRC → timed lines, and the reverse stamp. */
internal object Lrc {
    private val stampRe = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(text: String): List<SubtitleLine> {
        val timed = mutableListOf<Pair<Long, String>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val stamps = stampRe.findAll(line).toList()
            if (stamps.isEmpty() || stamps.first().range.first != 0) continue
            val body = line.substring(stamps.last().range.last + 1).trim()
            if (body.isEmpty()) continue
            for (m in stamps) {
                val (mm, ss, frac) = m.destructured
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                timed += (mm.toLong() * 60_000 + ss.toLong() * 1000 + fracMs) to body
            }
        }
        timed.sortBy { it.first }
        return timed.mapIndexed { i, (from, body) ->
            val to = timed.getOrNull(i + 1)?.first ?: (from + 8_000)
            SubtitleLine(from, maxOf(to, from + 1), body)
        }
    }

    fun stamp(ms: Long): String {
        val m = ms / 60_000
        val s = (ms % 60_000) / 1000
        val cs = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(m, s, cs)
    }

    fun format(lines: List<SubtitleLine>): String = lines.joinToString("\n") { "[${stamp(it.fromMs)}]${it.text}" }
}

/**
 * A search query from a bilibili title: 《歌名》 when the title has one, else the title
 * with its bracket tags (【Hi-Res】, [MV], （中字）) and upload words (官方, 无损, 4K…) cut.
 */
internal fun lyricQueryFrom(title: String): String {
    Regex("""《([^》]+)》""").find(title)?.let { return it.groupValues[1].trim() }
    var t = title
    t = t.replace(Regex("""【[^】]*】|\[[^\]]*]|（[^）]*）|\([^)]*\)"""), " ")
    t = t.replace(Regex("""(?i)hi-?res|flac|无损|高音质|4k|1080p|mv|pv付?|官方|完整版|中字|中文字幕|歌词版|动态歌词|纯享版?|翻唱|cover"""), " ")
    t = t.split('|', '｜').firstOrNull { it.isNotBlank() } ?: t
    // Left-overs like "付❀" (PV付き) and decoration: keep letters, digits, CJK and spaces.
    t = t.replace(Regex("(?<=\\s|^)付"), " ").replace(Regex("[^\\p{L}\\p{N}\\s'&.-]"), " ")
    return t.replace(Regex("""\s+"""), " ").trim(' ', '-', '_', '·')
}
