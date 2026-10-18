package com.gpo.pilipala.feature.video

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import com.gpo.pilipala.ui.theme.RichHaptics
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.rememberRichHaptics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 三连 burst over three slots (点赞 投币 收藏), one after another
 * [RichHaptics.CelebrateBeatMs] apart: behind each glyph a circle blooms into a soft
 * burst on the expressive spring (it overshoots, turns and fades), a ring of sparks
 * flies out, and the glyph itself gets a velocity kick. The haptic beats land on the
 * same three starts; on a device without a vibrator the picture carries it alone.
 */
@Stable
internal class TripleBurst(val count: Int) {
    /** Growth of each slot's bloom, 0..1 (overshoots on the way). */
    val bloom = List(count) { Animatable(0f) }

    /** 0 = fully shown, 1 = faded away. */
    val fade = List(count) { Animatable(0f) }

    /** Spark flight 0..1. */
    val sparks = List(count) { Animatable(0f) }

    /** Glyph scale (1 at rest; kicked above 1 as its slot lands). */
    val pop = List(count) { Animatable(1f) }

    /** How much of slot [k]'s bloom shows right now (draw-phase read). */
    fun bloomShown(k: Int): Float = if (k !in 0 until count) 0f else bloom[k].value * (1f - fade[k].value)
}

/**
 * A [TripleBurst] that plays each time [trigger] changes after the first composition
 * (the count a page opens with never plays). A negative [trigger] means "not this
 * row's video" (a neighbouring page of the portrait pager): it never plays, and the
 * first real count it switches to is only recorded.
 */
@Composable
internal fun rememberTripleBurst(trigger: Int, count: Int = 3): TripleBurst {
    val burst = remember(count) { TripleBurst(count) }
    val haptics = rememberRichHaptics()
    val grow = YoinMotion.fastSpatialSpec<Float>(YoinMotionRole.Expressive)
    val fly = YoinMotion.slowSpatialSpec<Float>(YoinMotionRole.Standard)
    val kick = YoinMotion.fastSpatialSpec<Float>(YoinMotionRole.Expressive)
    val fadeOut = YoinMotion.slowEffectsSpec<Float>()
    val seen = remember { intArrayOf(trigger) }
    LaunchedEffect(trigger) {
        val previous = seen[0]
        seen[0] = trigger
        if (trigger == previous || trigger < 0 || previous < 0) return@LaunchedEffect
        haptics.celebrate(RichHaptics.CelebrateBeatMs)
        coroutineScope {
            repeat(burst.count) { k ->
                launch {
                    delay(k * RichHaptics.CelebrateBeatMs.toLong())
                    burst.bloom[k].snapTo(0f)
                    burst.fade[k].snapTo(0f)
                    burst.sparks[k].snapTo(0f)
                    launch { burst.bloom[k].animateTo(1f, grow) }
                    launch { burst.sparks[k].animateTo(1f, fly) }
                    launch { burst.pop[k].animateTo(1f, kick, initialVelocity = PopKick) }
                    delay(BloomHoldMs)
                    burst.fade[k].animateTo(1f, fadeOut)
                }
            }
        }
        burst.bloom.forEach { it.snapTo(0f) }
        burst.sparks.forEach { it.snapTo(0f) }
        burst.fade.forEach { it.snapTo(0f) }
    }
    return burst
}

/** Scale velocity of the glyph kick: peaks about 35% over rest on the expressive fast spring. */
private const val PopKick = 20f

/** How long a bloom holds before it fades. */
private const val BloomHoldMs = 220L

private val BloomMorph: Morph by lazy { Morph(MaterialShapes.Circle, MaterialShapes.SoftBurst) }

/**
 * Slot [k]'s bloom: a soft burst of [radius] centred on [center], morphing out of a
 * circle as it grows and turning a little, faded by the burst's fade.
 */
internal fun DrawScope.drawTripleBloom(burst: TripleBurst, k: Int, center: Offset, radius: Float, color: Color) {
    if (k !in 0 until burst.count) return
    val g = burst.bloom[k].value
    val a = 1f - burst.fade[k].value
    if (g <= 0.001f || a <= 0.001f) return
    val r = radius * g
    val path = BloomMorph.toPath(g.coerceIn(0f, 1f))
    val m = android.graphics.Matrix()
    m.setTranslate(-0.5f, -0.5f)
    m.postScale(r * 2f, r * 2f)
    m.postRotate(g * 50f + k * 20f)
    m.postTranslate(center.x, center.y)
    path.transform(m)
    drawPath(path.asComposePath(), color, alpha = a.coerceIn(0f, 1f))
}

/** Slot [k]'s sparks: [SparkCount] dots flying from [from] to [to] px out of [center], shrinking. */
internal fun DrawScope.drawTripleSparks(burst: TripleBurst, k: Int, center: Offset, from: Float, to: Float, dot: Float, color: Color) {
    if (k !in 0 until burst.count) return
    val s = burst.sparks[k].value
    if (s <= 0.001f || s >= 0.999f) return
    val dist = from + (to - from) * s
    val size = dot * (1f - s).coerceIn(0f, 1f)
    val alpha = (1f - s * s).coerceIn(0f, 1f)
    val turn = k * 0.3f
    for (i in 0 until SparkCount) {
        val angle = (i.toFloat() / SparkCount) * 2f * PI.toFloat() + turn - PI.toFloat() / 2f
        // Every other spark travels a little less, so the ring reads as scattered, not stamped.
        val d = if (i % 2 == 0) dist else dist * 0.78f
        drawCircle(color, radius = size, center = Offset(center.x + cos(angle) * d, center.y + sin(angle) * d), alpha = alpha)
    }
}

private const val SparkCount = 8
