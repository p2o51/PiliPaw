package com.gpo.pilipala.feature.live

import androidx.compose.runtime.Immutable

/** Room header info (getH5InfoByRoom / getInfoByRoom, plus Room/get_info extras). */
@Immutable
internal data class LiveRoomInfo(
    val roomId: Long = 0,
    val uid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val background: String = "",
    /** 0 = offline, 1 = live, 2 = rotation. */
    val liveStatus: Int = 0,
    val liveStartTime: Long = 0,
    val online: Long = 0,
    val watchedText: String = "",
    val areaName: String = "",
    val parentAreaName: String = "",
    val anchorName: String = "",
    val anchorFace: String = "",
    val attention: Long = 0,
    val description: String = "",
    val tags: List<String> = emptyList(),
    /** 主播公告. */
    val news: String = "",
)

@Immutable
internal data class LiveQn(val qn: Int, val desc: String)

internal enum class LiveChatKind { Chat, SuperChat, Gift, Guard, System }

/** An image emote: inline `[emoji]` or a whole-message sticker. */
@Immutable
internal data class LiveEmote(val url: String, val width: Int = 0, val height: Int = 0) {
    /** width / height, 1 when unknown. */
    val ratio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
}

/** Fan medal worn by the sender. */
@Immutable
internal data class LiveMedal(
    val name: String,
    val level: Int,
    /** 0 none, 1 总督, 2 提督, 3 舰长. */
    val guardLevel: Int = 0,
    val lit: Boolean = true,
)

@Immutable
internal data class LiveChat(
    val key: String,
    val uid: Long,
    val name: String,
    val text: String,
    val face: String = "",
    /** RGB (0xRRGGBB) danmaku color, 0xFFFFFF = default white. */
    val color: Int = 0xFFFFFF,
    val kind: LiveChatKind = LiveChatKind.Chat,
    /** Whole-message sticker (dm_type 1). */
    val sticker: LiveEmote? = null,
    /** Inline emotes by their `[code]`. */
    val emots: Map<String, LiveEmote> = emptyMap(),
    val medal: LiveMedal? = null,
    /** SC price in yuan, guard / gift price label. */
    val price: String = "",
    /** 1 scroll, 4 bottom, 5 top. */
    val mode: Int = 1,
    // Gifts / guards
    val giftName: String = "",
    val giftIcon: String = "",
    val giftNum: Int = 0,
    val action: String = "",
    /** Gifts with the same combo merge into one row. */
    val comboKey: String = "",
    val guardLevel: Int = 0,
)

internal val LiveQnNames = mapOf(
    30000 to "杜比",
    20000 to "4K",
    15000 to "2K",
    10000 to "原画",
    400 to "蓝光",
    250 to "超清",
    150 to "高清",
    80 to "流畅",
)

internal fun guardName(level: Int): String = when (level) {
    1 -> "总督"
    2 -> "提督"
    3 -> "舰长"
    else -> ""
}
