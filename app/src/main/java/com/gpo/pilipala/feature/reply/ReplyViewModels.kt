package com.gpo.pilipala.feature.reply

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

@Immutable
internal data class LikeState(val liked: Boolean, val count: Long)

/** Where a new comment goes: root = 0 for a top-level comment. */
@Immutable
internal data class ComposeTarget(
    val root: Long = 0,
    val parent: Long = 0,
    /** Name shown in the sheet ("回复 @name"); prefixed into the message for 2nd-level replies. */
    val replyTo: String = "",
    val prefixMention: Boolean = false,
)

/** Like / delete / send shared by the list and the thread page. */
internal abstract class BaseReplyViewModel(val oid: Long, val type: Int) : ViewModel() {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val likes = mutableStateMapOf<Long, LikeState>()
    var upperMid: Long = 0
        protected set
    private val liking = mutableSetOf<Long>()

    fun likeState(r: Reply): LikeState = likes[r.rpid] ?: LikeState(r.liked, r.like)

    /** Returns false when the user must log in first. */
    fun toggleLike(r: Reply): Boolean {
        if (!BiliClient.isLoggedIn) return false
        if (!liking.add(r.rpid)) return true
        val before = likeState(r)
        val after = LikeState(!before.liked, (before.count + if (before.liked) -1 else 1).coerceAtLeast(0))
        likes[r.rpid] = after
        viewModelScope.launch {
            runCatching { ReplyApi.like(oid, type, r.rpid, after.liked) }
                .onFailure {
                    likes[r.rpid] = before
                    messages.tryEmit(it.friendlyMessage())
                }
            liking.remove(r.rpid)
        }
        return true
    }

    fun delete(r: Reply, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { ReplyApi.delete(r.oid.takeIf { it != 0L } ?: oid, type, r.rpid) }
                .onSuccess {
                    removeLocally(r.rpid)
                    messages.tryEmit("已删除")
                    onDone()
                }
                .onFailure { messages.tryEmit(it.friendlyMessage()) }
        }
    }

    /** Sends and returns the created reply (null on failure; the error is emitted). */
    suspend fun send(target: ComposeTarget, text: String): Boolean {
        val message = if (target.prefixMention && target.replyTo.isNotEmpty()) "回复 @${target.replyTo} :$text" else text
        return runCatching { ReplyApi.add(oid, type, message, target.root, target.parent, upperMid) }
            .onSuccess { res ->
                messages.tryEmit(res.toast)
                res.reply?.let { insertLocally(target, it) }
            }
            .onFailure { messages.tryEmit(it.friendlyMessage()) }
            .isSuccess
    }

    protected abstract fun removeLocally(rpid: Long)
    protected abstract fun insertLocally(target: ComposeTarget, reply: Reply)
}

internal class ReplyListViewModel(oid: Long, type: Int) : BaseReplyViewModel(oid, type) {
    /** 1 = 热门, 0 = 最新 (AppSettings.replySort). */
    var sort by mutableIntStateOf(AppSettings.replySort.value)
        private set
    var count by mutableLongStateOf(0L)
        private set
    var top by mutableStateOf<List<Reply>>(emptyList())
        private set
    var readOnly by mutableStateOf(false)
        private set

    val pager = Pager<Reply>(viewModelScope, dedupKey = { it.rpid }) { cursor ->
        val page = ReplyApi.main(oid, type, if (sort == 1) 3 else 2, cursor?.toLongOrNull() ?: 0L)
        upperMid = page.upperMid
        if (cursor == null) {
            top = page.top
            count = page.count
            readOnly = page.readOnly
        }
        val topIds = top.map { it.rpid }.toSet()
        Page(
            items = page.replies.filter { it.rpid !in topIds },
            hasMore = !page.isEnd && page.replies.isNotEmpty(),
            nextCursor = page.next.toString(),
        )
    }

    init {
        pager.refresh()
    }

    fun refresh() {
        likes.clear()
        pager.refresh()
    }

    fun changeSort(value: Int) {
        if (value == sort) return
        sort = value
        AppSettings.set(AppSettings.replySort, value)
        pager.update { emptyList() }
        pager.refresh()
    }

    override fun removeLocally(rpid: Long) {
        val removedTop = top.any { it.rpid == rpid }
        val removedMain = pager.items.any { it.rpid == rpid }
        top = top.filterNot { it.rpid == rpid }
        pager.update { list ->
            list.filterNot { it.rpid == rpid }.map { r ->
                if (r.replies.any { it.rpid == rpid }) {
                    r.copy(replies = r.replies.filterNot { it.rpid == rpid }, rcount = (r.rcount - 1).coerceAtLeast(0))
                } else {
                    r
                }
            }
        }
        if (removedTop || removedMain) count = (count - 1).coerceAtLeast(0)
    }

    override fun insertLocally(target: ComposeTarget, reply: Reply) {
        count += 1
        if (target.root == 0L) {
            pager.update { listOf(reply) + it }
        } else {
            fun Reply.withChild() = if (rpid == target.root) {
                copy(replies = replies + reply, rcount = rcount + 1)
            } else {
                this
            }
            top = top.map { it.withChild() }
            pager.update { list -> list.map { it.withChild() } }
        }
    }
}

internal class ReplyThreadViewModel(oid: Long, type: Int, val rootRpid: Long) : BaseReplyViewModel(oid, type) {
    var root by mutableStateOf<Reply?>(null)
        private set
    var count by mutableLongStateOf(0L)
        private set

    val pager = Pager<Reply>(viewModelScope, dedupKey = { it.rpid }) { cursor ->
        val pn = cursor?.toIntOrNull() ?: 1
        val page = ReplyApi.thread(oid, type, rootRpid, pn)
        upperMid = page.upperMid
        if (pn == 1) {
            page.root?.let { root = it }
            count = page.count
        }
        Page(
            items = page.replies,
            hasMore = page.replies.isNotEmpty() && pn.toLong() * ReplyApi.PAGE_SIZE < page.count,
            nextCursor = (pn + 1).toString(),
        )
    }

    init {
        pager.refresh()
    }

    fun refresh() {
        likes.clear()
        pager.refresh()
    }

    override fun removeLocally(rpid: Long) {
        if (pager.items.any { it.rpid == rpid }) count = (count - 1).coerceAtLeast(0)
        pager.update { list -> list.filterNot { it.rpid == rpid } }
    }

    override fun insertLocally(target: ComposeTarget, reply: Reply) {
        count += 1
        pager.update { listOf(reply) + it }
    }
}
