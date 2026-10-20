package com.gpo.pilipala.feature.reply

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.media.LocalImageViewer
import com.gpo.pilipala.feature.media.ViewerImage
import com.gpo.pilipala.feature.media.imageViewerOrigin
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.pageSharedSource
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.CoverBadge
import com.gpo.pilipala.ui.components.FameSelf
import com.gpo.pilipala.ui.components.fameHides
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import com.gpo.pilipala.ui.components.inlineIconSize
import com.gpo.pilipala.ui.components.centered

/** Everything a reply row can trigger; hosts (list / thread page) wire it to their ViewModel. */
@Stable
internal class ReplyActions(
    val likeState: (Reply) -> LikeState,
    val onLike: (Reply) -> Unit,
    val onReply: (Reply) -> Unit,
    /** Opens the thread (pushed page or in place); null on the thread itself. */
    val onOpenThread: ((Reply) -> Unit)?,
    val onLongPress: (Reply) -> Unit,
    val onTimestampClick: ((Long) -> Unit)?,
    /** The reply just sent grows in (null: nothing animates in). */
    val entrance: ReplyEntrance? = null,
    /** Rows resizing in this list (gates its placement springs). */
    val layout: ReplyLayoutMotion? = null,
    /** The preview block is the pushed thread page's container-transform source (false: the thread opens in place). */
    val threadSource: Boolean = true,
    /** The user's relation with a commenter (2 关注, 6 互关, 0 none); read in composition, so tags follow it. */
    val relationOf: (Long) -> Int = { 0 },
)

private val PictureShape = ContinuousRoundedCornerShape(10.dp)
private val PreviewShape = ContinuousRoundedCornerShape(14.dp)

/** Lines a list row shows before 展开. */
private const val ClampLines = 6

/**
 * One comment. Avatar column on the left, everything else stacked on the right:
 * name + level/UP/置顶 tags, time and IP location as separate quiet labels,
 * rich body, pictures, actions, then the sub-reply preview block.
 */
@Composable
internal fun ReplyRow(
    reply: Reply,
    actions: ReplyActions,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 36.dp,
    showPreview: Boolean = true,
    clampLines: Boolean = true,
    onClick: () -> Unit,
) {
    val nav = LocalNavigator.current
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val entrance = remember(reply.rpid) { actions.entrance?.claim(reply.rpid) }
    val longPress = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        actions.onLongPress(reply)
    }
    Row(
        modifier = modifier
            .entrance(entrance, actions.layout)
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = longPress)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Avatar(reply.member.face, avatarSize) {
                nav.push(Route.Member(reply.member.mid, reply.member.face, reply.member.name))
            }
            if (reply.member.officialType >= 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(scheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(if (reply.member.officialType == 0) scheme.tertiary else scheme.primary),
                )
            }
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    reply.member.name,
                    // Set tight: the LV / UP / 关注 tags centre on the name's glyphs.
                    style = MaterialTheme.typography.labelLarge.centered(),
                    fontWeight = if (reply.member.vip) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (reply.member.vip) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LevelTag(reply.member.level)
                if (reply.isUp) Tag("UP", container = scheme.primaryContainer, content = scheme.onPrimaryContainer)
                RelationTag(actions.relationOf(reply.member.mid))
                if (reply.isTop) Tag("置顶", container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val meta = MaterialTheme.typography.labelSmall.centered()
                Text(Fmt.relativeTime(reply.ctime), style = meta, color = scheme.outline)
                if (reply.location.isNotEmpty()) {
                    Text(reply.location, style = meta, color = scheme.outline)
                }
                if (reply.invisible) {
                    Text("已隐藏", style = meta, color = scheme.error)
                }
            }
            if (clampLines) {
                ClampedReplyText(
                    content = reply.content,
                    stateKey = reply.rpid,
                    layoutMotion = actions.layout,
                    onTimestampClick = actions.onTimestampClick,
                    onEmoteLongPress = longPress,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                ReplyRichText(
                    content = reply.content,
                    modifier = Modifier.padding(top = 6.dp),
                    onTimestampClick = actions.onTimestampClick,
                    onEmoteLongPress = longPress,
                )
            }
            if (reply.content.pictures.isNotEmpty()) {
                PictureGrid(
                    pictures = reply.content.pictures,
                    keyPrefix = "reply:${reply.rpid}",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            ActionRow(reply, actions)
            if (showPreview && (reply.replies.isNotEmpty() || reply.rcount > 0) && actions.onOpenThread != null) {
                SubReplyPreview(
                    reply = reply,
                    actions = actions,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

// ── 展开 / 收起 ──────────────────────────────────────────────────────────────

/** Written by the text's layout (synchronously, in its measure), read by layout and draw only. */
private class ClampMetrics {
    var overflow = false
    var collapsedPx = 0
    var lastLinePx = 0f
    val layerPaint = Paint()

    fun update(result: TextLayoutResult) {
        overflow = result.lineCount > ClampLines
        if (overflow) {
            val bottom = result.getLineBottom(ClampLines - 1)
            collapsedPx = ceil(bottom).toInt()
            lastLinePx = bottom - result.getLineTop(ClampLines - 1)
        }
    }
}

/**
 * A list row's body: [ClampLines] lines, the last one fading out, with 展开
 * under it when there is more. The height follows the default spatial
 * spring, critically damped, both ways (the fade lifts with it; nothing
 * overshoots) and the label cross-fades on the same progress; nothing
 * recomposes per frame. While it moves the list's
 * placement springs stand down, so the rows below track it exactly.
 * Short comments never compose the toggle: its slot is reserved in the very
 * layout that finds the overflow, and it fills in on the next frame.
 */
@Composable
private fun ClampedReplyText(
    content: ReplyContent,
    stateKey: Long,
    layoutMotion: ReplyLayoutMotion?,
    onTimestampClick: ((Long) -> Unit)?,
    onEmoteLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val metrics = remember(content) { ClampMetrics() }
    var hasMore by remember(content) { mutableStateOf(false) }
    val toggleLine = MaterialTheme.typography.labelLarge.lineHeight
    val progress = remember(stateKey) { Animatable(if (expanded) 1f else 0f) }
    val spatial = settledSpatialSpec<Float>()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(expanded) {
        val target = if (expanded) 1f else 0f
        if (progress.value == target) return@LaunchedEffect
        layoutMotion?.begin()
        try {
            progress.animateTo(target, spatial)
        } finally {
            layoutMotion?.end()
        }
    }
    Layout(
        content = {
            ReplyRichText(
                content = content,
                onTimestampClick = onTimestampClick,
                onEmoteLongPress = onEmoteLongPress,
                onTextLayout = {
                    metrics.update(it)
                    if (hasMore != metrics.overflow) hasMore = metrics.overflow
                },
                modifier = Modifier
                    .layoutId(TextId)
                    .clampReveal(metrics, progress),
            )
            if (hasMore) {
                ExpandToggle(
                    expanded = expanded,
                    progress = progress,
                    modifier = Modifier.layoutId(ToggleId),
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    expanded = !expanded
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val text = measurables.first { it.layoutId == TextId }.measure(loose)
        if (!metrics.overflow) {
            return@Layout layout(text.width, text.height) { text.place(0, 0) }
        }
        val toggle = measurables.firstOrNull { it.layoutId == ToggleId }?.measure(loose)
        val reserved = (if (toggleLine.isSp) toggleLine.toPx() else 20.sp.toPx()) + (ToggleVerticalPadding * 2).toPx()
        val toggleHeight = toggle?.height ?: reserved.roundToInt()
        // The toggle's pill padding hangs outside the text column, like the action row.
        val inset = ToggleInset.roundToPx()
        layout(max(text.width, (toggle?.width ?: 0) - inset), text.height + toggleHeight) {
            text.place(0, 0)
            toggle?.place(-inset, text.height)
        }
    }
}

private const val TextId = "text"
private const val ToggleId = "toggle"
private val ToggleInset = 8.dp
private val ToggleVerticalPadding = 4.dp

/** Height lerps from the clamp to the full text on [progress]; the clamp's last line fades by (1 − progress). */
private fun Modifier.clampReveal(metrics: ClampMetrics, progress: Animatable<Float, AnimationVector1D>): Modifier = this
    .clipToBounds()
    .drawWithContent {
        val strength = 1f - progress.value.coerceIn(0f, 1f)
        val fade = metrics.lastLinePx
        if (!metrics.overflow || fade <= 0f || strength <= 0.001f) {
            drawContent()
            return@drawWithContent
        }
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(Offset.Zero, size), metrics.layerPaint)
        drawContent()
        val top = size.height - fade
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black,
                1f to Color.Black.copy(alpha = 1f - strength),
                startY = top,
                endY = size.height,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val full = placeable.height
        val height = if (metrics.overflow) {
            val collapsed = metrics.collapsedPx.coerceAtMost(full)
            (collapsed + (full - collapsed) * progress.value).roundToInt().coerceIn(collapsed, full)
        } else {
            full
        }
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

/** 展开 ⇄ 收起 cross-fading on the reveal's own progress, the chevron turning with it. */
@Composable
private fun ExpandToggle(
    expanded: Boolean,
    progress: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val label = if (expanded) "收起" else "展开"
    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = ToggleInset, vertical = ToggleVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box {
            Text(
                "展开",
                style = MaterialTheme.typography.labelLarge.centered(),
                color = primary,
                modifier = Modifier.graphicsLayer { alpha = 1f - progress.value.coerceIn(0f, 1f) },
            )
            Text(
                "收起",
                style = MaterialTheme.typography.labelLarge.centered(),
                color = primary,
                modifier = Modifier.graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
            )
        }
        Icon(
            YoinSymbols.ChevronDown,
            contentDescription = null,
            tint = primary,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = 180f * progress.value.coerceIn(0f, 1f) },
        )
    }
}

/**
 * 关注 / 互关 next to a commenter the user follows: a tonal block like LV / UP, fading
 * (and widening) in once the relation is known. Nothing for 0.
 */
@Composable
private fun RelationTag(relation: Int) {
    val scheme = MaterialTheme.colorScheme
    // Keeps the last tag while it leaves (unfollowed elsewhere, list refreshed).
    val shown = remember { intArrayOf(relation) }
    if (relation != 0) shown[0] = relation
    AnimatedVisibility(
        visible = relation != 0,
        enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandHorizontally(YoinMotion.defaultSpatialSpec(), expandFrom = Alignment.Start),
        exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkHorizontally(YoinMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Start),
    ) {
        if (shown[0] == 6) {
            Tag("互关", container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
        } else {
            Tag("关注", container = scheme.surfaceContainerHighest, content = scheme.primary)
        }
    }
}

@Composable
private fun LevelTag(level: Int) {
    val scheme = MaterialTheme.colorScheme
    val high = level >= 6
    Tag(
        "LV$level",
        container = if (high) scheme.secondaryContainer else scheme.surfaceContainerHighest,
        content = if (high) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
    )
}

@Composable
private fun ActionRow(reply: Reply, actions: ReplyActions) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).offset(x = (-8).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    actions.onReply(reply)
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(ReplyIcons.Reply, null, Modifier.size(16.dp), tint = scheme.outline)
            Text("回复", style = MaterialTheme.typography.labelMedium.centered(), color = scheme.outline)
        }
        if (reply.upLiked) {
            Text("UP主觉得很赞", style = MaterialTheme.typography.labelSmall.centered(), color = scheme.primary)
        }
        if (reply.hot) {
            Tag("热评", container = scheme.errorContainer, content = scheme.onErrorContainer)
        }
        Spacer(Modifier.weight(1f))
        // Keyed: a lazy slot reused for another comment must not play this one's wash / roll.
        key(reply.rpid) {
            // 名声模式: the likes on the user's own comment count as their own.
            FameSelf(reply.member.mid) {
                LikeButton(actions.likeState(reply)) {
                    actions.onLike(reply)
                }
            }
        }
    }
}

/**
 * Thumb toggle, kept quiet: the outline and filled glyphs cross-fade while
 * the color washes, both on the effects spring; only the digits that change
 * roll (up when the count grows, down when it shrinks). No kick, no tilt.
 */
@Composable
internal fun LikeButton(state: LikeState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    // Read only in draw (icon tint, glyph mix, digit color): the wash recomposes nothing.
    val color = animateColorAsState(
        if (state.liked) scheme.primary else scheme.outline,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "likeColor",
    )
    val filled = animateFloatAsState(
        if (state.liked) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "likeGlyph",
    )
    val outline = rememberVectorPainter(PiliSymbols.ThumbUp)
    val solid = rememberVectorPainter(PiliSymbols.ThumbUpFilled)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable {
                haptic.performHapticFeedback(if (state.liked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val label = if (state.liked) "取消点赞" else "点赞"
        Box(
            Modifier
                .size(16.dp)
                .semantics {
                    contentDescription = label
                    role = Role.Image
                }
                .drawBehind {
                    val mix = filled.value.coerceIn(0f, 1f)
                    val tint = ColorFilter.tint(color.value)
                    if (mix < 1f) with(outline) { draw(size, alpha = 1f - mix, colorFilter = tint) }
                    if (mix > 0f) with(solid) { draw(size, alpha = mix, colorFilter = tint) }
                },
        )
        RollingCount(state.count) { color.value }
    }
}

/**
 * Odometer count: one slot per character, keyed from the right so ones stay
 * over ones; a slot rolls only when its character changes, on the fast
 * spatial spring critically damped (short, no bounce). Under 名声模式 there
 * is no count at all: the button is its thumb alone, with nothing that looks
 * like a line to tap open.
 */
@Composable
private fun RollingCount(count: Long, color: ColorProducer) {
    val motion = replyMotion()
    val text = if (count > 0 && !fameHides()) Fmt.count(count) else ""
    val last = remember { longArrayOf(count) }
    val up = count >= last[0]
    SideEffect { last[0] = count }
    val slots = max(text.length, 1)
    Row(Modifier.clipToBounds()) {
        for (i in 0 until slots) {
            val fromEnd = slots - 1 - i
            val char = text.getOrNull(text.length - 1 - fromEnd)?.toString().orEmpty()
            key(fromEnd) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        (slideInVertically(motion.offset) { if (up) it else -it } + fadeIn(motion.fade)) togetherWith
                            (slideOutVertically(motion.offset) { if (up) -it else it } + fadeOut(motion.fade)) using
                            SizeTransform(clip = true) { _, _ -> motion.size }
                    },
                    label = "likeDigit",
                ) { c ->
                    BasicText(c, style = MaterialTheme.typography.labelMedium.centered(), color = color)
                }
            }
        }
    }
}

/**
 * The 楼中楼 preview block. On phones it is the thread page's
 * container-transform source (the page grows out of it); on wider windows
 * the thread opens in the same pane. A sub-reply just sent grows in.
 */
@Composable
private fun SubReplyPreview(reply: Reply, actions: ReplyActions, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val open = { actions.onOpenThread?.invoke(reply) ?: Unit }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (actions.threadSource) {
                    Modifier.pageSharedSource(Route.ReplyDetail(reply.oid, reply.type, reply.rpid))
                } else {
                    Modifier
                },
            )
            .clip(PreviewShape)
            .background(scheme.surfaceContainer)
            .clickable(onClick = open)
            .padding(vertical = 6.dp),
    ) {
        reply.replies.take(3).forEach { sub ->
            key(sub.rpid) {
                val lineEntrance = remember(sub.rpid) {
                    if (actions.entrance?.claimSent(sub.rpid) == true) Entrance(fade = true) else null
                }
                val subLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    actions.onLongPress(sub)
                }
                ReplyRichText(
                    content = sub.content,
                    prefix = RichPrefix(sub.member.name, sub.member.mid, sub.member.face, sub.isUp, actions.relationOf(sub.member.mid)),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    onTimestampClick = actions.onTimestampClick,
                    onEmoteLongPress = subLongPress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(lineEntrance, actions.layout)
                        // A 关注 / 互关 tag landing after the name can rewrap the line: the height follows on the spring.
                        .animateContentSize(YoinMotion.defaultSpatialSpec())
                        .combinedClickable(onClick = open, onLongClick = subLongPress)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        val shownAll = reply.rcount <= reply.replies.size && reply.replies.isNotEmpty()
        if (!shownAll || reply.upReplied) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val label = MaterialTheme.typography.labelMedium.centered()
                if (reply.upReplied) {
                    Text("UP主等人", style = label, color = scheme.onSurfaceVariant)
                }
                Text(
                    reply.entryText.ifEmpty { "${reply.rcount} 条回复" },
                    style = label,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
                Icon(YoinSymbols.ChevronRight, null, Modifier.size(label.inlineIconSize()), tint = scheme.primary)
            }
        }
    }
}

@Composable
internal fun PictureGrid(
    pictures: List<ReplyPicture>,
    keyPrefix: String,
    modifier: Modifier = Modifier,
) {
    // Each cell is an image-viewer origin: the picture grows out of it and flies home into it.
    val viewer = LocalImageViewer.current
    val keys = remember(keyPrefix, pictures.size) { List(pictures.size) { "$keyPrefix:$it" } }
    val images = remember(pictures) { pictures.map { ViewerImage(it.src, it.width, it.height) } }
    val onOpen: (Int) -> Unit = { viewer.open(images, it, keys) }
    if (pictures.size == 1) {
        val p = pictures.first()
        val ratio = if (p.width > 0 && p.height > 0) (p.width.toFloat() / p.height).coerceIn(0.6f, 1.8f) else 1f
        val long = p.width > 0 && p.height > p.width * 2.2f
        Box(
            modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth(0.62f)
                .aspectRatio(ratio)
                // Before the clip: the cell (badge included) gives way while its picture is up.
                .imageViewerOrigin(keys[0], PictureShape)
                .clip(PictureShape)
                .clickable { onOpen(0) },
        ) {
            NetImage(p.src, Modifier.matchParentSize(), shape = PictureShape, thumbWidth = 480.dp)
            if (long) CoverBadge("长图", Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        return
    }
    val cols = if (pictures.size == 2 || pictures.size == 4) 2 else 3
    Column(
        modifier.widthIn(max = if (cols == 2) 260.dp else 380.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        pictures.chunked(cols).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                chunk.forEachIndexed { i, p ->
                    val index = row * cols + i
                    NetImage(
                        p.src,
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .imageViewerOrigin(keys[index], PictureShape)
                            .clip(PictureShape)
                            .clickable { onOpen(index) },
                        shape = PictureShape,
                        thumbWidth = 240.dp,
                    )
                }
                repeat(cols - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
