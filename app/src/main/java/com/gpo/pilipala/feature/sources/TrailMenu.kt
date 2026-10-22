package com.gpo.pilipala.feature.sources

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The video page's back menu (继续播放, with videos to go back to): it grows out of the back
 * button. [progress] 0 closed, 1 open; a predictive back drives it by hand.
 */
@Stable
class TrailMenuState(private val scope: CoroutineScope) {
    val progress = Animatable(0f)

    /** Open, or opening (a back that would now be 返回首页). */
    val isOpen: Boolean get() = progress.targetValue > 0.5f

    val shown: Boolean get() = progress.value > 0.001f

    /**
     * The page's own back is running (返回首页 by gesture: the page shrinks toward the bar): the
     * menu steps out of its way, and comes back if that back is cancelled.
     */
    var aside by androidx.compose.runtime.mutableStateOf(false)

    fun open() {
        MiniLog.event("back menu", "open")
        scope.launch { progress.animateTo(1f, Open) }
    }

    fun close() {
        scope.launch { progress.animateTo(0f, Close) }
    }

    /** Gone this frame (返回首页: the page itself leaves). */
    fun dismiss() {
        scope.launch { progress.snapTo(0f) }
    }

    internal suspend fun follow(p: Float) = progress.snapTo(p)

    internal suspend fun settle(open: Boolean) = progress.animateTo(if (open) 1f else 0f, if (open) Open else Close)

    private companion object {
        val Open = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.9f, stiffness = 520f)
        val Close = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 900f)
    }
}

@Composable
fun rememberTrailMenuState(): TrailMenuState {
    val scope = rememberCoroutineScope()
    return remember { TrailMenuState(scope) }
}

/**
 * Back on a video page with videos to go back to (继续播放): the menu opens instead of leaving,
 * following a predictive back's finger. While it is open this handler stands down, so the next
 * back is the page's own (返回首页: the video docks). Compose it before the page's own in-page
 * handlers (fullscreen, the immersive pager's) so those still outrank it.
 */
@Composable
fun TrailBackHandler(menu: TrailMenuState, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val open by remember(menu) { derivedStateOf { menu.isOpen } }
    PredictiveBackHandler(enabled = enabled && !open) { events ->
        try {
            events.collect { e -> menu.follow(YoinMotion.backGestureEasing.transform(e.progress) * 0.6f) }
            // Let go (or a back with no gesture): it opens.
            MiniLog.event("back menu", "open by back")
            menu.settle(true)
        } catch (c: CancellationException) {
            scope.launch { menu.settle(false) }
            throw c
        }
    }
}

/**
 * The menu itself, over the whole page: a light scrim (a tap on it closes the menu and stays),
 * and the card at the back button ([anchorX], [anchorY]: the button's top start, window dp):
 * the [entries] newest first, at most [WatchTrail.MenuEntries], then 返回首页 under a divider.
 */
@Composable
fun TrailMenu(
    menu: TrailMenuState,
    entries: List<TrailEntry>,
    anchorX: Dp,
    anchorY: Dp,
    onPick: (TrailEntry) -> Unit,
    onHome: () -> Unit,
) {
    if (!menu.shown || menu.aside) return
    val latestPick by rememberUpdatedState(onPick)
    val latestHome by rememberUpdatedState(onHome)
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = menu.progress.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu.close() },
        )
        Column(
            Modifier
                .offset(anchorX, anchorY)
                .width(292.dp)
                .graphicsLayer {
                    val p = menu.progress.value.coerceIn(0f, 1.05f)
                    // Out of the back button (its centre is 20dp in).
                    transformOrigin = TransformOrigin(20.dp.toPx() / 292.dp.toPx(), 0.06f)
                    scaleX = 0.12f + 0.88f * p
                    scaleY = 0.12f + 0.88f * p
                    alpha = (p * 2f).coerceIn(0f, 1f)
                }
                .shadow(12.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(scheme.surfaceContainer)
                // Taps inside the card are its own (not the scrim's close).
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(6.dp),
        ) {
            entries.take(WatchTrail.MenuEntries).forEach { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { latestPick(e) }
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NetImage(e.cover, Modifier.size(64.dp, 40.dp), shape = RoundedCornerShape(8.dp), thumbWidth = 160.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val f = if (e.durationMs > 0) (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(scheme.surfaceContainerHighest)) {
                            Box(Modifier.fillMaxWidth(f).height(3.dp).background(scheme.primary))
                        }
                        Text(
                            "${clock(e.positionMs)} / ${clock(e.durationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 4.dp), color = scheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                    .clickable { latestHome() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(YoinSymbols.Home, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("返回首页", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The page's back button has videos behind it (继续播放): it shows [TrailBackGlyph]'s dots. */
val LocalBackHasMore = androidx.compose.runtime.compositionLocalOf { false }

/** The back button with videos behind it: the arrow and three dots in one pill. */
@Composable
fun TrailBackGlyph(more: Boolean, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(YoinSymbols.Back, contentDescription = if (more) "返回菜单" else "返回", tint = tint, modifier = Modifier.size(22.dp))
        if (more) {
            Spacer(Modifier.width(3.dp))
            repeat(3) { i ->
                if (i > 0) Spacer(Modifier.width(3.dp))
                Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(tint))
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
