package com.gpo.pilipala.feature.reply

import androidx.compose.ui.unit.TextUnit
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.rememberTagPlaceholder
import com.gpo.pilipala.ui.theme.YoinMotion

private val UrlRegex = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")
private val BvRegex = Regex("BV[0-9A-Za-z]{10}")
private val AvRegex = Regex("(?<![A-Za-z0-9])[aA][vV](\\d{2,})")
private val TimeRegex = Regex("(?<![\\d:：])(?:\\d{1,2}[:：])?[0-5]?\\d[:：][0-5]\\d(?![\\d:：])")
private val VoteRegex = Regex("""\{vote:.*?\}""")
private val CvRegex = Regex("^cv(\\d+)$")

private const val UP_TAG_ID = "tag:up"
private const val RELATION_TAG_ID = "tag:relation"

/** Optional leading "name UP" used by the sub-reply preview rows. */
internal data class RichPrefix(
    val name: String,
    val mid: Long,
    val face: String,
    val isUp: Boolean,
    /** The user's relation with them: 2 关注, 6 互关 (an inline tag after the name), 0 none. */
    val relation: Int = 0,
)

/** Inline emote sizes (em of the body text); 2+ = a package's big stickers, laid out larger as bilibili does. */
private const val EmoteEm = 1.4f
private const val BigEmoteEm = 3.2f

private val EmoteTapShape = RoundedCornerShape(6.dp)

/**
 * Comment body: inline emotes, @mentions, topics, jump-url keywords, BV/av ids,
 * URLs and (on video pages) mm:ss timestamps. Links are native [LinkAnnotation]s,
 * so taps outside them fall through to the row's own click / long-press.
 * Tapping an emote previews it ([InlineEmotePreviewDialog]); a long press on
 * it is the row's ([onEmoteLongPress]).
 */
@Composable
internal fun ReplyRichText(
    content: ReplyContent,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE,
    prefix: RichPrefix? = null,
    onTimestampClick: ((Long) -> Unit)? = null,
    onEmoteLongPress: (() -> Unit)? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val nav = LocalNavigator.current
    val primary = MaterialTheme.colorScheme.primary
    val tsCallback by rememberUpdatedState(onTimestampClick)
    val hasTs = onTimestampClick != null
    val text = remember(content, prefix, primary, hasTs) {
        buildReplyText(content, prefix, primary, nav, if (hasTs) { s: Long -> tsCallback?.invoke(s) } else null)
    }
    val density = LocalDensity.current
    var preview by remember { mutableStateOf<InlineEmotePreview?>(null) }
    val longPress by rememberUpdatedState(onEmoteLongPress)
    val hasLongPress = onEmoteLongPress != null
    // A relation tag known when the line first showed is simply there; one that lands later fades in.
    val fadeRelationTag = remember { (prefix?.relation ?: 0) == 0 }
    // Inline tags take exactly a header Tag's size, centred on the line's text.
    val upPlaceholder = rememberTagPlaceholder("UP")
    val relationPlaceholder = rememberTagPlaceholder(if (prefix?.relation == 6) "互关" else "关注")
    val inline = remember(content, prefix != null && prefix.isUp, prefix?.relation ?: 0, density, hasLongPress, upPlaceholder, relationPlaceholder) {
        buildMap {
            content.emotes.forEach { (key, emote) ->
                val big = emote.size >= 2
                val sizeEm = if (big) BigEmoteEm else EmoteEm
                val model = Images.thumb(emote.url, with(density) { (if (big) 48.dp else 24.dp).roundToPx() * 2 })
                val previewModel = Images.thumb(emote.url, with(density) { (if (big) 144.dp else 120.dp).roundToPx() })
                val name = emote.name.ifEmpty { key.removeSurrounding("[", "]") }
                put(
                    "emote:$key",
                    InlineTextContent(Placeholder(sizeEm.em, sizeEm.em, PlaceholderVerticalAlign.TextCenter)) {
                        InlineEmote(
                            model = model,
                            name = name,
                            onLongPress = if (hasLongPress) {
                                { longPress?.invoke() }
                            } else {
                                null
                            },
                            onOpen = { source ->
                                preview = InlineEmotePreview(previewModel, model, name, emote.pack, big, source)
                            },
                        )
                    },
                )
            }
            val relation = prefix?.relation ?: 0
            if (relation != 0) {
                put(
                    RELATION_TAG_ID,
                    InlineTextContent(relationPlaceholder) {
                        InlineRelationTag(relation, fadeRelationTag)
                    },
                )
            }
            if (prefix?.isUp == true) {
                put(
                    UP_TAG_ID,
                    InlineTextContent(upPlaceholder) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Tag(
                                "UP",
                                container = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    },
                )
            }
        }
    }
    Text(
        text = text,
        modifier = modifier,
        // A fixed line height can't make room for a big sticker (3.2em), so it would spill into
        // the lines around it: with big stickers the lines size to their content instead.
        style = if (content.emotes.values.any { it.size >= 2 }) style.copy(lineHeight = TextUnit.Unspecified) else style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        inlineContent = inline,
        onTextLayout = onTextLayout ?: {},
    )
    preview?.let { p ->
        InlineEmotePreviewDialog(p) { preview = null }
    }
}

/** The inline 关注 / 互关 tag after a preview line's name; [fade]: it landed after the line showed. */
@Composable
private fun InlineRelationTag(relation: Int, fade: Boolean) {
    val alpha = remember { Animatable(if (fade) 0f else 1f) }
    val spec = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(Unit) { alpha.animateTo(1f, spec) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value },
        contentAlignment = Alignment.Center,
    ) {
        if (relation == 6) {
            Tag(
                "互关",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else {
            Tag(
                "关注",
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** One inline emote: tap previews it out of its own bounds (on screen). */
@Composable
private fun InlineEmote(model: String, name: String, onLongPress: (() -> Unit)?, onOpen: (Rect) -> Unit) {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    AsyncImage(
        model = model,
        contentDescription = name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onPlaced { coords[0] = it }
            .clip(EmoteTapShape)
            .combinedClickable(
                onClickLabel = "预览",
                onLongClick = onLongPress,
                onClick = {
                    val c = coords[0]?.takeIf { it.isAttached } ?: return@combinedClickable
                    onOpen(Rect(c.positionOnScreen(), c.size.toSize()))
                },
            ),
    )
}

private fun buildReplyText(
    content: ReplyContent,
    prefix: RichPrefix?,
    primary: Color,
    nav: Navigator,
    onTimestamp: ((Long) -> Unit)?,
): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(style = SpanStyle(color = primary))
    fun link(label: String, onClick: () -> Unit) {
        withLink(LinkAnnotation.Clickable(tag = label, styles = linkStyle) { onClick() }) { append(label) }
    }

    if (prefix != null) {
        withLink(
            LinkAnnotation.Clickable(
                tag = "member",
                styles = TextLinkStyles(style = SpanStyle(color = primary, fontWeight = FontWeight.Medium)),
            ) { nav.push(Route.Member(prefix.mid, prefix.face, prefix.name)) },
        ) { append(prefix.name) }
        if (prefix.isUp) {
            append(' ')
            appendInlineContent(UP_TAG_ID, "UP")
        }
        if (prefix.relation != 0) {
            append(' ')
            appendInlineContent(RELATION_TAG_ID, if (prefix.relation == 6) "互关" else "关注")
        }
        append("  ")
    }

    if (content.voteTitle.isNotEmpty()) {
        link("投票 ${content.voteTitle}") { nav.openUrl(content.voteUrl, content.voteTitle) }
        append(' ')
    }
    val message = content.message.replace(VoteRegex, " ")

    val specials = buildList {
        addAll(content.emotes.keys)
        addAll(content.atNameToMid.keys.map { "@$it" })
        addAll(content.topics.map { "#$it#" })
        addAll(content.jumpUrls.keys)
    }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
    val pattern = (specials.map { Regex.escape(it) } + listOf(UrlRegex.pattern, BvRegex.pattern, AvRegex.pattern, TimeRegex.pattern))
        .joinToString("|")
    val regex = runCatching { Regex(pattern) }.getOrElse { Regex(listOf(UrlRegex.pattern, BvRegex.pattern).joinToString("|")) }

    val usedJumps = mutableSetOf<String>()
    var last = 0
    for (m in regex.findAll(message)) {
        if (m.range.first > last) append(message.substring(last, m.range.first))
        last = m.range.last + 1
        val v = m.value
        val emote = content.emotes[v]
        val atMid = if (v.startsWith("@")) content.atNameToMid[v.substring(1)] else null
        val jump = content.jumpUrls[v]
        when {
            emote != null -> appendInlineContent("emote:$v", v)
            atMid != null -> link(v) { nav.push(Route.Member(atMid, name = v.substring(1))) }
            v.length > 2 && v.startsWith("#") && v.endsWith("#") && v.substring(1, v.length - 1) in content.topics ->
                link(v) { nav.push(Route.SearchResult(v.substring(1, v.length - 1))) }
            jump != null && v !in usedJumps -> {
                usedJumps += v
                if (jump.appUrl.startsWith("bilibili://search")) {
                    // Search-word ads stay plain text (pilipala default).
                    append(v)
                } else {
                    link(jump.title) { openJump(nav, v, jump) }
                }
            }
            UrlRegex.matches(v) -> link(v) { nav.openUrl(v) }
            BvRegex.matches(v) -> link(v) { nav.push(Route.Video(bvid = v)) }
            AvRegex.matches(v) -> {
                val aid = AvRegex.find(v)?.groupValues?.get(1)?.toLongOrNull()
                if (aid != null) link(v) { nav.push(Route.Video(bvid = IdUtils.av2bv(aid), aid = aid)) } else append(v)
            }
            TimeRegex.matches(v) && onTimestamp != null -> {
                val normalized = v.replace('：', ':')
                link(normalized) { onTimestamp(Fmt.parseDuration(normalized)) }
            }
            else -> append(v)
        }
    }
    if (last < message.length) append(message.substring(last))

    // Jump urls the message does not contain (e.g. attached links).
    content.jumpUrls.filterKeys { it !in usedJumps && !message.contains(it) && !it.startsWith("bilibili://search") }
        .forEach { (key, jump) ->
            if (jump.appUrl.startsWith("bilibili://search")) return@forEach
            append(' ')
            link(jump.title) { openJump(nav, key, jump) }
        }
    if (content.noteUrl.isNotEmpty()) {
        append(' ')
        link("笔记") { nav.openUrl(content.noteUrl, "笔记") }
    }
}

private fun openJump(nav: Navigator, key: String, jump: ReplyJump) {
    val cv = CvRegex.find(key)?.groupValues?.get(1)?.toLongOrNull()
    when {
        key.startsWith("BV") -> nav.push(Route.Video(bvid = IdUtils.findBvid(key) ?: key))
        cv != null -> nav.push(Route.Read(cv, jump.title))
        key.startsWith("http") || key.startsWith("//") -> nav.openUrl(key, jump.title)
        jump.pcUrl.isNotEmpty() -> nav.openUrl(jump.pcUrl, jump.title)
        jump.appUrl.isNotEmpty() -> nav.openUrl(jump.appUrl, jump.title)
        else -> nav.openUrl(key, jump.title)
    }
}
