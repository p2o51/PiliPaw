package com.gpo.pilipala.feature.bangumi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.feature.player.PlayTarget
import com.gpo.pilipala.feature.video.ArchiveActions
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class BangumiDetailViewModel(private val route: Route.Bangumi) : ViewModel() {
    var detail by mutableStateOf<BangumiDetail?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var seasonId = route.seasonId
    var epId by mutableLongStateOf(route.epId)
        private set
    var target by mutableStateOf<PlayTarget?>(null)
        private set
    var fullscreen by mutableStateOf(false)

    var following by mutableStateOf(false)
        private set
    var followBusy by mutableStateOf(false)
        private set

    /** Per-episode reply count (from the episode info endpoint). */
    var episodeReplies by mutableLongStateOf(-1L)
        private set

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val actions = ArchiveActions(viewModelScope)

    private var loadJob: Job? = null
    private var epJob: Job? = null

    val cover: String get() = detail?.cover ?: route.cover

    val currentEpisode: BangumiEpisode?
        get() = detail?.allEpisodes?.firstOrNull { it.id == epId }

    init {
        load()
    }

    fun load(keepContent: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!keepContent) loading = true
            error = null
            val params = if (epId > 0) mapOf("ep_id" to epId) else mapOf("season_id" to seasonId)
            runCatching { parseBangumi(BiliClient.getData("/pgc/view/web/season", params).asObj!!) }
                .onSuccess { d ->
                    detail = d
                    seasonId = d.seasonId
                    following = d.following
                    val all = d.allEpisodes
                    val ep = all.firstOrNull { it.id == epId }
                        ?: all.firstOrNull { it.id == d.lastEpId }
                        ?: d.episodes.firstOrNull()
                        ?: all.firstOrNull()
                    if (ep != null) play(ep, d) else target = null
                }
                .onFailure { if (detail == null || !keepContent) error = it.friendlyMessage() }
            loading = false
        }
    }

    private fun play(ep: BangumiEpisode, d: BangumiDetail) {
        epId = ep.id
        target = PlayTarget(
            bvid = ep.bvid,
            aid = ep.aid,
            cid = ep.cid,
            epId = ep.id,
            seasonId = d.seasonId,
            isPgc = true,
            title = ep.showTitle.ifEmpty { d.title },
            cover = ep.cover.ifEmpty { d.cover },
        )
        actions.bind(ep.aid, ep.bvid, d.likes, d.coins, d.favorites, d.share)
        episodeReplies = -1
        epJob?.cancel()
        epJob = viewModelScope.launch {
            runCatching { BiliClient.getData("/pgc/season/episode/web/info", mapOf("ep_id" to ep.id)).asObj }
                .onSuccess { info ->
                    val s = info.obj("stat")
                    actions.likeCount = s.long("like")
                    actions.coinCount = s.long("coin")
                    actions.favCount = s.long("favorite")
                    actions.shareCount = s.long("share")
                    episodeReplies = s.long("reply")
                }
        }
    }

    fun selectEpisode(ep: BangumiEpisode) {
        val d = detail ?: return
        if (ep.id == epId) return
        play(ep, d)
    }

    fun switchSeason(id: Long) {
        if (id == seasonId) return
        seasonId = id
        epId = 0
        load(keepContent = true)
    }

    /** Auto-play next / 下一集: the next episode of the list the current one is in. */
    fun playNext(): Boolean = step(+1, apply = true)

    /** 上一集 in the player and the system media controls. */
    fun playPrevious(): Boolean = step(-1, apply = true)

    /** Whether [playNext] / [playPrevious] has somewhere to go (reads snapshot state). */
    fun hasNext(): Boolean = step(+1, apply = false)
    fun hasPrevious(): Boolean = step(-1, apply = false)

    private fun step(delta: Int, apply: Boolean): Boolean {
        val d = detail ?: return false
        val list = if (d.episodes.any { it.id == epId }) d.episodes else d.sections.firstOrNull { s -> s.episodes.any { it.id == epId } }?.episodes.orEmpty()
        val i = list.indexOfFirst { it.id == epId }
        val ep = list.getOrNull(i + delta)
        if (i < 0 || ep == null) return false
        if (apply) play(ep, d)
        return true
    }

    fun rememberPosition(ms: Long) {
        val t = target ?: return
        if (ms > 1000) target = t.copy(startPositionMs = ms)
    }

    fun toggleFollow() {
        val d = detail ?: return
        if (followBusy) return
        viewModelScope.launch {
            followBusy = true
            val add = !following
            runCatching {
                BiliClient.postData(if (add) "/pgc/web/follow/add" else "/pgc/web/follow/del", mapOf("season_id" to d.seasonId)).asObj
            }.onSuccess { r ->
                following = add
                messages.tryEmit(r.str("toast").ifEmpty { if (add) "已${d.followVerb}" else "已取消${d.followVerb}" })
            }.onFailure { messages.tryEmit(it.friendlyMessage()) }
            followBusy = false
        }
    }
}
