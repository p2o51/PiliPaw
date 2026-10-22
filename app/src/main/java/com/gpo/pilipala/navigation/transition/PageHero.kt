package com.gpo.pilipala.navigation.transition

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.gpo.pilipala.navigation.Route
import kotlin.math.roundToInt

/*
 * The hero of a back: a page opened from a card's cover (a video) hands its picture back to
 * that cover. Once the back commits, the picture's current frame leaves the closing page and
 * flies on the spatial spring into the cover's place on the page beneath, riding with that
 * page as it comes home, while the rest of the closing page dissolves as usual.
 *
 *  - Cards mark their cover with [pageHeroCover]; at the push its bounds are kept for the
 *    pushed entry (the page beneath is a snapshot taken at that moment, so the cover is
 *    still exactly there when the back comes).
 *  - The page registers a [PageHeroCapture] for its route ([PageHeroEffect]): its picture's
 *    frame and bounds, or null when there is nothing to fly (fullscreen, no first frame).
 */

/** The picture's frame and its bounds in the window, or null: no hero for this back. */
typealias PageHeroCapture = suspend () -> Pair<ImageBitmap, Rect>?

/** One flight: [image] from [from] (window px, untransformed page) into [to] (the cover, page beneath). */
/** [page]: the shared key of the page whose picture flies (it hides its own while this is up). */
internal class HeroFlight(val image: ImageBitmap, val from: Rect, val to: Rect, val page: String, val targetCorner: Float? = null)

/** Registers the page's hero for [route] while composed. */
@Composable
fun PageHeroEffect(route: Route, capture: PageHeroCapture) {
    val key = route.sharedKey() ?: return
    val transitions = LocalPageTransitions.current ?: return
    val latest = rememberUpdatedState(capture)
    DisposableEffect(transitions, key) {
        val entry: PageHeroCapture = { latest.value() }
        transitions.registerHero(key, entry)
        onDispose { transitions.unregisterHero(key, entry) }
    }
}

/** Marks a card's cover as where the page it opens ([route]) flies its picture back to. */
@Composable
fun Modifier.pageHeroCover(route: Route): Modifier {
    val key = route.sharedKey() ?: return this
    val transitions = LocalPageTransitions.current ?: return this
    return this then HeroCoverElement(transitions, key)
}

private class HeroCoverElement(val transitions: PageTransitions, val key: String) : ModifierNodeElement<HeroCoverNode>() {
    override fun create() = HeroCoverNode(transitions, key)

    override fun update(node: HeroCoverNode) {
        if (node.transitions !== transitions || node.key != key) {
            node.transitions.unregisterCover(node.key, node)
            node.transitions = transitions
            node.key = key
            if (node.isAttached) transitions.registerCover(key, node)
        }
    }

    override fun equals(other: Any?) = other is HeroCoverElement && other.transitions === transitions && other.key == key

    override fun hashCode() = 31 * System.identityHashCode(transitions) + key.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "pageHeroCover"
        properties["key"] = key
    }
}

/** Keeps only a reference to its coordinates; the push resolves the bounds. */
internal class HeroCoverNode(var transitions: PageTransitions, var key: String) : Modifier.Node(), LayoutAwareModifierNode {
    var coordinates: LayoutCoordinates? = null
        private set

    override fun onAttach() = transitions.registerCover(key, this)

    override fun onDetach() {
        transitions.unregisterCover(key, this)
        coordinates = null
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        transitions.noteCover(key, this)
    }
}

/**
 * Draws the flight above everything (closing page, its dissolve cover): from the picture's
 * place on the closing page, posed as that page is, into the cover's place on the page
 * beneath, posed as it rides home. Corners open from the page's to the cover's 12dp; the
 * frame is cropped to whatever shape the rect has on the way. It fades as the live page
 * replaces its snapshot.
 */
@Composable
internal fun HeroOverlay(t: PageTransitions) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val flight = t.hero ?: return@drawBehind
                val alpha = if (t.phase == BackPhase.Revealing) 1f - t.reveal.value else 1f
                if (alpha <= 0f) return@drawBehind
                val s = t.heroProgress.value
                val from = posedClosing(t, flight.from)
                val to = posedBeneath(t, flight.to)
                val rect = Rect(
                    lerp(from.left, to.left, s),
                    lerp(from.top, to.top, s),
                    lerp(from.right, to.right, s),
                    lerp(from.bottom, to.bottom, s),
                )
                if (rect.width <= 1f || rect.height <= 1f) return@drawBehind
                val radius = lerp(0f, flight.targetCorner ?: HeroCoverCorner.toPx(), s.coerceIn(0f, 1f))
                val clip = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(radius)))
                }
                // Center-crop the frame to the rect's shape (the cover crops the same way).
                val img = flight.image
                val imgAspect = img.width.toFloat() / img.height
                val rectAspect = rect.width / rect.height
                val (srcW, srcH) = if (imgAspect > rectAspect) {
                    (img.height * rectAspect) to img.height.toFloat()
                } else {
                    img.width.toFloat() to (img.width / rectAspect)
                }
                val srcOffset = IntOffset(((img.width - srcW) / 2f).roundToInt(), ((img.height - srcH) / 2f).roundToInt())
                clipPath(clip) {
                    drawImage(
                        img,
                        srcOffset = srcOffset,
                        srcSize = IntSize(srcW.roundToInt().coerceAtLeast(1), srcH.roundToInt().coerceAtLeast(1)),
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                        alpha = alpha,
                    )
                }
            },
    )
}

/** The covers' corner (the cards' CoverShape). */
private val HeroCoverCorner = 12.dp

/** [r] on the closing page as that page is posed now (scale about its centre, then offsets). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.posedClosing(t: PageTransitions, r: Rect): Rect {
    val pose = closingPose(t, size)
    return r.scaledAbout(Offset(size.width / 2f, size.height / 2f), pose.scale).translate(pose.tx, pose.ty)
}

/** [r] on the page beneath as it is posed now (parked at −96dp and scaled, riding home). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.posedBeneath(t: PageTransitions, r: Rect): Rect {
    val pose = beneathPose(t, size)
    return r.scaledAbout(Offset(size.width / 2f, size.height / 2f), pose.scale).translate(pose.tx, pose.ty)
}

private fun Rect.scaledAbout(c: Offset, k: Float): Rect =
    Rect(c.x + (left - c.x) * k, c.y + (top - c.y) * k, c.x + (right - c.x) * k, c.y + (bottom - c.y) * k)
