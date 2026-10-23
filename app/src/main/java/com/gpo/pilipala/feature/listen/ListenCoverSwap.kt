package com.gpo.pilipala.feature.listen

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.components.seam.SeamMotion
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.CancellationException
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin

/** How a new cover takes over from the one on screen. */
internal enum class CoverReveal { Crossfade, Dissolve }

/**
 * The cover, swapped the way Yoin's 切歌 swaps artwork (ArtworkSwap): the painter on screen
 * stays drawn, opaque, until the next one has decoded, which then develops over it — the
 * large cover in a fine staggered halftone ([CoverHalftone]) carried by a wave front from the
 * right for a next and from the left for a previous ([direction]), small ones in a crossfade.
 * A skip mid-reveal freezes the blend so far as the new floor. Never an empty box between.
 */
@Composable
internal fun ListenCoverImage(
    url: String,
    thumbWidth: Dp,
    reveal: CoverReveal,
    direction: Int,
    modifier: Modifier = Modifier,
    onReveal: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { thumbWidth.roundToPx() }.coerceAtLeast(1)
    var floor by remember { mutableStateOf<Painter?>(null) }
    var incoming by remember { mutableStateOf<Painter?>(null) }
    val progress = remember { Animatable(1f) }
    var mode by remember { mutableStateOf(reveal) }
    var revealDirection by remember { mutableStateOf(direction) }
    val fade = YoinMotion.defaultEffectsSpec<Float>()
    val latestOnReveal by rememberUpdatedState(onReveal)
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val frameLayer = rememberGraphicsLayer()
    val imageLayer = rememberGraphicsLayer()
    val halftone = remember { CoverHalftone() }

    LaunchedEffect(url, px) {
        if (url.isBlank()) return@LaunchedEffect
        val result = SingletonImageLoader.get(context).execute(listenCoverRequest(context, url, px))
            as? SuccessResult ?: return@LaunchedEffect
        val next = result.image.asPainter(context)
        if (floor == null) {
            floor = next
            latestOnReveal(url)
            return@LaunchedEffect
        }
        // A key change cancels the old animation but keeps its pixels on screen while loading.
        // Flatten an interrupted blend ONCE. Never retain a recursive stack of old painters:
        // rapid skips must have constant bitmap retention and constant work on later frames.
        // Without a capture (not drawn yet, or no memory for it) the cover it was heading to
        // becomes the floor: a jump, but never a skip that leaves the old cover up.
        incoming?.let { interrupted ->
            val frozen = if (frameLayer.size.width <= 0 || frameLayer.size.height <= 0) null else try {
                BitmapPainter(frameLayer.toImageBitmap())
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            floor = frozen ?: interrupted
        }
        progress.snapTo(0f)
        incoming = next
        revealDirection = direction
        SeamMotion.ensureWatching(context)
        mode = if (SeamMotion.reduced(this)) CoverReveal.Crossfade else reveal
        repeat(2) { withFrameNanos { } }
        var told = false
        progress.animateTo(1f, if (mode == CoverReveal.Dissolve) CoverDissolveSpring else fade) {
            if (!told && value >= RevealVisibleAt) {
                told = true
                latestOnReveal(url)
            }
        }
        if (!told) latestOnReveal(url)
        floor = next
        incoming = null
    }
    Box(modifier.drawWithContent {
        frameLayer.record { this@drawWithContent.drawContent() }
        drawLayer(frameLayer)
    }) {
        floor?.let { painter ->
            Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        incoming?.let { painter ->
            Image(
                painter, null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().drawWithContent {
                    val amount = progress.value.coerceIn(0f, 1f)
                    when {
                        amount >= 1f -> drawContent()
                        amount <= 0f -> Unit
                        else -> {
                            imageLayer.record { this@drawWithContent.drawContent() }
                            if (mode == CoverReveal.Dissolve) {
                                imageLayer.alpha = CoverHalftone.opacity(amount)
                                halftone.draw(this, amount, revealDirection, primary, tertiary) { drawLayer(imageLayer) }
                            } else {
                                imageLayer.alpha = amount
                                drawLayer(imageLayer)
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * The request a listen cover is drawn from, so [preloadListenCover] warms exactly the entry
 * [ListenCoverImage] reads (same data, size and memory-cache key).
 */
internal fun listenCoverRequest(context: Context, url: String, px: Int): ImageRequest {
    val model = Images.thumb(url, px)
    return ImageRequest.Builder(context)
        .data(model)
        .crossfade(false)
        .size(px)
        .memoryCacheKey("listen#$model#$px")
        .build()
}

/** Decodes the cover ahead of its reveal (failures are left to the image itself). */
internal suspend fun preloadListenCover(context: Context, url: String, px: Int) {
    runCatching { SingletonImageLoader.get(context).execute(listenCoverRequest(context, url, px)) }
}

/**
 * Yoin's artwork dissolve spring: low stiffness, critically damped, so the fine dots have time
 * to emerge before they join up, and the reveal only ever moves forward (~700ms).
 */
private val CoverDissolveSpring: FiniteAnimationSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 45f,
    visibilityThreshold = .005f,
)

/** How far into the reveal the new cover first shows (the dissolve's front starts off the edge). */
private const val RevealVisibleAt = .2f

/**
 * Yoin's ArtworkHalftone, ported as is: a fine (6dp) staggered screen-print texture carried by
 * a curved front. Dots in the moving band grow, drift and join; behind the band the revealed
 * rows merge into strips. A thin Primary → Tertiary fringe and a faint bloom ride the front
 * only, outside the new image's mask. Every input is the reveal's progress, never time, so an
 * interrupted frame holds still.
 */
internal class CoverHalftone {
    private val mask = Path()
    private val ink = Path()
    private val bloom = Path()

    fun draw(scope: DrawScope, progress: Float, direction: Int, primary: Color, tertiary: Color, content: () -> Unit) =
        with(scope) {
            val pitch = 6.dp.toPx()
            val columns = ceil(size.width / pitch).toInt().coerceAtLeast(1)
            val rows = ceil(size.height / (pitch * .866f)).toInt().coerceAtLeast(1)
            val cellWidth = size.width / columns
            val cellHeight = size.height / rows
            val fullRadius = hypot(cellWidth, cellHeight) * .53f
            val front = progress * 1.65f - .325f
            val fromRight = direction >= 0
            mask.rewind()
            ink.rewind()
            bloom.rewind()

            // Overscan keeps half-dots and the mask continuous at the cover's edges.
            for (row in -1..rows) {
                val y = (row + .5f) * cellHeight
                val v = y / size.height
                // The field rolls as it travels, slower than the reveal, so filled cells never reopen.
                val bend = .09f * sin((v * .95f + progress * .6f + .07f) * TAU) +
                    .045f * sin((v * 1.85f - progress * .4f - .1f) * TAU)
                val stagger = if (row and 1 == 0) 0f else .5f
                var solidEdge = if (fromRight) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY
                for (column in -1..columns) {
                    val x = (column + .5f + stagger) * cellWidth
                    val u = x / size.width
                    val order = if (fromRight) 1f - u else u
                    val drift = .025f * sin((order * .8f + v * .8f + progress * .2f) * TAU) +
                        .015f * sin((order * 2.3f + v * 3.1f) * TAU)
                    val arrival = .14f + order * .72f + bend + drift
                    val local = ((front - arrival) / .52f + .5f).coerceIn(0f, 1f)
                    if (local <= 0f) continue
                    if (local >= 1f) {
                        solidEdge = if (fromRight) minOf(solidEdge, x) else maxOf(solidEdge, x)
                        continue
                    }
                    val envelope = sin(local * PI).toFloat()
                    val flowX = sin((v * 1.4f + order * .45f - progress * .8f) * TAU)
                    val flowY = sin((v * .7f - order * 1.3f + progress * .65f) * TAU)
                    val center = Offset(
                        x + cellWidth * .42f * flowX * envelope,
                        y + cellHeight * .38f * flowY * envelope,
                    )
                    val growth = ((local - .12f) / .88f).coerceIn(0f, 1f)
                    val radius = fullRadius * (.2f * local + .8f * smoothstep(growth))
                    mask.dot(center, radius)
                    ink.dot(center, radius + cellWidth * .06f * envelope)
                    bloom.dot(center, radius + cellWidth * .32f * envelope)
                }
                if (solidEdge.isFinite()) {
                    // The finished part of the row as one strip: only the band needs circles.
                    val edge = solidEdge.coerceIn(0f, size.width)
                    mask.addRect(
                        Rect(
                            if (fromRight) edge else 0f,
                            y - cellHeight * .5f,
                            if (fromRight) size.width else edge,
                            y + cellHeight * .5f,
                        ),
                    )
                    mask.dot(Offset(solidEdge, y), fullRadius)
                }
            }
            val tint = Brush.linearGradient(
                colors = listOf(primary, tertiary),
                start = Offset(size.width, 0f),
                end = Offset(0f, size.height),
            )
            // The tint stays outside the new image's mask: joined dots leave no second edge.
            clipPath(mask, clipOp = ClipOp.Difference) {
                drawPath(bloom, tint, alpha = .045f)
                drawPath(ink, tint, alpha = .14f)
            }
            clipPath(mask) { content() }
        }

    private fun Path.dot(center: Offset, radius: Float) {
        addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
    }

    companion object {
        private const val TAU = (PI * 2).toFloat()

        /** The old cover stays opaque underneath; the new print develops softly over it. */
        fun opacity(progress: Float): Float = .62f + .38f * smoothstep(progress.coerceIn(0f, 1f))

        private fun smoothstep(value: Float): Float = value * value * (3f - 2f * value)
    }
}
