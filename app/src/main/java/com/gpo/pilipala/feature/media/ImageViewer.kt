package com.gpo.pilipala.feature.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape

/**
 * One picture for the viewer: its source [url] (any hdslb form; the viewer asks
 * the CDN for its own size) and, when the API gives them, its real pixel size.
 * The size lets the picture's full rect be known on the tap, before anything
 * loads; without it the viewer reads the aspect off what it has.
 */
@Immutable
data class ViewerImage(val url: String, val width: Int = 0, val height: Int = 0) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f
}

/**
 * CONTRACT (owner: feature/media). Opens the full-screen picture viewer over
 * everything, bottom sheets and dialogs included (it has a window of its
 * own). [originKeys] has one key per image, the same keys the cells
 * registered with [imageViewerOrigin]: the picture grows out of its cell and,
 * on dismiss, flies back to the cell of the picture now showing. Empty (or a
 * key no cell holds): the picture fades and scales in place instead.
 */
@Stable
interface ImageViewerController {
    fun open(images: List<ViewerImage>, index: Int, originKeys: List<String> = emptyList())
}

val LocalImageViewer = staticCompositionLocalOf<ImageViewerController> {
    object : ImageViewerController {
        override fun open(images: List<ViewerImage>, index: Int, originKeys: List<String>) = Unit
    }
}

/** The corner a cell is assumed to have when it does not pass its shape. */
private val DefaultOriginShape: Shape = ContinuousRoundedCornerShape(12.dp)

/**
 * CONTRACT. Marks a picture cell as a viewer origin (its bounds, shape and crop).
 *
 * Pass the cell's own clip [shape] so the picture grows out of exactly that
 * outline and grows back into it on the way home; without it the cell is taken
 * as 12dp all round. [alignment] is the cell's crop anchor (what it passes to
 * its image); null is the centre, as the CDN's `_1c` crops and
 * `ContentScale.Crop` both cut.
 *
 * Cheap: the cell only keeps its layout coordinates; bounds are read when the
 * viewer opens or flies (through screen coordinates, so a cell inside a
 * bottom sheet works the same). While its picture is up in the viewer the
 * cell draws nothing (the flying picture is it).
 */
@Composable
fun Modifier.imageViewerOrigin(key: String, shape: Shape? = null, alignment: Alignment? = null): Modifier {
    val viewer = LocalImageViewer.current as? ViewerState ?: return this
    return this then OriginElement(key, shape ?: DefaultOriginShape, alignment, viewer.origins)
}

/**
 * CONTRACT. Wraps the whole app (MainActivity, inside the theme) and provides
 * [LocalImageViewer]. The viewer opens in a full-screen window of its own
 * above every other window of the app.
 */
@Composable
fun ImageViewerHost(content: @Composable () -> Unit) {
    val state = remember { ViewerState() }
    CompositionLocalProvider(LocalImageViewer provides state) {
        content()
        ImageViewerWindow(state)
    }
}
