package com.gpo.pilipala.feature.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.feature.player.PlayQueue
import com.gpo.pilipala.feature.player.PlayQueues
import com.gpo.pilipala.feature.player.PlayTarget
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class VideoDetailViewModel(private val route: Route.Video) : ViewModel() {
    var bvid by mutableStateOf(route.bvid.ifEmpty { if (route.aid > 0) IdUtils.av2bv(route.aid) else "" })
        private set
    var detail by mutableStateOf<VideoDetail?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var cid by mutableLongStateOf(route.cid)
        private set
    var target by mutableStateOf<PlayTarget?>(
        if (route.cid > 0) {
            PlayTarget(
                bvid = bvid,
                aid = route.aid,
                cid = route.cid,
                title = route.title,
                cover = route.cover,
                startPositionMs = route.progress * 1000,
            )
        } else {
            null
        },
    )
        private set

    var related by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var relatedLoading by mutableStateOf(true)
        private set
    var tags by mutableStateOf<List<String>>(emptyList())
        private set

    /** Width / height of the current video's picture (view API `dimension`, rotate applied); 0 = unknown. */
    var videoAspect by mutableFloatStateOf(0f)
        private set

    /** The same aspect for each related video, by bvid. */
    var relatedAspects by mutableStateOf<Map<String, Float>>(emptyMap())
        private set

    /** The bvid [related] was loaded for. */
    var relatedFor by mutableStateOf("")
        private set

    /**
     * The page's layout: null until the first detail decides, then sticky. True for a portrait
     * video outside a play queue → the immersive vertical player (feature/video/portrait), which
     * keeps it while the user pages through other videos (landscape ones included).
     */
    var immersive by mutableStateOf<Boolean?>(null)
        private set

    /**
     * The user's own switch for this page visit: 缩小 leaves the immersive pager for the classic
     * page, 全屏 on a portrait video goes back. Never remembered past the page.
     */
    fun showImmersive(on: Boolean) {
        if (on && queue != null) return
        immersive = on
    }

    /**
     * 清屏 in the immersive pager (a sideways swipe on the picture): what sits on the picture
     * is hidden. Kept while the user pages through videos, never remembered past the page.
     */
    var immersiveCleared by mutableStateOf(false)

    /** Set when the archive is really a bangumi episode → the screen replaces itself. */
    var redirect by mutableStateOf<Route?>(null)
        private set

    var fullscreen by mutableStateOf(false)

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val actions = ArchiveActions(viewModelScope)
    val up = UpRelation(viewModelScope) { messages.tryEmit(it) }

    /** The 收藏夹 / 稍后再看 queue this page plays from (null = a single video, or lost with the process). */
    val queue: PlayQueue? = PlayQueues.get(route.playlistId)

    /**
     * 仅听声音 for a single video: opened to listen ([Route.Video.audioOnly]), then whatever the
     * player was left at when the page let it go (coming back rebuilds the player). A queue
     * carries it itself.
     */
    var listening: Boolean = route.audioOnly && queue == null

    private var loadJob: Job? = null

    init {
        if (route.audioOnly) queue?.setAudioOnly(true)
        load()
        // The player steps the queue (also in the background); the page follows its current item.
        queue?.let { q -> viewModelScope.launch { q.index.drop(1).collect { followQueue(q, it) } } }
    }

    private fun followQueue(q: PlayQueue, i: Int) {
        val item = q.items.getOrNull(i) ?: return
        val c = q.cidOf(i)
        if (item.bvid == bvid && (c <= 0 || c == cid)) return
        if (c > 0) {
            cid = c
            target = PlayTarget(bvid = item.bvid, aid = item.aid, cid = c, title = item.title, cover = item.cover)
        }
        if (item.bvid != bvid) {
            bvid = item.bvid
            load(keepContent = true)
        }
    }

    val cover: String get() = detail?.pic ?: route.cover

    fun load(keepContent: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!keepContent) loading = true
            error = null
            val params = if (bvid.isNotEmpty()) mapOf("bvid" to bvid) else mapOf("aid" to route.aid)
            val relatedJob = async { loadRelated() }
            val tagsJob = async { loadTags() }
            var aspect = 0f
            runCatching {
                val obj = BiliClient.getData("/x/web-interface/wbi/view", params, wbi = true).asObj!!
                aspect = obj.obj("dimension").pictureAspect()
                parseVideoDetail(obj)
            }
                .onSuccess { d ->
                    val ep = Regex("""/bangumi/play/ep(\d+)""").find(d.redirectUrl)?.groupValues?.get(1)?.toLongOrNull()
                    if (ep != null) {
                        redirect = Route.Bangumi(epId = ep, cover = d.pic)
                        return@launch
                    }
                    videoAspect = aspect
                    if (immersive == null) immersive = queue == null && aspect > 0f && aspect < 1f
                    detail = d
                    if (bvid.isEmpty()) bvid = d.bvid
                    val knownCid = cid.takeIf { c -> c > 0 && (d.pages.any { it.cid == c } || d.season?.episodes?.any { it.cid == c } == true) }
                    cid = knownCid ?: d.pages.firstOrNull()?.cid ?: d.cid
                    val cur = target
                    target = if (cur != null && cur.cid == cid && cur.bvid == d.bvid) {
                        // Same stream already playing: only fill gaps, never restart it.
                        if (cur.aid == 0L || cur.title.isEmpty()) cur.copy(aid = d.aid, title = d.title, cover = cur.cover.ifEmpty { d.pic }) else cur
                    } else {
                        PlayTarget(
                            bvid = d.bvid,
                            aid = d.aid,
                            cid = cid,
                            title = d.title,
                            cover = d.pic,
                            startPositionMs = when {
                                pendingStartMs > 0 -> pendingStartMs
                                cid == route.cid -> route.progress * 1000
                                else -> 0
                            },
                        )
                    }
                    pendingStartMs = 0
                    actions.bind(d.aid, d.bvid, d.like, d.coin, d.favorite, d.share)
                    up.bind(d.ownerMid)
                }
                .onFailure { if (detail == null || !keepContent) error = it.friendlyMessage() }
            loading = false
            relatedJob.await()
            tagsJob.await()
        }
    }

    private suspend fun loadRelated() {
        relatedLoading = true
        val forBvid = bvid
        runCatching { BiliClient.getData("/x/web-interface/archive/related", mapOf("bvid" to bvid.ifEmpty { null }, "aid" to route.aid.takeIf { bvid.isEmpty() })) }
            .onSuccess { el ->
                val objs = el.asArr?.objects().orEmpty().filter { it.str("bvid").isNotEmpty() }
                relatedAspects = relatedAspects + objs.associate { it.str("bvid") to it.obj("dimension").pictureAspect() }
                related = objs.map { it.toVideoItem() }
                relatedFor = forBvid
            }
        relatedLoading = false
    }

    /**
     * Immersive paging: make [item] the page's video in place (player target, intro, relation,
     * related). The page stays; system back still leaves it.
     */
    fun openInPlace(item: VideoItem) {
        if (item.bvid.isEmpty() || item.bvid == bvid) return
        bvid = item.bvid
        cid = item.cid.coerceAtLeast(0)
        // Unknown cid: no target until the detail resolves it (the layout pauses the last video).
        target = if (item.cid > 0) {
            PlayTarget(bvid = item.bvid, aid = item.aid, cid = item.cid, title = item.title, cover = item.cover)
        } else {
            null
        }
        tags = emptyList()
        load(keepContent = true)
    }

    /** Where the next video switched to in place resumes (the back menu's), until its stream is known. */
    private var pendingStartMs = 0L

    /**
     * 继续播放's in-place switch: a related video, a link, or one picked back from the back menu,
     * on this same page, resuming at [startMs].
     */
    fun openTarget(bvid: String, aid: Long, cid: Long, title: String, cover: String, startMs: Long) {
        if (bvid.isEmpty() || bvid == this.bvid) return
        this.bvid = bvid
        this.cid = cid.coerceAtLeast(0)
        pendingStartMs = startMs.coerceAtLeast(0)
        target = if (cid > 0) {
            PlayTarget(bvid = bvid, aid = aid, cid = cid, title = title, cover = cover, startPositionMs = pendingStartMs)
        } else {
            null
        }
        tags = emptyList()
        load(keepContent = true)
    }

    private suspend fun loadTags() {
        if (bvid.isEmpty()) return
        runCatching { BiliClient.getData("/x/tag/archive/tags", mapOf("bvid" to bvid)) }
            .onSuccess { el -> tags = el.asArr?.objects()?.map { it.str("tag_name") }?.filter { it.isNotEmpty() }.orEmpty() }
    }

    /** Switch to another part (分P) of the current video. */
    fun selectPage(page: VideoPage) {
        val d = detail ?: return
        if (page.cid == cid) return
        cid = page.cid
        target = PlayTarget(
            bvid = d.bvid,
            aid = d.aid,
            cid = page.cid,
            title = d.title,
            // The part's first_frame is often black; the session / notification artwork keeps the video's cover.
            cover = d.pic,
        )
    }

    /** Switch to another video of the 合集 — reloads the intro, keeps the player. */
    fun selectEpisode(ep: SeasonEpisode) {
        if (ep.cid == cid && ep.bvid == bvid) return
        cid = ep.cid
        target = PlayTarget(bvid = ep.bvid, aid = ep.aid, cid = ep.cid, title = ep.title, cover = ep.cover)
        if (ep.bvid != bvid) {
            bvid = ep.bvid
            load(keepContent = true)
        }
    }

    /** The player is disposed when another page covers this one; resume from here on return. */
    fun rememberPosition(ms: Long, playing: Boolean) {
        val t = target ?: return
        // Paused by hand (say, to open a commenter's space): it stays paused on return.
        target = t.copy(startPositionMs = if (ms > 1000) ms else t.startPositionMs, startPaused = !playing)
    }

    /** Auto-play next: next part, else next video in the season. Returns false at the end. */
    fun playNext(): Boolean = step(+1, apply = true)

    /** Previous part, else previous video in the season (上一个 in the media controls). */
    fun playPrevious(): Boolean = step(-1, apply = true)

    /** Whether [playNext] / [playPrevious] has somewhere to go (reads snapshot state). */
    fun hasNext(): Boolean = step(+1, apply = false)
    fun hasPrevious(): Boolean = step(-1, apply = false)

    private fun step(delta: Int, apply: Boolean): Boolean {
        if (queue != null) return false // the queue decides (the player steps it)
        if (immersive == true) return false // the immersive pager decides (it loops a video)
        val d = detail ?: return false
        if (d.pages.size > 1) {
            val i = d.pages.indexOfFirst { it.cid == cid }
            val p = d.pages.getOrNull(i + delta)
            if (i >= 0 && p != null) {
                if (apply) selectPage(p)
                return true
            }
        }
        val eps = d.season?.episodes.orEmpty()
        val j = eps.indexOfFirst { it.cid == cid || it.bvid == d.bvid }
        val e = eps.getOrNull(j + delta)
        if (j >= 0 && e != null) {
            if (apply) selectEpisode(e)
            return true
        }
        return false
    }
}

/** Width / height from a view `dimension` object ({width, height, rotate}); 0 when unknown. */
private fun JsonObject?.pictureAspect(): Float {
    val w = long("width")
    val h = long("height")
    if (w <= 0 || h <= 0) return 0f
    return if (int("rotate") == 1) h.toFloat() / w else w.toFloat() / h
}
