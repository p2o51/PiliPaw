package com.gpo.pilipala.feature.bangumi

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.double
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import kotlinx.serialization.json.JsonObject

/** /pgc/view/web/season `result`. */
@Immutable
data class BangumiDetail(
    val seasonId: Long,
    val mediaId: Long,
    val type: Int,
    val title: String,
    val cover: String,
    val evaluate: String,
    val score: Double,
    val ratingCount: Long,
    val styles: List<String>,
    val areas: List<String>,
    val pubTimeShow: String,
    val isFinish: Boolean,
    val newEpDesc: String,
    val actors: String,
    val staff: String,
    val views: Long,
    val danmakus: Long,
    val likes: Long,
    val coins: Long,
    val favorites: Long,
    val reply: Long,
    val share: Long,
    val followText: String,
    val episodes: List<BangumiEpisode>,
    val sections: List<BangumiSection>,
    val seasons: List<BangumiSeasonRef>,
    val following: Boolean,
    val lastEpId: Long,
) {
    /** 追番 for anime / 国创, 追剧 for everything else. */
    val followVerb: String get() = if (type == 1 || type == 4) "追番" else "追剧"
    val allEpisodes: List<BangumiEpisode> get() = episodes + sections.flatMap { it.episodes }
}

@Immutable
data class BangumiEpisode(
    val id: Long,
    val aid: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val longTitle: String,
    val showTitle: String,
    val cover: String,
    /** Seconds. */
    val duration: Long,
    val badge: String,
    val pubTime: Long,
)

@Immutable
data class BangumiSection(val title: String, val episodes: List<BangumiEpisode>)

@Immutable
data class BangumiSeasonRef(val seasonId: Long, val title: String, val cover: String, val badge: String)

private fun JsonObject.toEpisode(type: Int): BangumiEpisode {
    val t = str("title")
    val index = when {
        t.toIntOrNull() != null && (type == 1 || type == 4) -> "第${t}话"
        t.toIntOrNull() != null -> "第${t}集"
        else -> t
    }
    return BangumiEpisode(
        id = long("id").takeIf { it > 0 } ?: long("ep_id"),
        aid = long("aid"),
        bvid = str("bvid"),
        cid = long("cid"),
        title = index,
        longTitle = str("long_title"),
        showTitle = str("show_title").ifEmpty { listOf(index, str("long_title")).filter { it.isNotEmpty() }.joinToString(" ") },
        cover = str("cover"),
        duration = long("duration") / 1000,
        badge = str("badge"),
        pubTime = long("pub_time"),
    )
}

internal fun parseBangumi(r: JsonObject): BangumiDetail {
    val type = r.int("type", 1)
    val stat = r.obj("stat")
    val rating = r.obj("rating")
    val publish = r.obj("publish")
    val user = r.obj("user_status")
    return BangumiDetail(
        seasonId = r.long("season_id"),
        mediaId = r.long("media_id"),
        type = type,
        title = r.str("title").ifEmpty { r.str("season_title") },
        cover = r.str("cover"),
        evaluate = r.str("evaluate"),
        score = rating.double("score"),
        ratingCount = rating.long("count"),
        styles = r.arr("styles").map { it.asString() }.filter { it.isNotEmpty() },
        areas = r.arr("areas").objects().map { it.str("name") }.filter { it.isNotEmpty() },
        pubTimeShow = publish.str("pub_time_show"),
        isFinish = publish.int("is_finish") == 1,
        newEpDesc = r.obj("new_ep").str("desc"),
        actors = r.str("actors"),
        staff = r.str("staff"),
        views = stat.long("views"),
        danmakus = stat.long("danmakus"),
        likes = stat.long("likes"),
        coins = stat.long("coins"),
        favorites = stat.long("favorites").takeIf { it > 0 } ?: stat.long("favorite"),
        reply = stat.long("reply"),
        share = stat.long("share"),
        followText = stat.str("follow_text"),
        episodes = r.arr("episodes").objects().map { it.toEpisode(type) },
        sections = r.arr("section").objects().map { s ->
            BangumiSection(s.str("title"), s.arr("episodes").objects().map { it.toEpisode(0) })
        }.filter { it.episodes.isNotEmpty() },
        seasons = r.arr("seasons").objects().map {
            BangumiSeasonRef(
                seasonId = it.long("season_id"),
                title = it.str("season_title").ifEmpty { it.str("title") },
                cover = it.str("cover"),
                badge = it.str("badge"),
            )
        },
        following = user.int("follow") == 1,
        lastEpId = user.obj("progress").long("last_ep_id"),
    )
}
