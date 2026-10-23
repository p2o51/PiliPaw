package com.gpo.pilipala.feature.live

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.min

/**
 * Fullscreen predictive back for the live room, the video player's gesture in small: the
 * back swipe shrinks the fullscreen picture toward the inline slot it came from (a centred
 * card when the window will turn back), its corners round and the controls fade. Letting
 * go exits fullscreen and the inline picture springs from where the finger left it into
 * its slot; cancelling springs back to the full picture. All in graphics layers, over a
 * TextureView (the fullscreen live picture draws through one so it can scale).
 */
@Stable
internal class LiveBackState {
    /** Eased gesture: 0 the full picture, 1 the picture on its target. */
    val progress = Animatable(0f)

    /** After a commit: 1 where the picture was let go, 0 at rest in the inline slot. */
    val settle = Animatable(0f)

    // The inline picture's window rect, the window size then and its corner (px).
    internal var inline: Rect? = null
    internal var inlineWindow: IntSize = IntSize.Zero
    internal var inlineRadius: Float = 0f

    // The fullscreen frame's window rect; the gesture's target and corner; the release pose.
    internal var frame: Rect? = null
    internal var target: Rect? = null
    internal var targetRadius: Float = 0f
    internal var release: Rect? = null

    /**
     * The fullscreen picture as it was let go, drawn over the inline slot until the inline
     * view shows its own first frame: the inline view is a new SurfaceView, black until then.
     */
    var bridge by mutableStateOf<ImageBitmap?>(null)

    /** Set by the fullscreen view: a software-turned picture exits without following the finger. */
    internal var turned: Boolean = false

    /** The 16:9 picture box inside the fullscreen frame, and where the gesture draws it now. */
    fun poseNow(): Rect? {
        val f = frame ?: return null
        return solve(f, progress.value)?.second
    }

    /** (scale, visual picture rect) at gesture [g]. */
    internal fun solve(f: Rect, g: Float): Pair<Float, Rect>? {
        if (f.width <= 1f || f.height <= 1f) return null
        val bw = min(f.width, f.height * 16f / 9f)
        val bh = bw * 9f / 16f
        val box = Rect(Offset(f.center.x - bw / 2f, f.center.y - bh / 2f), Size(bw, bh))
        val t = target ?: centredCard(f)
        val k = lerp(1f, t.width / bw, g)
        val c = lerp(box.center, t.center, g)
        return k to Rect(Offset(c.x - bw * k / 2f, c.y - bh * k / 2f), Size(bw * k, bh * k))
    }

    private fun centredCard(f: Rect): Rect {
        val cw = min(f.width * 0.72f, f.height * 0.72f * 16f / 9f)
        val ch = cw * 9f / 16f
        return Rect(Offset(f.center.x - cw / 2f, f.center.y - ch / 2f), Size(cw, ch))
    }
}

private val LiveCardRadius = 20.dp

/** The inline picture: notes its slot, and lands from the release pose after a commit. */
internal fun Modifier.liveBackInline(st: LiveBackState, radiusPx: Float, window: () -> IntSize): Modifier =
    onGloballyPositioned { c ->
        st.inline = Rect(c.positionInWindow(), c.size.toSize())
        st.inlineWindow = window()
        st.inlineRadius = radiusPx
    }.drawWithContent {
        drawContent()
        st.bridge?.let { b ->
            drawImage(b, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        }
    }.graphicsLayer {
        val s = st.settle.value
        val from = st.release
        val own = st.inline
        if (s == 0f || from == null || own == null || own.width <= 1f) {
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            return@graphicsLayer
        }
        val pose = lerp(own, from, s)
        val k = pose.width / own.width
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = k
        scaleY = k
        translationX = pose.left - own.left
        translationY = pose.top - own.top
    }

/** The fullscreen frame: scales toward the target with the finger, its clip closing on the picture. */
internal fun Modifier.liveBackFrame(st: LiveBackState): Modifier =
    onGloballyPositioned { c -> st.frame = Rect(c.positionInWindow(), c.size.toSize()) }
        .graphicsLayer {
            val g = st.progress.value
            val f = st.frame
            val solved = if (g != 0f && f != null) st.solve(f, g) else null
            if (solved == null) {
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                clip = false
                shape = RectangleShape
                return@graphicsLayer
            }
            val (k, pose) = solved
            val bw = pose.width / k
            val bh = pose.height / k
            // Local box (unscaled), centred in the frame.
            val box = Rect(Offset((f!!.width - bw) / 2f, (f.height - bh) / 2f), Size(bw, bh))
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = k
            scaleY = k
            translationX = pose.left - f.left - box.left * k
            translationY = pose.top - f.top - box.top * k
            val morph = min(1f, 2f * g)
            val clipRect = lerp(Rect(0f, 0f, f.width, f.height), box, morph)
            val radius = (st.targetRadius * g) / k.coerceAtLeast(0.05f)
            shape = LiveBackClip(clipRect, radius)
            clip = true
        }

private class LiveBackClip(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius, radius)))
}

/** The controls' fade along the gesture (gone a third of the way in). */
internal fun LiveBackState.chromeAlpha(): Float = (1f - 3f * progress.value).coerceIn(0f, 1f)

/**
 * Replaces the plain fullscreen BackHandler. Without a gesture (3-button, keyboard) or with
 * a software-turned picture, back simply exits as before.
 */
@Composable
internal fun LiveFullscreenBackHandler(
    st: LiveBackState,
    enabled: Boolean,
    window: () -> IntSize,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val density = LocalDensity.current
    val latestExit by rememberUpdatedState(onExit)
    val latestWindow by rememberUpdatedState(window)
    val landSpec = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Standard)
    val cancelSpec = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Standard)
    PredictiveBackHandler(enabled = enabled) { events ->
        if (st.turned) {
            events.collect { }
            latestExit()
            return@PredictiveBackHandler
        }
        // The inline slot counts only in the window it was measured in (a phone turns back).
        val inline = st.inline?.takeIf { st.inlineWindow == latestWindow() && it.width > 1f }
        st.target = inline
        st.targetRadius = if (inline != null) st.inlineRadius else with(density) { LiveCardRadius.toPx() }
        var moved = false
        try {
            events.collect { e ->
                val g = YoinMotion.backGestureEasing.transform(e.progress.coerceIn(0f, 1f))
                if (g > 0f) moved = true
                st.progress.snapTo(g)
            }
        } catch (c: CancellationException) {
            scope.launch { st.progress.animateTo(0f, cancelSpec) }
            throw c
        }
        if (!moved) {
            st.progress.snapTo(0f)
            latestExit()
            return@PredictiveBackHandler
        }
        val pose = if (inline != null) st.poseNow() else null
        if (pose != null) {
            st.bridge = activity?.window?.let { copyPicture(it, pose) }
            st.release = pose
            st.settle.snapTo(1f)
        }
        st.progress.snapTo(0f)
        latestExit()
        if (pose != null) {
            scope.launch {
                st.settle.animateTo(0f, landSpec)
                st.release = null
            }
        }
    }
}

/** The window's pixels under [rect] (the let-go picture), or null when the copy fails or takes too long. */
private suspend fun copyPicture(window: Window, rect: Rect): ImageBitmap? {
    val left = rect.left.roundToInt()
    val top = rect.top.roundToInt()
    val w = rect.width.roundToInt()
    val h = rect.height.roundToInt()
    if (w <= 1 || h <= 1) return null
    val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
    val src = android.graphics.Rect(left, top, left + w, top + h)
    val copied = withTimeoutOrNull(BridgeCopyTimeoutMs) {
        com.gpo.pilipala.feature.player.awaitBitmapCopy(bitmap) { callback ->
            PixelCopy.request(window, src, bitmap, callback, Handler(Looper.getMainLooper()))
        }
    }
    return copied?.asImageBitmap()
}

/** The copy usually lands within a frame; past this the exit goes ahead without the bridge. */
private const val BridgeCopyTimeoutMs = 120L
