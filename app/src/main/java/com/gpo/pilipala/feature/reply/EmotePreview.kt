package com.gpo.pilipala.feature.reply

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val CardShape = ContinuousRoundedCornerShape(24.dp)

/** M3 scrim opacity behind the inline preview. */
private const val ScrimAlpha = 0.32f

/**
 * An emote at preview size. [placeholder] is the small copy already on screen
 * (memory cache): it shows at once and gives way when the large one lands —
 * not underneath it, emotes are transparent. GIFs / animated WebP play.
 */
@Composable
internal fun EmoteImage(model: String, placeholder: String?, contentDescription: String?, modifier: Modifier = Modifier) {
    var loaded by remember(model) { mutableStateOf(false) }
    Box(modifier) {
        if (!loaded && !placeholder.isNullOrEmpty() && placeholder != model) {
            AsyncImage(
                model = placeholder,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize(),
            onSuccess = { loaded = true },
        )
    }
}

/** Image (or kaomoji) over its name, on a filled block. */
@Composable
private fun EmoteCard(
    model: String,
    placeholder: String?,
    name: String,
    sub: String,
    imageSize: Dp,
    modifier: Modifier = Modifier,
    kaomoji: String? = null,
    elevated: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .then(if (elevated) Modifier.shadow(6.dp, CardShape, clip = false) else Modifier)
            .background(if (elevated) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh, CardShape)
            .widthIn(min = 96.dp, max = 280.dp)
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (kaomoji != null) {
            Text(
                kaomoji,
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            EmoteImage(model, placeholder, name, Modifier.size(imageSize))
        }
        if (name.isNotEmpty() && kaomoji == null) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (sub.isNotEmpty()) {
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Inline emote preview (comment text) ─────────────────────────────────────

/** A tapped inline emote; [source] is its bounds on screen. */
@Immutable
internal class InlineEmotePreview(
    val model: String,
    val placeholder: String,
    val name: String,
    val pack: String,
    val big: Boolean,
    val source: Rect,
)

/**
 * Tap an emote in a comment: the card scales out of the emote (pivot on it)
 * on the expressive spatial spring over a scrim; a tap anywhere or back sends
 * it home the same way before the dialog goes.
 */
@Composable
internal fun InlineEmotePreviewDialog(preview: InlineEmotePreview, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    val popSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val homeSpec = YoinMotion.fastSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    val dismiss by rememberUpdatedState(onDismiss)
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                coroutineScope {
                    launch { fade.animateTo(0f, effects) }
                    pop.animateTo(0f, homeSpec)
                }
                dismiss()
            }
        }
    }
    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, effects) }
        pop.animateTo(1f, popSpec)
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The scrim is ours (it follows the card); the platform dim would double it.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val scrim = MaterialTheme.colorScheme.scrim
        val insets = WindowInsets.safeDrawing
        val imageSize = if (preview.big) 144.dp else 120.dp
        Layout(
            content = {
                EmoteCard(
                    model = preview.model,
                    placeholder = preview.placeholder,
                    name = preview.name,
                    sub = preview.pack,
                    imageSize = imageSize,
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(scrim, alpha = ScrimAlpha * fade.value.coerceIn(0f, 1f)) }
                .pointerInput(Unit) { detectTapGestures { close() } },
        ) { measurables, constraints ->
            val card = measurables.first().measure(Constraints())
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            layout(width, height) {
                val origin = coordinates?.positionOnScreen() ?: Offset.Zero
                val src = preview.source.translate(-origin)
                val margin = 16.dp.roundToPx()
                val gap = 12.dp.roundToPx()
                val minTop = insets.getTop(this@Layout) + margin
                val maxBottom = height - insets.getBottom(this@Layout) - margin
                val minX = insets.getLeft(this@Layout, layoutDirection) + margin
                val maxX = width - insets.getRight(this@Layout, layoutDirection) - margin - card.width
                val x = (src.center.x - card.width / 2f).roundToInt().coerceIn(minX, max(minX, maxX))
                var y = (src.top - gap - card.height).roundToInt()
                if (y < minTop) y = (src.bottom + gap).roundToInt().coerceAtMost(max(minTop, maxBottom - card.height))
                val pivot = TransformOrigin(
                    (src.center.x - x) / card.width.coerceAtLeast(1),
                    (src.center.y - y) / card.height.coerceAtLeast(1),
                )
                // At rest-home the image is the emote's own size.
                val home = (src.width / imageSize.toPx()).coerceIn(0.05f, 1f)
                card.placeWithLayer(x, y) {
                    val s = home + (1f - home) * pop.value
                    scaleX = s
                    scaleY = s
                    alpha = fade.value.coerceIn(0f, 1f)
                    transformOrigin = pivot
                }
            }
        }
    }
}

// ── Emote panel: press and hold ─────────────────────────────────────────────

/** The emote under a held finger in the composer's panel; [anchor] is its cell in the overlay's space. */
@Immutable
internal class EmotePeek(val emote: Emote, val anchor: Rect, val placeholder: String, val kaomoji: Boolean)

@Stable
internal class EmotePeekState {
    var current by mutableStateOf<EmotePeek?>(null)
        internal set

    /** The overlay's coordinates (the composer sheet's content box). */
    internal var host: LayoutCoordinates? = null
}

/**
 * Keyboard-style hold on an emote grid: after a long press the bubble follows
 * the finger from cell to cell (a tick per change); lifting only closes it —
 * the tap that inserts stays the cell's own. Events are taken in the Initial
 * pass once the hold starts, so neither the grid nor the sheet move under it.
 */
@Composable
internal fun Modifier.emotePeek(
    peek: EmotePeekState,
    gridState: LazyGridState,
    cells: Map<String, LayoutCoordinates>,
    emotes: List<Emote>,
    kaomoji: Boolean,
    placeholder: (Emote) -> String,
    haptic: HapticFeedback,
): Modifier {
    val grid = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val byText = remember(emotes) { emotes.associateBy { it.text } }
    val placeholderOf by rememberUpdatedState(placeholder)
    fun hit(position: Offset): EmotePeek? {
        val gridCoords = grid[0]?.takeIf { it.isAttached } ?: return null
        val host = peek.host?.takeIf { it.isAttached } ?: return null
        for (info in gridState.layoutInfo.visibleItemsInfo) {
            val key = info.key as? String ?: continue
            val cell = cells[key]?.takeIf { it.isAttached } ?: continue
            if (!gridCoords.localBoundingBoxOf(cell, clipBounds = false).contains(position)) continue
            val emote = byText[key] ?: return null
            return EmotePeek(emote, host.localBoundingBoxOf(cell, clipBounds = false), placeholderOf(emote), kaomoji)
        }
        return null
    }
    return this
        .onPlaced { grid[0] = it }
        .pointerInput(byText, kaomoji) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var released = false
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed || event.changes.size > 1 ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) {
                            released = true
                            break
                        }
                    }
                }
                // A tap (the cell inserts) or a scroll (the grid moves): not ours.
                if (released) return@awaitEachGesture
                var target = hit(down.position) ?: return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                peek.current = target
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val next = hit(change.position) ?: continue
                        if (next.emote.text != target.emote.text) {
                            target = next
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            peek.current = next
                        }
                    }
                } finally {
                    peek.current = null
                }
            }
        }
}

/** Scale the bubble starts from / returns to (relative to rest). */
private const val PeekHiddenScale = 0.4f

/**
 * The hold bubble, drawn over the whole composer sheet so the top row's
 * bubble can rise above the panel. It springs out of the held cell (pivot at
 * its bottom centre), glides cell to cell on the fast spatial spring, and
 * springs back in on release. Reads its animations only in placement / layer.
 */
@Composable
internal fun EmotePeekOverlay(peek: EmotePeekState, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val x = remember { Animatable(0f) }
    val y = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<EmotePeek?>(null) }
    val popSpec = YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val glideSpec = YoinMotion.fastSpatialSpec<Float>()
    val effects = YoinMotion.fastEffectsSpec<Float>()
    LaunchedEffect(peek) {
        var visible = false
        snapshotFlow { peek.current }.collect { target ->
            if (target != null) {
                shown = target
                if (!visible) {
                    visible = true
                    x.snapTo(target.anchor.center.x)
                    y.snapTo(target.anchor.top)
                    launch { scale.animateTo(1f, popSpec) }
                    launch { alpha.animateTo(1f, effects) }
                } else {
                    launch { x.animateTo(target.anchor.center.x, glideSpec) }
                    launch { y.animateTo(target.anchor.top, glideSpec) }
                }
            } else if (visible) {
                visible = false
                launch { scale.animateTo(0f, glideSpec) }
                launch { alpha.animateTo(0f, effects) }
            }
        }
    }
    val current = shown
    Layout(
        content = {
            if (current != null) {
                val big = current.emote.size >= 2
                EmoteCard(
                    model = previewModel(current.emote.url, if (big) 128.dp else 104.dp),
                    placeholder = current.placeholder,
                    name = current.emote.label,
                    sub = "",
                    imageSize = if (big) 128.dp else 104.dp,
                    kaomoji = if (current.kaomoji || current.emote.url.isEmpty()) current.emote.text else null,
                    elevated = true,
                )
            }
        },
        modifier = modifier.onPlaced { peek.host = it },
    ) { measurables, constraints ->
        val card = measurables.firstOrNull()?.measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (card == null) return@layout
            val margin = 8.dp.roundToPx()
            val cx = x.value
            val px = (cx - card.width / 2f).roundToInt()
                .coerceIn(margin, max(margin, constraints.maxWidth - margin - card.width))
            // The bubble's foot overlaps the held cell a little, like a key preview.
            val py = (y.value + 10.dp.toPx() - card.height).roundToInt()
            val pivot = TransformOrigin((cx - px) / card.width.coerceAtLeast(1), 1f)
            card.placeWithLayer(px, py) {
                val s = PeekHiddenScale + (1f - PeekHiddenScale) * scale.value
                scaleX = s
                scaleY = s
                this.alpha = alpha.value.coerceIn(0f, 1f)
                transformOrigin = pivot
            }
        }
    }
}

/** A CDN-sized copy for a preview slot; GIFs stay as they are (they animate). */
@Composable
internal fun previewModel(url: String, size: Dp): String {
    val density = LocalDensity.current
    return Images.thumb(url, with(density) { size.roundToPx() })
}
