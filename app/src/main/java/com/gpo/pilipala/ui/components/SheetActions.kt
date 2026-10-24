package com.gpo.pilipala.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Actions for menu sheets, by M3's button rules: [SheetGroupButton]s in a standard button
 * group (tonal for the main action, outlined beside it) and a lighter row of round
 * [SheetIconAction]s. A write that lands answers inside the sheet first ([SheetFinisher]: the
 * button turns into a tick and its done label), then the sheet slides away; it never just
 * vanishes into a snackbar.
 */

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

/**
 * How loud a [SheetGroupButton] is (M3 button emphasis): [Tonal] for the action a sheet is
 * mostly opened for, [Outlined] for the ones beside it and for turning-away actions.
 */
enum class SheetEmphasis { Tonal, Outlined }

/** Space between the buttons of a standard button group (M3: 8dp for medium buttons). */
val SheetGroupSpacing = 8.dp

/**
 * One button of a standard button group in a sheet: icon over label, each button keeping its
 * own shape (the corners morph on press). Done, it fills with the accent and shows a tick and
 * [done] until the sheet goes.
 */
@Composable
fun SheetGroupButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    done: String? = null,
    enabled: Boolean = true,
    emphasis: SheetEmphasis = SheetEmphasis.Tonal,
    destructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val tonal = emphasis == SheetEmphasis.Tonal
    val (container, content) = blockColors(
        done != null,
        if (tonal) scheme.secondaryContainer else Color.Transparent,
        when {
            destructive -> scheme.error
            tonal -> scheme.onSecondaryContainer
            else -> scheme.onSurface
        },
    )
    // While the sheet is finishing every button is disabled; keep the colours so nothing greys out.
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier.heightIn(min = 64.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container,
            disabledContentColor = content,
        ),
        border = if (!tonal && done == null) BorderStroke(1.dp, scheme.outlineVariant) else null,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DoneIcon(icon, done != null, content, 22.dp)
            Spacer(Modifier.height(4.dp))
            DoneText(label, done, content, MaterialTheme.typography.labelLarge, TextAlign.Center)
        }
    }
}

/**
 * A negative or destructive action at the end of a menu sheet (不感兴趣, 举报, 拉黑): an
 * outlined button, apart from the everyday rows above it. [destructive] tints it error.
 */
@Composable
fun SheetOutlinedAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier.height(48.dp),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
    ) {
        androidx.compose.material3.Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
