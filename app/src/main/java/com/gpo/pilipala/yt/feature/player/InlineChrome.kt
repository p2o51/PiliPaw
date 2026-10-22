package com.gpo.pilipala.yt.feature.player

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.player.enterPip
import com.gpo.pilipala.feature.player.pipSupported
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberPlayPauseSymbolPainter
import kotlinx.coroutines.delay

/*
 * The open YouTube player's portrait controls, laid out as PiliPaw's (feature/player
 * PlayerControls: InlineCorners, InlineStrip, CentreButton, ProgressHairline). A tap on the
 * picture brings them: small round buttons in the picture's top corners, the play button in
 * its centre while paused, and the strip under the picture (seek bar; play, next, time;
 * speed and quality pills, 仅听声音, fullscreen), which takes real height. They fold away
 * 3.8 s after the last touch while playing; then a 2dp line along the picture's bottom edge
 * shows the progress. The look only: the code is still the YouTube part's own.
 */

/** Whether the controls are up, and what keeps them up. */
@Stable
internal class YtChrome {
    var visible by mutableStateOf(false)
    var poke by mutableIntStateOf(0)
        private set
    var sheet by mutableStateOf(false)

    fun toggle() {
        visible = !visible
        poke++
    }

    /** A touch on the controls: they stay a while longer. */
    fun touch() {
        poke++
    }
}

/** White over the picture whatever the theme (a scrim colour), as PiliPaw's OnVideo. */
private val OnVideo = Color.White

/** Translucent dark plate behind the small corner buttons over the picture. */
private val CornerPlate = Color.Black.copy(alpha = 0.5f)

/** The fold-away: 3.8 s after the last touch while playing, as PiliPaw's ChromeAutoHide. */
@Composable
internal fun YtChromeAutoHide(chrome: YtChrome, seek: SeekState, session: Player.Session) {
    LaunchedEffect(chrome.visible, Player.isPlaying, chrome.poke, chrome.sheet, seek.scrubbing) {
        if (chrome.visible && Player.isPlaying && !chrome.sheet && !seek.scrubbing) {
            delay(3800)
            chrome.visible = false
        }
    }
    // A failure: bring the controls back.
    LaunchedEffect(session.error) { if (session.error != null) chrome.visible = true }
}

/** How far the controls are shown (0 hidden, 1 shown), on the effects spring. */
@Composable
internal fun rememberChromeReveal(chrome: YtChrome): () -> Float {
    val reveal by animateFloatAsState(if (chrome.visible) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "ytChrome")
    return { reveal }
}

/**
 * Over the picture: back (collapses the player) in the top start corner; PiP and the playback
 * settings on one plate in the top end corner; the centre play button while paused (or the
 * contained loading indicator while it buffers); the progress hairline while hidden.
 */
@Composable
internal fun BoxScope.YtPictureChrome(chrome: YtChrome, seek: SeekState, reveal: () -> Float, onCollapse: () -> Unit) {
    val activity = LocalActivity.current as? ComponentActivity
    val shown = reveal() > 0.01f
    if (shown) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(10.dp)
                .graphicsLayer {
                    val p = reveal().coerceIn(0f, 1f)
                    alpha = p
                    translationY = (1f - p) * -8.dp.toPx()
                },
        ) {
            if (com.gpo.pilipala.feature.sources.LocalBackHasMore.current) {
                // Videos to go back to (继续播放): the arrow and three dots, one pill; it opens the back menu.
                androidx.compose.material3.Surface(
                    onClick = onCollapse,
                    shape = YoinShapeTokens.Full,
                    color = CornerPlate,
                    contentColor = OnVideo,
                    modifier = Modifier.height(40.dp),
                ) {
                    Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                        com.gpo.pilipala.feature.sources.TrailBackGlyph(more = true, tint = OnVideo)
                    }
                }
            } else {
                CornerButton(YoinSymbols.Back, "收起", onCollapse)
            }
            Row(Modifier.align(Alignment.TopEnd).background(CornerPlate, YoinShapeTokens.Full), verticalAlignment = Alignment.CenterVertically) {
                if (activity != null && pipSupported(activity)) {
                    GroupButton(PiliSymbols.Pip, "画中画") { enterPip(activity, Player.videoAspect, title = Player.session?.title.orEmpty()) }
                }
                GroupButton(YoinSymbols.MoreVertical, "播放设置") {
                    chrome.sheet = true
                    chrome.touch()
                }
            }
        }
    }
    YtCentreButton(shown = chrome.visible, Modifier.align(Alignment.Center)) {
        Player.toggle()
        chrome.touch()
    }
    YtProgressHairline(seek, alpha = { 1f - reveal() }, Modifier.align(Alignment.BottomStart))
}

@Composable
private fun CornerButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(containerColor = CornerPlate, contentColor = OnVideo),
        modifier = Modifier.size(40.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun GroupButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(contentColor = OnVideo),
        modifier = Modifier.size(40.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

/** PiliPaw's centre button: buffering, or play while paused and shown; nothing otherwise. */
@Composable
private fun YtCentreButton(shown: Boolean, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    // Before the first frame the picture shows its own loading (the cover and an indicator).
    val buffering = Player.isBuffering && Player.durationMs > 0
    val state = when {
        buffering -> 1
        shown && !Player.isPlaying && Player.durationMs > 0 -> 2
        else -> 0
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            ((fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f)) togetherWith
                (fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f)))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        modifier = modifier,
        label = "ytCentre",
    ) { s ->
        when (s) {
            1 -> ContainedLoadingIndicator(Modifier.size(56.dp))
            2 -> FilledIconButton(
                onClick = onPlay,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
            ) {
                Icon(rememberPlayPauseSymbolPainter(false), contentDescription = "播放", modifier = Modifier.size(28.dp))
            }
            else -> Spacer(Modifier.size(1.dp))
        }
    }
}

/** The 2dp progress line along the picture's bottom edge while the controls are hidden. */
@Composable
private fun YtProgressHairline(seek: SeekState, alpha: () -> Float, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .graphicsLayer { this.alpha = alpha().coerceIn(0f, 1f) },
    ) {
        if (seek.duration <= 0L) return@Canvas
        drawRect(Color.Black.copy(alpha = 0.28f))
        drawRect(VideoTrackInk.rest)
        drawRect(VideoTrackInk.buffered.copy(alpha = 0.22f), size = Size(size.width * seek.bufferedFraction, size.height))
        drawRect(VideoTrackInk.played, size = Size(size.width * seek.fraction, size.height))
    }
}

/**
 * The strip under the picture, on the page in the page's ink (PiliPaw's InlineStrip): the seek
 * bar along its top; play (the morphing glyph), next, the two times; then the speed and
 * quality pills, 仅听声音 and fullscreen.
 */
@Composable
internal fun YtInlineStrip(seek: SeekState, chrome: YtChrome, hasNext: Boolean, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        PawSeekBar(seek, pageTrackInk(), onPoke = chrome::touch, modifier = Modifier.padding(horizontal = 14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val roomy = maxWidth >= 400.dp
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(start = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val playing = Player.isPlaying
                StripButton(rememberPlayPauseSymbolPainter(playing), if (playing) "暂停" else "播放") {
                    haptics.performHapticFeedback(if (playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    Player.toggle()
                    chrome.touch()
                }
                if (hasNext) {
                    StripButton(YoinSymbols.SkipNextFilled, "下一个") {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        Player.playNext()
                    }
                }
                val style = MaterialTheme.typography.labelMedium.withTabularFigures()
                Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.duration(seek.shownMs / 1000).ifEmpty { "0:00" }, style = style, color = scheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.duration(seek.duration / 1000).ifEmpty { "0:00" }, style = style, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val openSheet = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        chrome.sheet = true
                        chrome.touch()
                    }
                    if (roomy) StripPill(if (Player.speed == 1f) "倍速" else speedLabel(Player.speed), openSheet)
                    if (Player.heights.isNotEmpty()) StripPill(qualityLabel(Player.currentHeight), openSheet)
                }
                StripButton(YoinSymbols.Headphones, "仅听声音") { Player.changeAudioOnly(true) }
                StripButton(PiliSymbols.Fullscreen, "全屏") { Player.fullscreen = true }
            }
        }
    }
    if (chrome.sheet) PlaybackSheet(onDismiss = { chrome.sheet = false })
}

@Composable
private fun StripButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.size(44.dp),
    ) { Icon(icon, contentDescription = description) }
}

@Composable
private fun StripButton(painter: androidx.compose.ui.graphics.painter.Painter, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.size(44.dp),
    ) { Icon(painter, contentDescription = description) }
}

/** PiliPaw's strip pill: a full-round tonal block, its text cross-fading when it changes. */
@Composable
private fun StripPill(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = YoinShapeTokens.Full,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            label = "ytPill",
        ) { t ->
            Text(t, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}
