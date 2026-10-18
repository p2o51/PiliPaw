package com.gpo.pilipala.feature.video.portrait

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import com.gpo.pilipala.feature.player.PipSignals
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.player.BiliPlayerState
import com.gpo.pilipala.feature.player.GestureFeedback
import com.gpo.pilipala.feature.player.HoldSpeedPill
import com.gpo.pilipala.feature.player.HoldSpeedStep
import com.gpo.pilipala.feature.player.Press
import com.gpo.pilipala.feature.player.awaitLeave
import com.gpo.pilipala.feature.player.consumeUntilRelease
import com.gpo.pilipala.feature.player.holdSpeed
import com.gpo.pilipala.feature.player.swallowRelease
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayerPhase
import com.gpo.pilipala.feature.video.VideoDetail
import com.gpo.pilipala.feature.video.VideoDetailViewModel
import com.gpo.pilipala.feature.video.rememberRichText
import com.gpo.pilipala.feature.video.shareText
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/** The page-level actions a page forwards to (sheets live on the video page). */
@Immutable
internal class PortraitCallbacks(
    val gate: (() -> Unit) -> Unit,
    val onCoin: () -> Unit,
    val onFav: () -> Unit,
    val onTriple: () -> Unit,
    val onFollow: () -> Unit,
    val onComments: () -> Unit,
    val onScrubbingChange: (Boolean) -> Unit,
    /** 0 → 1 as the layout first appears: the overlay springs in over the picture. */
    val reveal: () -> Float,
)

/**
 * One video of the immersive pager. Compact: the picture fills the page (crop-fill or centered
 * on the blurred cover) with the info bottom-left and the action column at the right edge.
 * Medium / Expanded ([column] set): the picture sits in a phone-like column centered on the
 * blurred cover, the action column beside it.
 *
 * Only the [host] page (the settled one) carries the player, the seek bar and the gestures;
 * the others show the cover.
 */
@Composable
internal fun PortraitPage(
    vm: VideoDetailViewModel,
    item: VideoItem,
    aspect: Float,
    host: Boolean,
    column: DpSize?,
    statusTop: Dp,
    navBottom: Dp,
    playerState: BiliPlayerState,
    ctrl: PiliPlayerController?,
    player: @Composable (Modifier) -> Unit,
    callbacks: PortraitCallbacks,
) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val detail = vm.detail?.takeIf { it.bvid == item.bvid }
    val a = vm.actions
    // The relation state belongs to this video only once its detail has bound it.
    val acts = detail != null && a.aid == detail.aid
    val gate = callbacks.gate

    var expanded by rememberSaveable { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    val bursts = remember { mutableStateListOf<LikeBurstSpec>() }
    LaunchedEffect(host) {
        if (!host) {
            expanded = false
            bursts.clear()
        }
    }
    BackHandler(enabled = host && expanded) { expanded = false }
    // On its way into PiP: the text, actions, scrim and seek bar leave at once so only the
    // picture flies into the floating window.
    val pipEntering = host && PipSignals.entering
    val overlayAlpha by animateFloatAsState(
        if (scrubbing || pipEntering) 0f else 1f,
        if (pipEntering) snap() else YoinMotion.defaultEffectsSpec(),
        label = "overlayAlpha",
    )

    val ownerMid = detail?.ownerMid ?: item.ownerMid
    val ownerName = detail?.ownerName?.ifEmpty { null } ?: item.ownerName
    val face = detail?.ownerFace?.ifEmpty { null } ?: item.ownerFace
    val title = detail?.title?.ifEmpty { null } ?: item.title
    val up = vm.up
    val relationKnown = up.mid == ownerMid && ownerMid > 0
    // 名声模式: popularity counts give way to the action word (the 评论 count is not popularity).
    val fame = FameMode.on
    fun label(n: Long, fallback: String, popular: Boolean = true) = if (n > 0 && !(popular && fame)) Fmt.count(n) else fallback
    val actionState = ActionState(
        face = face,
        showFollow = relationKnown && ownerMid != BiliClient.mid,
        following = relationKnown && up.following,
        mutual = relationKnown && up.mutual,
        liked = acts && a.liked,
        likeLabel = label(if (acts) a.likeCount else item.likes, "点赞"),
        coined = acts && a.coined > 0,
        coinLabel = label(if (acts) a.coinCount else 0, "投币"),
        replyLabel = label(detail?.reply ?: 0, "评论", popular = false),
        favored = acts && a.favored,
        favLabel = label(if (acts) a.favCount else 0, "收藏"),
        shareLabel = label(if (acts) a.shareCount else 0, "分享"),
        // Only the page whose video the actions are bound to celebrates (and buzzes).
        tripleCelebration = if (acts && host) a.tripleCelebration else -1,
    )
    val openOwner = { if (ownerMid > 0) nav.push(Route.Member(ownerMid, face = face, name = ownerName)) }
    val seek: (Long) -> Unit = { s -> playerState.seekTo(s * 1000) }

    val actions: @Composable (Modifier) -> Unit = { m ->
        ActionColumn(
            state = actionState,
            onAvatar = openOwner,
            onFollow = callbacks.onFollow,
            onLike = {
                if (acts) {
                    gate {
                        haptic.performHapticFeedback(if (a.liked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        a.toggleLike()
                    }
                }
            },
            onTriple = { if (acts) callbacks.onTriple() },
            onCoin = { if (acts) callbacks.onCoin() },
            onComments = callbacks.onComments,
            onFav = { if (acts) callbacks.onFav() },
            onQuickFav = { if (acts) gate { a.quickFav() } },
            onShare = { shareText(context, title, "https://www.bilibili.com/video/${item.bvid}") },
            modifier = m,
        )
    }

    // Tap: collapse the text, else play / pause. Double tap: like with a burst where the finger is
    // (never un-likes). Hold still on a playing video: speed up while held, slide up / down to
    // set the speed (HoldSpeed.kt), with the speed pill near the top.
    //
    // Arbitration, two detectors on one node. The tap detector (outer) only does tap and double
    // tap. The press detector (inner, so it sees each event first) consumes no move before the
    // long-press timeout, so the pager keeps every swipe. Whichever comes first decides:
    // - the finger lifts: a tap, passed through untouched;
    // - the pager takes a move (checked on the Final pass): its swipe, left alone to the end;
    // - the finger travels past the touch slop and the pager does not take it (a sideways
    //   swipe): nothing, and only its release is consumed so it does not end as a tap;
    // - the finger stays down and in place for the timeout: a hold. On a playing video it owns
    //   the press until release (every event consumed, so the pager never scrolls, also locked
    //   by userScrollEnabled while boosting, and the tap is cancelled); paused, the press is
    //   swallowed so it does not end as a tap (as the old long press did).
    val latestActs by rememberUpdatedState(acts)
    val hold = remember { GestureFeedback() }
    // Not over the player's error panel: its retry has to stay reachable.
    val gesturesOn = host && ctrl?.phase !is PlayerPhase.Error
    val gestures = if (!gesturesOn) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = {
                if (expanded) {
                    expanded = false
                } else {
                    val playing = ctrl?.playWhenReady ?: playerState.isPlaying
                    haptic.performHapticFeedback(if (playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    if (ctrl != null) ctrl.togglePlay() else if (playing) playerState.pause() else playerState.play()
                }
            },
            onDoubleTap = { at ->
                if (latestActs) {
                    gate {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        bursts += LikeBurstSpec(System.nanoTime(), at, Random.nextInt(-18, 19).toFloat())
                        if (!a.liked) a.toggleLike()
                    }
                }
            },
        )
    }.pointerInput(ctrl) {
        val slop = viewConfiguration.touchSlop
        val holdTimeout = viewConfiguration.longPressTimeoutMillis
        val step = HoldSpeedStep.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            when (withTimeoutOrNull(holdTimeout) { awaitLeave(down.id, slop) }) {
                // Held still: a playing video speeds up until the finger lifts.
                null -> if (ctrl != null && ctrl.isPlaying) {
                    holdSpeed(down.id, step, ctrl, hold, haptic)
                } else {
                    consumeUntilRelease(down.id)
                }
                // A swipe: the pager's if it takes it, otherwise nothing (and not a tap).
                is Press.Moved -> swallowRelease(down.id)
                // A tap, or the pager already has it.
                else -> Unit
            }
        }
    }

    /** Picture + gestures + text + seek bar in a [w] × [h] frame; [inside] puts the actions in it too. */
    val stage: @Composable (Modifier, Dp, Dp, Dp, Boolean) -> Unit = { m, w, h, bottom, inside ->
        Box(m.clipToBounds()) {
            val pic = pictureSize(w, h, aspect)
            Box(Modifier.align(Alignment.Center).requiredSize(pic)) {
                if (host) {
                    player(Modifier.fillMaxSize())
                } else {
                    NetImage(
                        url = item.cover,
                        modifier = Modifier.fillMaxSize(),
                        shape = RectangleShape,
                        thumbWidth = 480.dp,
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Box(Modifier.matchParentSize().then(gestures))
            val scrimAlpha by animateFloatAsState(
                when {
                    pipEntering -> 0f
                    expanded -> 0.8f
                    else -> 0.5f
                },
                if (pipEntering) snap() else YoinMotion.defaultEffectsSpec(),
                label = "scrim",
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(h * 0.42f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = scrimAlpha)))),
            )
            Box(Modifier.matchParentSize().revealed(callbacks.reveal) { overlayAlpha }) {
                InfoBlock(
                    ownerName = ownerName,
                    onOwner = openOwner,
                    title = title,
                    detail = detail,
                    tags = if (detail != null) vm.tags else emptyList(),
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    maxExpandedHeight = h * 0.45f,
                    onSeek = seek,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = 16.dp,
                            end = if (inside) ActionColumnWidth + 12.dp else 16.dp,
                            bottom = bottom + SeekZone + 6.dp,
                        ),
                )
                if (inside) {
                    actions(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = bottom + SeekZone + 16.dp))
                }
            }
            if (host) {
                CentreStatus(ctrl, Modifier.align(Alignment.Center))
                if (ctrl != null) {
                    HoldSpeedPill(
                        ctrl = ctrl,
                        feedback = hold,
                        big = false,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = if (inside) statusTop + 60.dp else 48.dp),
                    )
                }
                Box(Modifier.matchParentSize()) {
                    bursts.forEach { b ->
                        key(b.id) { LikeBurst(b) { bursts.remove(b) } }
                    }
                }
                ImmersiveSeekBar(
                    ctrl = ctrl,
                    state = playerState,
                    onScrubbingChange = {
                        scrubbing = it
                        callbacks.onScrubbingChange(it)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = bottom)
                        .graphicsLayer { alpha = if (pipEntering) 0f else 1f },
                    horizontalPadding = if (inside) 0.dp else 12.dp,
                )
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        CoverBackdrop(item.cover, Modifier.matchParentSize())
        if (column == null) {
            stage(Modifier.fillMaxSize(), maxWidth, maxHeight, navBottom, true)
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = statusTop + ColumnVerticalMargin, bottom = navBottom + ColumnVerticalMargin),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // Balances the action column so the picture stays centered.
                    Spacer(Modifier.width(ActionColumnWidth + ColumnGap))
                    stage(
                        Modifier
                            .size(column)
                            .background(Color.Black.copy(alpha = 0.3f), YoinContainerShapes.Panel),
                        column.width,
                        column.height,
                        0.dp,
                        false,
                    )
                    Spacer(Modifier.width(ColumnGap))
                    actions(Modifier.padding(bottom = 24.dp).revealed(callbacks.reveal) { overlayAlpha })
                }
            }
        }
    }
}

/** Overlay alpha ([alpha] times the layout's reveal) with a short rise while it reveals. */
internal fun Modifier.revealed(reveal: () -> Float, alpha: () -> Float = { 1f }): Modifier = graphicsLayer {
    val r = reveal()
    this.alpha = alpha() * r
    translationY = (1f - r) * 24.dp.toPx()
}

/** The cover, blurred and dimmed, behind a picture that does not fill its frame. */
@Composable
internal fun CoverBackdrop(cover: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black)) {
        if (cover.isNotBlank()) {
            NetImage(
                url = cover,
                // A tiny thumbnail scaled up is soft on every API level; 31+ blurs it properly.
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 1.15f
                        scaleY = 1.15f
                    }
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp) else Modifier),
                shape = RectangleShape,
                thumbWidth = 96.dp,
                contentScale = ContentScale.Crop,
            )
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)))
    }
}

/** UP, title (two lines; a tap opens description, numbers and tags on a spring). */
@Composable
private fun InfoBlock(
    ownerName: String,
    onOwner: () -> Unit,
    title: String,
    detail: VideoDetail?,
    tags: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    maxExpandedHeight: Dp,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val noRipple = remember { MutableInteractionSource() }
    Column(modifier) {
        if (ownerName.isNotEmpty()) {
            Text(
                "@$ownerName",
                style = MaterialTheme.typography.titleMedium.onPicture(),
                fontWeight = FontWeight.SemiBold,
                color = OnPicture,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(interactionSource = noRipple, indication = null, onClick = onOwner),
            )
            Spacer(Modifier.height(6.dp))
        }
        Column(
            Modifier
                .animateContentSize(YoinMotion.defaultSpatialSpec())
                .heightIn(max = if (expanded) maxExpandedHeight else Dp.Unspecified)
                .verticalScroll(rememberScrollState(), enabled = expanded)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.onPicture(),
                color = OnPicture,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(
                visible = expanded && detail != null,
                enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
                exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.fastSpatialSpec()),
            ) {
                if (detail != null) ExpandedInfo(detail, tags, onSeek)
            }
        }
    }
}

@Composable
private fun ExpandedInfo(d: VideoDetail, tags: List<String>, onSeek: (Long) -> Unit) {
    val nav = LocalNavigator.current
    Column(Modifier.padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCount(PiliSymbols.Views, Fmt.count(d.view))
            IconCount(PiliSymbols.Danmaku, Fmt.count(d.danmaku))
            val time = Fmt.relativeTime(d.pubdate)
            if (time.isNotEmpty()) {
                Text(time, style = MaterialTheme.typography.labelMedium.onPicture(), color = OnPicture.copy(alpha = 0.72f))
            }
        }
        if (d.desc.isNotBlank() || d.descNodes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val rich = rememberRichText(d.descNodes, d.desc, MaterialTheme.colorScheme.primary, onSeek)
            Text(rich, style = MaterialTheme.typography.bodyMedium.onPicture(), color = OnPicture.copy(alpha = 0.88f))
        }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t ->
                    Text(
                        "#$t",
                        style = MaterialTheme.typography.labelLarge,
                        color = OnPicture,
                        modifier = Modifier
                            .clip(ContinuousRoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable { nav.push(Route.SearchResult(t)) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** 播放 / 弹幕 under the expanded title: a line under 名声模式, never tapped open (the block folds on tap). */
@Composable
private fun IconCount(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val tint = OnPicture.copy(alpha = 0.72f)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        FameCount(text, style = MaterialTheme.typography.labelMedium.onPicture(), color = tint, lineColor = tint, revealable = false)
    }
}

/** Paused glyph (a tap paused it) or a loader while the stream refills. */
@Composable
private fun CentreStatus(ctrl: PiliPlayerController?, modifier: Modifier = Modifier) {
    if (ctrl == null) return
    val started = ctrl.firstFrameRendered && ctrl.phase is PlayerPhase.Ready
    val paused = started && !ctrl.playWhenReady
    val buffering = started && ctrl.playWhenReady && ctrl.isBuffering
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = paused,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 1.5f),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.8f),
        ) {
            ShadowedIcon(YoinSymbols.PlayFilled, contentDescription = "播放", tint = OnPicture.copy(alpha = 0.82f), size = 76.dp)
        }
        AnimatedVisibility(
            visible = buffering,
            enter = fadeIn(YoinMotion.slowEffectsSpec()),
            exit = fadeOut(YoinMotion.fastEffectsSpec()),
        ) {
            LoadingIndicator(Modifier.size(52.dp), color = OnPicture)
        }
    }
}

@Immutable
internal data class LikeBurstSpec(val id: Long, val at: Offset, val tilt: Float)

private val BurstSize = 96.dp

/** The double-tap like: the glyph pops at the finger on a bouncy spring, then rises and fades. */
@Composable
private fun LikeBurst(spec: LikeBurstSpec, onDone: () -> Unit) {
    val scale = remember { Animatable(0.3f) }
    val lift = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val pop = YoinMotion.fastSpatialSpec<Float>()
    val drift = YoinMotion.slowSpatialSpec<Float>()
    val fade = YoinMotion.slowEffectsSpec<Float>()
    val latestDone by rememberUpdatedState(onDone)
    LaunchedEffect(spec.id) {
        scale.animateTo(1f, pop)
        coroutineScope {
            launch { lift.animateTo(1f, drift) }
            launch { scale.animateTo(1.3f, drift) }
            alpha.animateTo(0f, fade)
        }
        latestDone()
    }
    val density = LocalDensity.current
    val half = with(density) { BurstSize.toPx() } / 2f
    val rise = with(density) { 110.dp.toPx() }
    Icon(
        PiliSymbols.ThumbUpFilled,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(BurstSize)
            .graphicsLayer {
                translationX = spec.at.x - half
                translationY = spec.at.y - half - lift.value * rise
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = spec.tilt
                this.alpha = alpha.value
            },
    )
}
