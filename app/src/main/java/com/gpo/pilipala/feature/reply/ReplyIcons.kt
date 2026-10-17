package com.gpo.pilipala.feature.reply

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Glyphs missing from Yoin/Pili symbols, drawn in the same 24/1.5 round style. */
internal object ReplyIcons {
    private fun stroke(name: String, autoMirror: Boolean = false, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(
            name = "Reply.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        )
        paths.forEach {
            b.addPath(
                pathData = addPathNodes(it),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Emoji: ImageVector by lazy {
        stroke(
            "Emoji",
            false,
            "M12 3.5 C16.7 3.5 20.5 7.3 20.5 12 C20.5 16.7 16.7 20.5 12 20.5 C7.3 20.5 3.5 16.7 3.5 12 C3.5 7.3 7.3 3.5 12 3.5 Z",
            "M8.5 14 C9.3 15.4 10.6 16.2 12 16.2 C13.4 16.2 14.7 15.4 15.5 14",
            "M9 9.5 L9 10.5",
            "M15 9.5 L15 10.5",
        )
    }

    val Keyboard: ImageVector by lazy {
        stroke(
            "Keyboard",
            false,
            "M3.5 8 C3.5 6.9 4.4 6 5.5 6 L18.5 6 C19.6 6 20.5 6.9 20.5 8 L20.5 16 C20.5 17.1 19.6 18 18.5 18 L5.5 18 C4.4 18 3.5 17.1 3.5 16 Z",
            "M7 9.5 L7.01 9.5",
            "M10.3 9.5 L10.31 9.5",
            "M13.7 9.5 L13.71 9.5",
            "M17 9.5 L17.01 9.5",
            "M7 12.3 L7.01 12.3",
            "M10.3 12.3 L10.31 12.3",
            "M13.7 12.3 L13.71 12.3",
            "M17 12.3 L17.01 12.3",
            "M8.5 15 L15.5 15",
        )
    }

    val Reply: ImageVector by lazy {
        stroke(
            "Reply",
            true,
            "M9.5 6.5 L4.5 11.5 L9.5 16.5",
            "M4.5 11.5 L14 11.5 C17 11.5 19.5 14 19.5 17 L19.5 18",
        )
    }
}
