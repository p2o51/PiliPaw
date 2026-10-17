package com.gpo.pilipala.feature.player

import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asInt
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.has
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import kotlinx.serialization.json.JsonObject

/** One DASH representation (video or audio) with its base + backup URLs. */
internal data class MediaStream(
    val id: Int,
    val urls: List<String>,
    val codecId: Int,
    val codecs: String,
    val width: Int,
    val height: Int,
    val bandwidth: Long,
    val frameRate: String,
)

internal data class DurlSegment(val urls: List<String>, val lengthMs: Long)

internal data class QualityOption(val qn: Int, val label: String, val available: Boolean)

internal data class PlayInfo(
    val quality: Int,
    val qualities: List<QualityOption>,
    val videos: List<MediaStream>,
    val audios: List<MediaStream>,
    val durl: List<DurlSegment>,
    val timelengthMs: Long,
    val lastPlayTimeMs: Long,
) {
    val isDash: Boolean get() = videos.isNotEmpty()
}

/** Labels mirroring the Flutter `VideoQuality` / `AudioQuality` / decode-format enums. */
internal object Qualities {
    private val video = mapOf(
        6 to "240P 极速",
        16 to "360P 流畅",
        32 to "480P 清晰",
        64 to "720P 高清",
        74 to "720P60 高帧率",
        80 to "1080P 高清",
        100 to "智能修复",
        112 to "1080P+ 高码率",
        116 to "1080P60 高帧率",
        120 to "4K 超清",
        125 to "HDR 真彩色",
        126 to "杜比视界",
        127 to "8K 超高清",
    )
    private val audio = mapOf(
        30216 to "64K",
        30232 to "132K",
        30280 to "192K",
        30250 to "杜比全景声",
        30251 to "Hi-Res 无损",
    )
    private val audioRank = mapOf(30216 to 1, 30232 to 2, 30280 to 3, 30250 to 4, 30251 to 5)

    fun videoName(qn: Int): String = video[qn] ?: "${qn}P"

    /** "1080P+ 高码率" -> "1080P+" for the compact bottom-bar button. */
    fun videoShort(qn: Int): String = when (qn) {
        125 -> "HDR"
        126 -> "杜比"
        100 -> "修复"
        else -> videoName(qn).substringBefore(' ')
    }

    fun audioName(id: Int): String = audio[id] ?: "$id"
    fun audioRank(id: Int): Int = audioRank[id] ?: 0

    fun codecName(codecId: Int): String = when (codecId) {
        7 -> "AVC"
        12 -> "HEVC"
        13 -> "AV1"
        else -> "codec $codecId"
    }

    /** AppSettings.preferredCodec (0 avc / 1 hevc / 2 av1) -> bilibili codecid. */
    fun codecIdForSetting(setting: Int): Int = when (setting) {
        1 -> 12
        2 -> 13
        else -> 7
    }
}

/** Decoder presence per codec, cached for the process. */
internal object CodecSupport {
    private val cache = HashMap<String, Boolean>()

    private fun has(mime: String): Boolean = synchronized(cache) {
        cache.getOrPut(mime) {
            runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty() }.getOrDefault(false)
        }
    }

    fun video(codecId: Int): Boolean = when (codecId) {
        7 -> true
        12 -> has("video/hevc")
        13 -> has("video/av01")
        else -> true
    }

    /** Runs the (slow, first-time) decoder queries off the main thread. */
    fun warm(info: PlayInfo) {
        info.videos.map { it.codecId }.distinct().forEach { video(it) }
        info.audios.forEach { audio(it) }
    }

    fun audio(stream: MediaStream): Boolean = when {
        stream.id == 30250 || stream.codecs.startsWith("ec-3") -> has("audio/eac3") || has("audio/eac3-joc")
        stream.codecs.startsWith("fLaC", ignoreCase = true) -> has("audio/flac")
        else -> true
    }
}

internal object PlayUrlApi {
    suspend fun fetch(target: PlayTarget, qn: Int): PlayInfo {
        val root = if (target.isPgc) pgc(target, qn) else ugc(target, qn)
        return parse(root)
    }

    private suspend fun ugc(t: PlayTarget, qn: Int): JsonObject {
        val params = buildMap<String, Any?> {
            if (t.bvid.isNotBlank()) put("bvid", t.bvid) else put("avid", t.aid)
            put("cid", t.cid)
            put("qn", qn)
            put("fnval", 4048)
            put("fnver", 0)
            put("fourk", 1)
            put("voice_balance", 1)
            put("gaia_source", "pre-load")
            put("web_location", 1550101)
            if (!BiliClient.isLoggedIn) put("try_look", 1)
        }
        return BiliClient.getData("/x/player/wbi/playurl", params, wbi = true).asObj
            ?: throw BiliException(-1, "播放地址解析失败")
    }

    private suspend fun pgc(t: PlayTarget, qn: Int): JsonObject {
        val params = buildMap<String, Any?> {
            if (t.epId > 0) put("ep_id", t.epId)
            put("cid", t.cid)
            if (t.bvid.isNotBlank()) put("bvid", t.bvid)
            put("qn", qn)
            put("fnval", 4048)
            put("fnver", 0)
            put("fourk", 1)
            put("from_client", "BROWSER")
            put("drm_tech_type", 2)
        }
        val v2 = runCatching {
            BiliClient.getData("/pgc/player/web/v2/playurl", params).asObj?.obj("video_info")
        }.getOrNull()
        if (v2 != null && (v2.has("dash") || v2.has("durl"))) return v2
        return BiliClient.getData("/pgc/player/web/playurl", params).asObj
            ?: throw BiliException(-1, "播放地址解析失败")
    }

    fun parse(o: JsonObject): PlayInfo {
        val formatNames = o.arr("support_formats").objects().associate { f ->
            f.int("quality") to f.str("new_description").ifEmpty { f.str("display_desc") }
        }
        val dash = o.obj("dash")
        val videos = dash.arr("video").objects().map(::stream).filter { it.urls.isNotEmpty() }
        val audios = buildList {
            dash.obj("flac")?.obj("audio")?.let { add(stream(it)) }
            dash.obj("dolby")?.arr("audio")?.objects()?.forEach { add(stream(it)) }
            dash.arr("audio").objects().forEach { add(stream(it)) }
        }.filter { it.urls.isNotEmpty() }.distinctBy { it.id }
        val durl = o.arr("durl").objects().map { d ->
            val urls = buildList {
                d.str("url").takeIf { it.isNotBlank() }?.let(::add)
                d.arr("backup_url").forEach { u -> u.asString().takeIf { it.isNotBlank() }?.let(::add) }
            }
            DurlSegment(urls, d.long("length"))
        }.filter { it.urls.isNotEmpty() }

        val availableIds = videos.map { it.id }.toSet()
        val accept = o.arr("accept_quality").map { it.asInt() }.ifEmpty { availableIds.sortedDescending() }
        val quality = o.int("quality")
        val qualities = accept.distinct().map { qn ->
            QualityOption(
                qn = qn,
                label = formatNames[qn]?.takeIf { it.isNotBlank() } ?: Qualities.videoName(qn),
                available = if (videos.isNotEmpty()) qn in availableIds else true,
            )
        }
        return PlayInfo(
            quality = quality,
            qualities = qualities,
            videos = videos,
            audios = audios,
            durl = durl,
            timelengthMs = o.long("timelength"),
            lastPlayTimeMs = o.long("last_play_time"),
        )
    }

    private fun stream(o: JsonObject): MediaStream {
        val urls = buildList {
            o.str("baseUrl").ifEmpty { o.str("base_url") }.takeIf { it.isNotBlank() }?.let(::add)
            o.arr("backupUrl").ifEmpty { o.arr("backup_url") }.forEach { u ->
                u.asString().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()
        return MediaStream(
            id = o.int("id"),
            urls = urls,
            codecId = o.int("codecid"),
            codecs = o.str("codecs"),
            width = o.int("width"),
            height = o.int("height"),
            bandwidth = o.long("bandwidth"),
            frameRate = o.str("frameRate").ifEmpty { o.str("frame_rate") },
        )
    }
}

/** Stream choice rules (Flutter `queryVideoUrl`): best quality ≤ wish, preferred codec, nearest audio. */
internal object StreamPicker {
    fun videoQn(info: PlayInfo, wish: Int): Int {
        val ids = info.videos.map { it.id }.distinct()
        if (ids.isEmpty()) return info.quality
        return ids.filter { it <= wish }.maxOrNull() ?: ids.min()
    }

    fun codecsFor(info: PlayInfo, qn: Int): List<Int> =
        info.videos.filter { it.id == qn }.map { it.codecId }.distinct().filter { CodecSupport.video(it) }

    fun video(info: PlayInfo, qn: Int, codecId: Int): MediaStream? {
        val sameQn = info.videos.filter { it.id == qn }.ifEmpty { info.videos }
        val decodable = sameQn.filter { CodecSupport.video(it.codecId) }.ifEmpty { sameQn }
        return decodable.firstOrNull { it.codecId == codecId }
            ?: decodable.firstOrNull { it.codecId == 7 }
            ?: decodable.firstOrNull { it.codecId == 12 }
            ?: decodable.firstOrNull()
    }

    fun playableAudios(info: PlayInfo): List<MediaStream> =
        info.audios.filter { CodecSupport.audio(it) }.ifEmpty { info.audios }

    fun audio(info: PlayInfo, wish: Int): MediaStream? {
        val list = playableAudios(info)
        if (list.isEmpty()) return null
        list.firstOrNull { it.id == wish }?.let { return it }
        val rank = Qualities.audioRank(wish)
        return list.filter { Qualities.audioRank(it.id) <= rank }.maxByOrNull { Qualities.audioRank(it.id) }
            ?: list.minBy { Qualities.audioRank(it.id) }
    }
}
