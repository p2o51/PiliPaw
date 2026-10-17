package com.gpo.yoin.symbols

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke width of every Yoin symbol, in viewport units (24 x 24 viewport = dp at default size). */
public const val YoinSymbolStrokeWidth: Float = 1.5f

internal val Ink = SolidColor(Color.Black)

/** [autoMirror] flips a directional symbol (back, chevrons, send) in right-to-left layouts. */
internal fun symbol(name: String, autoMirror: Boolean = false, block: SymbolScope.() -> Unit): ImageVector {
    val builder = ImageVector.Builder(
        name = "YoinSymbols.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    )
    SymbolScope(builder).block()
    return builder.build()
}

internal class SymbolScope(private val builder: ImageVector.Builder) {

    fun stroke(pathData: String) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            fill = null,
            stroke = Ink,
            strokeLineWidth = YoinSymbolStrokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun fill(pathData: String, evenOdd: Boolean = false) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            fill = Ink,
        )
    }

    /** Fill plus a stroke of the same width, so a filled twin keeps the outline's outer edge. */
    fun fillStroke(pathData: String) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            fill = Ink,
            stroke = Ink,
            strokeLineWidth = YoinSymbolStrokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun group(name: String, block: SymbolScope.() -> Unit) {
        builder.addGroup(name = name)
        block()
        builder.clearGroup()
    }
}
