package com.gpo.pilipala.feature.sources

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.library.FavFolder
import com.gpo.pilipala.feature.library.FolderCard
import com.gpo.pilipala.feature.library.LibraryScaffold
import com.gpo.pilipala.feature.library.sectionHeader
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.extractor.YtAccount
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.yoin.symbols.YoinSymbols

/**
 * The signed-in account's playlists, kept for the whole session so 收藏 comes back exactly as it
 * was left (a refresh only swaps them in place). Null: not loaded yet.
 */
object YtLibraryCache {
    private val _lists = kotlinx.coroutines.flow.MutableStateFlow<List<YtAccount.Playlist>?>(null)
    val lists: kotlinx.coroutines.flow.StateFlow<List<YtAccount.Playlist>?> = _lists

    suspend fun refresh() {
        runCatching { YtAccount.libraryPlaylists() }.onSuccess { _lists.value = it }
            .onFailure { if (_lists.value == null) _lists.value = emptyList() }
    }

    fun clear() {
        _lists.value = null
    }
}

/**
 * The signed-in YouTube account's playlists in 收藏, as cards like the 收藏夹 below them. Until
 * the first load lands ([lists] null) two blank cards hold the place, so nothing below jumps.
 */
internal fun LazyGridScope.youtubeSection(lists: List<YtAccount.Playlist>?, onOpen: (YtAccount.Playlist) -> Unit) {
    if (lists != null && lists.isEmpty()) return
    sectionHeader("h_yt", "YouTube")
    if (lists == null) {
        items(2, key = { "y_hold$it" }) {
            FolderCard(FavFolder(id = -1L - it, title = "", cover = "", mediaCount = 0, attr = 2), modifier = Modifier.alpha(0.4f), onClick = {})
        }
        return
    }
    items(lists, key = { "y${it.id}" }) { pl ->
        FolderCard(
            FavFolder(id = pl.id.hashCode().toLong(), title = pl.title, cover = pl.thumbnail, mediaCount = pl.count.filter(Char::isDigit).toIntOrNull() ?: 0, attr = 2),
            route = Route.YtPlaylist(pl.url, pl.title, pl.thumbnail),
            onClick = { onOpen(pl) },
        )
    }
}

/** A YouTube playlist: its videos in order; a row plays in the YouTube player over this page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YtPlaylistScreen(route: Route.YtPlaylist) {
    var videos by remember { mutableStateOf<List<VideoItem>?>(null) }
    var title by remember { mutableStateOf(route.title) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route.url) {
        runCatching { Yt.playlist(route.url) }
            .onSuccess { (t, v) ->
                if (t.isNotEmpty()) title = t
                videos = v
            }
            .onFailure { error = it.friendlyMessage() }
    }
    CoverTheme(route.cover.ifEmpty { videos?.firstOrNull()?.thumbnail }) {
        LibraryScaffold(title = title.ifEmpty { "播放列表" }, subtitle = videos?.let { "${it.size} 个视频" }) { padding ->
            val list = videos
            when {
                error != null -> ErrorState(error.orEmpty(), Modifier.padding(padding))
                list == null -> LoadingState(Modifier.padding(padding))
                list.isEmpty() -> EmptyState("还没有视频", Modifier.padding(padding), icon = YoinSymbols.Playlist)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
                    itemsIndexed(list, key = { i, v -> "$i:${v.url}" }) { _, v ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable { Player.play(v.url, v) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NetImage(v.thumbnail, Modifier.width(160.dp).aspectRatio(16f / 10f), shape = ContinuousRoundedCornerShape(10.dp), thumbWidth = 320.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(v.channelName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
