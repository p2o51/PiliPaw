package com.gpo.pilipala.feature.search

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion

/** Covers inside search cards: 12dp continuous corners (Yoin rule 7). */
internal val SearchCoverShape = ContinuousRoundedCornerShape(12.dp)

internal val ChipShape = ContinuousRoundedCornerShape(12.dp)

/** Private glyphs in the Yoin Symbols language (24 viewport, 1.5 stroke, round caps). */
internal object SearchGlyphs {
    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** North-west arrow: put a suggestion into the field without searching. */
    val Insert: ImageVector by lazy { stroke("SearchInsert", "M17.5 17.5 L7 7", "M7 15 L7 7 L15 7") }

    /** Verified mark: a badge circle with a tick. */
    val Verified: ImageVector by lazy {
        stroke(
            "SearchVerified",
            "M12 3.5 C16.7 3.5 20.5 7.3 20.5 12 C20.5 16.7 16.7 20.5 12 20.5 C7.3 20.5 3.5 16.7 3.5 12 C3.5 7.3 7.3 3.5 12 3.5 Z",
            "M8.5 12.2 L10.9 14.5 L15.5 9.8",
        )
    }
}

/**
 * Tonal chip (color block, never an outline). Selected = secondaryContainer,
 * resting = surfaceContainerHigh; the swap animates on the effects spring.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    maxWidth: Dp = 280.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        YoinMotion.defaultEffectsSpec(),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        YoinMotion.defaultEffectsSpec(),
        label = "chipContent",
    )
    Text(
        label,
        modifier = modifier
            .widthIn(max = maxWidth)
            .heightIn(min = 36.dp)
            .clip(ChipShape)
            .background(container)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Single-choice row as a connected M3 Expressive button group (ToggleButtons
 * with leading / middle / trailing shapes). Scrolls horizontally and bleeds to
 * the page edges with [contentPadding] matching the page margin.
 */
@Composable
internal fun <T> ConnectedChoiceRow(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        choices.forEachIndexed { i, c ->
            val checked = c.value == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = {
                    if (!checked) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(c.value)
                    }
                },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text(c.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

private val emRegex = Regex("""<em[^>]*>(.*?)</em>""", RegexOption.IGNORE_CASE)

/**
 * Builds a two-weight string from bilibili's `<em>` markup: matched parts in
 * [strong], the rest in [base]. Falls back to a case-insensitive match of
 * [query] when the markup carries no `<em>`.
 */
internal fun highlightEm(raw: String, query: String, base: SpanStyle, strong: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        if (emRegex.containsMatchIn(raw)) {
            var cursor = 0
            emRegex.findAll(raw).forEach { m ->
                if (m.range.first > cursor) {
                    withStyle(base) { append(com.gpo.pilipala.core.util.Fmt.stripTags(raw.substring(cursor, m.range.first))) }
                }
                withStyle(strong) { append(com.gpo.pilipala.core.util.Fmt.stripTags(m.groupValues[1])) }
                cursor = m.range.last + 1
            }
            if (cursor < raw.length) withStyle(base) { append(com.gpo.pilipala.core.util.Fmt.stripTags(raw.substring(cursor))) }
        } else {
            val text = com.gpo.pilipala.core.util.Fmt.stripTags(raw)
            val q = query.trim()
            val at = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
            if (at < 0) {
                withStyle(base) { append(text) }
            } else {
                withStyle(base) { append(text.substring(0, at)) }
                withStyle(strong) { append(text.substring(at, at + q.length)) }
                withStyle(base) { append(text.substring(at + q.length)) }
            }
        }
    }

/** Cross-fade for state swaps: effects spring in, fast effects spring out. */
@Composable
internal fun <S> fadeThrough(): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    return { fadeIn(enter) togetherWith fadeOut(exit) }
}
