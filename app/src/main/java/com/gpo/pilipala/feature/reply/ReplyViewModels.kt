package com.gpo.pilipala.feature.reply

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.dynamics.UploadedPic
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

@Immutable
internal data class LikeState(val liked: Boolean, val count: Long, val disliked: Boolean = false)

/** Mids per /x/relation/relations request. */
private const val RELATION_BATCH = 40

/** Where a new comment goes: root = 0 for a top-level comment. */
@Immutable
internal data class ComposeTarget(
    val root: Long = 0,
    val parent: Long = 0,
    /** Name shown in the sheet ("回复 @name"); prefixed into the message for 2nd-level replies. */
    val replyTo: String = "",
    val prefixMention: Boolean = false,
)

/** A reply sent or deleted on one surface, for another that shows the same comment (the list under a thread). */
@Immutable
internal sealed interface ReplyChange {
    data class Sent(val target: ComposeTarget, val reply: Reply) : ReplyChange

    data class Deleted(val rpid: Long) : ReplyChange
}

/** Like / delete / send shared by the list and the thread page. */
internal abstract class BaseReplyViewModel(val oid: Long, val type: Int) : ViewModel() {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val likes = mutableStateMapOf<Long, LikeState>()

    /** The reply just sent, which grows in once (kept here so a returning page does not replay it). */
    val entrance = ReplyEntrance()

    /** A reply landed through the composer (after it was inserted locally). */
    val sentEvents = MutableSharedFlow<ComposeTarget>(extraBufferCapacity = 1)

    /** Sends and deletes done here, for a host mirroring them elsewhere (dropped when nobody listens). */
    val changes = MutableSharedFlow<ReplyChange>(extraBufferCapacity = 8)
    var upperMid: Long = 0
        protected set
    private val liking = mutableSetOf<Long>()

    /**
     * mid → the user's relation with a commenter, only the ones worth a tag: 2 = 关注
     * (悄悄关注 too), 6 = 互关. Filled per loaded page, logged in only, without holding
     * the page back; rows read it and their tag fades in once it is known.
     */
    val relations = mutableStateMapOf<Long, Int>()

    /** Mids already learnt or on their way. Read and written on the main thread only. */
    private val relationAsked = HashSet<Long>()

    fun relationOf(mid: Long): Int = relations[mid] ?: 0

    /**
     * Learns the relations for a page of replies (and their previews): payload first, then one
     * batched GET. Called from the pager's loader (a background thread): the page is only walked
     * there; the cache is checked and filled back on the main thread.
     */
    protected fun learnRelations(replies: List<Reply>) {
        if (!BiliClient.isLoggedIn || replies.isEmpty()) return
        val me = BiliClient.mid
        // mid → the payload's relation, or -1 when the payload does not say.
        val seen = LinkedHashMap<Long, Int>()
        fun visit(r: Reply) {
            val mid = r.member.mid
            if (mid != 0L && mid != me && (seen[mid] ?: -1) < 0) seen[mid] = r.relation
            r.replies.forEach { visit(it) }
        }
        replies.forEach { visit(it) }
        if (seen.isEmpty()) return
        viewModelScope.launch {
            val unknown = ArrayList<Long>()
            seen.forEach { (mid, relation) ->
                if (!relationAsked.add(mid)) return@forEach
                if (relation >= 0) noteRelation(mid, relation) else unknown += mid
            }
            unknown.chunked(RELATION_BATCH).forEach { chunk ->
                runCatching { ReplyApi.relations(chunk) }
                    .onSuccess { found -> found.forEach { (mid, a) -> noteRelation(mid, a) } }
                    // Asked again with the next page.
                    .onFailure { if (it !is CancellationException) relationAsked -= chunk.toSet() }
            }
        }
    }

    private fun noteRelation(mid: Long, attribute: Int) {
        when (attribute) {
            1, 2 -> relations[mid] = 2
            6 -> relations[mid] = 6
            else -> relations.remove(mid)
        }
    }

    fun likeState(r: Reply): LikeState = likes[r.rpid] ?: LikeState(r.liked, r.like, r.disliked && !r.liked)

    /** Returns false when the user must log in first. */
    fun toggleLike(r: Reply): Boolean {
        if (!BiliClient.isLoggedIn) return false
        if (!liking.add(r.rpid)) return true
        val before = likeState(r)
        // A like takes a 点踩 off (the server does the same).
        val after = LikeState(!before.liked, (before.count + if (before.liked) -1 else 1).coerceAtLeast(0), disliked = false)
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

    /** 点踩 / 取消点踩; a 点踩 takes the user's like off. Returns false when the user must log in first. */
    fun toggleDislike(r: Reply): Boolean {
        if (!BiliClient.isLoggedIn) return false
        if (!liking.add(r.rpid)) return true
        val before = likeState(r)
        val target = !before.disliked
        val after = if (target) {
            LikeState(false, (before.count - if (before.liked) 1 else 0).coerceAtLeast(0), disliked = true)
        } else {
            before.copy(disliked = false)
        }
        likes[r.rpid] = after
        viewModelScope.launch {
            runCatching { ReplyApi.hate(oid, type, r.rpid, target) }
                .onFailure {
                    likes[r.rpid] = before
                    messages.tryEmit(it.friendlyMessage())
                }
            liking.remove(r.rpid)
        }
        return true
    }

    /** 举报; [onDone] runs once it landed (the sheet shows 已举报), failures go to [messages]. */
    fun report(r: Reply, reason: Int, detail: String, block: Boolean, onDone: () -> Unit, onFail: () -> Unit) {
        viewModelScope.launch {
            runCatching { ReplyApi.report(r.oid.takeIf { it != 0L } ?: oid, type, r.rpid, reason, detail, block) }
                .onSuccess {
                    onDone()
                    // 同时拉黑: their comment leaves the list once the sheet has said 已举报.
                    if (block) {
                        com.gpo.pilipala.feature.home.Blacklist.add(r.member.mid)
                        removeLocally(r.rpid)
                    }
                }
                .onFailure {
                    messages.tryEmit(it.friendlyMessage())
                    onFail()
                }
        }
    }

    /** 拉黑 the commenter (the same blacklist as 拉黑 UP). */
    fun block(r: Reply) {
        viewModelScope.launch {
            runCatching { com.gpo.pilipala.feature.home.FeedActions.block(r.member.mid) }
                .onSuccess {
                    removeLocally(r.rpid)
                    messages.tryEmit("已拉黑 ${r.member.name}")
                }
                .onFailure { messages.tryEmit(it.friendlyMessage()) }
        }
    }

    fun delete(r: Reply, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { ReplyApi.delete(r.oid.takeIf { it != 0L } ?: oid, type, r.rpid) }
                .onSuccess {
                    removeLocally(r.rpid)
                    changes.tryEmit(ReplyChange.Deleted(r.rpid))
                    messages.tryEmit("已删除")
                    onDone()
                }
                .onFailure { messages.tryEmit(it.friendlyMessage()) }
        }
    }

    /** Sends and returns the created reply (null on failure; the error is emitted). */
    suspend fun send(target: ComposeTarget, text: String, pictures: List<UploadedPic> = emptyList()): Boolean {
        val message = if (target.prefixMention && target.replyTo.isNotEmpty()) "回复 @${target.replyTo} :$text" else text
        return runCatching { ReplyApi.add(oid, type, message, target.root, target.parent, upperMid, pictures) }
            .onSuccess { res ->
                messages.tryEmit(res.toast)
                res.reply?.let {
                    entrance.sent = it.rpid
                    insertLocally(target, it)
                    sentEvents.tryEmit(target)
                    changes.tryEmit(ReplyChange.Sent(target, it))
                }
            }
            .onFailure { messages.tryEmit(it.friendlyMessage()) }
            .isSuccess
    }

    protected abstract fun removeLocally(rpid: Long)
    protected abstract fun insertLocally(target: ComposeTarget, reply: Reply)
}

/**
 * A notification's pin, from arrival: scrolled to ([Landed]), then either marked and
 * settled ([Done]) or, for a reply inside it, its thread opened ([Opened]).
 */
internal enum class AnchorPhase { Pending, Landed, Opened, Done }

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

    /**
     * The thread a notification opened this list for ([ReplyAnchors]): pinned above 置顶
     * and the rest, its root carrying the anchored reply as its only preview.
     */
    var anchored by mutableStateOf<Reply?>(null)
        private set

    /** The rpid the notification named (the root itself, or a reply inside it); 0 without one. */
    var anchorRpid by mutableLongStateOf(0L)
        private set

    /** How far the pin's arrival has gone: not again when the list comes back from a thread. */
    var anchorPhase by mutableStateOf(AnchorPhase.Pending)

    /**
     * Sort switches: [requestedGen] bumps at the tap, [listGen] once the new
     * first page replaced the list. Rows keyed by their generation leave and
     * arrive as a plain cross-fade once the new order lands.
     */
    var requestedGen by mutableIntStateOf(0)
        private set
    var listGen by mutableIntStateOf(0)
        private set

    val pager: Pager<Reply> = Pager(viewModelScope, dedupKey = { it.rpid }) { cursor ->
        val gen = requestedGen
        val page = try {
            ReplyApi.main(oid, type, if (sort == 1) 3 else 2, cursor?.toLongOrNull() ?: 0L)
        } catch (e: Throwable) {
            if (cursor == null && gen != listGen && e !is CancellationException) {
                // The switch failed: drop the stale list so the error and its retry show.
                listGen = gen
                pager.update { emptyList() }
            }
            throw e
        }
        upperMid = page.upperMid
        val pinned = anchored?.rpid ?: 0L
        if (cursor == null) {
            if (gen != listGen) listGen = gen
            top = page.top.filter { it.rpid != pinned }
            count = page.count
            readOnly = page.readOnly
        }
        val topIds = top.map { it.rpid }.toSet()
        learnRelations(if (cursor == null) page.top + page.replies else page.replies)
        Page(
            items = page.replies.filter { it.rpid !in topIds && it.rpid != pinned },
            hasMore = !page.isEnd && page.replies.isNotEmpty(),
            nextCursor = page.next.toString(),
        )
    }

    /**
     * Holds the ViewModel of a thread open in place over this list (Medium /
     * Expanded), so it outlives a trip to another page; ReplyList clears it once
     * the thread has closed.
     */
    val threadStore = ViewModelStore()

    init {
        ReplyAnchors.take(oid, type)?.let(::loadAnchor)
        pager.refresh()
    }

    private fun loadAnchor(anchor: ReplyAnchor) {
        anchorRpid = anchor.rpid
        viewModelScope.launch {
            val page = runCatching { ReplyApi.thread(oid, type, anchor.root, 1) }.getOrNull() ?: return@launch
            val root = page.root ?: return@launch
            learnRelations(listOf(root) + page.replies)
            val child = page.replies.firstOrNull { it.rpid == anchor.rpid }.takeIf { anchor.rpid != anchor.root }
            anchored = root.copy(replies = listOfNotNull(child))
            // Already listed (置顶, or a page that landed first): it lives only in the pin now.
            top = top.filterNot { it.rpid == root.rpid }
            pager.update { list -> list.filterNot { it.rpid == root.rpid } }
        }
    }

    override fun onCleared() {
        threadStore.clear()
    }

    fun refresh() {
        likes.clear()
        pager.refresh()
    }

    /** Keeps the current list until the new order lands, then swaps. */
    fun changeSort(value: Int) {
        if (value == sort) return
        sort = value
        AppSettings.set(AppSettings.replySort, value)
        requestedGen++
        pager.refresh()
    }

    /**
     * A thread open in place over this list sent or deleted a reply: the
     * root's preview and the counts follow, with no entrance (the list is
     * off screen meanwhile).
     */
    fun mirror(change: ReplyChange) {
        when (change) {
            is ReplyChange.Sent -> insertLocally(change.target, change.reply)
            is ReplyChange.Deleted -> removeLocally(change.rpid)
        }
    }

    override fun removeLocally(rpid: Long) {
        val removedTop = top.any { it.rpid == rpid }
        val removedMain = pager.items.any { it.rpid == rpid } || anchored?.rpid == rpid
        top = top.filterNot { it.rpid == rpid }
        anchored?.let { a ->
            anchored = when {
                a.rpid == rpid -> null
                a.replies.any { it.rpid == rpid } ->
                    a.copy(replies = a.replies.filterNot { it.rpid == rpid }, rcount = (a.rcount - 1).coerceAtLeast(0))
                else -> a
            }
        }
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
            anchored = anchored?.withChild()
            pager.update { list -> list.map { it.withChild() } }
        }
    }
}

internal class ReplyThreadViewModel(oid: Long, type: Int, val rootRpid: Long) : BaseReplyViewModel(oid, type) {
    var root by mutableStateOf<Reply?>(null)
        private set
    var count by mutableLongStateOf(0L)
        private set

    /** The reply a notification opened this thread for was looked for: not again when the page comes back. */
    var focusHandled = false

    /** That reply, once scrolled to: its row carries the highlight. */
    var focusLanded by mutableLongStateOf(0L)

    val pager = Pager<Reply>(viewModelScope, dedupKey = { it.rpid }) { cursor ->
        val pn = cursor?.toIntOrNull() ?: 1
        val page = ReplyApi.thread(oid, type, rootRpid, pn)
        upperMid = page.upperMid
        if (pn == 1) {
            page.root?.let { root = it }
            count = page.count
        }
        learnRelations(listOfNotNull(page.root.takeIf { pn == 1 }) + page.replies)
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
