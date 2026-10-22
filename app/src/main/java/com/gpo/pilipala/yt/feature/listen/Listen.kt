package com.gpo.pilipala.yt.feature.listen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import kotlin.math.min
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.listen.ChargeLikeButton
import com.gpo.pilipala.feature.listen.ListenFlight
import com.gpo.pilipala.feature.listen.ListenMode
import com.gpo.pilipala.feature.listen.ListenPage
import com.gpo.pilipala.feature.listen.ListenPlayback
import com.gpo.pilipala.feature.listen.ListenSource
import com.gpo.pilipala.feature.player.SubtitleLine
import com.gpo.pilipala.feature.player.lyrics.lyricQueryFrom
import com.gpo.pilipala.feature.sources.SaveToListSheet
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.feature.comments.CommentsList
import com.gpo.pilipala.yt.feature.comments.rememberCommentsState
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.player.Cue
import com.gpo.pilipala.yt.feature.player.Player
import com.gpo.pilipala.yt.feature.player.YtLikes
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.SymbolPlayMode
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

/**
 * 仅听声音 over the YouTube player: the app's one listen page ([ListenPage]) on the YouTube
 * player. The picture ([from]: its window rect) flies into the cover on the way in, and the
 * cover back into the picture for 显示画面. [overlay] is the player's own open progress (1 open,
 * 0 the mini bar): as a back or 收起 docks the player, the page folds into the picture all the
 * way down to the bar; opening the bar unfolds it.
 */
@Composable
fun ListenScreen(overlay: () -> Float, from: () -> Rect?) {
    val session = Player.session ?: return
    val listening = Player.audioOnly
    val own = remember { Animatable(if (listening) 1f else 0f) }
    val inSpec = YoinMotion.slowSpatialSpec<Float>(YoinMotionRole.Standard)
    LaunchedEffect(listening) { own.animateTo(if (listening) 1f else 0f, if (listening) inSpec else ShowVideoSpec) }
    val latestOverlay by rememberUpdatedState(overlay)
    // The fold runs the whole way down, so the cover visibly flies into the bar's tile and the bar
    // takes over only as it lands (folding over the first quarter let the bar catch it at once).
    val progress: () -> Float = remember { { min(own.value, latestOverlay().coerceIn(0f, 1f)) } }
    val shown by remember { derivedStateOf { progress() > 0.001f } }
    if (!shown) return

    val key = remember(session.url) { Library.keyOf(session.url) }
    LaunchedEffect(key) { LyricsStore.load(key) }
    val picked = LyricsStore.lines(key)
    val cues = Player.cues
    val lines = remember(picked, cues) { (picked ?: cues).map { SubtitleLine(it.startMs, it.endMs, it.text) } }
    val source = remember(session.url) { YtListenSource(session.url, key) }
    source.lines = lines
    val latestFrom by rememberUpdatedState(from)
    val flight = remember { ListenFlight(progress, { latestFrom() }) }
    ListenPage(source, flight)
}

/** 显示画面: the cover back into the picture, a little quicker than the way in (as B站's). */
private val ShowVideoSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 170f)

@Stable
private class YtListenSource(val url: String, val key: String) : ListenSource {
    override var lines: List<SubtitleLine> by mutableStateOf(emptyList())

    private val session get() = Player.session?.takeIf { it.url == url }
    override val title: String get() = session?.title.orEmpty()
    override val subtitle: String get() = session?.channelName.orEmpty()
    override val cover: String get() = session?.thumbnail.orEmpty()
    override val header: String get() = "仅听声音"
    override val playback: ListenPlayback = YtListenPlayback
    override val noLyricsLabel: String get() = if (Player.subtitleTracks.isEmpty()) "这个视频没有字幕" else "字幕加载中"

    override val lyricsQuery: String get() = lyricQueryFrom(title)
    override val hasPickedLyrics: Boolean get() = LyricsStore.lines(key) != null
    override suspend fun pickLyrics(lines: List<SubtitleLine>) = LyricsStore.save(key, lines.map { Cue(it.fromMs, it.toMs, it.text) })
    override suspend fun clearLyrics() = LyricsStore.clear(key)

    override fun collapse() {
        Player.expanded = false
    }

    override fun showVideo() = Player.changeAudioOnly(false)

    /** The video as a card, for 加到 Mix. */
    private fun video(): VideoItem? {
        val s = session ?: return null
        s.preview?.let { return it }
        val info = s.info ?: return null
        return VideoItem(
            url = info.url, title = info.name.orEmpty(), thumbnail = info.thumbnailUrl.orEmpty(),
            channelName = info.uploaderName.orEmpty(), channelUrl = info.uploaderUrl.orEmpty(),
            channelAvatar = info.uploaderAvatarUrl.orEmpty(), durationSec = info.duration, views = info.viewCount,
            uploaded = "", uploadedAt = 0, isLive = false, isShort = false, channelVerified = false,
        )
    }

    /**
     * Signed in: 赞 (an account write), held: 赞 and 加到 Mix, as the video page's 赞 does.
     * Signed out YouTube likes have no account to go to, so the slot is 加到 Mix.
     */
    @Composable
    override fun Like() {
        val profile by Accounts.youtube.collectAsState()
        val scope = rememberCoroutineScope()
        val toast = rememberToast()
        var listSheet by remember { mutableStateOf(false) }
        val id = session?.info?.id
        if (profile is Profile.SignedIn && id != null) {
            val liked = YtLikes.liked(url)
            ChargeLikeButton(
                checked = liked,
                icon = PiliSymbols.ThumbUp,
                checkedIcon = PiliSymbols.ThumbUpFilled,
                label = "赞",
                checkedLabel = "取消赞",
                onClick = { scope.launch { YtLikes.set(url, id, !liked)?.let(toast) } },
                onCharged = {
                    if (!liked) scope.launch { YtLikes.set(url, id, true)?.let(toast) }
                    listSheet = true
                },
                celebration = 0,
            )
        } else {
            FilledTonalIconButton(onClick = { listSheet = true }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
                Icon(YoinSymbols.Library, contentDescription = "加到 Mix")
            }
        }
        val video = video()
        if (listSheet && video != null) SaveToListSheet(Library.itemOf(video), onDismiss = { listSheet = false })
    }

    @Composable
    override fun CommentsSheet(onDismiss: () -> Unit) {
        val state = rememberCommentsState(url)
        ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
            CommentsList(state, { Player.exo.seekTo(it * 1000) }, Modifier.fillMaxWidth(), PaddingValues(bottom = sheetBottomPadding())) {
                item(key = "title") { Text("评论", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
        }
    }

    @Composable
    override fun CommentsPane(modifier: Modifier) {
        CommentsList(rememberCommentsState(url), { Player.exo.seekTo(it * 1000) }, modifier)
    }
}

/** The YouTube player behind the listen transport: 下一个 is YouTube's up next, 上一个 回到开头. */
private object YtListenPlayback : ListenPlayback {
    override val playing: Boolean get() = Player.isPlaying
    override val hasNext: Boolean get() = Player.hasNext
    override val durationMs: Long get() = Player.durationMs
    override fun positionMs(): Long = Player.exo.currentPosition
    override fun togglePlay() = Player.toggle()
    override fun skipNext() = Player.playNext()
    override fun skipPrevious() = Player.exo.seekTo(0)
    override fun seekTo(ms: Long) = Player.exo.seekTo(ms)
    override val previousLabel: String get() = "回到开头"

    @Composable
    override fun mode(): ListenMode {
        val loop = Player.loop
        return ListenMode(if (loop) SymbolPlayMode.RepeatOne else SymbolPlayMode.RepeatAll, if (loop) "单曲循环" else "不循环", lit = loop)
    }

    override fun stepMode() = Player.changeLoop(!Player.loop)
}
