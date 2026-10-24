package com.gpo.pilipala.feature.player

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asDouble
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.settings.AppSettings
import kotlinx.serialization.json.JsonArray

/** What happens when the playhead enters a segment of a kind (设置 › 跳过片段). */
internal enum class SkipMode(val label: String) {
    Skip("自动跳过"),
    Ask("提示我"),
    Mark("只标出"),
}

/**
 * The kinds of Bilibili segments: 番剧 片头 / 片尾 come from Bilibili itself (the pgc playurl's
 * clip_info_list); the rest from 空降助手 (BilibiliSponsorBlock, bsbsb.top), marked by viewers.
 */
internal enum class BiliSkipKind(val api: String, val label: String, val default: SkipMode, val official: Boolean = false) {
    BangumiOp("CLIP_TYPE_OP", "番剧片头", SkipMode.Skip, official = true),
    BangumiEd("CLIP_TYPE_ED", "番剧片尾", SkipMode.Skip, official = true),
    Sponsor("sponsor", "恰饭", SkipMode.Skip),
    SelfPromo("selfpromo", "自我推广", SkipMode.Ask),
    Interaction("interaction", "求三连", SkipMode.Skip),
    Intro("intro", "片头", SkipMode.Mark),
    Outro("outro", "片尾", SkipMode.Mark),
    Preview("preview", "回顾与预告", SkipMode.Mark),
    Filler("filler", "离题闲聊", SkipMode.Mark),
    MusicOfftopic("music_offtopic", "非音乐部分", SkipMode.Mark),
    ;

    val modeKey: AppSettings.Key<String> = AppSettings.register("bili_skip_${api.lowercase()}", default.name)

    val mode: SkipMode get() = SkipMode.entries.firstOrNull { it.name == modeKey.value } ?: default

    companion object {
        fun of(api: String): BiliSkipKind? = entries.firstOrNull { it.api == api }
        val community: List<BiliSkipKind> get() = entries.filter { !it.official }
    }
}

/** A segment [startMs] until [endMs] of [kind]; [id] is unique within the video. */
@Immutable
internal class BiliSkip(val id: String, val startMs: Long, val endMs: Long, val kind: BiliSkipKind)

internal object BiliSkips {
    /** 空降助手 on or off; off asks bsbsb.top nothing. 番剧片头片尾 follow their own rows. */
    val community: AppSettings.Key<Boolean> = AppSettings.register("bili_bsb_enabled", true)

    private const val Server = "https://www.bsbsb.top"

    /** 番剧 片头 / 片尾 from the pgc playurl (seconds in, milliseconds out). */
    fun clips(list: JsonArray): List<BiliSkip> = list.objects().mapNotNull { c ->
        val kind = BiliSkipKind.of(c.str("clipType")) ?: return@mapNotNull null
        val s = (c["start"].asDouble() * 1000).toLong()
        val e = (c["end"].asDouble() * 1000).toLong()
        if (e - s < 1_000) return@mapNotNull null
        BiliSkip("clip-${kind.api}-$s", s, e, kind)
    }

    /** The video's 空降助手 segments; empty when it has none, the server is unreachable or it is off. */
    suspend fun community(bvid: String, cid: Long): List<BiliSkip> {
        if (!community.value || bvid.isBlank()) return emptyList()
        val text = runCatching {
            BiliClient.getText(
                "$Server/api/skipSegments",
                buildMap {
                    put("videoID", bvid)
                    if (cid > 0) put("cid", cid)
                },
            )
        }.getOrNull() ?: return emptyList()
        val list = runCatching { BiliJson.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return list.objects().mapNotNull { o ->
            if (o.str("actionType", "skip") != "skip") return@mapNotNull null
            val oc = o.str("cid")
            if (cid > 0 && oc.isNotBlank() && oc != cid.toString()) return@mapNotNull null
            val kind = BiliSkipKind.of(o.str("category"))?.takeIf { !it.official } ?: return@mapNotNull null
            val seg = o.arr("segment")
            if (seg.size < 2) return@mapNotNull null
            val s = (seg[0].asDouble() * 1000).toLong()
            val e = (seg[1].asDouble() * 1000).toLong()
            if (e - s < 1_000) return@mapNotNull null
            BiliSkip(o.str("UUID").ifBlank { "bsb-$s" }, s, e, kind)
        }
    }
}
