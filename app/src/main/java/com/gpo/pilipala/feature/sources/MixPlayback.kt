package com.gpo.pilipala.feature.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player as Media3Player
import com.gpo.pilipala.feature.player.PiliPlayerController
import com.gpo.pilipala.feature.player.PlayMode
import com.gpo.pilipala.feature.player.PlayTarget
import com.gpo.pilipala.feature.player.QueueItem
import com.gpo.pilipala.feature.player.resolveFirstCid
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.yt.feature.library.LibItem
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.player.Player

/**
 * A Mix played through, Bilibili and YouTube videos one after another. Each video plays in its
 * own source's player (Bilibili's video page, YouTube's player over the page); when one reaches
 * its end, the next starts in whichever player it needs, and the other one steps aside. It
 * plays by [mode] like a 收藏夹 queue: the list round again, a random order, or one video over.
 * Started with 仅听声音 ([listening]), every video opens listening; turned off (显示画面) on one,
 * the ones after it show their pictures again, as a 收藏夹 queue keeps it.
 *
 * With the app away (in the background, the screen off) no page can open, so a B站 video plays
 * in the B站 player that just finished (its page's), else where the mini bar keeps one
 * (BiliKeeper), and its page opens on it once the app is back.
 */
object MixPlayback {
    /** The Mix being played through, or null. */
    var listId by mutableStateOf<String?>(null)
        private set
    var index by mutableIntStateOf(-1)
        private set

    /**
     * A list played as it was handed over (a YouTube playlist, or a Mix sorted or searched on its
     * page): its name and videos. Null: the Mix [listId] itself, as the list has it now.
     */
    private var fixed by mutableStateOf<Pair<String, List<LibItem>>?>(null)

    /** The Mix's videos, as the list has them now. */
    val items: List<LibItem>
        get() = fixed?.second ?: Library.playlists.value.firstOrNull { it.id == listId }?.items.orEmpty()

    /** The Mix's name, for the listen page's header and the 播放列表 sheet. */
    val title: String
        get() = fixed?.first ?: Library.playlists.value.firstOrNull { it.id == listId }?.name.orEmpty()

    var mode by mutableStateOf(PlayMode.ListLoop)
        private set

    /** 仅听声音 for the run: each video opens audio-only. */
    var listening by mutableStateOf(false)
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

    private lateinit var app: android.app.Application
    private val scope = MainScope()

    /** Activities on screen: none, the app is away and a page cannot open (see [playAway]). */
    private var started = 0

    /** The B站 video played while away, whose page opens once the app is back. */
    private var awayRoute: Route.Video? = null
    private var awayJob: Job? = null
    private var awayFailures = 0

    fun init(application: android.app.Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                started++
                // Back: the video that played meanwhile gets its page, taking over its player.
                val r = awayRoute ?: return
                awayRoute = null
                val playing = ((PlayerHost.front as? PlayerSlot.Bili)?.player as? PiliPlayerController) ?: BiliKeeper.held
                if (playing?.target?.bvid == r.bvid) BiliLinks.open(r.copy(audioOnly = playing.audioOnly))
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                started = (started - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    /** Plays Mix [id] from [start] on ([listen]: 仅听声音). */
    fun start(id: String, start: Int = 0, listen: Boolean = false) {
        hook()
        fixed = null
        listId = id
        listening = listen
        if (mode == PlayMode.Shuffle) mode = PlayMode.ListLoop
        order = items.indices.toList()
        play(start)
    }

    /** Plays Mix [id] in a random order ([listen]: 仅听声音). */
    fun shuffle(id: String, listen: Boolean = false) {
        hook()
        fixed = null
        listId = id
        listening = listen
        mode = PlayMode.Shuffle
        order = items.indices.shuffled()
        order.firstOrNull()?.let(::play) ?: stop()
    }

    /**
     * Plays [list] as handed over, under [id] and [name] (a YouTube playlist's page, a Mix's page
     * sorted or searched), from [start], or in a random order with [shuffle].
     */
    fun play(id: String, name: String, list: List<LibItem>, start: Int = 0, listen: Boolean = false, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        hook()
        fixed = name to list
        listId = id
        listening = listen
        if (shuffle) {
            mode = PlayMode.Shuffle
            order = list.indices.shuffled()
            play(order.first())
        } else {
            if (mode == PlayMode.Shuffle) mode = PlayMode.ListLoop
            order = list.indices.toList()
            play(start)
        }
    }

    fun stop() {
        fixed = null
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
        listening = listeningNow()
        play(i)
    }

    /** The video after the current one: the list goes round again (a fresh order when random). */
    fun next() {
        if (listId == null) return
        val n = items.size
        if (n == 0) return stop()
        listening = listeningNow()
        if (order.size != n) order = if (mode == PlayMode.Shuffle) (0 until n).shuffled() else (0 until n).toList()
        val at = order.indexOf(index)
        val following = order.getOrNull(at + 1)
        if (following != null) return play(following)
        if (mode == PlayMode.Shuffle) order = (0 until n).shuffled()
        play(order.first())
    }

    /** Whether the video playing now is listening (the run follows a 显示画面 or 仅听声音 on it). */
    private fun listeningNow(): Boolean {
        val cur = current ?: return listening
        return if (MixItems.isBili(cur)) {
            val front = (PlayerHost.front as? PlayerSlot.Bili)?.player as? PiliPlayerController
            (front ?: BiliKeeper.held?.takeIf { it.target?.bvid == cur.key.removePrefix("bili:") })?.audioOnly ?: listening
        } else {
            if (Player.session?.url == cur.url) Player.audioOnly else listening
        }
    }

    private fun play(i: Int) {
        val item = items.getOrNull(i) ?: return stop()
        index = i
        awayJob?.cancel()
        if (MixItems.isBili(item)) {
            val route = Route.Video(bvid = item.key.removePrefix("bili:"), cover = item.thumbnail, title = item.title, audioOnly = listening)
            if (::app.isInitialized && started == 0) return playAway(route, item.channelName)
            // YouTube steps aside; the Bilibili page opens and plays.
            if (Player.session != null) Player.close()
            BiliLinks.open(route)
        } else {
            awayRoute = null
            // A kept Bilibili player steps aside; YouTube plays over whatever page is up.
            BiliKeeper.close()
            Player.play(item.url, item.toVideo())
            Player.changeAudioOnly(listening)
        }
    }

    /**
     * The app is away: [route]'s video plays in the mini bar's player (the one kept there, else a
     * new one), with no page; YouTube steps aside only once it is ready to start, so the sound
     * never stops long enough for the system to drop the app.
     */
    private fun playAway(route: Route.Video, artist: String) {
        // A B站 page's player that just finished the last one: the next plays in it, and the page
        // (whose Mix step replaces it in place on return) carries on with it, not from the top.
        val page = ((PlayerHost.front as? PlayerSlot.Bili)?.player as? PiliPlayerController)?.takeIf { it !== BiliKeeper.held }
        val ctrl = page ?: BiliKeeper.held ?: PiliPlayerController(app)
        awayJob = scope.launch {
            val cid = runCatching { resolveFirstCid(QueueItem(bvid = route.bvid)) }.getOrDefault(0L)
            if (cid <= 0) {
                if (ctrl !== page && BiliKeeper.held !== ctrl) ctrl.dispose()
                // 失效 or offline: on to the next, a few in a row at most.
                if (++awayFailures <= 3) next() else stop()
                return@launch
            }
            awayFailures = 0
            if (ctrl !== page) BiliKeeper.playAway(ctrl)
            ctrl.load(PlayTarget(bvid = route.bvid, cid = cid, title = route.title, cover = route.cover), fresh = true)
            // The notification's second line (the page sets it from the video's detail).
            ctrl.artist = artist
            ctrl.listenOnly(listening)
            ctrl.setBackground(true)
            awayRoute = route
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
