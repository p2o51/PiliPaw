package com.gpo.pilipala.feature.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player as Media3Player
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayMode
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.yt.feature.library.LibItem
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.player.Player

/**
 * A Mix played through, Bilibili and YouTube videos one after another. Each video plays in its
 * own source's player (Bilibili's video page, YouTube's player over the page); when one reaches
 * its end, the next starts in whichever player it needs, and the other one steps aside. It
 * plays by [mode] like a 收藏夹 queue: the list round again, a random order, or one video over.
 */
object MixPlayback {
    /** The Mix being played through, or null. */
    var listId by mutableStateOf<String?>(null)
        private set
    var index by mutableIntStateOf(-1)
        private set

    /** The Mix's videos, as the list has them now. */
    val items: List<LibItem>
        get() = Library.playlists.value.firstOrNull { it.id == listId }?.items.orEmpty()

    /** The Mix's name, for the listen page's header and the 播放列表 sheet. */
    val title: String
        get() = Library.playlists.value.firstOrNull { it.id == listId }?.name.orEmpty()

    var mode by mutableStateOf(PlayMode.ListLoop)
        private set

    /** The play order over [items] (shuffled for 随机播放); [index] is a position in the list itself. */
    private var order: List<Int> = emptyList()

    val current: LibItem? get() = items.getOrNull(index)

    /**
     * Whether the run is on the video [key] (a Mix key, `bili:BV…`, or a YouTube url): a video
     * opened from anywhere else is not part of it, and shows no Mix list or Mix 下一个.
     */
    fun runs(key: String?): Boolean = listId != null && key != null && current?.let { it.key == key || it.url == key } == true

    private var hooked = false

    /** Plays Mix [id] from [start] on. */
    fun start(id: String, start: Int = 0) {
        hook()
        listId = id
        if (mode == PlayMode.Shuffle) mode = PlayMode.ListLoop
        order = items.indices.toList()
        play(start)
    }

    /** Plays Mix [id] in a random order. */
    fun shuffle(id: String) {
        hook()
        listId = id
        mode = PlayMode.Shuffle
        order = items.indices.shuffled()
        order.firstOrNull()?.let(::play) ?: stop()
    }

    fun stop() {
        listId = null
        index = -1
        order = emptyList()
    }

    /** 列表循环 / 随机 / 单曲循环, from the 播放列表 sheet: a new random order starts at the current video. */
    fun changeMode(m: PlayMode) {
        if (m == mode) return
        mode = m
        val n = items.size
        order = if (m == PlayMode.Shuffle) listOf(index) + (0 until n).filter { it != index }.shuffled() else (0 until n).toList()
    }

    /** A video picked in the 播放列表 sheet. */
    fun playAt(i: Int) {
        if (listId == null || i == index) return
        if (order.size != items.size) order = items.indices.toList()
        play(i)
    }

    /** The video after the current one: the list goes round again (a fresh order when random). */
    fun next() {
        if (listId == null) return
        val n = items.size
        if (n == 0) return stop()
        if (order.size != n) order = if (mode == PlayMode.Shuffle) (0 until n).shuffled() else (0 until n).toList()
        val at = order.indexOf(index)
        val following = order.getOrNull(at + 1)
        if (following != null) return play(following)
        if (mode == PlayMode.Shuffle) order = (0 until n).shuffled()
        play(order.first())
    }

    private fun play(i: Int) {
        val item = items.getOrNull(i) ?: return stop()
        index = i
        if (MixItems.isBili(item)) {
            // YouTube steps aside; the Bilibili page opens and plays.
            if (Player.session != null) Player.close()
            BiliLinks.open(Route.Video(bvid = item.key.removePrefix("bili:"), cover = item.thumbnail, title = item.title))
        } else {
            // A kept Bilibili player steps aside; YouTube plays over whatever page is up.
            BiliKeeper.close()
            Player.play(item.url, item.toVideo())
        }
    }

    private fun hook() {
        if (hooked) return
        hooked = true
        // Bilibili: the page's player reached the end of the video this run asked for.
        PiliPlayerController.completionHook = hook@{ ctrl, bvid ->
            val cur = current ?: return@hook false
            if (!MixItems.isBili(cur) || cur.key != "bili:$bvid") return@hook false
            if (mode == PlayMode.RepeatOne) ctrl.play() else next()
            true
        }
        // YouTube: its one player reached the end of the video this run asked for.
        Player.exo.addListener(object : Media3Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Media3Player.STATE_ENDED) return
                val cur = current ?: return
                if (MixItems.isBili(cur) || Player.session?.url != cur.url) return
                if (mode == PlayMode.RepeatOne) {
                    Player.exo.seekTo(0)
                    Player.exo.play()
                } else {
                    next()
                }
            }
        })
    }
}
