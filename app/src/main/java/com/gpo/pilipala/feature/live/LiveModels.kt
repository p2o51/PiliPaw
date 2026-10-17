package com.gpo.pilipala.feature.live

import androidx.compose.runtime.Immutable

/** Room header info (getH5InfoByRoom / getInfoByRoom). */
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
)

@Immutable
internal data class LiveQn(val qn: Int, val desc: String)

internal enum class LiveChatKind { Chat, SuperChat, System }

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
    /** Big sticker danmaku (emoticon) image. */
    val emoticon: String = "",
    val price: String = "",
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
