package com.gpo.pilipala.feature.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.withTabularFigures
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Seconds per double tap / per follow-up tap. */
private const val SeekStepSeconds = 10

/** How long after the last tap a further single tap on the same side still adds a step. */
private const val StreakWindowMs = 800L

/**
 * Double tap to skip, YouTube style: a double tap on the left / right third of the
 * picture skips back / forward 10 s; further quick taps on either side keep adding 10 s
 * (20, 30 …) without waiting for another double tap, while the arc on that side counts.
 */
@Stable
internal class DoubleTapSeek {
    /** -1 back, 1 forward, 0 no streak (the arc hides). */
    var side by mutableIntStateOf(0)
        private set
    var seconds by mutableIntStateOf(0)
        private set

    /** Bumped on every step: kicks the arc and its ripple. */
    var bump by mutableIntStateOf(0)
        private set

    /** Where the last tap landed, in the gesture node's pixels (the ripple's origin). */
    var origin by mutableStateOf(Offset.Zero)
        private set

    private var baseMs = 0L
    private var lastTapAt = 0L

    fun active(now: Long = SystemClock.uptimeMillis()): Boolean = side != 0 && now - lastTapAt < StreakWindowMs

    /** One step of [dir] (-1 / 1) from a tap at [at]. */
    fun step(dir: Int, at: Offset, ctrl: PiliPlayerController) {
        val now = SystemClock.uptimeMillis()
        if (side != dir || !active(now)) {
            side = dir
            seconds = 0
            baseMs = ctrl.exactPositionMs()
        }
        seconds += SeekStepSeconds
        lastTapAt = now
        origin = at
        bump++
        ctrl.seekTo(baseMs + dir * seconds * 1000L)
    }

    fun end() {
        side = 0
    }
}

/** Which third of [width] [x] falls in: -1 left, 0 middle, 1 right. */
internal fun tapThird(x: Float, width: Int): Int = when {
    x < width / 3f -> -1
    x > width * 2f / 3f -> 1
    else -> 0
}

/**
 * Tap / double tap for the full chrome, like detectTapGestures (a single tap still waits
 * the double-tap timeout, as before), except that a tap [continues] claims is handled the
 * moment the finger lifts: the follow-up taps of a skip streak never wait.
 */
internal suspend fun PointerInputScope.detectPlayerTaps(
    continues: (Offset) -> Boolean,
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
) = awaitEachGesture {
    awaitFirstDown()
    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
    up.consume()
    if (continues(up.position)) return@awaitEachGesture
    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        var event: PointerEvent
        do {
            event = awaitPointerEvent()
        } while (!event.changes.fastAll { it.changedToDown() })
        event.changes[0]
    }
    if (second == null) {
        onTap(up.position)
        return@awaitEachGesture
    }
    val up2 = waitForUpOrCancellation()
    if (up2 == null) {
        // The second press became a drag or a hold: the first was a plain tap.
        onTap(up.position)
        return@awaitEachGesture
    }
    up2.consume()
    onDoubleTap(up2.position)
}

/**
 * The skip arc: a dark half-moon hugging the tapped side, a white ripple from the tap,
 * the chevrons and the running total (a bold number and a quiet 秒). Everything springs:
 * the arc in, a kick per step, the ripple out, the number rolling up.
 */
@Composable
internal fun DoubleTapSeekLayer(seek: DoubleTapSeek, big: Boolean, modifier: Modifier = Modifier) {
    // The last streak stays drawn while the arc fades out.
    val last = remember { intArrayOf(1, SeekStepSeconds) }
    if (seek.side != 0) {
        last[0] = seek.side
        last[1] = seek.seconds
    }
    val forward = last[0] > 0
    val kick = remember { Animatable(1f) }
    val ripple = remember { Animatable(1f) }
    val nudge = remember { Animatable(0f) }
    val kickSpec = YoinMotion.fastSpatialSpec<Float>(YoinMotionRole.Expressive)
    val rippleSpec = YoinMotion.slowSpatialSpec<Float>(YoinMotionRole.Standard)
    LaunchedEffect(seek.bump) {
        if (seek.bump == 0) return@LaunchedEffect
        launch {
            kick.snapTo(0.94f)
            kick.animateTo(1f, kickSpec)
        }
        launch {
            ripple.snapTo(0f)
            ripple.animateTo(1f, rippleSpec)
        }
        launch {
            nudge.snapTo(1f)
            nudge.animateTo(0f, kickSpec)
        }
        delay(StreakWindowMs)
        seek.end()
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val regionWidth = maxWidth * 0.4f
        AnimatedVisibility(
            visible = seek.side != 0,
            enter = fadeIn(YoinMotion.fastEffectsSpec()),
            exit = fadeOut(YoinMotion.defaultEffectsSpec()),
            modifier = Modifier
                .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                .width(regionWidth)
                .fillMaxHeight(),
        ) {
            Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // A circle centred beyond the outer edge: its inner rim bows into the picture.
                    val r = maxOf(w * 1.25f, h * 0.62f) * kick.value
                    val cx = if (forward) w + r - w * 0.98f else w * 0.98f - r
                    drawCircle(Color.Black.copy(alpha = 0.3f), radius = r, center = Offset(cx, h / 2f))
                    // The ripple from the tap (origin is in frame pixels; map it into the region).
                    val p = ripple.value
                    if (p < 0.999f) {
                        val ox = if (forward) seek.origin.x - (this@BoxWithConstraints.constraints.maxWidth - w) else seek.origin.x
                        val o = Offset(ox.coerceIn(0f, w), seek.origin.y.coerceIn(0f, h))
                        val reach = hypot(maxOf(o.x, w - o.x), maxOf(o.y, h - o.y))
                        drawCircle(Color.White.copy(alpha = 0.22f * (1f - p)), radius = reach * (0.2f + 0.8f * p), center = o)
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset {
                        IntOffset(((if (forward) 1 else -1) * nudge.value * 6.dp.toPx()).roundToInt(), 0)
                    },
                ) {
                    Row {
                        repeat(3) { i ->
                            // Brightest at the skip direction's end; each step flashes the trailing ones.
                            val lead = (if (forward) i else 2 - i) + 1
                            Icon(
                                if (forward) YoinSymbols.ChevronRight else YoinSymbols.ChevronLeft,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .size(if (big) 24.dp else 20.dp)
                                    .graphicsLayer {
                                        alpha = (0.35f + 0.65f * lead / 3f + 0.4f * nudge.value).coerceAtMost(1f)
                                    },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    SeekSeconds(seconds = last[1], big = big)
                }
            }
        }
    }
}

@Composable
private fun SeekSeconds(seconds: Int, big: Boolean) {
    val shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 8f)
    Row(verticalAlignment = Alignment.Bottom) {
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = seconds,
            transitionSpec = {
                (slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec())) togetherWith
                    (slideOutVertically(motion.fastSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec())) using
                    SizeTransform(clip = false)
            },
            label = "seekSeconds",
        ) { s ->
            Text(
                "$s",
                style = (if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge)
                    .withTabularFigures().copy(shadow = shadow),
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "秒",
            style = MaterialTheme.typography.labelLarge.copy(shadow = shadow),
            color = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.padding(bottom = if (big) 5.dp else 3.dp),
        )
    }
}
