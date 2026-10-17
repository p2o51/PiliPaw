package com.gpo.pilipala.feature.reply

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.CoverBadge
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

/** Everything a reply row can trigger; hosts (list / thread page) wire it to their ViewModel. */
@Stable
internal class ReplyActions(
    val likeState: (Reply) -> LikeState,
    val onLike: (Reply) -> Unit,
    val onReply: (Reply) -> Unit,
    /** Opens the thread page; null on the thread page itself. */
    val onOpenThread: ((Reply) -> Unit)?,
    val onLongPress: (Reply) -> Unit,
    val onImage: (urls: List<String>, index: Int) -> Unit,
    val onTimestampClick: ((Long) -> Unit)?,
)

private val PictureShape = ContinuousRoundedCornerShape(10.dp)
private val PreviewShape = ContinuousRoundedCornerShape(14.dp)

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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    actions.onLongPress(reply)
                },
            )
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
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (reply.member.vip) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (reply.member.vip) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LevelTag(reply.member.level)
                if (reply.isUp) Tag("UP", container = scheme.primaryContainer, content = scheme.onPrimaryContainer)
                if (reply.isTop) Tag("置顶", container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
            }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Fmt.relativeTime(reply.ctime), style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                if (reply.location.isNotEmpty()) {
                    Text(reply.location, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                }
                if (reply.invisible) {
                    Text("已隐藏", style = MaterialTheme.typography.labelSmall, color = scheme.error)
                }
            }
            ReplyRichText(
                content = reply.content,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = if (clampLines && reply.content.isPlainText) 6 else Int.MAX_VALUE,
                onTimestampClick = actions.onTimestampClick,
            )
            if (reply.content.pictures.isNotEmpty()) {
                PictureGrid(
                    pictures = reply.content.pictures,
                    modifier = Modifier.padding(top = 8.dp),
                    onOpen = { i -> actions.onImage(reply.content.pictures.map { it.src }, i) },
                )
            }
            ActionRow(reply, actions)
            if (showPreview && (reply.replies.isNotEmpty() || reply.rcount > 0) && actions.onOpenThread != null) {
                SubReplyPreview(reply, actions, Modifier.padding(bottom = 8.dp))
            }
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
            Text("回复", style = MaterialTheme.typography.labelMedium, color = scheme.outline)
        }
        if (reply.upLiked) {
            Text("UP主觉得很赞", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
        }
        if (reply.hot) {
            Tag("热评", container = scheme.errorContainer, content = scheme.onErrorContainer)
        }
        Spacer(Modifier.weight(1f))
        LikeButton(actions.likeState(reply)) {
            actions.onLike(reply)
        }
    }
}

/** Thumb toggle: color + count animate with springs, the glyph pops on like. */
@Composable
internal fun LikeButton(state: LikeState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val color by animateColorAsState(
        if (state.liked) scheme.primary else scheme.outline,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "likeColor",
    )
    val motion = replyMotion()
    val scale = remember { Animatable(1f) }
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(state.liked) {
        if (first.value) {
            first.value = false
            return@LaunchedEffect
        }
        if (state.liked) {
            scale.snapTo(0.6f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        } else {
            scale.snapTo(0.85f)
            scale.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
        }
    }
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
        Icon(
            if (state.liked) PiliSymbols.ThumbUpFilled else PiliSymbols.ThumbUp,
            contentDescription = if (state.liked) "取消点赞" else "点赞",
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            tint = color,
        )
        AnimatedContent(
            targetState = state.count,
            transitionSpec = {
                val up = targetState > initialState
                (
                    androidx.compose.animation.slideInVertically(motion.offset) { if (up) it else -it } +
                        androidx.compose.animation.fadeIn(motion.fade)
                    ) togetherWith (
                    androidx.compose.animation.slideOutVertically(motion.offset) { if (up) -it else it } +
                        androidx.compose.animation.fadeOut(motion.fade)
                    )
            },
            label = "likeCount",
        ) { count ->
            Text(
                if (count > 0) Fmt.count(count) else "",
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun SubReplyPreview(reply: Reply, actions: ReplyActions, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val open = { actions.onOpenThread?.invoke(reply) ?: Unit }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PreviewShape)
            .background(scheme.surfaceContainer)
            .clickable(onClick = open)
            .padding(vertical = 6.dp),
    ) {
        reply.replies.take(3).forEach { sub ->
            ReplyRichText(
                content = sub.content,
                prefix = RichPrefix(sub.member.name, sub.member.mid, sub.member.face, sub.isUp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                onTimestampClick = actions.onTimestampClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = open,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onLongPress(sub)
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        val shownAll = reply.rcount <= reply.replies.size && reply.replies.isNotEmpty()
        if (!shownAll || reply.upReplied) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (reply.upReplied) {
                    Text("UP主等人", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                Text(
                    reply.entryText.ifEmpty { "${reply.rcount} 条回复" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
                Icon(YoinSymbols.ChevronRight, null, Modifier.size(14.dp), tint = scheme.primary)
            }
        }
    }
}

@Composable
internal fun PictureGrid(
    pictures: List<ReplyPicture>,
    modifier: Modifier = Modifier,
    onOpen: (Int) -> Unit,
) {
    if (pictures.size == 1) {
        val p = pictures.first()
        val ratio = if (p.width > 0 && p.height > 0) (p.width.toFloat() / p.height).coerceIn(0.6f, 1.8f) else 1f
        val long = p.width > 0 && p.height > p.width * 2.2f
        Box(
            modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth(0.62f)
                .aspectRatio(ratio)
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
