package com.gpo.pilipala.feature.video.portrait

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.gpo.pilipala.feature.video.VideoDetailViewModel
import com.gpo.pilipala.ui.components.VideoItem
import kotlin.math.abs

/**
 * The immersive pager's list, kept for the nav entry (the page's composition goes away while
 * another page covers it, the list and the page index must not): the opened video, then its
 * related videos with portrait ones first. It grows with the related videos of whatever plays
 * near its end, so paging never runs dry.
 */
internal class PortraitFeedModel(head: VideoItem) : ViewModel() {
    val items = mutableStateListOf(head)
    var page by mutableIntStateOf(0)
    private val seen = hashSetOf(head.bvid)

    /** Appends the [related] videos not in the list yet, portrait ones first (order kept within each). */
    fun offer(related: List<VideoItem>, aspects: Map<String, Float>) {
        val fresh = related.filter { it.bvid.isNotEmpty() && it.bvid !in seen }
        if (fresh.isEmpty()) return
        val (portrait, rest) = fresh.partition { isPortrait(aspects[it.bvid] ?: 0f) }
        seen += fresh.map { it.bvid }
        items += portrait + rest
    }

    /**
     * Points [page] at [item] (the video the page shows as the pager opens again after the
     * classic layout): its own entry when listed, else it joins right after the last page.
     */
    fun focus(item: VideoItem) {
        if (item.bvid.isEmpty()) return
        val i = items.indexOfFirst { it.bvid == item.bvid }
        if (i >= 0) {
            page = i
            return
        }
        val at = (page + 1).coerceIn(0, items.size)
        items.add(at, item)
        seen += item.bvid
        page = at
    }
}

internal fun isPortrait(aspect: Float) = aspect > 0f && aspect < 1f

/** The opened video as the feed's first entry (from the detail, which is loaded once immersive is chosen). */
internal fun headItem(vm: VideoDetailViewModel): VideoItem {
    val d = vm.detail?.takeIf { vm.bvid.isEmpty() || it.bvid == vm.bvid } ?: return VideoItem(bvid = vm.bvid, cid = vm.cid, title = vm.target?.title.orEmpty(), cover = vm.cover)
    return VideoItem(
        bvid = d.bvid,
        aid = d.aid,
        cid = vm.cid,
        title = d.title,
        cover = d.pic,
        duration = d.duration,
        ownerMid = d.ownerMid,
        ownerName = d.ownerName,
        ownerFace = d.ownerFace,
        views = d.view,
        danmaku = d.danmaku,
        likes = d.like,
        pubdate = d.pubdate,
    )
}

/** Used until a video's real aspect is known. */
internal const val DefaultAspect = 9f / 16f

/** How far the width-fitted height may miss the frame's and still crop-fill it. */
private const val CoverSlack = 0.12f

/**
 * The picture's size in a [frameW] × [frameH] frame. Fitted to the width; when that lands
 * within ~12 % of the frame's height (shorter or taller) the picture covers the frame with a
 * slight crop, otherwise it is shown whole (width-fitted, or height-fitted when taller) and
 * centered over the blurred cover.
 */
internal fun pictureSize(frameW: Dp, frameH: Dp, aspect: Float): DpSize {
    val a = aspect.takeIf { it > 0f } ?: DefaultAspect
    if (frameW <= 0.dp || frameH <= 0.dp) return DpSize(frameW.coerceAtLeast(0.dp), frameH.coerceAtLeast(0.dp))
    val fittedH = frameW / a
    val ratio = fittedH / frameH
    return when {
        abs(ratio - 1f) <= CoverSlack -> if (ratio >= 1f) DpSize(frameW, fittedH) else DpSize(frameH * a, frameH)
        ratio < 1f -> DpSize(frameW, fittedH)
        else -> DpSize(frameH * a, frameH)
    }
}

/**
 * Medium / Expanded: the phone-like column the picture lives in — as tall as the window
 * allows, at most 9:16 wide, leaving room for the action column on both sides (the other
 * side's space keeps the column centered).
 */
internal fun columnSize(windowW: Dp, windowH: Dp, top: Dp, bottom: Dp): DpSize {
    val h = (windowH - top - bottom - ColumnVerticalMargin * 2).coerceAtLeast(240.dp)
    val roomW = windowW - (ActionColumnWidth + ColumnGap) * 2 - 32.dp
    val w = minOf(h * (9f / 16f), roomW).coerceAtLeast(160.dp)
    return DpSize(w, h)
}

internal val ColumnGap = 12.dp
internal val ColumnVerticalMargin = 12.dp

/**
 * How the pager uses the window.
 * - [Phone]: a phone held upright, the picture full-bleed with everything over it.
 * - [PhoneLandscape]: a short landscape window (a phone held sideways), the picture at full
 *   height, centered, the action column beside it, no panel.
 * - [Column]: the phone-like column centered on the blurred cover (tablet upright).
 * - [Panel]: a landscape window with room (tablet landscape): the column and its actions, and a
 *   side panel with the info and comments.
 */
internal enum class PortraitMode { Phone, PhoneLandscape, Column, Panel }

internal fun portraitMode(w: Dp, h: Dp, top: Dp, bottom: Dp): PortraitMode = when {
    w > h && h < 480.dp -> PortraitMode.PhoneLandscape
    w < 600.dp -> PortraitMode.Phone
    w > h && panelWidth(w, h, top, bottom) >= PanelMinWidth -> PortraitMode.Panel
    else -> PortraitMode.Column
}

/** The side panel's width in [PortraitMode.Panel]: what the column and its actions leave, capped. */
internal fun panelWidth(w: Dp, h: Dp, top: Dp, bottom: Dp): Dp {
    val colH = (h - top - bottom - ColumnVerticalMargin * 2).coerceAtLeast(240.dp)
    val left = colH * (9f / 16f) + ColumnGap + ActionColumnWidth + PanelSideMargin * 2
    return minOf(PanelMaxWidth, w - left - PanelEndMargin)
}

/** [PortraitMode.Panel]: the column in the pager's part of the window ([pageW] × [pageH]). */
internal fun panelColumnSize(pageW: Dp, pageH: Dp, top: Dp, bottom: Dp): DpSize {
    val h = (pageH - top - bottom - ColumnVerticalMargin * 2).coerceAtLeast(240.dp)
    val w = minOf(h * (9f / 16f), pageW - ActionColumnWidth - ColumnGap - PanelSideMargin * 2).coerceAtLeast(160.dp)
    return DpSize(w, h)
}

/**
 * [PortraitMode.PhoneLandscape]: the picture's frame at the window's full height, as wide as
 * its own aspect asks (a landscape video in the feed gets the width it needs), leaving room for
 * the action column on both sides.
 */
internal fun landscapeFrameSize(pageW: Dp, pageH: Dp, aspect: Float): DpSize {
    val a = aspect.takeIf { it > 0f } ?: DefaultAspect
    // Both sides also keep clear of 缩小 in the bottom-right corner.
    val roomW = pageW - (ActionColumnWidth + ColumnGap) * 2 - LandscapeCornerRoom * 2
    return DpSize(minOf(pageH * a, roomW).coerceAtLeast(160.dp), pageH)
}

private val LandscapeCornerRoom = 104.dp
internal val PanelSideMargin = 24.dp
internal val PanelEndMargin = 12.dp
private val PanelMinWidth = 320.dp
private val PanelMaxWidth = 600.dp
