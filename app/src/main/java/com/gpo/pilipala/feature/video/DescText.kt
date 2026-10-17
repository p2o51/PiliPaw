package com.gpo.pilipala.feature.video

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.theme.YoinMotion

private val linkRegex = Regex(
    """(https?://[^\s，。、）)】\]]+)|(BV[0-9A-Za-z]{10})|((?i)av\d{3,})|((?<![\d:])(?:\d{1,2}:)?\d{1,2}:\d{2}(?![\d:]))""",
)

/**
 * Rich description: URLs open through the navigator (native page when known),
 * BV/av ids open the video, "03:21" seeks the player, desc_v2 @mentions open the space.
 */
@Composable
internal fun rememberRichText(
    nodes: List<DescNode>,
    fallback: String,
    linkColor: Color,
    onSeek: ((Long) -> Unit)?,
): AnnotatedString {
    val nav = LocalNavigator.current
    return remember(nodes, fallback, linkColor, onSeek != null) {
        buildAnnotatedString {
            val styles = TextLinkStyles(SpanStyle(color = linkColor))
            val list = nodes.ifEmpty { listOf(DescNode(fallback, 1, 0)) }
            list.forEach { node ->
                if (node.type == 2 && node.bizId > 0) {
                    withLink(LinkAnnotation.Clickable("at", styles) { nav.push(Route.Member(node.bizId, name = node.text)) }) {
                        append("@${node.text}")
                    }
                    append(' ')
                } else {
                    appendLinkified(node.text, styles, nav, onSeek)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendLinkified(
    text: String,
    styles: TextLinkStyles,
    nav: Navigator,
    onSeek: ((Long) -> Unit)?,
) {
    var last = 0
    for (m in linkRegex.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val v = m.value
        when {
            m.groups[1] != null -> withLink(LinkAnnotation.Clickable("url", styles) { nav.openUrl(v) }) { append(v) }
            m.groups[2] != null -> withLink(LinkAnnotation.Clickable("bv", styles) { nav.push(Route.Video(v)) }) { append(v) }
            m.groups[3] != null -> {
                val aid = v.drop(2).toLongOrNull()
                if (aid != null) {
                    withLink(LinkAnnotation.Clickable("av", styles) { nav.push(Route.Video(IdUtils.av2bv(aid), aid = aid)) }) { append(v) }
                } else {
                    append(v)
                }
            }
            m.groups[4] != null && onSeek != null -> {
                val secs = Fmt.parseDuration(v)
                withLink(LinkAnnotation.Clickable("ts", styles) { onSeek(secs) }) { append(v) }
            }
            else -> append(v)
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

/**
 * Text that folds to [collapsedLines] and unfolds with the spatial spring.
 * Tapping anywhere outside a link toggles it.
 */
@Composable
internal fun ExpandableText(
    text: AnnotatedString,
    style: TextStyle,
    collapsedLines: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    expandedState: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    var localExpanded by rememberSaveable { mutableStateOf(false) }
    val expanded = expandedState ?: localExpanded
    Column(
        modifier
            .animateContentSize(YoinMotion.defaultSpatialSpec())
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (onToggle != null) onToggle() else localExpanded = !localExpanded
            },
    ) {
        Text(
            text,
            style = style,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
