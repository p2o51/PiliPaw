package com.gpo.pilipala.yt.ui.components

import com.gpo.pilipala.ui.components.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.mute.Mutes
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

/**
 * A video card's long-press menu (PiliPaw's VideoCardMenu, lighter): 稍后再看, 加入播放列表, and
 * 不看此频道 at the end in the error colour.
 */
@Composable
fun VideoMenuSheet(video: VideoItem, onDismiss: () -> Unit) {
    val toast = rememberToast()
    var playlists by remember { mutableStateOf(false) }
    if (playlists) {
        com.gpo.pilipala.feature.sources.SaveToListSheet(com.gpo.pilipala.yt.feature.library.Library.itemOf(video), onDismiss = onDismiss)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
        Column(Modifier.fillMaxWidth().padding(bottom = sheetBottomPadding())) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            MenuRow(PiliSymbols.WatchLater, if (Library.inWatchLater(video.url)) "移出稍后再看" else "稍后再看") {
                toast(if (Library.toggleWatchLater(video)) "已加入稍后再看" else "已移出稍后再看")
                onDismiss()
            }
            MenuRow(YoinSymbols.LibraryAdd, "加入到列表") { playlists = true }
            if (video.channelUrl.isNotEmpty()) {
                MenuRow(YoinSymbols.VisibilityOff, "不看此频道「${video.channelName}」", MaterialTheme.colorScheme.error) {
                    Mutes.muteChannel(video.channelUrl, video.channelName, video.channelAvatar)
                    toast("已屏蔽「${video.channelName}」")
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = tint)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
