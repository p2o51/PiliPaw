package com.gpo.pilipala.feature.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.message.MessagesScreen
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.extractor.YtNotification
import com.gpo.pilipala.yt.core.extractor.YtNotifications
import com.gpo.pilipala.yt.feature.player.Player

/**
 * 消息 (Home's top right). Bilibili's messages as they were; with YouTube signed in, two tabs at
 * the top, 哔哩哔哩 and YouTube (its notification bell), each its own list: the two inboxes are
 * different things, so they sit side by side instead of merged.
 */
@Composable
fun MessagesHome() {
    val profile by Accounts.youtube.collectAsState()
    if (profile !is Profile.SignedIn) {
        MessagesScreen()
        return
    }
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // The app's own segment row (Home's), so the two inboxes switch like Home's tabs do.
        com.gpo.pilipala.feature.home.SegmentTabs(
            labels = listOf("哔哩哔哩", "YouTube"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.statusBarsPadding().padding(vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            if (tab == 0) MessagesScreen() else YouTubeNotifications()
        }
    }
}

@Composable
private fun YouTubeNotifications() {
    var items by remember { mutableStateOf<List<YtNotification>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { YtNotifications.load() }
            .onSuccess { items = it }
            .onFailure { error = it.message ?: "网络错误" }
    }
    val list = items
    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            com.gpo.pilipala.ui.components.ErrorState(error.orEmpty(), onRetry = null)
        }
        list == null -> com.gpo.pilipala.ui.components.LoadingState()
        list.isEmpty() -> com.gpo.pilipala.ui.components.EmptyState("没有通知", icon = com.gpo.yoin.symbols.PiliSymbols.Mail)
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list) { n ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = n.videoUrl != null) { n.videoUrl?.let { Player.play(it) } }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!n.read) Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(n.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (n.thumbnail.isNotEmpty()) {
                        NetImage(n.thumbnail, Modifier.width(112.dp).aspectRatio(16f / 9f), shape = ContinuousRoundedCornerShape(10.dp), thumbWidth = 224.dp)
                    }
                }
            }
        }
    }
}
