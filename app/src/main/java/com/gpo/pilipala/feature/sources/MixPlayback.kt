package com.gpo.pilipala.feature.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player as Media3Player
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.yt.feature.library.LibItem
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.player.Player

/**
 * A Mix played through, Bilibili and YouTube videos one after another. Each video plays in its
 * own source's player (Bilibili's video page, YouTube's player over the page); when one reaches
 * its end, the next starts in whichever player it needs, and the other one steps aside. The
 * last video ends the run.
 */
object MixPlayback {
    /** The Mix being played through, or null. */
    var listId by mutableStateOf<String?>(null)
        private set
    var index by mutableIntStateOf(-1)
        private set

    private val items: List<LibItem>
        get() = Library.playlists.value.firstOrNull { it.id == listId }?.items.orEmpty()

    /** The play order over [items] (shuffled for 随机播放); [index] is a position in the list itself. */
    private var order: List<Int> = emptyList()

    val current: LibItem? get() = items.getOrNull(index)

    private var hooked = false

    /** Plays Mix [id] from [start] on. */
    fun start(id: String, start: Int = 0) {
        hook()
        listId = id
        order = items.indices.toList()
        play(start)
    }

    /** Plays Mix [id] in a random order. */
    fun shuffle(id: String) {
        hook()
        listId = id
        order = items.indices.shuffled()
        order.firstOrNull()?.let(::play) ?: stop()
    }

    fun stop() {
        listId = null
        index = -1
        order = emptyList()
    }

    fun next() {
        if (listId == null) return
        val at = order.indexOf(index)
        order.getOrNull(at + 1)?.let(::play) ?: stop()
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
        PiliPlayerController.completionHook = hook@{ bvid ->
            val cur = current ?: return@hook false
            if (!MixItems.isBili(cur) || cur.key != "bili:$bvid") return@hook false
            next()
            true
        }
        // YouTube: its one player reached the end of the video this run asked for.
        Player.exo.addListener(object : Media3Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Media3Player.STATE_ENDED) return
                val cur = current ?: return
                if (MixItems.isBili(cur) || Player.session?.url != cur.url) return
                next()
            }
        })
    }
}
