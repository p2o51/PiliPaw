package com.gpo.pilipala.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.pilipala.ui.components.centered

/** One row of the live chat: danmaku bubble, super chat card, gift or guard row. */
@Composable
internal fun LiveChatItem(chat: LiveChat, roomEmotes: Map<String, LiveEmote>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        when (chat.kind) {
            LiveChatKind.Chat -> ChatBubble(chat, roomEmotes)
            LiveChatKind.SuperChat -> SuperChatCard(chat, roomEmotes)
            LiveChatKind.Gift -> GiftRow(chat)
            LiveChatKind.Guard -> GuardRow(chat)
            LiveChatKind.System -> Text(
                chat.text,
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatBubble(chat: LiveChat, roomEmotes: Map<String, LiveEmote>) {
    Column(
        Modifier
            .widthIn(max = 520.dp)
            .clip(ContinuousRoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            chat.medal?.let { MedalChip(it) }
            Text(
                chat.name.ifEmpty { "匿名" },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val sticker = chat.sticker
        var stickerFailed by remember(sticker?.url) { mutableStateOf(false) }
        if (sticker != null && !stickerFailed) {
            Sticker(sticker, chat.text) { stickerFailed = true }
        } else {
            EmoteText(
                chat.text,
                chat.emots,
                roomEmotes,
                MaterialTheme.typography.bodyMedium,
                MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Whole-message sticker, sized from its own aspect ratio; [onError] falls back to the text. */
@Composable
private fun Sticker(e: LiveEmote, desc: String, onError: () -> Unit) {
    val h = 72.dp
    val w = (h * e.ratio.coerceIn(0.6f, 3f)).coerceAtMost(200.dp)
    val px = with(LocalDensity.current) { w.roundToPx() }
    AsyncImage(
        model = Images.thumb(e.url, px),
        contentDescription = desc,
        contentScale = ContentScale.Fit,
        onError = { onError() },
        modifier = Modifier.size(w, h),
    )
}

/** Text with `[code]` emotes drawn inline at text height. */
@Composable
internal fun EmoteText(
    text: String,
    emots: Map<String, LiveEmote>,
    roomEmotes: Map<String, LiveEmote>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val segments = remember(text, emots, roomEmotes) { LiveMessages.segments(text, emots, roomEmotes) }
    if (segments.none { it.second != null }) {
        Text(text, modifier, style = style, color = color)
        return
    }
    val inline = HashMap<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        segments.forEach { (s, e) ->
            if (e == null) {
                append(s)
            } else {
                appendInlineContent(s, s)
                inline.getOrPut(s) {
                    InlineTextContent(
                        Placeholder((1.4f * e.ratio.coerceIn(0.5f, 3f)).em, 1.4f.em, PlaceholderVerticalAlign.TextCenter),
                    ) {
                        AsyncImage(
                            model = Images.thumb(e.url, 72),
                            contentDescription = s,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
    Text(annotated, modifier, style = style, color = color, inlineContent = inline)
}

/**
 * Fan medal: name block + level block; guards get the tertiary pair, unlit medals go quiet.
 * On a row that is already a secondary block, [onSecondary] lifts the name block off it.
 */
@Composable
private fun MedalChip(m: LiveMedal, onSecondary: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val (container, content, levelBg, levelFg) = when {
        !m.lit -> listOf(cs.surfaceContainerHigh, cs.onSurfaceVariant, cs.outlineVariant, cs.onSurface)
        m.guardLevel in 1..3 -> listOf(cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary)
        onSecondary -> listOf(cs.surfaceContainerLowest, cs.onSurface, cs.secondary, cs.onSecondary)
        else -> listOf(cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary)
    }
    Row(
        Modifier.height(18.dp).clip(ContinuousRoundedCornerShape(6.dp)).background(container),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            m.name,
            modifier = Modifier.padding(start = 6.dp, end = 4.dp),
            style = MaterialTheme.typography.labelSmall.centered(),
            color = content,
            maxLines = 1,
        )
        Box(
            Modifier.height(18.dp).widthIn(min = 18.dp).background(levelBg).padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                m.level.toString(),
                style = MaterialTheme.typography.labelSmall.centered(),
                fontWeight = FontWeight.Bold,
                color = levelFg,
            )
        }
    }
}

@Composable
private fun SuperChatCard(chat: LiveChat, roomEmotes: Map<String, LiveEmote>) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(YoinContainerShapes.Card)) {
        Row(
            Modifier.fillMaxWidth().background(cs.tertiary).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(chat.face, 28.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    chat.name.ifEmpty { "匿名" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                chat.medal?.let { MedalChip(it) }
            }
            if (chat.price.isNotEmpty()) {
                Text(
                    "¥${chat.price}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.onTertiary,
                )
            }
        }
        EmoteText(
            chat.text,
            chat.emots,
            roomEmotes,
            MaterialTheme.typography.bodyMedium,
            cs.onTertiaryContainer,
            Modifier.fillMaxWidth().background(cs.tertiaryContainer).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun GiftRow(chat: LiveChat) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .widthIn(max = 520.dp)
            .heightIn(min = 36.dp)
            .clip(ContinuousRoundedCornerShape(14.dp))
            .background(cs.secondaryContainer)
            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (chat.giftIcon.isNotEmpty()) {
            AsyncImage(
                model = Images.thumb(chat.giftIcon, 84),
                contentDescription = chat.giftName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Icon(YoinSymbols.Gift, null, Modifier.padding(4.dp).size(20.dp), tint = cs.secondary)
        }
        chat.medal?.let { MedalChip(it, onSecondary = true) }
        Text(
            chat.name.ifEmpty { "匿名" },
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(chat.action, style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer.copy(alpha = 0.72f))
        Text(
            chat.giftName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSecondaryContainer,
            maxLines = 1,
        )
        Text(
            "×${chat.giftNum}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = cs.secondary,
        )
        if (chat.price.isNotEmpty()) {
            Text(chat.price, style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun GuardRow(chat: LiveChat) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .widthIn(max = 520.dp)
            .heightIn(min = 36.dp)
            .clip(ContinuousRoundedCornerShape(14.dp))
            .background(cs.tertiaryContainer)
            .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(24.dp).clip(ContinuousRoundedCornerShape(8.dp)).background(cs.tertiary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(YoinSymbols.StarFilled, null, Modifier.size(16.dp), tint = cs.onTertiary)
        }
        Text(
            chat.name.ifEmpty { "匿名" },
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(chat.action, style = MaterialTheme.typography.labelMedium, color = cs.onTertiaryContainer.copy(alpha = 0.72f))
        Text(
            chat.giftName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = cs.tertiary,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            "${chat.giftNum}${chat.price}",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onTertiaryContainer,
        )
    }
}
