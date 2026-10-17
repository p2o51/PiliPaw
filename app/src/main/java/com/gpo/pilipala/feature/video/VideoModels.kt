package com.gpo.pilipala.feature.video

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.ui.components.VideoItem
import kotlinx.serialization.json.JsonObject

/** /x/web-interface/view `data`. */
@Immutable
data class VideoDetail(
    val aid: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val pic: String,
    val desc: String,
    val descNodes: List<DescNode>,
    val pubdate: Long,
    val duration: Long,
    val tname: String,
    val copyright: Int,
    val ownerMid: Long,
    val ownerName: String,
    val ownerFace: String,
    val view: Long,
    val danmaku: Long,
    val reply: Long,
    val favorite: Long,
    val coin: Long,
    val share: Long,
    val like: Long,
    val pages: List<VideoPage>,
    val season: UgcSeason?,
    val staff: List<Staff>,
    val redirectUrl: String,
    val honor: List<String>,
)

@Immutable
data class VideoPage(val cid: Long, val page: Int, val part: String, val duration: Long, val cover: String)

@Immutable
data class UgcSeason(
    val id: Long,
    val title: String,
    val cover: String,
    val intro: String,
    val sections: List<SeasonSection>,
    val epCount: Int,
) {
    val episodes: List<SeasonEpisode> get() = sections.flatMap { it.episodes }
}

@Immutable
data class SeasonSection(val id: Long, val title: String, val episodes: List<SeasonEpisode>)

@Immutable
data class SeasonEpisode(
    val aid: Long,
    val cid: Long,
    val bvid: String,
    val title: String,
    val cover: String,
    val duration: Long,
    val views: Long,
)

@Immutable
data class Staff(val mid: Long, val title: String, val name: String, val face: String, val follower: Long)

/** desc_v2 node — type 1 = text, 2 = @user (bizId = mid). */
@Immutable
data class DescNode(val text: String, val type: Int, val bizId: Long)

@Immutable
data class FavFolder(val id: Long, val title: String, val count: Int, val faved: Boolean, val isDefault: Boolean, val isPrivate: Boolean)

internal fun parseVideoDetail(d: JsonObject): VideoDetail {
    val owner = d.obj("owner")
    val stat = d.obj("stat")
    val seasonObj = d.obj("ugc_season")
    return VideoDetail(
        aid = d.long("aid"),
        bvid = d.str("bvid"),
        cid = d.long("cid"),
        title = d.str("title"),
        pic = d.str("pic"),
        desc = d.str("desc"),
        descNodes = d.arr("desc_v2").objects().map { DescNode(it.str("raw_text"), it.int("type", 1), it.long("biz_id")) },
        pubdate = d.long("pubdate"),
        duration = d.long("duration"),
        tname = d.str("tname"),
        copyright = d.int("copyright", 1),
        ownerMid = owner.long("mid"),
        ownerName = owner.str("name"),
        ownerFace = owner.str("face"),
        view = stat.long("view"),
        danmaku = stat.long("danmaku"),
        reply = stat.long("reply"),
        favorite = stat.long("favorite"),
        coin = stat.long("coin"),
        share = stat.long("share"),
        like = stat.long("like"),
        pages = d.arr("pages").objects().map {
            VideoPage(it.long("cid"), it.int("page"), it.str("part"), it.long("duration"), it.str("first_frame"))
        },
        season = seasonObj?.let { s ->
            UgcSeason(
                id = s.long("id"),
                title = s.str("title"),
                cover = s.str("cover"),
                intro = s.str("intro"),
                epCount = s.int("ep_count"),
                sections = s.arr("sections").objects().map { sec ->
                    SeasonSection(
                        id = sec.long("id"),
                        title = sec.str("title"),
                        episodes = sec.arr("episodes").objects().map { ep ->
                            val arc = ep.obj("arc")
                            SeasonEpisode(
                                aid = ep.long("aid"),
                                cid = ep.long("cid"),
                                bvid = ep.str("bvid"),
                                title = ep.str("title"),
                                cover = arc.str("pic"),
                                duration = arc.long("duration").takeIf { it > 0 } ?: ep.obj("page").long("duration"),
                                views = arc.obj("stat").long("view", -1),
                            )
                        },
                    )
                },
            )
        },
        staff = d.arr("staff").objects().map {
            Staff(it.long("mid"), it.str("title"), it.str("name"), it.str("face"), it.long("follower"))
        },
        redirectUrl = d.str("redirect_url"),
        honor = d.obj("honor_reply").arr("honor").objects().map { it.str("desc") }.filter { it.isNotEmpty() },
    )
}

/** Archive JSON (related / season lists) → shared [VideoItem]. */
internal fun JsonObject.toVideoItem(): VideoItem {
    val owner = obj("owner")
    val stat = obj("stat")
    return VideoItem(
        bvid = str("bvid"),
        aid = long("aid"),
        cid = long("cid"),
        title = str("title"),
        cover = str("pic"),
        duration = long("duration"),
        ownerMid = owner.long("mid"),
        ownerName = owner.str("name"),
        ownerFace = owner.str("face"),
        views = stat.long("view", -1),
        danmaku = stat.long("danmaku", -1),
        likes = stat.long("like", -1),
        pubdate = long("pubdate"),
    )
}
