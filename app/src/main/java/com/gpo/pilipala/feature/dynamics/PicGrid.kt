package com.gpo.pilipala.feature.dynamics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.feature.media.LocalImageViewer
import com.gpo.pilipala.feature.media.ViewerImage
import com.gpo.pilipala.feature.media.imageViewerOrigin
import com.gpo.pilipala.ui.components.CoverBadge
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import kotlin.math.roundToInt

private val PicShape = ContinuousRoundedCornerShape(12.dp)

/**
 * A dynamic's pictures (1 / 2 / 3 columns, Flutter pic_panel). Each cell is an
 * image-viewer origin keyed `dyn:<keyPrefix>:<i>`: a tap grows the picture out
 * of it, and the viewer flies home into the cell of the picture it ends on.
 */
@Composable
internal fun PicGrid(
    pics: List<DynPic>,
    keyPrefix: String,
    modifier: Modifier = Modifier,
) {
    if (pics.isEmpty()) return
    val viewer = LocalImageViewer.current
    val density = LocalDensity.current
    val keys = remember(keyPrefix, pics.size) { List(pics.size) { "dyn:$keyPrefix:$it" } }
    val images = remember(pics) { pics.map { ViewerImage(it.url, it.width, it.height) } }
    val open: (Int) -> Unit = { viewer.open(images, it, keys) }
    if (pics.size == 1) {
        val p = pics.first()
        val raw = if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height else 1f
        val ratio = raw.coerceIn(0.66f, 1.78f)
        val isLong = p.height > p.width * 2.5f && p.width > 0
        // Ask the CDN for the slot's crop (width AND height): a 长图 would otherwise
        // arrive at ~960×8000 and be decoded whole just to show its center.
        val wPx = with(density) { 480.dp.roundToPx() }
        val model = remember(p.url, wPx, ratio) { Images.thumb(p.url, wPx, (wPx / ratio).roundToInt()) }
        Box(
            modifier
                .fillMaxWidth(if (ratio < 1f) 0.66f else 1f)
                .widthIn(max = 520.dp)
                .aspectRatio(ratio)
                // Before the clip: the cell (badge included) gives way while its picture is up.
                .imageViewerOrigin(keys[0], PicShape)
                .clip(PicShape)
                .clickable(onClickLabel = "查看图片") { open(0) },
        ) {
            NetImage(model, Modifier.matchParentSize(), shape = PicShape, thumbWidth = null)
            if (isLong) CoverBadge("长图", Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        return
    }
    val tilePx = with(density) { 240.dp.roundToPx() }
    val cols = if (pics.size == 2 || pics.size == 4) 2 else 3
    Column(
        modifier.widthIn(max = if (cols == 2) 420.dp else 560.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        pics.chunked(cols).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { i, p ->
                    val index = rowIndex * cols + i
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .imageViewerOrigin(keys[index], PicShape)
                            .clip(PicShape)
                            .clickable(onClickLabel = "查看图片") { open(index) },
                    ) {
                        // Square tiles: let the CDN crop to a square instead of decoding a whole long image.
                        NetImage(Images.thumb(p.url, tilePx, tilePx), Modifier.matchParentSize(), shape = PicShape, thumbWidth = null)
                        if (p.width > 0 && p.height > p.width * 2.5f) {
                            CoverBadge("长图", Modifier.align(Alignment.BottomStart).padding(6.dp))
                        }
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
