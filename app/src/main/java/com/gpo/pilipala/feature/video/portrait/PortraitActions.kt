package com.gpo.pilipala.feature.video.portrait

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.delay

/** Glyphs and text over the picture: plain white with a soft shadow, never a stroke. */
internal val OnPicture = Color.White

private val PictureShadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 2f), blurRadius = 10f)

/** A typography style for text drawn straight on the picture. */
internal fun TextStyle.onPicture(): TextStyle = copy(shadow = PictureShadow)

internal val ActionColumnWidth = 64.dp

/** An icon with a soft dark halo so it reads over bright frames (the blur needs API 31; the scrims carry older devices). */
@Composable
internal fun ShadowedIcon(icon: ImageVector, contentDescription: String?, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.38f),
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = 1.dp)
                    .blur(6.dp, BlurredEdgeTreatment.Unbounded),
            )
        }
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.matchParentSize())
    }
}

/** What the right-hand column shows for one video. */
@Immutable
internal data class ActionState(
    val face: String,
    val showFollow: Boolean,
    val following: Boolean,
    val liked: Boolean,
    val likeLabel: String,
    val coined: Boolean,
    val coinLabel: String,
    val replyLabel: String,
    val favored: Boolean,
    val favLabel: String,
    val shareLabel: String,
)

/**
 * The Douyin-style action column: UP avatar with a follow badge, then 点赞 / 投币 / 评论 /
 * 收藏 / 分享 as white glyphs with their counts.
 */
@Composable
internal fun ActionColumn(
    state: ActionState,
    onAvatar: () -> Unit,
    onFollow: () -> Unit,
    onLike: () -> Unit,
    onTriple: () -> Unit,
    onCoin: () -> Unit,
    onComments: () -> Unit,
    onFav: () -> Unit,
    onQuickFav: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(ActionColumnWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UpAvatar(state.face, state.showFollow, state.following, onAvatar, onFollow)
        Spacer(Modifier.height(10.dp))
        ActionButton(
            icon = if (state.liked) PiliSymbols.ThumbUpFilled else PiliSymbols.ThumbUp,
            description = "点赞",
            label = state.likeLabel,
            checked = state.liked,
            onClick = onLike,
            onLongClick = onTriple,
        )
        ActionButton(
            icon = if (state.coined) PiliSymbols.CoinFilled else PiliSymbols.Coin,
            description = "投币",
            label = state.coinLabel,
            checked = state.coined,
            onClick = onCoin,
        )
        ActionButton(
            icon = PiliSymbols.Comment,
            description = "评论",
            label = state.replyLabel,
            checked = false,
            onClick = onComments,
        )
        ActionButton(
            icon = if (state.favored) YoinSymbols.StarFilled else YoinSymbols.Star,
            description = "收藏",
            label = state.favLabel,
            checked = state.favored,
            onClick = onFav,
            onLongClick = onQuickFav,
        )
        ActionButton(
            icon = YoinSymbols.Share,
            description = "分享",
            label = state.shareLabel,
            checked = false,
            onClick = onShare,
        )
    }
}

/**
 * One glyph + count. Presses squash it on a spring; turning on (after the user's own tap)
 * pops it, and the colour eases to the accent.
 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    description: String,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.82f else 1f, YoinMotion.fastSpatialSpec(), label = "actionPress")
    val pop = remember { Animatable(1f) }
    val popSpec = YoinMotion.fastSpatialSpec<Float>()
    // Only the user's own tap pops it (not the relation arriving from the network).
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(checked) {
        if (armed && checked) {
            pop.snapTo(1.35f)
            pop.animateTo(1f, popSpec)
        }
        armed = false
    }
    val tint by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else OnPicture,
        YoinMotion.defaultEffectsSpec(),
        label = "actionTint",
    )
    Column(
        Modifier
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = description,
                onLongClick = onLongClick,
                onClick = {
                    armed = true
                    onClick()
                },
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val fastEffects = YoinMotion.fastEffectsSpec<Float>()
        val fastSpatial = YoinMotion.fastSpatialSpec<Float>()
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (fadeIn(fastEffects) + scaleIn(fastSpatial, initialScale = 0.6f)) togetherWith fadeOut(fastEffects)
            },
            label = "actionIcon",
        ) { glyph ->
            ShadowedIcon(
                glyph,
                contentDescription = description,
                tint = tint,
                size = 34.dp,
                modifier = Modifier.graphicsLayer {
                    val s = press * pop.value
                    scaleX = s
                    scaleY = s
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.onPicture(),
            color = OnPicture,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

private enum class FollowBadge { Add, Done }

/** UP avatar (opens the space) with a small cookie badge that follows; it shows a check, then leaves. */
@Composable
private fun UpAvatar(face: String, showFollow: Boolean, following: Boolean, onAvatar: () -> Unit, onFollow: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var tapped by remember { mutableStateOf(false) }
    var justFollowed by remember { mutableStateOf(false) }
    LaunchedEffect(following) {
        if (following && tapped) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            justFollowed = true
            delay(900)
            justFollowed = false
        }
        tapped = false
    }
    val badge = when {
        showFollow && !following -> FollowBadge.Add
        justFollowed -> FollowBadge.Done
        else -> null
    }
    // The last badge stays drawn while it scales away.
    val shown = remember { arrayOf(FollowBadge.Add) }
    if (badge != null) shown[0] = badge
    Box(Modifier.padding(bottom = 10.dp), contentAlignment = Alignment.BottomCenter) {
        Avatar(url = face, size = 48.dp, onClick = onAvatar)
        AnimatedVisibility(
            visible = badge != null,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.4f),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.4f),
            modifier = Modifier.offset(y = 19.dp),
        ) {
            // A 40dp touch target around the 22dp badge.
            Box(
                Modifier
                    .size(40.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = badge == FollowBadge.Add,
                        role = Role.Button,
                        onClickLabel = "关注",
                    ) {
                        tapped = true
                        onFollow()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(MaterialShapes.Cookie6Sided.toShape())
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    val fastEffects = YoinMotion.fastEffectsSpec<Float>()
                    val fastSpatial = YoinMotion.fastSpatialSpec<Float>()
                    AnimatedContent(
                        targetState = shown[0],
                        transitionSpec = {
                            (fadeIn(fastEffects) + scaleIn(fastSpatial, initialScale = 0.5f)) togetherWith fadeOut(fastEffects)
                        },
                        label = "followBadge",
                    ) { b ->
                        Icon(
                            if (b == FollowBadge.Done) YoinSymbols.Check else YoinSymbols.Add,
                            contentDescription = if (b == FollowBadge.Done) "已关注" else "关注",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}
