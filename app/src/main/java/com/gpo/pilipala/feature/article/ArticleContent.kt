package com.gpo.pilipala.feature.article

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.feature.dynamics.RichBuilder
import com.gpo.pilipala.feature.dynamics.RichContent
import com.gpo.pilipala.feature.dynamics.VoteCard
import com.gpo.pilipala.feature.dynamics.VoteSummary
import com.gpo.pilipala.feature.dynamics.normalizeUrl
import com.gpo.pilipala.feature.dynamics.voteSummaryOf
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ── Block model (shared by opus paragraphs and legacy HTML articles) ───────

internal sealed interface ArticleBlock {
    data class Paragraph(val content: RichContent, val align: TextAlign) : ArticleBlock
    data class Heading(val content: RichContent, val level: Int) : ArticleBlock
    data class Image(val url: String, val width: Int, val height: Int, val caption: String, val index: Int) : ArticleBlock
    data class Decor(val url: String, val width: Int, val height: Int) : ArticleBlock
    data class Quote(val children: List<ArticleBlock>) : ArticleBlock
    data class ListItem(val marker: String?, val level: Int, val content: RichContent) : ArticleBlock
    data class Code(val text: String) : ArticleBlock
    data class Card(val title: String, val subtitle: String, val cover: String, val url: String, val badge: String) : ArticleBlock
    /** An opus `LINK_CARD_TYPE_VOTE` ([dynamicId] = the opus id), or a legacy article's `data-vote-id` image. */
    data class Vote(val summary: VoteSummary, val dynamicId: String = "") : ArticleBlock
    data object Gap : ArticleBlock
}

internal class ParsedArticle(val blocks: List<ArticleBlock>, val images: List<String>)

/** Theme values the parsers need (they run off the main thread). */
internal class ParseStyle(val primary: Color, val bodySize: TextUnit)

// ── Opus paragraphs (module_content.paragraphs) ─────────────────────────────

/** [dynamicId]: the opus id (the same id as its dynamic), passed on to vote cards. */
internal fun parseOpusParagraphs(paragraphs: JsonArray, style: ParseStyle, dynamicId: String = ""): ParsedArticle {
    val images = mutableListOf<String>()
    val blocks = mutableListOf<ArticleBlock>()
    paragraphs.objects().forEach { parseParagraph(it, blocks, images, style, dynamicId) }
    return ParsedArticle(blocks, images)
}

private fun alignOf(p: JsonObject): TextAlign = when (p.obj("format")?.int("align") ?: p.int("align")) {
    1 -> TextAlign.Center
    2 -> TextAlign.End
    else -> TextAlign.Start
}

private fun parseParagraph(
    p: JsonObject,
    out: MutableList<ArticleBlock>,
    images: MutableList<String>,
    style: ParseStyle,
    dynamicId: String,
) {
    val heading = p.obj("heading")
    val text = p.obj("text")
    val pic = p.obj("pic")
    val line = p.obj("line")
    val quote = p.obj("blockquote")
    val list = p.obj("list")
    val code = p.obj("code")
    val card = p.obj("link_card")
    when {
        heading != null -> {
            val c = nodesToRich(heading.arr("nodes"), style)
            if (!c.isEmpty) out += ArticleBlock.Heading(c, heading.int("level", 2))
        }
        text != null -> {
            val c = nodesToRich(text.arr("nodes"), style)
            if (!c.isEmpty) out += ArticleBlock.Paragraph(c, alignOf(p)) else out += ArticleBlock.Gap
        }
        pic != null -> pic.arr("pics").objects().forEach {
            val url = it.str("url")
            if (url.isNotEmpty()) {
                out += ArticleBlock.Image(url, it.int("width"), it.int("height"), it.str("comment"), images.size)
                images += url
            }
        }
        line != null -> {
            val lp = line.obj("pic")
            if (lp != null && lp.str("url").isNotEmpty()) {
                out += ArticleBlock.Decor(lp.str("url"), lp.int("width"), lp.int("height", 20))
            } else {
                out += ArticleBlock.Gap
            }
        }
        quote != null -> {
            val children = mutableListOf<ArticleBlock>()
            quote.arr("children").objects().forEach { parseParagraph(it, children, images, style, dynamicId) }
            if (children.isNotEmpty()) out += ArticleBlock.Quote(children)
        }
        list != null -> parseList(list, 1, out, style)
        code != null -> out += ArticleBlock.Code(code.str("content"))
        card != null -> parseCard(card, dynamicId)?.let { out += it }
        else -> Unit
    }
}

private fun parseList(list: JsonObject, level: Int, out: MutableList<ArticleBlock>, style: ParseStyle) {
    val ordered = list.int("style") == 1
    list.arr("items").objects().forEach { item ->
        val c = nodesToRich(item.arr("nodes"), style)
        out += ArticleBlock.ListItem(if (ordered) "${item.int("order", 1)}." else null, item.int("level", level), c)
        item.arr("children").objects().forEach { child -> child.obj("list")?.let { parseList(it, level + 1, out, style) } }
    }
}

private fun parseCard(linkCard: JsonObject, dynamicId: String): ArticleBlock? {
    val card = linkCard.obj("card") ?: return null
    val type = card.str("type").removePrefix("LINK_CARD_TYPE_").lowercase()
    if (type == "vote") {
        return voteSummaryOf(card.obj("vote"), card.long("oid"))?.let { ArticleBlock.Vote(it, dynamicId) }
    }
    val inner: JsonObject = card.obj(type)
        ?: card.values.firstNotNullOfOrNull { (it as? JsonObject)?.takeIf { o -> o.str("title").isNotEmpty() } }
        ?: return null
    val url = inner.str("jump_url").ifEmpty { card.str("jump_url") }
    return ArticleBlock.Card(
        title = inner.str("title"),
        subtitle = inner.str("desc_second").ifEmpty { inner.str("sub_title") }.ifEmpty { inner.str("desc") }.ifEmpty { inner.str("desc1") },
        cover = inner.str("cover"),
        url = url,
        badge = inner.str("duration").ifEmpty { inner.str("head_text") },
    )
}

private fun nodesToRich(nodes: JsonArray, style: ParseStyle): RichContent {
    val b = RichBuilder(style.primary)
    nodes.objects().forEach { n ->
        when (n.str("type")) {
            "TEXT_NODE_TYPE_RICH" -> n.obj("rich")?.let { b.richNode(it) }
            "TEXT_NODE_TYPE_FORMULA" -> b.text(n.obj("formula").str("latex_content"))
            else -> n.obj("word")?.let { word(b, it, style) }
        }
    }
    return b.build()
}

private fun word(b: RichBuilder, w: JsonObject, style: ParseStyle) {
    val words = w.str("words")
    if (words.isEmpty()) return
    val s = w.obj("style")
    val fs = w.int("font_size", 17)
    val decorations = buildList {
        if (s.bool("underline")) add(TextDecoration.Underline)
        if (s.bool("strikethrough")) add(TextDecoration.LineThrough)
    }
    val span = SpanStyle(
        fontWeight = if (s.bool("bold")) FontWeight.Bold else null,
        fontStyle = if (s.bool("italic")) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        color = if (w.str("color").isNotEmpty()) style.primary else Color.Unspecified,
        background = if (s.str("background").isNotEmpty()) style.primary.copy(alpha = 0.12f) else Color.Unspecified,
        fontSize = if (fs > 18 && style.bodySize != TextUnit.Unspecified) (style.bodySize.value * fs / 17f).sp else TextUnit.Unspecified,
    )
    if (span == SpanStyle()) b.text(words) else b.styled(span, words)
}

/** First image URL anywhere under [el] (module_top banner). */
internal fun findImageUrl(el: JsonElement?): String? {
    when (el) {
        is JsonObject -> {
            val u = (el["url"] as? JsonPrimitive)?.content
            if (u != null && Regex("""\.(jpe?g|png|webp|gif)""", RegexOption.IGNORE_CASE).containsMatchIn(u)) return u
            el.values.forEach { v -> findImageUrl(v)?.let { return it } }
        }
        is JsonArray -> el.forEach { v -> findImageUrl(v)?.let { return it } }
        else -> Unit
    }
    return null
}

// ── Legacy HTML articles (readInfo.content) ─────────────────────────────────

private val tokenRegex = Regex("""<!--[\s\S]*?-->|<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|([^<]+)""")
private val attrRegex = Regex("""([a-zA-Z_:-]+)\s*=\s*("([^"]*)"|'([^']*)')""")

private fun attrs(raw: String): Map<String, String> =
    attrRegex.findAll(raw).associate { it.groupValues[1].lowercase() to (it.groupValues[3].ifEmpty { it.groupValues[4] }) }

internal fun decodeEntities(s: String): String {
    if (!s.contains('&')) return s
    return Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> when (e) {
                "nbsp" -> " "
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "hellip" -> "…"
                "mdash" -> "—"
                "ldquo" -> "“"
                "rdquo" -> "”"
                else -> m.value
            }
        }
    }
}

/**
 * Small HTML → blocks converter for bilibili article markup: p / h1-h6 /
 * strong / em / u / s / span(color/size) / a / br / figure+img+figcaption /
 * blockquote / ul / ol / li / pre / code / hr.
 */
internal fun parseHtml(html: String, style: ParseStyle): ParsedArticle {
    val root = mutableListOf<ArticleBlock>()
    val images = mutableListOf<String>()
    val containers = ArrayDeque<MutableList<ArticleBlock>>().apply { addLast(root) }
    val lists = ArrayDeque<IntArray>() // [ordered(0/1), counter]
    val open = ArrayDeque<Pair<String, Any>>() // tag → SpanStyle | link url
    var rb = RichBuilder(style.primary)
    var align = TextAlign.Start
    var heading = 0
    var liMarker: String? = null
    var liLevel = 1
    var inPre = false
    val pre = StringBuilder()
    var inCaption = false
    val caption = StringBuilder()

    fun reopen() {
        open.forEach { (_, v) ->
            when (v) {
                is SpanStyle -> rb.builder.pushStyle(v)
                is String -> rb.builder.pushLink(LinkAnnotation.Url(normalizeUrl(v), rb.linkStyles))
            }
        }
    }

    fun flush() {
        val c = rb.build(trimStart = true)
        val target = containers.last()
        if (!c.isEmpty) {
            target += when {
                heading > 0 -> ArticleBlock.Heading(c, heading)
                liMarker != null -> ArticleBlock.ListItem(liMarker!!.ifEmpty { null }, liLevel, c)
                else -> ArticleBlock.Paragraph(c, align)
            }
        }
        rb = RichBuilder(style.primary)
        reopen()
    }

    fun closeTag(tag: String) {
        val idx = open.indexOfLast { it.first == tag }
        if (idx < 0) return
        // Pop everything above and including the tag, then re-push the ones above.
        val above = open.toList().subList(idx + 1, open.size)
        repeat(open.size - idx) { rb.builder.pop() }
        while (open.size > idx) open.removeLast()
        above.forEach { (t, v) ->
            open.addLast(t to v)
            when (v) {
                is SpanStyle -> rb.builder.pushStyle(v)
                is String -> rb.builder.pushLink(LinkAnnotation.Url(normalizeUrl(v), rb.linkStyles))
            }
        }
    }

    fun pushStyle(tag: String, s: SpanStyle) {
        open.addLast(tag to s)
        rb.builder.pushStyle(s)
    }

    for (m in tokenRegex.findAll(html)) {
        if (m.value.startsWith("<!--")) continue
        val text = m.groups[4]?.value
        if (text != null) {
            when {
                inPre -> pre.append(decodeEntities(text))
                inCaption -> caption.append(decodeEntities(text))
                else -> {
                    val t = decodeEntities(text.replace(Regex("""\s+"""), " "))
                    if (t.isNotEmpty()) rb.text(t)
                }
            }
            continue
        }
        val closing = m.groupValues[1] == "/"
        val tag = m.groupValues[2].lowercase()
        val a = attrs(m.groupValues[3])
        when (tag) {
            "p", "div", "section" -> {
                flush()
                align = if (!closing && (a["style"].orEmpty().contains("center") || a["class"].orEmpty().contains("center"))) {
                    TextAlign.Center
                } else {
                    TextAlign.Start
                }
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flush()
                heading = if (closing) 0 else tag.drop(1).toInt()
            }
            "br" -> if (inPre) pre.append('\n') else rb.text("\n")
            "strong", "b" -> if (closing) closeTag(tag) else pushStyle(tag, SpanStyle(fontWeight = FontWeight.Bold))
            "em", "i" -> if (closing) closeTag(tag) else pushStyle(tag, SpanStyle(fontStyle = FontStyle.Italic))
            "u", "ins" -> if (closing) closeTag(tag) else pushStyle(tag, SpanStyle(textDecoration = TextDecoration.Underline))
            "s", "del", "strike" -> if (closing) closeTag(tag) else pushStyle(tag, SpanStyle(textDecoration = TextDecoration.LineThrough))
            "code" -> if (!inPre) {
                if (closing) closeTag(tag) else pushStyle(tag, SpanStyle(fontFamily = FontFamily.Monospace, background = style.primary.copy(alpha = 0.10f)))
            }
            "span", "font" -> if (closing) {
                closeTag(tag)
            } else {
                val cls = a["class"].orEmpty() + " " + a["style"].orEmpty()
                val size = Regex("""font-size-(\d+)""").find(cls)?.groupValues?.get(1)?.toIntOrNull()
                pushStyle(
                    tag,
                    SpanStyle(
                        color = if (cls.contains("color")) style.primary else Color.Unspecified,
                        fontSize = if (size != null && size > 17 && style.bodySize != TextUnit.Unspecified) (style.bodySize.value * size / 17f).sp else TextUnit.Unspecified,
                    ),
                )
            }
            "a" -> if (closing) {
                closeTag(tag)
            } else {
                val href = a["href"].orEmpty()
                if (href.isNotEmpty()) {
                    open.addLast(tag to href)
                    rb.builder.pushLink(LinkAnnotation.Url(normalizeUrl(href), rb.linkStyles))
                }
            }
            "img" -> {
                val src = a["data-src"].orEmpty().ifEmpty { a["src"].orEmpty() }
                val cls = a["class"].orEmpty()
                // <img class="vote-display" data-vote-id="…">: the editor's placeholder picture for a vote.
                val voteId = a["data-vote-id"]?.toLongOrNull()?.takeIf { it > 0 }
                if (voteId != null) {
                    flush()
                    containers.last() += ArticleBlock.Vote(VoteSummary(voteId, title = "", joinCount = 0L, endTime = 0L, status = 0, choiceCount = 1))
                } else if (src.isNotEmpty() && !src.startsWith("data:")) {
                    val w = a["width"]?.toIntOrNull() ?: 0
                    val h = a["height"]?.toIntOrNull() ?: 0
                    if (cls.contains("cut-off") || (w in 1..120 && h in 1..60)) {
                        flush()
                        containers.last() += ArticleBlock.Decor(src, w, h)
                    } else {
                        flush()
                        containers.last() += ArticleBlock.Image(src, w, h, "", images.size)
                        images += src
                    }
                }
            }
            "figure" -> flush()
            "figcaption" -> if (closing) {
                inCaption = false
                val cap = caption.toString().trim()
                caption.clear()
                val target = containers.last()
                val last = target.lastOrNull()
                if (cap.isNotEmpty() && last is ArticleBlock.Image) target[target.lastIndex] = last.copy(caption = cap)
            } else {
                inCaption = true
            }
            "blockquote" -> {
                flush()
                if (closing) {
                    if (containers.size > 1) {
                        val children = containers.removeLast()
                        if (children.isNotEmpty()) containers.last() += ArticleBlock.Quote(children)
                    }
                } else {
                    containers.addLast(mutableListOf())
                }
            }
            "ul", "ol" -> {
                flush()
                if (closing) lists.removeLastOrNull() else lists.addLast(intArrayOf(if (tag == "ol") 1 else 0, 0))
            }
            "li" -> {
                flush()
                if (closing) {
                    liMarker = null
                } else {
                    val l = lists.lastOrNull()
                    liLevel = lists.size.coerceAtLeast(1)
                    liMarker = if (l != null && l[0] == 1) {
                        l[1] += 1
                        "${l[1]}."
                    } else {
                        ""
                    }
                }
            }
            "pre" -> {
                flush()
                if (closing) {
                    if (pre.isNotBlank()) containers.last() += ArticleBlock.Code(pre.toString().trimEnd())
                    pre.clear()
                    inPre = false
                } else {
                    inPre = true
                }
            }
            "hr" -> {
                flush()
                containers.last() += ArticleBlock.Gap
            }
            else -> Unit
        }
    }
    flush()
    while (containers.size > 1) {
        val children = containers.removeLast()
        if (children.isNotEmpty()) containers.last() += ArticleBlock.Quote(children)
    }
    return ParsedArticle(root, images)
}

// ── Rendering ───────────────────────────────────────────────────────────────

private val ImageShape = ContinuousRoundedCornerShape(12.dp)

/** One block (non-lazy; quotes render their children inline). */
@Composable
internal fun ArticleBlockView(block: ArticleBlock, onImage: (Int) -> Unit, modifier: Modifier = Modifier) {
    val body = MaterialTheme.typography.bodyLarge
    when (block) {
        is ArticleBlock.Paragraph -> SelectionContainer(modifier) {
            Text(
                block.content.text,
                style = body.copy(lineHeight = body.fontSize * 1.75f),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = block.align,
                inlineContent = block.content.inline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is ArticleBlock.Heading -> SelectionContainer(modifier.padding(top = 8.dp)) {
            Text(
                block.content.text,
                style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                inlineContent = block.content.inline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is ArticleBlock.Image -> Column(
            modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ratio = if (block.width > 0 && block.height > 0) (block.width.toFloat() / block.height).coerceAtLeast(0.2f) else 16f / 9f
            val narrow = block.width in 1..399
            NetImage(
                block.url,
                Modifier
                    .then(if (narrow) Modifier.width(block.width.dp.coerceAtMost(400.dp)) else Modifier.fillMaxWidth())
                    .aspectRatio(ratio)
                    .clip(ImageShape)
                    .clickable { onImage(block.index) },
                shape = ImageShape,
                thumbWidth = 720.dp,
            )
            if (block.caption.isNotEmpty()) {
                Text(
                    block.caption,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        is ArticleBlock.Decor -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = if (block.width > 0 && block.height > 0) block.width.toFloat() / block.height else 20f
            NetImage(
                block.url,
                Modifier.fillMaxWidth().widthIn(max = 600.dp).aspectRatio(ratio).heightIn(max = 48.dp),
                shape = androidx.compose.ui.graphics.RectangleShape,
                thumbWidth = 600.dp,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
        is ArticleBlock.Quote -> Column(
            modifier
                .fillMaxWidth()
                .clip(YoinContainerShapes.Card)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            block.children.forEach { ArticleBlockView(it, onImage) }
        }
        is ArticleBlock.ListItem -> Row(
            modifier.fillMaxWidth().padding(start = ((block.level - 1).coerceAtLeast(0) * 20).dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (block.marker != null) {
                Text(block.marker, style = body, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            } else {
                Box(Modifier.padding(top = 10.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    block.content.text,
                    style = body.copy(lineHeight = body.fontSize * 1.6f),
                    inlineContent = block.content.inline,
                )
            }
        }
        is ArticleBlock.Code -> Box(
            modifier
                .fillMaxWidth()
                .clip(YoinContainerShapes.Card)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SelectionContainer {
                Text(block.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
        is ArticleBlock.Card -> {
            val nav = LocalNavigator.current
            Row(
                modifier
                    .fillMaxWidth()
                    .clip(YoinContainerShapes.Card)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable(enabled = block.url.isNotEmpty()) { nav.openUrl(normalizeUrl(block.url), block.title) }
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (block.cover.isNotEmpty()) {
                    NetImage(block.cover, Modifier.height(64.dp).aspectRatio(16f / 10f), shape = ContinuousRoundedCornerShape(8.dp), thumbWidth = 200.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(block.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (block.subtitle.isNotEmpty()) {
                        Text(
                            block.subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (block.badge.isNotEmpty()) Tag(block.badge)
            }
        }
        is ArticleBlock.Vote -> VoteCard(
            block.summary,
            modifier,
            dynamicId = block.dynamicId,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        )
        ArticleBlock.Gap -> Spacer(modifier.height(8.dp))
    }
}

// ── Header ──────────────────────────────────────────────────────────────────

internal data class ArticleHeaderData(
    val title: String,
    val banner: String,
    val authorMid: Long,
    val authorName: String,
    val authorFace: String,
    val time: String,
    val stats: List<Pair<ImageVector, Long>>,
    val collection: String,
)

@Composable
internal fun ArticleBanner(url: String, modifier: Modifier = Modifier) {
    NetImage(url, modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = YoinContainerShapes.Card, thumbWidth = 720.dp)
}

@Composable
internal fun ArticleTitle(title: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMeta(h: ArticleHeaderData, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (h.authorName.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    h.authorFace,
                    40.dp,
                    onClick = if (h.authorMid > 0) { { nav.push(Route.Member(h.authorMid, h.authorFace, h.authorName)) } } else null,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(h.authorName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (h.time.isNotEmpty()) {
                        Text(h.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val stats = h.stats.filter { it.second > 0 }
        if (stats.isNotEmpty() || h.collection.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                // 名声模式: the user's own article counts as their own.
                val self = FameMode.isSelf(h.authorMid)
                stats.forEach { (icon, n) -> Stat(icon, Fmt.count(n), self = self) }
                if (h.collection.isNotEmpty()) Tag(h.collection)
            }
        }
    }
}

/** Opus detail JSON (`data.item`) → header + paragraphs + comment subject. */
internal class OpusDoc(
    /** The opus id (`id_str`), which is also its dynamic's id. */
    val id: String,
    val header: ArticleHeaderData,
    val paragraphs: JsonArray,
    val commentOid: Long,
    val commentType: Int,
)

internal fun opusDoc(item: JsonObject): OpusDoc {
    val modules = item.arr("modules").objects()
    fun module(key: String): JsonObject? = modules.firstNotNullOfOrNull { it.obj(key) }
    val author = module("module_author")
    val stat = module("module_stat")
    val basic = item.obj("basic")
    val title = module("module_title").str("text").ifEmpty { basic.str("title").removeSuffix(" - 哔哩哔哩") }
    val header = ArticleHeaderData(
        title = title,
        banner = findImageUrl(module("module_top")).orEmpty(),
        authorMid = author.str("mid").toLongOrNull() ?: 0L,
        authorName = author.str("name"),
        authorFace = author.str("face"),
        time = author.str("pub_time"),
        stats = listOf(
            com.gpo.yoin.symbols.PiliSymbols.Comment to stat.obj("comment").str("count").toLongOrZero(),
            com.gpo.yoin.symbols.PiliSymbols.ThumbUp to stat.obj("like").str("count").toLongOrZero(),
            com.gpo.yoin.symbols.PiliSymbols.Forward to stat.obj("forward").str("count").toLongOrZero(),
        ),
        collection = module("module_collection").str("name"),
    )
    return OpusDoc(
        id = item.str("id_str"),
        header = header,
        paragraphs = module("module_content").arr("paragraphs"),
        commentOid = basic.str("comment_id_str").toLongOrNull() ?: 0L,
        commentType = basic.int("comment_type").let { if (it == 0) 11 else it },
    )
}

private fun String.toLongOrZero(): Long = toLongOrNull() ?: 0L

@Suppress("unused")
private fun JsonElement?.objOrNull(): JsonObject? = this.asObj
