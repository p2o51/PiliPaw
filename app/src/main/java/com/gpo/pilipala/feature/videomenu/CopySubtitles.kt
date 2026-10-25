package com.gpo.pilipala.feature.videomenu

import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.feature.player.PlayTarget
import com.gpo.pilipala.feature.player.SubtitleApi
import com.gpo.pilipala.feature.player.SubtitleTrack
import com.gpo.pilipala.feature.video.parseVideoDetail

/**
 * Builds what 复制字幕 copies: 标题, 链接, UP主 and their UID, 分P (when there are several),
 * 标签, 简介, then the subtitles of [cid] (the first part when 0). A video without subtitles
 * (or whose AI ones need a login) just gets the rest, quietly.
 */
internal suspend fun loadVideoTranscript(target: ShareTarget): String {
    val params = mapOf("aid" to target.aid)
    val d = parseVideoDetail(BiliClient.getData("/x/web-interface/wbi/view", params, wbi = true).asObj!!)
    val cid = target.cid.takeIf { it > 0 } ?: d.cid
    val tags = runCatching {
        BiliClient.getData("/x/tag/archive/tags", mapOf("bvid" to d.bvid)).asArr?.objects()
            ?.map { it.str("tag_name") }?.filter { it.isNotEmpty() }
    }.getOrNull().orEmpty()
    val track = runCatching {
        preferredTrack(SubtitleApi.playerInfo(PlayTarget(bvid = d.bvid, aid = d.aid, cid = cid)).first)
    }.getOrNull()
    val lines = track?.let { runCatching { SubtitleApi.lines(it) }.getOrNull() }.orEmpty()
        .filter { it.text.isNotBlank() }
    return buildString {
        appendLine("标题：${d.title}")
        appendLine("链接：https://www.bilibili.com/video/${d.bvid}")
        appendLine("UP主：${d.ownerName}（UID：${d.ownerMid}）")
        if (d.pages.size > 1) {
            d.pages.firstOrNull { it.cid == cid }?.let { appendLine("分P：P${it.page} ${it.part}") }
        }
        if (tags.isNotEmpty()) appendLine("标签：${tags.joinToString("、")}")
        appendLine("简介：")
        appendLine(d.desc.trim().ifEmpty { "（无）" })
        if (track != null && lines.isNotEmpty()) {
            appendLine()
            appendLine("字幕（${track.label}）：")
            lines.forEach { appendLine("[${stamp(it.fromMs)}] ${it.text.replace('\n', ' ')}") }
        }
    }.trimEnd()
}

/** Uploaded Chinese first, then B站's AI Chinese, then whatever comes first. */
private fun preferredTrack(tracks: List<SubtitleTrack>): SubtitleTrack? =
    tracks.firstOrNull { it.lan.startsWith("zh") }
        ?: tracks.firstOrNull { it.lan.startsWith("ai-zh") }
        ?: tracks.firstOrNull()

private fun stamp(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
