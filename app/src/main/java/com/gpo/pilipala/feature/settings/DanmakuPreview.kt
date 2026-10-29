package com.gpo.pilipala.feature.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlin.math.floor

private val PreviewLines = listOf(
    "前方高能" to 0.00f,
    "这个转场绝了" to 0.35f,
    "awsl" to 0.62f,
    "名场面" to 0.15f,
    "哈哈哈哈哈哈哈" to 0.80f,
    "泪目" to 0.48f,
    "下次一定" to 0.05f,
    "第一次来" to 0.70f,
)

/**
 * A small live strip that plays the current danmaku settings: scrolling lines move
 * right-to-left at the chosen speed, the area band limits their lanes, top/bottom
 * fixed lines appear unless blocked. The backdrop stands in for video (a dark scrim
 * tinted with the primary), so the danmaku are drawn as they would be over a picture.
 */
@Composable
internal fun DanmakuPreview(
    enabled: Boolean,
    opacity: Float,
    fontScale: Float,
    area: Float,
    speed: Float,
    hideTop: Boolean,
    hideBottom: Boolean,
    hideScroll: Boolean,
) {
    val shape = ContinuousRoundedCornerShape(20.dp)
    val primary = MaterialTheme.colorScheme.primary
    val backdrop = Brush.linearGradient(
        listOf(lerp(Color.Black, primary, 0.28f), lerp(Color.Black, MaterialTheme.colorScheme.tertiary, 0.18f)),
    )
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    val alpha by animateFloatAsState(if (enabled) opacity else 0f, YoinMotion.defaultEffectsSpec(), label = "dmAlpha")
    val offAlpha by animateFloatAsState(if (enabled) 0f else 1f, YoinMotion.defaultEffectsSpec(), label = "dmOff")
    val topAlpha by animateFloatAsState(if (hideTop) 0f else 1f, YoinMotion.defaultEffectsSpec(), label = "dmTop")
    val bottomAlpha by animateFloatAsState(if (hideBottom) 0f else 1f, YoinMotion.defaultEffectsSpec(), label = "dmBottom")
    val scrollAlpha by animateFloatAsState(if (hideScroll) 0f else 1f, YoinMotion.defaultEffectsSpec(), label = "dmScroll")

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 7f)
            .heightIn(max = 220.dp)
            .clip(shape)
            .background(backdrop),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val fontSize = 14.sp * fontScale
        val laneHeightPx = with(density) { (fontSize * 1.7f).toPx() }
        val bandHeight by animateDpAsState(maxHeight * area, YoinMotion.defaultSpatialSpec(), label = "dmBand")
        val style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), blurRadius = 4f),
        )

        // Area band: the part of the picture danmaku may use.
        Box(
            Modifier
                .fillMaxWidth()
                .height(bandHeight)
                .background(Color.White.copy(alpha = 0.06f * offAlpha.let { 1f - it })),
        )

        val lanes = floor((heightPx * area) / laneHeightPx).toInt().coerceAtLeast(1)
        val pxPerSecond = widthPx / 6f * speed
        val travel = widthPx + with(density) { 160.dp.toPx() }
        PreviewLines.forEachIndexed { i, (text, phase) ->
            val lane = i % lanes.coerceAtMost(PreviewLines.size)
            val laneTop = laneHeightPx * (lane + if (hideTop) 0 else 1)
            if (laneTop + laneHeightPx > heightPx * area + 1f) return@forEachIndexed
            Text(
                text,
                style = style,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .offset {
                        // Read the clock in the layout phase only: no recomposition per frame.
                        val x = widthPx - ((time * pxPerSecond + phase * travel) % travel)
                        IntOffset(x.toInt(), laneTop.toInt())
                    }
                    .graphicsLayer { this.alpha = alpha * scrollAlpha },
            )
        }

        Text(
            "顶部弹幕",
            style = style,
            color = MaterialTheme.colorScheme.primaryFixed,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer { this.alpha = alpha * topAlpha },
        )
        Text(
            "底部弹幕",
            style = style,
            color = MaterialTheme.colorScheme.tertiaryFixed,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .graphicsLayer { this.alpha = alpha * bottomAlpha },
        )

        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = offAlpha },
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(YoinSymbols.DanmakuOff, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(32.dp))
            Text("弹幕已关闭", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
        }
    }
}
