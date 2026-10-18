package com.gpo.pilipala.feature.video.portrait

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import com.gpo.pilipala.feature.video.TripleBurst
import com.gpo.pilipala.feature.video.drawTripleBloom
import com.gpo.pilipala.feature.video.drawTripleSparks
import com.gpo.pilipala.feature.video.rememberTripleBurst
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.rememberRichHaptics
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

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
    /** [com.gpo.pilipala.feature.video.ArchiveActions.tripleCelebration] for this page's video; -1 while it isn't bound. */
    val tripleCelebration: Int = -1,
    /** Following each other: the follow badge's check reads 互相关注. */
    val mutual: Boolean = false,
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
    // 三连 landed: 点赞, 投币 and 收藏 bloom one after another (the same burst as the detail page's row).
    val burst = rememberTripleBurst(state.tripleCelebration)
    // Holding 点赞 charges 三连 down the column (the landscape row's gesture, TripleCharge in
    // ActionGroup.kt): an accent cookie blooms behind 点赞, 投币 and 收藏 in turn, and only a
    // full charge opens the 三连 sheet (which still asks before anything is sent).
    val charge = remember { Animatable(0f) }
    // Overlapping thirds: each slot starts filling while the one before is half full.
    fun fill(k: Int): () -> Float = { (((charge.value - k * 0.25f) / 0.5f).coerceIn(0f, 1f)).coerceAtLeast(burst.bloomShown(k)) }
    Column(
        modifier.width(ActionColumnWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UpAvatar(state.face, state.showFollow, state.following, state.mutual, onAvatar, onFollow)
        Spacer(Modifier.height(10.dp))
        ActionButton(
            icon = if (state.liked) PiliSymbols.ThumbUpFilled else PiliSymbols.ThumbUp,
            description = "点赞",
            label = state.likeLabel,
            checked = state.liked,
            onClick = onLike,
            burst = burst,
            slot = 0,
            charge = charge,
            onCharged = onTriple,
            fill = fill(0),
        )
        ActionButton(
            icon = if (state.coined) PiliSymbols.CoinFilled else PiliSymbols.Coin,
            description = "投币",
            label = state.coinLabel,
            checked = state.coined,
            onClick = onCoin,
            burst = burst,
            slot = 1,
            fill = fill(1),
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
            burst = burst,
            slot = 2,
            fill = fill(2),
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

/** How long the finger holds, past the long-press timeout, to fill the 三连 charge (as on the landscape row). */
private const val ChargeMs = 620f

/**
 * One glyph + count. Presses squash it on a spring; turning on (after the user's own tap)
 * pops it, and the colour eases to the accent.
 *
 * [charge] (点赞 only): holding past the long-press timeout fills it instead of firing a
 * long click, and [onCharged] runs once it is full; letting go early drains it and does
 * nothing, and a quick tap is still a tap. [fill]: this slot's share of the charge,
 * drawn as an accent cookie behind the glyph with the glyph turning to its on-colour.
 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    description: String,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    burst: TripleBurst? = null,
    slot: Int = -1,
    charge: Animatable<Float, AnimationVector1D>? = null,
    onCharged: (() -> Unit)? = null,
    fill: (() -> Float)? = null,
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
    // The hold that charged (or tried to) is not also a tap.
    var consumedByHold by remember { mutableStateOf(false) }
    val rich = rememberRichHaptics()
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val latestOnCharged by rememberUpdatedState(onCharged)
    val drain = YoinMotion.fastSpatialSpec<Float>()
    val settle = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Expressive)
    DisposableEffect(charge) { onDispose { if (charge != null) rich.cancel() } }
    LaunchedEffect(interaction, charge) {
        if (charge == null) return@LaunchedEffect
        // Explicit press bookkeeping: the timer only lives while the finger is down
        // (Release / Cancel kill it), so a slow tap can never start the charge late.
        var timer: Job? = null
        var charging = false
        var full = false
        interaction.interactions.collect { i ->
            when (i) {
                is PressInteraction.Press -> {
                    timer?.cancel()
                    consumedByHold = false
                    charging = false
                    full = false
                    timer = launch {
                        delay(longPressMs)
                        consumedByHold = true
                        // The fill follows the held time, slow at first and gathering pace (a
                        // gesture-driven value; springs take over once the finger lets go or it lands).
                        charging = true
                        rich.charge()
                        val start = withFrameNanos { it }
                        var t = 0f
                        while (t < 1f) {
                            t = ((withFrameNanos { it } - start) / 1_000_000f / ChargeMs).coerceAtMost(1f)
                            charge.snapTo(t.pow(1.6f))
                        }
                        full = true
                        charging = false
                        rich.chargeFull()
                        latestOnCharged?.invoke()
                        // Drained in the collector's scope so the release can't cut the spring short.
                        this@LaunchedEffect.launch {
                            delay(90)
                            charge.animateTo(0f, settle)
                        }
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    timer?.cancel()
                    timer = null
                    // A cancelled press (the pager took the drag, the finger slid off) never
                    // clicks, so nothing would clear the swallow and the next quick tap would
                    // be lost: only the click right after a released hold is swallowed.
                    if (i is PressInteraction.Cancel) consumedByHold = false
                    if (charging && !full) {
                        charging = false
                        rich.cancel()
                        launch { charge.animateTo(0f, drain) }
                    }
                }
            }
        }
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
                onLongClick = if (charge != null) null else onLongClick,
                onClick = {
                    if (consumedByHold) {
                        consumedByHold = false
                    } else {
                        armed = true
                        onClick()
                    }
                },
            )
            .then(
                if (charge != null && onCharged != null) {
                    // TalkBack can't hold a charge: the long-press action opens the 三连 sheet straight away.
                    Modifier.semantics {
                        onLongClick(label = "三连") {
                            latestOnCharged?.invoke()
                            true
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val fastEffects = YoinMotion.fastEffectsSpec<Float>()
        val fastSpatial = YoinMotion.fastSpatialSpec<Float>()
        val accent = MaterialTheme.colorScheme.tertiary
        val onAccent = MaterialTheme.colorScheme.onTertiary
        Box(contentAlignment = Alignment.Center) {
            if (fill != null) ChargeCookie(fill)
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    (fadeIn(fastEffects) + scaleIn(fastSpatial, initialScale = 0.6f)) togetherWith fadeOut(fastEffects)
                },
                label = "actionIcon",
                modifier = if (burst != null && slot >= 0) {
                    Modifier.drawWithContent {
                        // The 三连 bloom behind the glyph, its sparks over it.
                        drawTripleBloom(burst, slot, center, radius = size.minDimension * 0.62f, color = accent)
                        drawContent()
                        drawTripleSparks(burst, slot, center, from = 16.dp.toPx(), to = 40.dp.toPx(), dot = 2.5.dp.toPx(), color = accent)
                    }
                } else {
                    Modifier
                },
            ) { glyph ->
                Box(
                    Modifier.graphicsLayer {
                        val kick = if (burst != null && slot >= 0) burst.pop[slot].value else 1f
                        // A charging glyph swells a little with its share.
                        val swell = if (fill != null) 1f + 0.14f * fill().coerceIn(0f, 1f) else 1f
                        val s = press * pop.value * kick * swell
                        scaleX = s
                        scaleY = s
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    ShadowedIcon(glyph, contentDescription = description, tint = tint, size = 34.dp)
                    if (fill != null) {
                        // The glyph turns to the accent's on-colour exactly as the cookie covers it.
                        Icon(
                            glyph,
                            contentDescription = null,
                            tint = onAccent,
                            modifier = Modifier
                                .size(34.dp)
                                .graphicsLayer { alpha = fill().coerceIn(0f, 1f) },
                        )
                    }
                }
            }
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

/** The accent cookie behind a glyph of the 三连 run: grows and turns with [fill]. */
@Composable
private fun ChargeCookie(fill: () -> Float) {
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    Box(
        Modifier
            .requiredSize(50.dp)
            .graphicsLayer {
                val f = fill().coerceIn(0f, 1.2f)
                scaleX = f
                scaleY = f
                rotationZ = f * 40f
                alpha = if (f <= 0.001f) 0f else 1f
            }
            .clip(cookie)
            .background(MaterialTheme.colorScheme.tertiary),
    )
}

private enum class FollowBadge { Add, Done }

/** UP avatar (opens the space) with a small cookie badge that follows; it shows a check, then leaves. */
@Composable
private fun UpAvatar(face: String, showFollow: Boolean, following: Boolean, mutual: Boolean, onAvatar: () -> Unit, onFollow: () -> Unit) {
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
                            contentDescription = when {
                                b != FollowBadge.Done -> "关注"
                                mutual -> "互相关注"
                                else -> "已关注"
                            },
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}
