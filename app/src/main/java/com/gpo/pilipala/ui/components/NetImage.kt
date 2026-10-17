package com.gpo.pilipala.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.theme.YoinArtworkShapes

/**
 * Network image with Yoin's rules: no border, a flat surface placeholder,
 * and the CDN asked for a size close to the slot ([thumbWidth]). Pass
 * [thumbHeight] too when the slot crops (long pictures in a clamped slot):
 * the CDN then crops to that box, so a 1080×9000 picture never arrives —
 * or decodes — whole.
 */
@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    shape: Shape = YoinArtworkShapes.Cover,
    thumbWidth: Dp? = 320.dp,
    contentScale: ContentScale = ContentScale.Crop,
    thumbHeight: Dp? = null,
) {
    val density = LocalDensity.current
    val model = if (thumbWidth != null) {
        with(density) { Images.thumb(url, thumbWidth.roundToPx(), thumbHeight?.roundToPx()) }
    } else {
        Images.normalize(url)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (model.isNotEmpty()) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Round avatar. */
@Composable
fun Avatar(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    NetImage(
        url = url,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = CircleShape,
        thumbWidth = size * 2,
    )
}
