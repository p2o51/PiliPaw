package com.gpo.pilipala.feature.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
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

    /** Set when the archive is really a bangumi episode → the screen replaces itself. */
    var redirect by mutableStateOf<Route?>(null)
        private set

    var fullscreen by mutableStateOf(false)

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val actions = ArchiveActions(viewModelScope)
    val up = UpRelation(viewModelScope) { messages.tryEmit(it) }

    /** The 收藏夹 / 稍后再看 queue this page plays from (null = a single video, or lost with the process). */
    val queue: PlayQueue? = PlayQueues.get(route.playlistId)

    private var loadJob: Job? = null

    init {
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
            runCatching { parseVideoDetail(BiliClient.getData("/x/web-interface/wbi/view", params, wbi = true).asObj!!) }
                .onSuccess { d ->
                    val ep = Regex("""/bangumi/play/ep(\d+)""").find(d.redirectUrl)?.groupValues?.get(1)?.toLongOrNull()
                    if (ep != null) {
                        redirect = Route.Bangumi(epId = ep, cover = d.pic)
                        return@launch
                    }
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
                            startPositionMs = if (cid == route.cid) route.progress * 1000 else 0,
                        )
                    }
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
        runCatching { BiliClient.getData("/x/web-interface/archive/related", mapOf("bvid" to bvid.ifEmpty { null }, "aid" to route.aid.takeIf { bvid.isEmpty() })) }
            .onSuccess { el -> related = el.asArr?.objects()?.map { it.toVideoItem() }?.filter { it.bvid.isNotEmpty() }.orEmpty() }
        relatedLoading = false
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
            cover = page.cover.ifEmpty { d.pic },
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
    fun rememberPosition(ms: Long) {
        val t = target ?: return
        if (ms > 1000) target = t.copy(startPositionMs = ms)
    }

    /** Auto-play next: next part, else next video in the season. Returns false at the end. */
    fun playNext(): Boolean {
        if (queue != null) return false // the queue decides (the player steps it)
        val d = detail ?: return false
        if (d.pages.size > 1) {
            val i = d.pages.indexOfFirst { it.cid == cid }
            if (i in 0 until d.pages.lastIndex) {
                selectPage(d.pages[i + 1])
                return true
            }
        }
        val eps = d.season?.episodes.orEmpty()
        val j = eps.indexOfFirst { it.cid == cid || it.bvid == d.bvid }
        if (j in 0 until eps.lastIndex) {
            selectEpisode(eps[j + 1])
            return true
        }
        return false
    }
}
