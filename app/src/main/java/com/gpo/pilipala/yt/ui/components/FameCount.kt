package com.gpo.pilipala.yt.ui.components

import com.gpo.pilipala.ui.components.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** What 名声模式 hides: nothing, only the signed-in user's own numbers, or everyone's. */
enum class FameScope(val key: String, val label: String) {
    Off("off", "关闭"),
    Self("self", "只隐藏自己的"),
    All("all", "全部隐藏"),
    ;

    companion object {
        fun of(key: String): FameScope = entries.firstOrNull { it.key == key } ?: Off
    }
}

/**
 * 名声模式 (设置 › 习惯): popularity numbers draw as a short line ([FameCount]) —
 * 粉丝 and 获赞 for people; 播放, 弹幕, 点赞 / 投币 / 收藏 / 分享 for content; comment likes.
 * Counts that are not popularity (关注, 投稿, 评论数) stay.
 *
 * [scope]: [FameScope.All] hides every such number; [FameScope.Self] only the ones
 * that measure the signed-in user (their 粉丝 / 获赞, the counts on their own videos,
 * dynamics, articles and comments); logged out it hides nothing. Whether a number is
 * the user's own comes from [LocalFameSelf] (set with [FameSelf]) or an owner mid
 * passed to [hides] / [fameHides]. The scope logic lives only here.
 */
object FameMode {
    /** off | self | all ([FameScope.key]). Empty until the one-time migration below runs. */
    val scopeKey: AppSettings.Key<String> = AppSettings.register("fame_scope", "")

    // The old on/off switch: read once to seed fame_scope, then ignored.
    private val legacyKey: AppSettings.Key<Boolean> = AppSettings.register("fame_mode", false)

    // Snapshot mirrors, so every row reads them without a collector of its own.
    private val scopeMirror: MutableState<FameScope>
    // No YouTube sign-in yet: nobody is "self" until an account lands (then mirror it here).
    private val selfMirror = mutableStateOf(0L)

    init {
        // Read-only here (no writes from object init): an unset scope takes the old switch.
        scopeMirror = mutableStateOf(resolve(scopeKey.value))
        MainScope().launch { scopeKey.state.collect { scopeMirror.value = resolve(it) } }
    }

    private fun resolve(stored: String): FameScope =
        if (stored.isEmpty()) {
            if (legacyKey.value) FameScope.All else FameScope.Off
        } else {
            FameScope.of(stored)
        }

    /** Snapshot-readable: a composable reading it recomposes when the setting changes. */
    val scope: FameScope get() = scopeMirror.value

    /** The signed-in user's mid, 0 when logged out. Snapshot-readable. */
    val selfMid: Long
        get() = selfMirror.value

    /** True when [mid] is the signed-in user. */
    fun isSelf(mid: Long?): Boolean {
        val me = selfMid
        return me > 0 && mid != null && mid == me
    }

    /** Whether a popularity number is hidden; [self]: the number measures the signed-in user. */
    fun hides(self: Boolean): Boolean = when (scope) {
        FameScope.Off -> false
        FameScope.Self -> self && selfMid > 0
        FameScope.All -> true
    }

    /** Whether a popularity number on content owned by [ownerMid] is hidden. */
    fun hides(ownerMid: Long?): Boolean = hides(isSelf(ownerMid))

    fun select(value: FameScope) {
        AppSettings.set(scopeKey, value.key)
        scopeMirror.value = value
    }
}

/**
 * True inside content that belongs to the signed-in user (their own space, their
 * video's stats, their dynamic or comment): [FameCount] and [Stat] there count as
 * the user's own for [FameScope.Self].
 */
val LocalFameSelf = compositionLocalOf { false }

/**
 * Marks [content] as owned by [ownerMid] for 名声模式. A known owner (> 0) decides on
 * its own (someone else's card inside the user's space is not the user's); an unknown
 * one (null / 0) keeps what the parent says.
 */
@Composable
fun FameSelf(ownerMid: Long?, content: @Composable () -> Unit) {
    if (ownerMid == null || ownerMid <= 0L) {
        content()
        return
    }
    CompositionLocalProvider(LocalFameSelf provides FameMode.isSelf(ownerMid), content = content)
}

/** Marks [content] as the user's own ([self]) or not, whatever the parent says. */
@Composable
fun FameSelf(self: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFameSelf provides self, content = content)
}

/**
 * Whether a popularity number here is hidden: by [ownerMid] when the caller knows it
 * (> 0), else by [LocalFameSelf].
 */
@Composable
fun fameHides(ownerMid: Long? = null): Boolean =
    FameMode.hides(if (ownerMid != null && ownerMid > 0L) FameMode.isSelf(ownerMid) else LocalFameSelf.current)

/** [Stat] glyphs whose numbers are popularity: 名声模式 hides them by default. */
private val PopularityGlyphs: List<ImageVector> by lazy {
    listOf(
        PiliSymbols.Views,
        PiliSymbols.Danmaku,
        PiliSymbols.ThumbUp,
        PiliSymbols.ThumbUpFilled,
        PiliSymbols.Coin,
        PiliSymbols.CoinFilled,
        PiliSymbols.Forward,
        PiliSymbols.Live,
        PiliSymbols.PersonFilled,
        PiliSymbols.Fire,
        YoinSymbols.Star,
        YoinSymbols.StarFilled,
        YoinSymbols.Share,
        YoinSymbols.Favorite,
        YoinSymbols.FavoriteFilled,
    )
}

/** True for the glyphs whose counts are popularity (播放, 弹幕, 点赞…); identity, the symbols are singletons. */
fun ImageVector.isPopularityGlyph(): Boolean = PopularityGlyphs.any { it === this }

/**
 * A popularity number. Normally the [text]; under 名声模式 a short rounded line
 * in its place, the height of the text line so nothing around it moves.
 *
 * [revealable]: tapping the line rolls the real number in (spatial spring) for this
 * spot. Only where the number has no parent tap. Inside a bigger clickable (a card, a
 * row, a button, a stat block that opens a list) pass false: the line's tap area (its
 * slop plus the 48dp minimum touch target) would take the taps meant for the parent
 * and the text next to it. [hide] false keeps the number (not a popularity count).
 * [lineColor] defaults to onSurfaceVariant. [revealState] hoists the revealed flag for
 * a caller that swaps this composable out when the number changes (an AnimatedContent
 * keyed on the value), so a shown number stays shown. [self]: the number measures the
 * signed-in user (for [FameScope.Self]); [LocalFameSelf] by default.
 */
@Composable
fun FameCount(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    lineColor: Color = Color.Unspecified,
    revealable: Boolean = true,
    hide: Boolean = true,
    revealState: MutableState<Boolean>? = null,
    self: Boolean = LocalFameSelf.current,
) {
    if (!hide || !FameMode.hides(self) || text.isBlank()) {
        Text(text, modifier, color = color, style = style, fontWeight = fontWeight, maxLines = 1)
        return
    }
    val bar = lineColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant }
    if (!revealable) {
        FameLine(style, bar, modifier)
        return
    }
    val ownState = remember { mutableStateOf(false) }
    var revealed by (revealState ?: ownState)
    val haptic = LocalHapticFeedback.current
    val offsetSpec = YoinMotion.defaultSpatialSpec<IntOffset>()
    val sizeSpec = YoinMotion.defaultSpatialSpec<IntSize>()
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()
    Box(
        modifier
            .then(
                if (revealed) {
                    Modifier
                } else {
                    // The line is a glyph-sized target: the tap area reaches past it without moving the layout.
                    Modifier
                        .expandTouch(RevealSlopH, RevealSlopV)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "显示",
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            revealed = true
                        }
                        .padding(horizontal = RevealSlopH, vertical = RevealSlopV)
                },
            ),
    ) {
        AnimatedContent(
            targetState = revealed,
            transitionSpec = {
                // The number rolls up into the line's place; the slot widens on the same spring.
                (slideInVertically(offsetSpec) { it } + fadeIn(fadeInSpec)) togetherWith
                    (slideOutVertically(offsetSpec) { -it } + fadeOut(fadeOutSpec)) using
                    SizeTransform(clip = true) { _, _ -> sizeSpec }
            },
            contentAlignment = Alignment.CenterStart,
            label = "fameReveal",
        ) { shown ->
            if (shown) {
                Text(text, color = color, style = style, fontWeight = fontWeight, maxLines = 1)
            } else {
                FameLine(style, bar)
            }
        }
    }
}

private val RevealSlopH = 8.dp
private val RevealSlopV = 6.dp

/**
 * Lays the node out at its content size minus the padding the chain adds inside it
 * (`.expandTouch(h, v).clickable { }.padding(h, v)`): the clickable covers the padded
 * area while siblings see only the content's size.
 */
private fun Modifier.expandTouch(h: Dp, v: Dp): Modifier = layout { measurable, constraints ->
    val hp = h.roundToPx()
    val vp = v.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = hp * 2, vertical = vp * 2))
    val width = (placeable.width - hp * 2).coerceAtLeast(0)
    val height = (placeable.height - vp * 2).coerceAtLeast(0)
    layout(width, height) { placeable.place(-hp, -vp) }
}

/** The short rounded line: about two digits wide, centred in the text's line box. */
@Composable
private fun FameLine(style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val fontSize = style.fontSize.takeIf { it.isSpecified } ?: 14.sp
    val lineHeight: TextUnit = when {
        style.lineHeight.isSpecified && style.lineHeight.isSp -> style.lineHeight
        style.lineHeight.isSpecified && style.lineHeight.isEm -> (fontSize.value * style.lineHeight.value).sp
        else -> (fontSize.value * 1.3f).sp
    }
    val (width, height, thickness) = with(density) {
        Triple(
            (fontSize.value * 1.6f).sp.toDp(),
            lineHeight.toDp(),
            (fontSize.value * 0.22f).sp.toDp().coerceIn(2.dp, 4.dp),
        )
    }
    Box(
        modifier
            .size(width, height)
            .semantics { contentDescription = "已隐藏" }
            .drawBehind {
                val t = thickness.toPx()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, (size.height - t) / 2f),
                    size = Size(size.width, t),
                    cornerRadius = CornerRadius(t / 2f),
                )
            },
    )
}
