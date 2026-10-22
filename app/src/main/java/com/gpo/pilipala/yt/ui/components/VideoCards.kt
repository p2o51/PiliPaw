package com.gpo.pilipala.yt.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route

// YouTube videos draw as PiliPaw's cards (ui/components/VideoCards.kt, through
// feature/sources/MixFilter.kt's toCard()); only the channel row is the YouTube part's own.

/** Covers in cards: 12dp continuous corners, bare (no border). */
val CardCoverShape = ContinuousRoundedCornerShape(12.dp)

/** A channel in a list (订阅), as PiliPaw's library user rows: avatar, name, subscribers. */
@Composable
fun ChannelRow(channel: ChannelItem, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(YoinContainerShapes.ListRow)
            .clickable { nav.push(Route.Channel(channel.url, channel.name, channel.avatar)) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(channel.avatar, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.subscribers >= 0) ValueLabel(Fmt.count(channel.subscribers), "订阅者")
        }
    }
}

/** A bold value and a quiet label ("12万" "订阅者"). [fame]: a popularity number (名声模式 hides it). */
@Composable
fun ValueLabel(value: String, label: String, modifier: Modifier = Modifier, fame: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        FameCount(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, revealable = false, hide = fame)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
