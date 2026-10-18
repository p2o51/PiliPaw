package com.gpo.pilipala.navigation.transition

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.theme.BackMotionTokens
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.YoinMotionSpeed
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** M3 scrim opacity over the page a container grows above. */
private const val ContainerScrimAlpha = 0.32f

/**
 * A card that opens [route]: a push of that route grows out of the card
 * (container transform). Only registers the card and keeps a reference to
 * its layout — its bounds are resolved at the push, not on every scroll
 * frame — inert when the route has no shared key or outside the NavDisplay.
 */
@Composable
fun Modifier.pageSharedSource(route: Route): Modifier {
    val key = route.sharedKey() ?: return this
    val transitions = LocalPageTransitions.current ?: return this
    return this then PageSharedSourceElement(transitions, key)
}

private class PageSharedSourceElement(
    val transitions: PageTransitions,
    val key: String,
) : ModifierNodeElement<PageSharedSourceNode>() {
    override fun create() = PageSharedSourceNode(transitions, key)

    override fun update(node: PageSharedSourceNode) = node.update(transitions, key)

    override fun equals(other: Any?): Boolean =
        other is PageSharedSourceElement && other.transitions === transitions && other.key == key

    override fun hashCode(): Int = 31 * System.identityHashCode(transitions) + key.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "pageSharedSource"
        properties["key"] = key
    }
}

/**
 * Registered while attached; [onPlaced] only stores the coordinates
 * reference (no math, no allocation) — [PageTransitions.beforePush]
 * resolves the card's bounds from it.
 */
private class PageSharedSourceNode(
    private var transitions: PageTransitions,
    private var key: String,
) : Modifier.Node(), LayoutAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null

    override fun onAttach() {
        transitions.registerSource(key)
    }

    override fun onDetach() {
        transitions.unregisterSource(key, coordinates)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        transitions.noteSourceCoordinates(key, coordinates)
    }

    fun update(transitions: PageTransitions, key: String) {
        if (transitions === this.transitions && key == this.key) return
        if (isAttached) this.transitions.unregisterSource(this.key, coordinates)
        this.transitions = transitions
        this.key = key
        if (isAttached) {
            transitions.registerSource(key)
            coordinates?.let { transitions.noteSourceCoordinates(key, it) }
        }
    }
}

/**
 * Every nav entry's root, applied by the host's entry decorator so feature
 * screens need nothing:
 *  - plays the push's forward motion (taken from [PageTransitions.takeEnter])
 *    in real time, over the page beneath as captured at the tap:
 *    · from a card — container transform: the container's bounds lerp from
 *      the card to the window on the spatial spring; it reads as a rounded
 *      card the whole way ([containerCornerPx]: the card's 12dp opens to the
 *      28dp window corner early, holds while it moves, eases to 0 only as it
 *      lands); it starts as the card's own pixels, which fade off the page
 *      (laid out at full size, scaled to fill, top aligned like the cover) on
 *      the effects spring; the page beneath dims under the M3 scrim;
 *    · otherwise — Yoin's forward mirror: in from 96dp on EMPHASIZED with the
 *      effects fade, clipped to the 28dp window corner while it slides
 *      ([slideCornerPx]), the page beneath stays put.
 *    The clock starts only once the page has composed, drawn (hidden) and
 *    its heavy first frames have passed, so they never eat the opening;
 *  - provides [LocalPageSettled]: true from the frame the motion lands (its
 *    own, or the scene's forward mirror when nothing was captured);
 *  - records the page into a layer, the source of the pixels a later back
 *    reveals beneath the page pushed on top of it (the corner clip sits
 *    outside it: snapshots are always the square, full page);
 *  - votes 120Hz while it enters.
 */
@Composable
internal fun PageEntryFrame(
    transitions: PageTransitions,
    contentKey: Any,
    content: @Composable () -> Unit,
) {
    val layer = rememberGraphicsLayer()
    DisposableEffect(transitions, contentKey, layer) {
        transitions.attachLayer(contentKey, layer)
        onDispose { transitions.detachLayer(contentKey, layer) }
    }
    val enter = remember(contentKey) { transitions.takeEnter(contentKey) }
    val growth = remember(enter) { Animatable(if (enter?.container == true) 0f else 1f) }
    val slide = remember(enter) { Animatable(if (enter != null && !enter.container) 1f else 0f) }
    val fade = remember(enter) { Animatable(if (enter != null) 0f else 1f) }
    var entering by remember(enter) { mutableStateOf(enter != null) }
    // Without a capture the scene itself plays the forward mirror
    // (pagePushTransform): the page is still moving until that enter is done.
    // Only the entering direction counts — a page being covered (target
    // PostExit) stays settled, its picture must not drop out beneath.
    val sceneTransition = LocalNavAnimatedContentScope.current.transition
    val sceneSettled by remember(sceneTransition) {
        derivedStateOf {
            sceneTransition.targetState != EnterExitState.Visible ||
                sceneTransition.currentState == EnterExitState.Visible
        }
    }
    if (enter != null) {
        LaunchedEffect(enter) {
            // The page composes and draws (hidden behind the card's own pixels
            // or at alpha 0) before the clock starts, and its first heavy
            // frames pass: a spring started under them would show up already
            // half way (Yoin's prewarm rule, DetailPaneHost.awaitPrewarm).
            awaitCalmFrames()
            coroutineScope {
                launch { fade.animateSteadily(1f, transitions.enterFadeSpec) }
                if (enter.container) {
                    launch { growth.animateSteadily(1f, transitions.containerGrowthSpec) }
                } else {
                    launch {
                        slide.animateSteadily(
                            0f,
                            tween(BackMotionTokens.PostCommitDurationMs, easing = BackMotionTokens.EmphasizedEasing),
                        )
                    }
                }
            }
            entering = false
        }
    }

    val scrimColor = MaterialTheme.colorScheme.scrim

    Box(Modifier.fillMaxSize().voteHighFrameRate(entering)) {
        if (entering && enter != null) {
            // The page beneath, as it was at the tap (its live scene is gone).
            Spacer(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        // One pass: the scrim is a tint on the backdrop, not a second full-window blend.
                        val dim = if (enter.container) ContainerScrimAlpha * growth.value.coerceIn(0f, 1f) else 0f
                        // A video page's SurfaceView is a hole in the snapshot: read it as player black,
                        // as the back path does, instead of letting the window surface show through.
                        drawRect(Color.Black)
                        drawImage(
                            enter.backdrop.image,
                            colorFilter = if (dim > 0f) {
                                ColorFilter.tint(scrimColor.copy(alpha = dim), BlendMode.SrcAtop)
                            } else {
                                null
                            },
                        )
                    },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (enter == null) return@graphicsLayer
                    if (!enter.container) {
                        val remaining = slide.value
                        translationX = BackMotionTokens.EnteringStartOffset.toPx() * remaining
                        val corner = slideCornerPx(remaining, EnterCorner.toPx())
                        if (corner > 0f && size.width > 0f && size.height > 0f) {
                            // Equal shapes compare equal: the outline is rebuilt
                            // only while the corner actually changes.
                            shape = LocalRectShape(Rect(0f, 0f, size.width, size.height), corner)
                            clip = true
                        }
                        return@graphicsLayer
                    }
                    val s = growth.value
                    if (s >= GrowthDone) return@graphicsLayer
                    val geometry = containerGeometry(transitions, enter.source, s, size) ?: return@graphicsLayer
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = geometry.scale
                    scaleY = geometry.scale
                    translationX = geometry.translationX
                    translationY = geometry.translationY
                    val corner = containerCornerPx(
                        s,
                        BackMotionTokens.ContainerSourceCorner.toPx(),
                        EnterCorner.toPx(),
                    )
                    // The clip lives in the page's own (unscaled) coordinates.
                    shape = LocalRectShape(geometry.local, corner / geometry.scale)
                    clip = true
                }
                .drawWithContent {
                    drawContent()
                    if (enter == null || !enter.container) return@drawWithContent
                    val s = growth.value
                    val cardAlpha = 1f - fade.value.coerceIn(0f, 1f)
                    if (s >= GrowthDone || cardAlpha <= 0f) return@drawWithContent
                    val geometry = containerGeometry(transitions, enter.source, s, size) ?: return@drawWithContent
                    // The container starts as the card itself — its pixels,
                    // scaled to fill — and they fade off the opaque page.
                    // (Fading the page in instead needs a full-window
                    // offscreen layer every frame.)
                    val local = geometry.local
                    val image = enter.backdrop.image
                    val full = enter.source
                    val src = full.intersect(Rect(0f, 0f, image.width.toFloat(), image.height.toFloat()))
                    if (src.isEmpty) return@drawWithContent
                    val c = max(local.width / full.width, local.height / full.height)
                    val left = local.center.x - full.width * c / 2f + (src.left - full.left) * c
                    val top = local.top + (src.top - full.top) * c
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(src.left.roundToInt(), src.top.roundToInt()),
                        srcSize = IntSize(src.width.roundToInt(), src.height.roundToInt()),
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize((src.width * c).roundToInt(), (src.height * c).roundToInt()),
                        alpha = cardAlpha,
                    )
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (enter != null && !enter.container) {
                            // Per-op alpha: a short fade over the backdrop
                            // needs no offscreen copy of the whole page.
                            alpha = fade.value.coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                    }
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    }
                    // Pages may leave their root transparent: the window
                    // surface is part of the page (and of its snapshot), so a
                    // backdrop never shows through an entering page.
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                // `entering` turns false only after the last animation has
                // snapped to its target — the layer above has drawn its final
                // frame with no transform or clip — so settled never leads
                // the landing (at most it trails it by the recomposition frame).
                CompositionLocalProvider(LocalPageSettled provides (!entering && sceneSettled)) {
                    content()
                }
            }
        }
    }
}

/**
 * False while this page is still entering (container transform / forward slide);
 * true once it has landed. Pages hold back things the transition can't carry —
 * a video SurfaceView ignores the container's rounded clip — until it is true.
 * CONTRACT for feature code; navigation/transition owns how it is driven.
 *
 * Read-tracked (not static) on purpose: it flips once per page, at the landing
 * frame, and only its readers recompose then — not the whole page.
 */
val LocalPageSettled = compositionLocalOf { true }

/** The container at growth s: where the full-size page is drawn and which part of it shows. */
private class ContainerGeometry(
    /** Page scale (fill the container, top aligned). */
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
    /** The container in the page's own (unscaled) coordinates. */
    val local: Rect,
)

private fun containerGeometry(transitions: PageTransitions, source: Rect, s: Float, size: Size): ContainerGeometry? {
    if (size.width <= 0f || size.height <= 0f) return null
    val rect = transitions.containerRect(source, s)
    if (rect.isEmpty) return null
    val k = max(rect.width / size.width, rect.height / size.height)
    val lw = rect.width / k
    val lh = rect.height / k
    val local = Rect(size.width / 2f - lw / 2f, 0f, size.width / 2f + lw / 2f, lh)
    return ContainerGeometry(k, rect.center.x - k * size.width / 2f, rect.top, local)
}

/**
 * Clip to [rect] (layer coordinates) with corners of [radiusPx]. A plain
 * rounded rect on purpose: the clip is resized every frame, and
 * Outline.Rounded stays on RenderNode's native clip — a continuous-curvature
 * path clip cost ~10ms of RenderThread per frame on the tablet (Yoin's
 * *Animated shapes rule). The radius never exceeds half the short side.
 * A data class: graphicsLayer compares shapes by equality, so an unchanged
 * clip (the slide's held 28dp) doesn't rebuild the outline each frame.
 */
private data class LocalRectShape(private val rect: Rect, private val radiusPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = radiusPx.coerceIn(0f, min(rect.width, rect.height).coerceAtLeast(0f) / 2f)
        return Outline.Rounded(RoundRect(rect, CornerRadius(r, r)))
    }
}

/**
 * The corner an entering page wears while it moves: the device-like window
 * corner, the same 28dp the back path's closing page clips to.
 */
private val EnterCorner = BackMotionTokens.PopPageCornerRadius

/** Growth by which the container's corner has opened from the card's to [EnterCorner]. */
private const val CornerOpenEnd = 0.3f

/** Travel fraction from which the corner eases to 0: the last ~10%, as the page lands. */
private const val CornerLandStart = 0.9f

/**
 * The container's corner (px, screen space) at growth [s] — one C¹ curve of
 * the growth value, no step: the card's [sourcePx] opens to [windowPx] over
 * the first [CornerOpenEnd] of the grow, holds while the container moves, and
 * eases to 0 over the last ~10% as it lands (0 at s ≥ 1, so a hair of spring
 * overshoot never brings a corner back).
 */
private fun containerCornerPx(s: Float, sourcePx: Float, windowPx: Float): Float {
    val open = sourcePx + (windowPx - sourcePx) * smoothstep(0f, CornerOpenEnd, s)
    return open * (1f - smoothstep(CornerLandStart, 1f, s))
}

/**
 * The forward mirror's corner (px) with [remaining] of the 96dp slide still to
 * go (1 → 0): [windowPx] while it slides, easing to 0 over the last ~10% of
 * the travel as it settles.
 */
private fun slideCornerPx(remaining: Float, windowPx: Float): Float =
    windowPx * (1f - smoothstep(CornerLandStart, 1f, 1f - remaining))

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The spring may overshoot by a hair; past this the page is simply in place. */
private const val GrowthDone = 0.9999f

/**
 * Waits until [EnterCalmFrames] consecutive frames arrive on time, capped at
 * [EnterCalmMaxNanos] after the first — the enter clock then starts on a
 * light frame and never skips its opening.
 */
private suspend fun awaitCalmFrames() {
    val start = withFrameNanos { it }
    var last = start
    var calm = 0
    while (calm < EnterCalmFrames && last - start < EnterCalmMaxNanos) {
        val now = withFrameNanos { it }
        calm = if (now - last <= EnterCalmFrameNanos) calm + 1 else 0
        last = now
    }
}

/**
 * Animates on a clamped frame clock: a late frame advances the animation by
 * at most [MaxStepNanos]. The page being entered can still block the main
 * thread when its data lands (a video page's first layout of its intro);
 * with the wall clock the container would then jump most of the way in one
 * frame — here it holds for the stalled frame and carries on continuously.
 */
private suspend fun Animatable<Float, AnimationVector1D>.animateSteadily(
    target: Float,
    spec: AnimationSpec<Float>,
) {
    val animation = TargetBasedAnimation(spec, Float.VectorConverter, value, target)
    var played = 0L
    var last = withFrameNanos { it }
    while (true) {
        val now = withFrameNanos { it }
        played += min(now - last, MaxStepNanos)
        last = now
        if (animation.isFinishedFromNanos(played)) {
            snapTo(target)
            return
        }
        snapTo(animation.getValueFromNanos(played))
    }
}

/** ≈ 1.5 frames at 60Hz: ordinary jitter passes through, a stall does not become a jump. */
private const val MaxStepNanos = 25_000_000L

private const val EnterCalmFrames = 2
private const val EnterCalmFrameNanos = 24_000_000L
private const val EnterCalmMaxNanos = 200_000_000L

/**
 * NavDisplay push spec. Normally no scene motion at all: the entering frame
 * plays the container / mirror itself over the captured page beneath, so the
 * live page beneath can leave at once. Without a capture (a deep link before
 * the first frame) the scene plays the forward mirror over the live page.
 */
internal fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.pagePushTransform(
    transitions: PageTransitions,
    density: Density,
): ContentTransform {
    if (!transitions.sceneMirror) return EnterTransition.None togetherWith ExitTransition.None
    val offset = with(density) { BackMotionTokens.EnteringStartOffset.roundToPx() }
    return (
        YoinMotion.crossActivitySlideIn(offset) +
            YoinMotion.fadeIn(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Default)
        ) togetherWith ExitTransition.KeepUntilTransitionsFinished
}

/**
 * NavDisplay pop spec: none. A pop only ever lands after the back replica
 * has finished its own commit ride (the closing page is already invisible and
 * the previous page's pixels are in place), so the swap itself must not move.
 */
internal fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.pagePopTransform(): ContentTransform =
    EnterTransition.None togetherWith ExitTransition.None
