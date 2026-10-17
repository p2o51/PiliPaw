package com.gpo.pilipala.feature.dynamics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.core.util.Images
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import androidx.compose.animation.animateContentSize

/** Scheme for "查看图片" nodes; the [androidx.compose.ui.platform.UriHandler] of the card opens the viewer. */
internal const val PICS_SCHEME = "pili-pics:"

/** Annotated text + the inline emoji/icon slots it references. */
internal class RichContent(
    val text: AnnotatedString,
    val inline: Map<String, InlineTextContent>,
) {
    val isEmpty: Boolean get() = text.isBlank()
}

/**
 * Builder state shared by dynamics rich text and article (opus) paragraphs.
 * Links are [LinkAnnotation.Url]s — the host provides a UriHandler that routes
 * them through `Navigator.openUrl` (bilibili links open native pages).
 */
internal class RichBuilder(val primary: Color) {
    val builder = AnnotatedString.Builder()
    val inline = LinkedHashMap<String, InlineTextContent>()

    val linkStyles = TextLinkStyles(SpanStyle(color = primary))

    fun text(s: String) {
        builder.append(s)
    }

    fun link(url: String, label: String) {
        if (url.isBlank()) {
            builder.append(label)
            return
        }
        builder.withLink(LinkAnnotation.Url(normalizeUrl(url), linkStyles)) { append(label) }
    }

    fun styled(style: SpanStyle, s: String) {
        builder.withStyle(style) { append(s) }
    }

    fun icon(key: String, vector: ImageVector) {
        val id = "icon:$key"
        if (id !in inline) {
            inline[id] = InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) {
                Icon(vector, null, Modifier.fillMaxSize(), tint = primary)
            }
        }
        builder.appendInlineContent(id, "[$key]")
    }

    fun emoji(url: String, size: Int, alt: String) {
        if (url.isEmpty()) {
            builder.append(alt)
            return
        }
        val em = if (size >= 2) 1.9.em else 1.35.em
        val id = "emoji:$url:$size"
        if (id !in inline) {
            inline[id] = InlineTextContent(Placeholder(em, em, PlaceholderVerticalAlign.TextCenter)) {
                AsyncImage(
                    model = Images.normalize(url),
                    contentDescription = alt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        builder.appendInlineContent(id, alt)
    }

    /** One `RICH_TEXT_NODE_TYPE_*` node (dynamics desc, opus summary, opus TEXT_NODE_TYPE_RICH). */
    fun richNode(n: JsonObject, dynId: String = "") {
        val text = n.str("text").ifEmpty { n.str("orig_text") }
        val jump = n.str("jump_url")
        when (n.str("type")) {
            "RICH_TEXT_NODE_TYPE_TEXT" -> text(n.str("orig_text").ifEmpty { text })
            "RICH_TEXT_NODE_TYPE_EMOJI" -> {
                val e = n.obj("emoji")
                emoji(e.str("icon_url").ifEmpty { e.str("gif_url") }, e.int("size", 1), text)
            }
            "RICH_TEXT_NODE_TYPE_AT" -> link("https://space.bilibili.com/${n.str("rid")}", text)
            "RICH_TEXT_NODE_TYPE_TOPIC" -> link(jump, text)
            "RICH_TEXT_NODE_TYPE_WEB" -> {
                icon("link", PiliSymbols.Link)
                link(jump.ifEmpty { n.str("orig_text") }, text)
            }
            "RICH_TEXT_NODE_TYPE_BV" -> {
                icon("video", PiliSymbols.Video)
                val rid = n.str("rid")
                link(jump.ifEmpty { "https://www.bilibili.com/video/$rid" }, text)
            }
            "RICH_TEXT_NODE_TYPE_AV" -> {
                icon("video", PiliSymbols.Video)
                val rid = n.str("rid")
                val url = jump.ifEmpty {
                    rid.toLongOrNull()?.let { "https://www.bilibili.com/video/${IdUtils.av2bv(it)}" } ?: ""
                }
                link(url, text)
            }
            "RICH_TEXT_NODE_TYPE_VOTE" -> {
                icon("vote", PiliSymbols.Trophy)
                link(
                    "https://t.bilibili.com/vote/h5/index/#/result?vote_id=${n.str("rid")}&dynamic_id=$dynId&isWeb=1",
                    text,
                )
            }
            "RICH_TEXT_NODE_TYPE_LOTTERY" -> {
                icon("gift", PiliSymbols.Gift)
                link(
                    jump.ifEmpty { "https://www.bilibili.com/h5/lottery/result?business_id=$dynId&business_type=1&isWeb=1" },
                    text,
                )
            }
            "RICH_TEXT_NODE_TYPE_GOODS" -> {
                icon("goods", PiliSymbols.Gift)
                link(jump, text)
            }
            "RICH_TEXT_NODE_TYPE_VIEW_PICTURE" -> {
                icon("image", PiliSymbols.Image)
                val pics = n.arr("pics").objects().map { it.str("src").ifEmpty { it.str("url") } }.filter { it.isNotEmpty() }
                if (pics.isEmpty()) text(text) else link(PICS_SCHEME + pics.joinToString("|"), text)
            }
            "RICH_TEXT_NODE_TYPE_CV", "RICH_TEXT_NODE_TYPE_OPUS" -> {
                icon("article", PiliSymbols.Article)
                link(jump, text)
            }
            "RICH_TEXT_NODE_TYPE_OGV_SEASON", "RICH_TEXT_NODE_TYPE_OGV_EP" -> {
                icon("video", PiliSymbols.Video)
                link(jump, text)
            }
            else -> if (jump.isNotEmpty()) link(jump, text) else text(text)
        }
    }

    fun build(trimStart: Boolean = false): RichContent {
        var s = builder.toAnnotatedString()
        var end = s.length
        while (end > 0 && s[end - 1].isWhitespace()) end--
        var start = 0
        if (trimStart) while (start < end && s[start].isWhitespace()) start++
        if (start > 0 || end < s.length) s = s.subSequence(start, end)
        return RichContent(s, inline.toMap())
    }
}

/** Title + rich nodes of a dynamic (desc first, else the opus summary — Flutter `richNode`). */
internal fun richSource(item: JsonObject): Pair<String?, JsonArray?> {
    val dyn = item.dynModule
    dyn.obj("desc")?.let { return null to it.arr("rich_text_nodes") }
    val opus = item.major.obj("opus") ?: return null to null
    return opus.str("title").ifEmpty { null } to opus.obj("summary")?.arr("rich_text_nodes")
}

@Composable
internal fun rememberRichContent(item: JsonObject): RichContent {
    val primary = MaterialTheme.colorScheme.primary
    val titleSize: TextUnit = MaterialTheme.typography.titleMedium.fontSize
    return remember(item, primary) {
        val (title, nodes) = richSource(item)
        val b = RichBuilder(primary)
        if (title != null) {
            b.styled(SpanStyle(fontWeight = FontWeight.Bold, fontSize = titleSize), title)
            if (nodes != null && nodes.isNotEmpty()) b.text("\n")
        }
        nodes?.objects()?.forEach { b.richNode(it, item.dynId) }
        b.build()
    }
}

/**
 * Rich text with optional fold ("展开"/"收起") once it overflows [collapsedLines].
 * Size changes ride the spatial spring.
 */
@Composable
internal fun RichTextView(
    content: RichContent,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    collapsedLines: Int = Int.MAX_VALUE,
    expandable: Boolean = false,
) {
    if (content.isEmpty) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }
    Column(modifier.animateContentSize(YoinMotion.defaultSpatialSpec())) {
        Text(
            content.text,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            inlineContent = content.inline,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        )
        if (expandable && (overflowing || expanded)) {
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp),
            )
        }
    }
}
