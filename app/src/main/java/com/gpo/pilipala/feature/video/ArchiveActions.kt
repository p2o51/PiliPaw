package com.gpo.pilipala.feature.video

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Like / coin / favourite / watch-later state for one archive (a UGC video or
 * a bangumi episode, which is an archive too). Shared by the video and bangumi
 * pages. Results are reported through [messages] as short labels.
 */
@Stable
class ArchiveActions(private val scope: CoroutineScope) {
    var aid by mutableLongStateOf(0L)
        private set
    var bvid by mutableStateOf("")
        private set

    var liked by mutableStateOf(false)
    var coined by mutableIntStateOf(0)
    var favored by mutableStateOf(false)
    var inLater by mutableStateOf(false)

    var likeCount by mutableLongStateOf(0L)
    var coinCount by mutableLongStateOf(0L)
    var favCount by mutableLongStateOf(0L)
    var shareCount by mutableLongStateOf(0L)

    /** True while a write request is in flight (buttons ignore taps). */
    var busy by mutableStateOf(false)
        private set

    /**
     * Bumped when one of the user's own actions leaves like + coin + favourite all on (a
     * 三连 landing, or the last of the three done by hand), and when 三连 is confirmed on a
     * video that already has all three. The action rows play their 三连 burst on it; the
     * relation arriving from the network never bumps it.
     */
    var tripleCelebration by mutableIntStateOf(0)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var relationJob: Job? = null

    fun bind(aid: Long, bvid: String, like: Long, coin: Long, fav: Long, share: Long) {
        val changed = aid != this.aid
        this.aid = aid
        this.bvid = bvid
        likeCount = like
        coinCount = coin
        favCount = fav
        shareCount = share
        if (changed) {
            liked = false
            coined = 0
            favored = false
            inLater = false
        }
        loadRelation()
    }

    fun loadRelation() {
        if (!BiliClient.isLoggedIn || aid == 0L) return
        relationJob?.cancel()
        relationJob = scope.launch {
            runCatching {
                BiliClient.getData("/x/web-interface/archive/relation", mapOf("aid" to aid, "bvid" to bvid.ifEmpty { null })).asObj
            }.onSuccess { r ->
                liked = r.bool("like")
                coined = r.int("coin")
                favored = r.bool("favorite")
            }
        }
    }

    private fun write(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            val id = aid
            val before = hasAllThree()
            runCatching { block() }
                .onSuccess { if (!before && hasAllThree() && aid == id) tripleCelebration++ }
                .onFailure { _messages.tryEmit(it.friendlyMessage()) }
            busy = false
        }
    }

    private fun hasAllThree(): Boolean = liked && coined > 0 && favored

    private fun idForm(): Map<String, Any?> = if (bvid.isNotEmpty()) mapOf("bvid" to bvid) else mapOf("aid" to aid)

    fun toggleLike() = write {
        val target = !liked
        BiliClient.postData("/x/web-interface/archive/like", idForm() + ("like" to if (target) 1 else 2))
        liked = target
        likeCount = (likeCount + if (target) 1 else -1).coerceAtLeast(0)
        _messages.tryEmit(if (target) "已点赞" else "已取消赞")
    }

    fun triple() = write {
        if (liked && coined > 0 && favored) {
            _messages.tryEmit("已三连")
            tripleCelebration++
            return@write
        }
        val r = BiliClient.postData("/x/web-interface/archive/like/triple", idForm()).asObj
        if (r.bool("like") && !liked) likeCount++
        if (r.bool("coin") && coined == 0) coinCount += r.int("multiply", 2).toLong()
        if (r.bool("fav") && !favored) favCount++
        liked = r.bool("like") || liked
        if (r.bool("coin")) coined = maxOf(coined, r.int("multiply", 2))
        favored = r.bool("fav") || favored
        _messages.tryEmit("三连成功")
    }

    fun coin(multiply: Int, alsoLike: Boolean) = write {
        BiliClient.postData(
            "/x/web-interface/coin/add",
            idForm() + mapOf("multiply" to multiply, "select_like" to if (alsoLike) 1 else 0),
        )
        coined += multiply
        coinCount += multiply
        if (alsoLike && !liked) {
            liked = true
            likeCount++
        }
        _messages.tryEmit("投币成功")
    }

    suspend fun loadFolders(): List<FavFolder> {
        val data = BiliClient.getData(
            "/x/v3/fav/folder/created/list-all",
            mapOf("up_mid" to BiliClient.mid, "rid" to aid, "type" to 2),
        ).asObj
        return data.arr("list").objects().map {
            val attr = it.int("attr")
            FavFolder(
                id = it.long("id"),
                title = it.str("title"),
                count = it.int("media_count"),
                faved = it.int("fav_state") == 1,
                isDefault = (attr and 2) == 0,
                isPrivate = (attr and 1) == 1,
            )
        }
    }

    fun saveFav(add: List<Long>, del: List<Long>, onDone: () -> Unit = {}) = write {
        if (add.isEmpty() && del.isEmpty()) {
            onDone()
            return@write
        }
        BiliClient.postData(
            "/x/v3/fav/resource/deal",
            mapOf(
                "rid" to aid,
                "type" to 2,
                "add_media_ids" to add.joinToString(","),
                "del_media_ids" to del.joinToString(","),
            ),
        )
        val wasFaved = favored
        // Re-read the truth: still faved if any folder keeps it.
        val folders = runCatching { loadFolders() }.getOrNull()
        favored = folders?.any { it.faved } ?: add.isNotEmpty()
        if (favored != wasFaved) favCount = (favCount + if (favored) 1 else -1).coerceAtLeast(0)
        _messages.tryEmit(if (favored) "已收藏" else "已取消收藏")
        onDone()
    }

    /** Long-press on 收藏: toggle the default folder directly. */
    fun quickFav() = write {
        val folders = loadFolders()
        val def = folders.firstOrNull { it.isDefault } ?: folders.firstOrNull() ?: return@write
        BiliClient.postData(
            "/x/v3/fav/resource/deal",
            mapOf(
                "rid" to aid,
                "type" to 2,
                "add_media_ids" to if (def.faved) "" else def.id.toString(),
                "del_media_ids" to if (def.faved) def.id.toString() else "",
            ),
        )
        val stillFaved = !def.faved || folders.any { it.faved && it.id != def.id }
        if (stillFaved != favored) favCount = (favCount + if (stillFaved) 1 else -1).coerceAtLeast(0)
        favored = stillFaved
        _messages.tryEmit(if (def.faved) "已移出${def.title}" else "已收藏到${def.title}")
    }

    fun toggleLater() = write {
        if (inLater) {
            BiliClient.postData("/x/v2/history/toview/del", mapOf("aid" to aid))
            inLater = false
            _messages.tryEmit("已移出稍后再看")
        } else {
            BiliClient.postData("/x/v2/history/toview/add", mapOf("aid" to aid))
            inLater = true
            _messages.tryEmit("已添加到稍后再看")
        }
    }
}

/**
 * Follow state + fans count for one UP (from /x/web-interface/card), and whether the
 * UP follows back ([mutual], from /x/relation: attribute 6 = 互相关注).
 */
@Stable
class UpRelation(private val scope: CoroutineScope, private val onMessage: (String) -> Unit) {
    var mid by mutableLongStateOf(0L)
        private set
    var fans by mutableLongStateOf(-1L)
    var following by mutableStateOf(false)

    /** Following each other: the follow button reads 互相关注 instead of 已关注. */
    var mutual by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    private var relationJob: Job? = null

    fun bind(mid: Long) {
        if (mid == this.mid || mid == 0L) return
        this.mid = mid
        fans = -1
        following = false
        mutual = false
        scope.launch {
            runCatching { BiliClient.getData("/x/web-interface/card", mapOf("mid" to mid)).asObj }
                .onSuccess { d ->
                    if (this@UpRelation.mid != mid) return@onSuccess
                    fans = d.obj("card").long("fans", d.long("follower"))
                    following = d.bool("following")
                }
        }
        loadRelation()
    }

    /**
     * Read-only GET /x/relation: 1 悄悄关注, 2 已关注, 6 互相关注 (logged in only).
     * [mutualOnly]: the check after a follow only learns 互相关注; a stale or empty
     * answer (attribute 0 right after the follow) leaves 已关注 alone.
     */
    private fun loadRelation(mutualOnly: Boolean = false) {
        val target = mid
        if (!BiliClient.isLoggedIn || target == 0L || target == BiliClient.mid) return
        relationJob?.cancel()
        relationJob = scope.launch {
            runCatching { BiliClient.getData("/x/relation", mapOf("fid" to target)).asObj }
                .onSuccess { d ->
                    if (mid != target || busy) return@onSuccess
                    val attribute = d.int("attribute")
                    if (mutualOnly) {
                        if (attribute == 6 && following) mutual = true
                    } else {
                        following = attribute == 1 || attribute == 2 || attribute == 6
                        mutual = attribute == 6
                    }
                }
        }
    }

    fun toggle() {
        if (busy || mid == 0L) return
        // An answer still on its way predates this change.
        relationJob?.cancel()
        scope.launch {
            busy = true
            val target = !following
            runCatching {
                BiliClient.postData("/x/relation/modify", mapOf("fid" to mid, "act" to if (target) 1 else 2, "re_src" to 14))
            }.onSuccess {
                following = target
                if (!target) mutual = false
                if (fans >= 0) fans = (fans + if (target) 1 else -1).coerceAtLeast(0)
                onMessage(if (target) "已关注" else "已取消关注")
            }.onFailure { onMessage(it.friendlyMessage()) }
            busy = false
            // A new follow of someone who already follows the user is 互相关注 at once.
            if (target && following) loadRelation(mutualOnly = true)
        }
    }
}
