@file:Suppress("MaxLineLength")

package com.gpo.yoin.symbols

import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale
import kotlin.math.hypot

/**
 * Video-app symbols drawn in the Yoin Symbols language: 24 viewport, 1.5 stroke,
 * round caps and joins, Yoin's continuous-curvature corners (the same three-cubic
 * corner YoinSymbols' Library / Copy / Launch use, extent ≈ 0.4 × the short side),
 * 8.5 circles, ~1dp dots, and the same optical box (squares 3.75–20.25, circles
 * 3.5–20.5). Outline by default; *Filled twins keep the outline's outer edge
 * (fillStroke), so switching never changes the glyph's size.
 */
public object PiliSymbols {
    private fun s(name: String, vararg strokes: String): ImageVector = symbol(name) { strokes.forEach { stroke(it) } }

    // ── Social / stats ──────────────────────────────────────────────────────

    /** 点赞 */
    public val ThumbUp: ImageVector by lazy {
        symbol("ThumbUp") {
            stroke(thumbPalm(Ident))
            stroke(thumbCuff(Ident))
        }
    }

    /** 已点赞 */
    public val ThumbUpFilled: ImageVector by lazy {
        symbol("ThumbUpFilled") {
            fillStroke(thumbPalm(Ident, closed = true))
            fillStroke(thumbCuff(Ident))
        }
    }

    /** 点踩 */
    public val ThumbDown: ImageVector by lazy {
        symbol("ThumbDown") {
            stroke(thumbPalm(Rotate180))
            stroke(thumbCuff(Rotate180))
        }
    }

    /** 已点踩 */
    public val ThumbDownFilled: ImageVector by lazy {
        symbol("ThumbDownFilled") {
            fillStroke(thumbPalm(Rotate180, closed = true))
            fillStroke(thumbCuff(Rotate180))
        }
    }

    /** 举报: a pennant on its pole */
    public val Flag: ImageVector by lazy {
        s(
            "Flag",
            "M6 20.25 L6 3.75",
            "M6 4.75 L17.1 4.75 C17.85 4.75 18.3 5.55 17.9 6.2 L15.95 9.4 L17.9 12.6 C18.3 13.25 17.85 14.05 17.1 14.05 L6 14.05",
        )
    }

    /** 投币 */
    public val Coin: ImageVector by lazy {
        symbol("Coin") {
            stroke(circle(12f, 12f, 8.5f))
            stroke("M8.9 8.85 L15.1 8.85")
            stroke("M12 8.85 L12 15.6")
        }
    }

    /** 已投币 */
    public val CoinFilled: ImageVector by lazy {
        symbol("CoinFilled") {
            // Outer edge = outline circle + half stroke; the T is a round-capped cut-out.
            fill(
                circle(12f, 12f, 9.25f) +
                    " M8.9 8.1 L15.1 8.1 A0.75 0.75 0 0 1 15.1 9.6 L12.75 9.6 L12.75 15.6 A0.75 0.75 0 0 1 11.25 15.6 L11.25 9.6 L8.9 9.6 A0.75 0.75 0 0 1 8.9 8.1 Z",
                evenOdd = true,
            )
        }
    }

    /** 弹幕 */
    public val Danmaku: ImageVector by lazy {
        symbol("Danmaku") {
            stroke(screenFrame())
            stroke("M7.6 9.6 L12.4 9.6")
            stroke("M14.6 9.6 L16.4 9.6")
            stroke("M7.6 14.4 L9.4 14.4")
            stroke("M11.6 14.4 L16.4 14.4")
        }
    }

    /** 弹幕关 */
    public val DanmakuOff: ImageVector by lazy {
        symbol("DanmakuOff") {
            // The slash cuts a clean gap through the frame (Yoin's *Off symbols).
            knockout(screenFramePath(), 4.6f, 3.9f, 19.4f, 20.1f, 1.45f).forEach { stroke(it) }
            stroke("M12.1 9.6 L16.4 9.6")
            stroke("M7.6 14.4 L11.4 14.4")
            stroke("M4.6 3.9 L19.4 20.1")
        }
    }

    /** 顶部弹幕 */
    public val DanmakuTop: ImageVector by lazy {
        symbol("DanmakuTop") {
            stroke(screenFrame())
            stroke("M8.6 9.4 L15.4 9.4")
            stroke("M10.4 12.6 L13.6 12.6")
        }
    }

    /** 底部弹幕 */
    public val DanmakuBottom: ImageVector by lazy {
        symbol("DanmakuBottom") {
            stroke(screenFrame())
            stroke("M10.4 11.4 L13.6 11.4")
            stroke("M8.6 14.6 L15.4 14.6")
        }
    }

    /** 滚动弹幕 */
    public val DanmakuScroll: ImageVector by lazy {
        symbol("DanmakuScroll", autoMirror = true) {
            stroke(screenFrame())
            stroke("M10.6 12 L16.4 12")
            stroke("M9.3 9.9 L7.2 12 L9.3 14.1")
        }
    }

    /** 评论 */
    public val Comment: ImageVector by lazy {
        symbol("Comment") {
            stroke(bubble())
            stroke("M8.1 8.9 L15.9 8.9")
            stroke("M8.1 12.1 L12.6 12.1")
        }
    }

    /** 播放量 */
    public val Views: ImageVector by lazy {
        symbol("Views") {
            stroke(rr(3.25f, 4.25f, 20.75f, 16.75f, 4.9f))
            stroke(translate(YoinPlayTriangle, -0.1f, -1.5f))
            stroke("M8.75 20 L15.25 20")
        }
    }

    /** 直播 */
    public val Live: ImageVector by lazy {
        symbol("Live") {
            stroke(circle(12f, 12f, 1.75f))
            stroke("M15.253 8.747 A4.6 4.6 0 0 1 15.253 15.253")
            stroke("M8.747 8.747 A4.6 4.6 0 0 0 8.747 15.253")
            stroke("M17.834 6.166 A8.25 8.25 0 0 1 17.834 17.834")
            stroke("M6.166 6.166 A8.25 8.25 0 0 0 6.166 17.834")
        }
    }

    /** 热门 */
    public val Fire: ImageVector by lazy {
        symbol("Fire") {
            stroke(FlamePath)
            stroke("M12 20.5 C10.62 20.5 9.55 19.48 9.55 18.15 C9.55 16.62 10.95 15.72 11.55 14.05 C13.35 15.05 14.45 16.5 14.45 18.15 C14.45 19.48 13.38 20.5 12 20.5")
        }
    }

    /** 排行榜 */
    public val Trophy: ImageVector by lazy {
        symbol("Trophy") {
            stroke(TrophyCup)
            stroke(TrophyHandleL)
            stroke(TrophyHandleR)
            stroke("M12 14.5 L12 17.25")
            stroke(rr(8.25f, 17.25f, 15.75f, 19.75f, 1.25f))
        }
    }

    public val TrophyFilled: ImageVector by lazy {
        symbol("TrophyFilled") {
            fillStroke(TrophyCup)
            stroke(TrophyHandleL)
            stroke(TrophyHandleR)
            stroke("M12 14.5 L12 17.25")
            fillStroke(rr(8.25f, 17.25f, 15.75f, 19.75f, 1.25f))
        }
    }

    /** 动态 */
    public val Dynamic: ImageVector by lazy {
        symbol("Dynamic") {
            stroke(circle(12f, 12f, 8.5f))
            stroke("M15.6 8.4 L13.55 13.55 L8.4 15.6 L10.45 10.45 Z")
        }
    }

    public val DynamicFilled: ImageVector by lazy {
        symbol("DynamicFilled") {
            fill(circle(12f, 12f, 9.25f) + " M16.25 7.75 L10.05 10.05 L7.75 16.25 L13.95 13.95 Z", evenOdd = true)
        }
    }

    /** 我的（选中） */
    public val PersonFilled: ImageVector by lazy {
        symbol("PersonFilled") {
            fillStroke("M12 4.25 C13.961 4.25 15.55 5.951 15.55 8.05 C15.55 10.149 13.961 11.85 12 11.85 C10.039 11.85 8.45 10.149 8.45 8.05 C8.45 5.951 10.039 4.25 12 4.25 Z")
            fillStroke("M4.8 19.9 C4.8 16.35 7.95 14.55 12 14.55 C16.05 14.55 19.2 16.35 19.2 19.9 Z")
        }
    }

    /** 通知 */
    public val Bell: ImageVector by lazy {
        s(
            "Bell",
            "M6.25 16.75 L6.25 10.6 C6.25 7.424 8.824 4.85 12 4.85 C15.176 4.85 17.75 7.424 17.75 10.6 L17.75 16.75",
            "M4.75 16.75 L19.25 16.75",
            "M12 3.25 L12 4.85",
            "M10.1 19.55 C10.5 20.15 11.2 20.5 12 20.5 C12.8 20.5 13.5 20.15 13.9 19.55",
        )
    }

    /** 私信 */
    public val Mail: ImageVector by lazy {
        symbol("Mail") {
            stroke(rr(3.75f, 5.25f, 20.25f, 18.75f, 5f))
            stroke("M7.4 9.2 L10.95 12.05 C11.564 12.543 12.436 12.543 13.05 12.05 L16.6 9.2")
        }
    }

    // ── Library ─────────────────────────────────────────────────────────────

    /** 历史记录 */
    public val History: ImageVector by lazy {
        s(
            "History",
            "M4.75 12 C4.75 16.28 8.22 19.75 12.5 19.75 C16.78 19.75 20.25 16.28 20.25 12 C20.25 7.72 16.78 4.25 12.5 4.25 C9.62 4.25 7.12 5.84 5.8 8.18",
            "M5.35 4.85 L5.8 8.18 L9.1 7.65",
            "M12.4 8.4 L12.4 12.35 L15 13.95",
        )
    }

    /** 稍后再看 */
    public val WatchLater: ImageVector by lazy {
        symbol("WatchLater") {
            stroke(circle(12f, 12f, 8.5f))
            stroke("M12 7.6 L12 12 L14.9 13.8")
        }
    }

    // ── Player ──────────────────────────────────────────────────────────────

    /** 全屏 */
    public val Fullscreen: ImageVector by lazy {
        s(
            "Fullscreen",
            "M4.75 9.25 L4.75 7.6 C4.75 6.026 6.026 4.75 7.6 4.75 L9.25 4.75",
            "M14.75 4.75 L16.4 4.75 C17.974 4.75 19.25 6.026 19.25 7.6 L19.25 9.25",
            "M19.25 14.75 L19.25 16.4 C19.25 17.974 17.974 19.25 16.4 19.25 L14.75 19.25",
            "M9.25 19.25 L7.6 19.25 C6.026 19.25 4.75 17.974 4.75 16.4 L4.75 14.75",
        )
    }

    /** 退出全屏 */
    public val FullscreenExit: ImageVector by lazy {
        s(
            "FullscreenExit",
            "M9.25 4.75 L9.25 6.4 C9.25 7.974 7.974 9.25 6.4 9.25 L4.75 9.25",
            "M19.25 9.25 L17.6 9.25 C16.026 9.25 14.75 7.974 14.75 6.4 L14.75 4.75",
            "M14.75 19.25 L14.75 17.6 C14.75 16.026 16.026 14.75 17.6 14.75 L19.25 14.75",
            "M4.75 14.75 L6.4 14.75 C7.974 14.75 9.25 16.026 9.25 17.6 L9.25 19.25",
        )
    }

    /** 画中画 */
    public val Pip: ImageVector by lazy {
        symbol("Pip") {
            stroke(screenFrame())
            fillStroke(rr(12.6f, 11.9f, 16.9f, 15.4f, 1.3f))
        }
    }

    public val Brightness: ImageVector by lazy {
        symbol("Brightness") {
            stroke(circle(12f, 12f, 3.55f))
            fill("M12 8.45 A3.55 3.55 0 0 0 12 15.55 Z")
            stroke("M18.35 12 L20.35 12")
            stroke("M16.49 16.49 L17.904 17.904")
            stroke("M12 18.35 L12 20.35")
            stroke("M7.51 16.49 L6.096 17.904")
            stroke("M5.65 12 L3.65 12")
            stroke("M7.51 7.51 L6.096 6.096")
            stroke("M12 5.65 L12 3.65")
            stroke("M16.49 7.51 L17.904 6.096")
        }
    }

    /** 重播 */
    public val Replay: ImageVector by lazy {
        symbol("Replay", autoMirror = true) {
            stroke("M5.25 12.5 C5.25 16.228 8.272 19.25 12 19.25 C15.728 19.25 18.75 16.228 18.75 12.5 C18.75 8.772 15.728 5.75 12 5.75 L8.6 5.75")
            stroke("M11 3.35 L8.6 5.75 L11 8.15")
        }
    }

    /** 字幕 */
    public val Subtitles: ImageVector by lazy {
        symbol("Subtitles") {
            stroke(screenFrame())
            stroke("M7.6 12.6 L10.2 12.6")
            stroke("M12.4 12.6 L16.4 12.6")
            stroke("M7.6 15.4 L13.2 15.4")
            stroke("M15.4 15.4 L16.4 15.4")
        }
    }

    /** 画质 */
    public val Hd: ImageVector by lazy {
        symbol("Hd") {
            stroke(screenFrame())
            stroke("M7.4 9.3 L7.4 14.7")
            stroke("M10.6 9.3 L10.6 14.7")
            stroke("M7.4 12 L10.6 12")
            stroke("M13.3 9.3 L13.3 14.7 L14.6 14.7 C16.091 14.7 17.1 13.6 17.1 12 C17.1 10.4 16.091 9.3 14.6 9.3 Z")
        }
    }

    // ── People ──────────────────────────────────────────────────────────────

    /** 关注 */
    public val PersonAdd: ImageVector by lazy {
        s(
            "PersonAdd",
            PersonHeadSide,
            PersonShoulderSide,
            "M18.25 13.75 L18.25 19.75",
            "M15.25 16.75 L21.25 16.75",
        )
    }

    /** 已关注 */
    public val PersonCheck: ImageVector by lazy {
        s(
            "PersonCheck",
            PersonHeadSide,
            PersonShoulderSide,
            "M15.1 16.95 L16.95 18.8 L20.75 15",
        )
    }

    // ── Misc ────────────────────────────────────────────────────────────────

    /** 链接 */
    public val Link: ImageVector by lazy {
        s(
            "Link",
            "M10.25 7.75 L8 7.75 C5.653 7.75 3.75 9.653 3.75 12 C3.75 14.347 5.653 16.25 8 16.25 L10.25 16.25",
            "M13.75 7.75 L16 7.75 C18.347 7.75 20.25 9.653 20.25 12 C20.25 14.347 18.347 16.25 16 16.25 L13.75 16.25",
            "M8.75 12 L15.25 12",
        )
    }

    /** 图片 */
    public val Image: ImageVector by lazy {
        symbol("Image") {
            stroke(rr(3.75f, 3.75f, 20.25f, 20.25f, 5.6f))
            stroke("M4.4 16.6 L8.56 12.44 C9.146 11.854 10.096 11.854 10.682 12.44 L14.2 15.95")
            stroke("M12.85 14.6 L14.31 13.14 C14.896 12.554 15.846 12.554 16.432 13.14 L19.75 16.45")
            stroke(circle(15.3f, 8.6f, 1.55f))
        }
    }

    /** 二维码 */
    public val Qr: ImageVector by lazy {
        symbol("Qr") {
            stroke(rr(3.75f, 3.75f, 10.25f, 10.25f, 2.5f))
            stroke(rr(13.75f, 3.75f, 20.25f, 10.25f, 2.5f))
            stroke(rr(3.75f, 13.75f, 10.25f, 20.25f, 2.5f))
            fill(circle(7f, 7f, 1f))
            fill(circle(17f, 7f, 1f))
            fill(circle(7f, 17f, 1f))
            fill(circle(14.6f, 14.6f, 1f))
            fill(circle(19.4f, 14.6f, 1f))
            fill(circle(17f, 17f, 1f))
            fill(circle(14.6f, 19.4f, 1f))
            fill(circle(19.4f, 19.4f, 1f))
        }
    }

    /** 退出登录 */
    public val Logout: ImageVector by lazy {
        symbol("Logout", autoMirror = true) {
            val b = PathB()
            b.m(10.25f, 4.25f)
            b.l(4.75f + 3.8f, 4.25f)
            b.corner(4.75f, 4.25f, -1f, 0f, 0f, 1f, 3.8f)
            b.l(4.75f, 19.75f - 3.8f)
            b.corner(4.75f, 19.75f, 0f, 1f, 1f, 0f, 3.8f)
            b.l(10.25f, 19.75f)
            stroke(b.str())
            stroke("M10.25 12 L19.75 12")
            stroke("M16.25 8.5 L19.75 12 L16.25 15.5")
        }
    }

    /** 深色模式 */
    public val DarkMode: ImageVector by lazy {
        s(
            "DarkMode",
            "M10.594 3.82 C6.435 4.535 3.474 8.257 3.713 12.471 C3.953 16.684 7.316 20.047 11.529 20.287 C15.743 20.526 19.465 17.565 20.18 13.406 C17.441 15.269 13.764 14.922 11.421 12.579 C9.078 10.236 8.731 6.559 10.594 3.82 Z",
            "M17.6 3.95 C17.704 5.43 18.17 5.896 19.65 6 C18.17 6.104 17.704 6.57 17.6 8.05 C17.496 6.57 17.03 6.104 15.55 6 C17.03 5.896 17.496 5.43 17.6 3.95 Z",
        )
    }

    /** 主题色 */
    public val Palette: ImageVector by lazy {
        symbol("Palette") {
            stroke("M12 3.75 C7.444 3.75 3.75 7.444 3.75 12 C3.75 16.556 7.444 20.25 12 20.25 C13.05 20.25 13.75 19.5 13.75 18.6 C13.75 18.15 13.6 17.8 13.35 17.5 C13.1 17.2 12.95 16.85 12.95 16.45 C12.95 15.55 13.65 14.85 14.55 14.85 L16.4 14.85 C18.526 14.85 20.25 13.126 20.25 11 C20.25 6.994 16.556 3.75 12 3.75 Z")
            fill(circle(7.6f, 12.2f, 1.05f))
            fill(circle(9.1f, 8.4f, 1.05f))
            fill(circle(12.9f, 7.1f, 1.05f))
            fill(circle(16.4f, 8.9f, 1.05f))
        }
    }

    /** 隐私 / 锁 */
    public val Lock: ImageVector by lazy {
        symbol("Lock") {
            stroke(rr(5.25f, 10f, 18.75f, 20.25f, 4.1f))
            stroke("M8.25 10 L8.25 7.75 C8.25 5.679 9.929 4 12 4 C14.071 4 15.75 5.679 15.75 7.75 L15.75 10")
            stroke("M12 14.15 L12 16.1")
        }
    }

    /** 视频 */
    public val Video: ImageVector by lazy {
        symbol("Video") {
            stroke(rr(3.25f, 6f, 15.75f, 18f, 4.5f))
            stroke("M15.75 10.35 L19.32 8.27 C19.92 7.92 20.75 8.35 20.75 9.07 L20.75 14.93 C20.75 15.65 19.92 16.08 19.32 15.73 L15.75 13.65")
        }
    }

    /** 专栏 */
    public val Article: ImageVector by lazy {
        symbol("Article") {
            stroke(rr(5f, 3.75f, 19f, 20.25f, 4.8f))
            stroke("M8.6 8.6 L15.4 8.6")
            stroke("M8.6 12 L15.4 12")
            stroke("M8.6 15.4 L12.4 15.4")
        }
    }

    /** 转发 */
    public val Forward: ImageVector by lazy {
        symbol("Forward", autoMirror = true) {
            stroke("M13.25 5.95 C13.25 5.29 14.04 4.95 14.52 5.4 L19.42 10.02 C19.97 10.54 19.97 11.41 19.42 11.93 L14.52 16.55 C14.04 17 13.25 16.66 13.25 16 L13.25 14.25 C8.85 14.25 6.05 15.45 4.4 18.55 C4.7 13.35 7.7 9.75 13.25 9.3 Z")
        }
    }

    /** @ */
    public val At: ImageVector by lazy {
        symbol("At") {
            stroke(circle(12f, 12f, 3.4f))
            stroke("M15.4 8.9 L15.4 13.4 C15.4 14.75 16.25 15.75 17.55 15.75 C19.05 15.75 20.25 14.35 20.25 12 C20.25 7.444 16.556 3.75 12 3.75 C7.444 3.75 3.75 7.444 3.75 12 C3.75 16.556 7.444 20.25 12 20.25 C13.65 20.25 15.15 19.8 16.4 19")
        }
    }

    /** 礼物 / 充电 */
    public val Gift: ImageVector by lazy {
        symbol("Gift") {
            stroke(rr(3.75f, 8.25f, 20.25f, 12f, 1.6f))
            val b = PathB()
            b.m(5.25f, 12f)
            b.l(5.25f, 20.25f - 3.6f)
            b.corner(5.25f, 20.25f, 0f, 1f, 1f, 0f, 3.6f)
            b.l(18.75f - 3.6f, 20.25f)
            b.corner(18.75f, 20.25f, 1f, 0f, 0f, -1f, 3.6f)
            b.l(18.75f, 12f)
            stroke(b.str())
            stroke("M12 8.25 L12 20.25")
            stroke("M12 8.25 C10.4 8.25 8.25 7.65 8.25 6 C8.25 4.95 9 4.25 9.95 4.25 C11.2 4.25 11.8 5.6 12 8.25 C12.2 5.6 12.8 4.25 14.05 4.25 C15 4.25 15.75 4.95 15.75 6 C15.75 7.65 13.6 8.25 12 8.25 Z")
        }
    }

    /** 置顶 / 回到顶部 */
    public val Up: ImageVector by lazy {
        s("Up", "M12 19.5 L12 4.75", "M6.15 10.6 L12 4.75 L17.85 10.6")
    }

    /** 清空 */
    public val Clear: ImageVector by lazy {
        s(
            "Clear",
            "M4.5 6.6 L19.5 6.6",
            "M9.35 6.6 L9.35 5.1 C9.35 4.492 9.842 4 10.45 4 L13.55 4 C14.158 4 14.65 4.492 14.65 5.1 L14.65 6.6",
            "M6.1 6.6 L6.807 17.752 C6.888 19.016 7.936 20 9.203 20 L14.797 20 C16.064 20 17.112 19.016 17.193 17.752 L17.9 6.6",
            "M9.6 12.6 L14.4 12.6",
        )
    }

    // ── Shared geometry ─────────────────────────────────────────────────────

    /** The 16:12-ish screen frame shared by Danmaku / Subtitles / Hd / Pip. */
    private fun screenFramePath(): PathB = PathB().also { it.rr(3.25f, 4.75f, 20.75f, 19.25f, 5f) }

    private fun screenFrame(): String = screenFramePath().str()

    /** Yoin's speech bubble (Lyrics / Reviews): continuous corners, the tail in the bottom-left. */
    private fun bubble(): String {
        val l = 3.75f
        val t = 4.1f
        val r = 20.25f
        val b = 16.7f
        val e = 5.6f
        val p = PathB()
        p.m(l + e, t)
        p.l(r - e, t)
        p.corner(r, t, 1f, 0f, 0f, 1f, e)
        p.l(r, b - e)
        p.corner(r, b, 0f, 1f, -1f, 0f, e)
        p.l(7.75f, b)
        p.l(l, 20.1f)
        p.l(l, t + e)
        p.corner(l, t, 0f, -1f, 1f, 0f, e)
        p.z()
        return p.str()
    }

    private const val PersonHeadSide =
        "M10.2 4.55 C12.023 4.55 13.5 6.139 13.5 8.1 C13.5 10.061 12.023 11.65 10.2 11.65 C8.377 11.65 6.9 10.061 6.9 8.1 C6.9 6.139 8.377 4.55 10.2 4.55 Z"
    private const val PersonShoulderSide =
        "M3.8 19.9 C3.8 16.7 6.6 14.9 10.2 14.9 C11.35 14.9 12.45 15.1 13.4 15.45"

    private const val FlamePath =
        "M12 20.5 C8.41 20.5 5.75 17.93 5.75 14.6 C5.75 11.6 7.7 9.6 9.25 7.75 C10.25 6.55 10.85 5.2 10.95 3.75 C13.9 5.35 15.95 8.1 16.2 11.05 C16.75 10.5 17.1 9.8 17.25 9.05 C18.2 10.4 18.25 12.5 18.25 14.6 C18.25 17.93 15.59 20.5 12 20.5 Z"

    private const val TrophyCup =
        "M7.25 4.25 L16.75 4.25 L16.75 9.75 C16.75 12.373 14.623 14.5 12 14.5 C9.377 14.5 7.25 12.373 7.25 9.75 Z"
    private const val TrophyHandleL =
        "M7.25 6.25 L5.6 6.25 C4.6 6.25 3.9 7.05 4.05 8.05 C4.35 9.95 5.6 11.2 7.55 11.45"
    private const val TrophyHandleR =
        "M16.75 6.25 L18.4 6.25 C19.4 6.25 20.1 7.05 19.95 8.05 C19.65 9.95 18.4 11.2 16.45 11.45"

    /** YoinSymbols.PlayCircle's rounded triangle (centred on 12.92, 12). */
    private const val YoinPlayTriangle =
        "M10.05 14.699 L10.05 9.301 C10.05 9.029 10.198 8.777 10.436 8.645 C10.674 8.513 10.965 8.52 11.197 8.664 L15.392 11.279 C15.641 11.434 15.793 11.707 15.793 12 C15.793 12.293 15.641 12.566 15.392 12.721 L11.197 15.336 C10.965 15.48 10.674 15.487 10.436 15.355 C10.198 15.223 10.05 14.971 10.05 14.699 Z"

    private val Ident: (Float, Float) -> Pair<Float, Float> = { x, y -> x to y }
    private val Rotate180: (Float, Float) -> Pair<Float, Float> = { x, y -> (24f - x) to (24f - y) }

    /** Palm + raised thumb; the cuff sits to its left. */
    private fun thumbPalm(tx: (Float, Float) -> Pair<Float, Float>, closed: Boolean = false): String {
        val p = PathB(tx)
        p.m(7.75f, 10.6f)
        p.l(10.42f, 5.02f)
        p.c(10.68f, 4.48f, 11.22f, 4.15f, 11.82f, 4.15f)
        p.c(12.8f, 4.15f, 13.55f, 5.03f, 13.4f, 6f)
        p.l(12.9f, 9.25f)
        p.l(17.45f, 9.25f)
        p.c(19.05f, 9.25f, 20.22f, 10.76f, 19.83f, 12.31f)
        p.l(18.68f, 17.11f)
        p.c(18.33f, 18.65f, 16.95f, 19.75f, 15.37f, 19.75f)
        p.l(7.75f, 19.75f)
        if (closed) {
            p.l(7.75f, 10.6f)
            p.z()
        }
        return p.str()
    }

    private fun thumbCuff(tx: (Float, Float) -> Pair<Float, Float>): String =
        PathB(tx).also { it.rr(3.75f, 10.6f, 7.75f, 19.75f, 1.7f) }.str()
}

// ── Geometry helpers (Yoin's continuous corner) ────────────────────────────

private fun fmt(v: Float): String {
    val s = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    return if (s == "-0") "0" else s
}

/**
 * Tiny path builder that writes absolute SVG path data and remembers the geometry
 * (for [knockout]). [tx] maps every point (mirror / rotate a whole glyph).
 */
private class PathB(private val tx: (Float, Float) -> Pair<Float, Float> = { x, y -> x to y }) {
    private val sb = StringBuilder()
    /** Sub-paths as lists of segments; a segment is 2 (line end) or 6 (cubic) floats. */
    val subpaths = mutableListOf<MutableList<FloatArray>>()
    private var start = floatArrayOf(0f, 0f)
    private var cur = floatArrayOf(0f, 0f)

    private fun pt(x: Float, y: Float): String {
        val (a, b) = tx(x, y)
        return "${fmt(a)} ${fmt(b)}"
    }

    private fun mapped(x: Float, y: Float): FloatArray = tx(x, y).let { floatArrayOf(it.first, it.second) }

    fun m(x: Float, y: Float) {
        sb.append(if (sb.isEmpty()) "" else " ").append("M").append(pt(x, y))
        start = mapped(x, y)
        cur = start
        subpaths += mutableListOf(floatArrayOf(start[0], start[1]))
    }

    fun l(x: Float, y: Float) {
        sb.append(" L").append(pt(x, y))
        cur = mapped(x, y)
        subpaths.last() += floatArrayOf(cur[0], cur[1])
    }

    fun c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
        sb.append(" C").append(pt(x1, y1)).append(' ').append(pt(x2, y2)).append(' ').append(pt(x, y))
        val a = mapped(x1, y1)
        val b = mapped(x2, y2)
        cur = mapped(x, y)
        subpaths.last() += floatArrayOf(a[0], a[1], b[0], b[1], cur[0], cur[1])
    }

    fun z() {
        sb.append(" Z")
        subpaths.last() += floatArrayOf(start[0], start[1])
        cur = start
    }

    /**
     * Yoin's continuous corner at (px, py): arriving along (ax, ay), leaving along
     * (bx, by); the curve starts [e] before the corner and ends [e] after it.
     * Call after a line that ends at (px - ax * e, py - ay * e).
     */
    fun corner(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, e: Float) {
        fun p(k1: Float, k2: Float) = floatArrayOf(px - ax * k1 * e + bx * k2 * e, py - ay * k1 * e + by * k2 * e)
        val k = CornerK
        val c1 = p(k[0], k[1]); val c2 = p(k[2], k[3]); val e1 = p(k[4], k[5])
        c(c1[0], c1[1], c2[0], c2[1], e1[0], e1[1])
        val c3 = p(k[6], k[7]); val c4 = p(k[8], k[9]); val e2 = p(k[10], k[11])
        c(c3[0], c3[1], c4[0], c4[1], e2[0], e2[1])
        val c5 = p(k[12], k[13]); val c6 = p(k[14], k[15]); val e3 = p(k[16], k[17])
        c(c5[0], c5[1], c6[0], c6[1], e3[0], e3[1])
    }

    /** Continuous-corner rounded rect, clockwise from the top edge. */
    fun rr(left: Float, top: Float, right: Float, bottom: Float, e: Float) {
        m(left + e, top)
        l(right - e, top)
        corner(right, top, 1f, 0f, 0f, 1f, e)
        l(right, bottom - e)
        corner(right, bottom, 0f, 1f, -1f, 0f, e)
        l(left + e, bottom)
        corner(left, bottom, -1f, 0f, 0f, -1f, e)
        l(left, top + e)
        corner(left, top, 0f, -1f, 1f, 0f, e)
        z()
    }

    fun str(): String = sb.toString()

    /** Dense polyline samples per sub-path. */
    fun sample(): List<List<FloatArray>> = subpaths.map { segs ->
        val out = mutableListOf<FloatArray>()
        var x0 = segs.first()[0]
        var y0 = segs.first()[1]
        out += floatArrayOf(x0, y0)
        segs.drop(1).forEach { s ->
            if (s.size == 2) {
                val n = (hypot(s[0] - x0, s[1] - y0) / 0.2f).toInt().coerceAtLeast(1)
                for (i in 1..n) {
                    val t = i / n.toFloat()
                    out += floatArrayOf(x0 + (s[0] - x0) * t, y0 + (s[1] - y0) * t)
                }
                x0 = s[0]; y0 = s[1]
            } else {
                val n = 16
                for (i in 1..n) {
                    val t = i / n.toFloat()
                    val u = 1 - t
                    val x = u * u * u * x0 + 3 * u * u * t * s[0] + 3 * u * t * t * s[2] + t * t * t * s[4]
                    val y = u * u * u * y0 + 3 * u * u * t * s[1] + 3 * u * t * t * s[3] + t * t * t * s[5]
                    out += floatArrayOf(x, y)
                }
                x0 = s[4]; y0 = s[5]
            }
        }
        out
    }
}

/** Factors (k1 along the incoming edge, k2 along the outgoing) of Yoin's 3-cubic corner. */
private val CornerK = floatArrayOf(
    0.65f, 0f, 0.475f, 0f, 0.3412f, 0.0682f,
    0.2237f, 0.128f, 0.128f, 0.2237f, 0.0682f, 0.3412f,
    0f, 0.475f, 0f, 0.65f, 0f, 1f,
)

private fun PathB.toStringPath(): String = str()

/** Continuous-corner rounded rect as path data. */
private fun rr(l: Float, t: Float, r: Float, b: Float, e: Float): String = PathB().also { it.rr(l, t, r, b, e) }.toStringPath()

/** Four-cubic circle (κ = 0.5523), starting at the top like YoinSymbols' circles. */
private fun circle(cx: Float, cy: Float, r: Float): String {
    val k = r * 0.5523f
    val p = PathB()
    p.m(cx, cy - r)
    p.c(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    p.c(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    p.c(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    p.c(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    p.z()
    return p.str()
}

/** Shifts every coordinate pair of an absolute M/L/C/Z path. */
private fun translate(path: String, dx: Float, dy: Float): String {
    val out = StringBuilder()
    var pairIndex = 0
    path.split(' ').forEach { token ->
        if (token.isEmpty()) return@forEach
        val cmd = token[0]
        val (prefix, num) = if (cmd.isLetter()) cmd.toString() to token.substring(1) else "" to token
        if (prefix.isNotEmpty()) pairIndex = 0
        if (num.isEmpty()) {
            out.append(if (out.isEmpty()) "" else " ").append(prefix)
            return@forEach
        }
        val v = num.toFloat() + if (pairIndex % 2 == 0) dx else dy
        pairIndex++
        out.append(if (out.isEmpty()) "" else " ").append(prefix).append(fmt(v))
    }
    return out.toString()
}

/**
 * The outline of [path] with a gap where the slash (ax, ay)→(bx, by) crosses it:
 * every sample within [gap] of the slash is dropped, the remaining runs come back
 * as separate open polylines.
 */
private fun knockout(path: PathB, ax: Float, ay: Float, bx: Float, by: Float, gap: Float): List<String> {
    fun dist(x: Float, y: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val t = (((x - ax) * vx + (y - ay) * vy) / (vx * vx + vy * vy)).coerceIn(0f, 1f)
        return hypot(x - (ax + vx * t), y - (ay + vy * t))
    }
    val runs = mutableListOf<MutableList<FloatArray>>()
    path.sample().forEach { pts ->
        var run: MutableList<FloatArray>? = null
        pts.forEach { p ->
            if (dist(p[0], p[1]) > gap) {
                if (run == null) run = mutableListOf<FloatArray>().also { runs += it }
                run.add(p)
            } else {
                run = null
            }
        }
    }
    // A closed outline cut once leaves its two halves split at the seam: join them.
    if (runs.size >= 2) {
        val first = runs.first()
        val last = runs.last()
        val a = first.first()
        val b = last.last()
        if (hypot(a[0] - b[0], a[1] - b[1]) < 0.3f) {
            last.addAll(first.drop(1))
            runs.removeAt(0)
        }
    }
    return runs.filter { it.size >= 2 }.map { run ->
        buildString {
            run.forEachIndexed { i, p ->
                append(if (i == 0) "M" else " L").append(fmt(p[0])).append(' ').append(fmt(p[1]))
            }
        }
    }
}
