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
import com.gpo.pilipala.yt.feature.library.LibItem
import com.gpo.pilipala.yt.feature.library.Library
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

/**
 * A YouTube playlist, laid out as a 收藏夹 is (VideoListPage): 搜索 and 排序, 播放全部 / 随机播放
 * with 仅听声音, and the videos playing through one after another (MixPlayback), so the listen
 * page has its 播放列表.
 */
@Composable
fun YtPlaylistScreen(route: Route.YtPlaylist) {
    var videos by remember { mutableStateOf<List<LibItem>?>(null) }
    var title by remember { mutableStateOf(route.title) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route.url) {
        runCatching { Yt.playlist(route.url) }
            .onSuccess { (t, v) ->
                if (t.isNotEmpty()) title = t
                // Each video's key once (a playlist may hold one twice; the grid keys by it).
                videos = v.map { Library.itemOf(it) }.distinctBy { it.key }
            }
            .onFailure { error = it.friendlyMessage() }
    }
    CoverTheme(route.cover.ifEmpty { videos?.firstOrNull()?.thumbnail }) {
        VideoListPage(
            id = "yt:${route.url}",
            title = title.ifEmpty { "播放列表" },
            subtitle = videos?.let { "${it.size} 个视频" },
            items = videos,
            orders = PlaylistOrders,
            error = error,
        )
    }
}
