package com.gpo.pilipala.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Action blocks for menu sheets, so a sheet reads in tiers instead of one stack of equal rows:
 * a wide [SheetHeroAction] beside square [SheetTileAction]s, a lighter row of round
 * [SheetIconAction]s, then quiet [SheetChipAction]s. A write that lands answers inside the
 * sheet first ([SheetFinisher]: the block turns into a tick and its done label), then the
 * sheet slides away; it never just vanishes into a snackbar.
 */

private val TileShape = ContinuousRoundedCornerShape(20.dp)

/** How long a done block stays on screen before the sheet slides down. */
private const val DoneHoldMillis = 820L

/**
 * Ends a sheet after an action: [finish] marks the action's block done (tick + label, with a
 * Confirm haptic), holds it a beat, then hides the sheet and calls `onDismiss` and `then`.
 * [close] is the plain slide-away. Blocks read [doneLabel] for their own key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class SheetFinisher internal constructor(
    private val scope: CoroutineScope,
    private val state: SheetState,
    private val dismiss: () -> Unit,
    private val confirm: () -> Unit,
) {
    var doneKey by mutableStateOf<String?>(null)
        private set
    private var label by mutableStateOf("")

    /** True once a block is done: the rest of the sheet stops taking taps. */
    val finishing: Boolean get() = doneKey != null

    fun doneLabel(key: String): String? = if (doneKey == key) label else null

    fun finish(key: String, doneLabel: String, then: () -> Unit = {}) {
        if (doneKey != null) return
        doneKey = key
        label = doneLabel
        confirm()
        scope.launch {
            delay(DoneHoldMillis)
            state.hide()
        }.invokeOnCompletion {
            dismiss()
            then()
        }
    }

    fun close(then: () -> Unit = {}) {
        scope.launch { state.hide() }.invokeOnCompletion {
            dismiss()
            then()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetFinisher(state: SheetState, onDismiss: () -> Unit): SheetFinisher {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val dismiss by rememberUpdatedState(onDismiss)
    return remember(state) {
        SheetFinisher(scope, state, { dismiss() }, { haptics.performHapticFeedback(HapticFeedbackType.Confirm) })
    }
}

/** The block's colours, easing to the done colours when [done]. */
@Composable
private fun blockColors(done: Boolean, container: Color, content: Color): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val spec = YoinMotion.defaultEffectsSpec<Color>()
    val c by animateColorAsState(if (done) scheme.primary else container, spec, label = "blockContainer")
    val t by animateColorAsState(if (done) scheme.onPrimary else content, spec, label = "blockContent")
    return c to t
}

/** [icon], or a tick that pops in once [done]. */
@Composable
private fun DoneIcon(icon: ImageVector, done: Boolean, tint: Color, size: Dp) {
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val pop = YoinMotion.fastSpatialSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = done,
        transitionSpec = { (fadeIn(enter) + scaleIn(pop, initialScale = 0.4f)) togetherWith fadeOut(exit) },
        label = "doneIcon",
    ) { d -> Icon(if (d) YoinSymbols.Check else icon, null, Modifier.size(size), tint = tint) }
}

@Composable
private fun DoneText(text: String, done: String?, color: Color, style: androidx.compose.ui.text.TextStyle, align: TextAlign? = null) {
    // One label at a time: the old one is gone before the new one fades in, so the two never
    // sit on top of each other mid-swap.
    AnimatedContent(
        targetState = done ?: text,
        transitionSpec = { fadeIn(tween(180, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
        label = "doneText",
    ) { t -> Text(t, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align) }
}

/** The sheet's main action: a wide accent block, icon beside its label. */
@Composable
fun SheetHeroAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    done: String? = null,
    enabled: Boolean = true,
    height: Dp = 76.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = blockColors(done != null, scheme.primaryContainer, scheme.onPrimaryContainer)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = TileShape,
        color = container,
        contentColor = content,
        modifier = modifier.height(height),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DoneIcon(icon, done != null, content, 26.dp)
            Spacer(Modifier.width(14.dp))
            DoneText(label, done, content, MaterialTheme.typography.titleMedium)
        }
    }
}

/** A square-ish block: icon over a short label. */
@Composable
fun SheetTileAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    done: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    quiet: Boolean = false,
    height: Dp = 76.dp,
) {
    val scheme = MaterialTheme.colorScheme
    // A quiet tile (不感兴趣, 删除…) keeps its place in the row but only an outline, so a
    // turning-away action never weighs as much as the ones beside it.
    val (container, content) = blockColors(
        done != null,
        if (quiet) Color.Transparent else scheme.surfaceContainerHighest,
        when {
            destructive -> scheme.error
            quiet -> scheme.onSurfaceVariant
            else -> scheme.onSurface
        },
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = TileShape,
        color = container,
        contentColor = content,
        border = if (quiet && done == null) androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant) else null,
        modifier = modifier.height(height),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            DoneIcon(icon, done != null, if (done != null || destructive) content else scheme.onSurfaceVariant, 24.dp)
            Spacer(Modifier.height(6.dp))
            DoneText(label, done, content, MaterialTheme.typography.labelLarge, TextAlign.Center)
        }
    }
}

/** A light round action with its label under it. */
@Composable
fun SheetIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    done: String? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = blockColors(done != null, scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
    Column(modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { DoneIcon(icon, done != null, content, 22.dp) }
        }
        Spacer(Modifier.height(6.dp))
        DoneText(label, done, scheme.onSurfaceVariant, MaterialTheme.typography.labelMedium, TextAlign.Center)
    }
}

/** A quiet pill at the end of a sheet (不感兴趣, 拉黑…); [destructive] ones take the error colour. */
@Composable
fun SheetChipAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (destructive) scheme.error else scheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (destructive) scheme.errorContainer.copy(alpha = 0.45f) else scheme.surfaceContainerHigh,
        contentColor = tint,
        modifier = modifier.height(44.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (destructive) tint else scheme.onSurface, maxLines = 1)
        }
    }
}

/**
 * Tier [index] of a sheet rising in: each tier fades up a few dp, a beat after the one above,
 * so the sheet settles from the top down instead of landing as one slab.
 */
@Composable
fun Modifier.sheetStagger(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    val lift = with(LocalDensity.current) { 14.dp.toPx() }
    val spec = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(40L + index * 35L)
        progress.animateTo(1f, spec)
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * lift
    }
}

/** Row of [SheetIconAction]s, left to right. */
@Composable
fun SheetIconRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Spread across the width: a short row left-aligned reads as a row missing its last item.
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) { content() }
}

/** A sheet button's label: [text], or a popped-in tick and [done] once the action landed. */
@Composable
fun SheetButtonLabel(text: String, done: String?) {
    val content = androidx.compose.material3.LocalContentColor.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (done != null) {
            DoneIcon(YoinSymbols.Check, true, content, 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        DoneText(text, done, content, MaterialTheme.typography.titleSmall)
    }
}
