package com.gpo.pilipala.feature.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.navigation.Route
import com.gpo.yoin.symbols.SymbolPlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** One entry of a play queue (a 收藏夹 / 稍后再看 played like a music playlist). */
@Immutable
data class QueueItem(
    val bvid: String,
    val aid: Long = 0,
    /** First page's cid when the list knows it; resolved on demand otherwise. */
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val owner: String = "",
    /** Seconds. */
    val duration: Long = 0,
)

/**
 * Yoin's play modes: one button, a tap steps 列表循环 → 随机 → 单曲循环 → 列表循环.
 * Shuffle plays a stable shuffled order (built when shuffle turns on, current item first).
 */
enum class PlayMode(val label: String, val symbol: SymbolPlayMode) {
    ListLoop("列表循环", SymbolPlayMode.RepeatAll),
    Shuffle("随机", SymbolPlayMode.Shuffle),
    RepeatOne("单曲循环", SymbolPlayMode.RepeatOne),
    ;

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}

/**
 * An in-memory play queue. The player ([PiliPlayerController]) drives it — it advances on
 * completion (also in the background), on notification / headset previous-next and on taps
 * in the list — and the video page follows [index]. State lives in flows so the
 * non-Compose sides (controller, view model) can observe it while the UI is paused.
 */
@Stable
class PlayQueue internal constructor(
    val id: String,
    val title: String,
    val items: List<QueueItem>,
    startIndex: Int,
) {
    private val _index = MutableStateFlow(startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _mode = MutableStateFlow(PlayMode.ListLoop)
    val mode: StateFlow<PlayMode> = _mode.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    /** Shuffle order (indices into [items]); stable while shuffle stays on. */
    private var order: IntArray = IntArray(0)

    /** cids learned after the queue was built, by bvid. */
    private val cids = ConcurrentHashMap<String, Long>()

    val size: Int get() = items.size
    val current: QueueItem get() = items[_index.value]

    fun cycleMode() = setMode(_mode.value.next())

    fun setMode(m: PlayMode) {
        if (m == PlayMode.Shuffle && _mode.value != PlayMode.Shuffle) {
            val cur = _index.value
            order = (listOf(cur) + items.indices.filter { it != cur }.shuffled()).toIntArray()
        }
        _mode.value = m
    }

    fun setAudioOnly(on: Boolean) {
        _audioOnly.value = on
    }

    /** The item after the current one in play order (wraps around: the list loops). */
    fun nextIndex(): Int = step(+1)

    /** The item before the current one in play order. */
    fun previousIndex(): Int = step(-1)

    private fun step(delta: Int): Int {
        val n = items.size
        if (n == 0) return 0
        val i = _index.value
        if (_mode.value == PlayMode.Shuffle && order.size == n) {
            val pos = order.indexOf(i).coerceAtLeast(0)
            return order[Math.floorMod(pos + delta, n)]
        }
        return Math.floorMod(i + delta, n)
    }

    internal fun moveTo(i: Int) {
        if (i in items.indices) _index.value = i
    }

    fun cidOf(i: Int): Long {
        val item = items.getOrNull(i) ?: return 0
        return item.cid.takeIf { it > 0 } ?: cids[item.bvid] ?: 0
    }

    internal fun rememberCid(bvid: String, cid: Long) {
        if (cid > 0) cids[bvid] = cid
    }
}

/** Process-wide registry so a [Route.Video] can carry a queue by id. */
object PlayQueues {
    private val queues = LinkedHashMap<String, PlayQueue>()
    private var seq = 0

    /** Registers a queue of the playable [items] (blank bvids dropped); null when nothing is left. */
    fun create(title: String, items: List<QueueItem>, startIndex: Int = 0): PlayQueue? {
        val playable = items.filter { it.bvid.isNotBlank() }.distinctBy { it.bvid }
        if (playable.isEmpty()) return null
        val start = items.getOrNull(startIndex)?.let { s -> playable.indexOfFirst { it.bvid == s.bvid } }?.takeIf { it >= 0 } ?: 0
        return synchronized(this) {
            val id = "q${++seq}_${System.currentTimeMillis()}"
            val q = PlayQueue(id, title, playable, start)
            queues[id] = q
            while (queues.size > 8) queues.remove(queues.keys.first())
            q
        }
    }

    fun get(id: String): PlayQueue? = if (id.isEmpty()) null else synchronized(this) { queues[id] }

    /** The video page for the queue's current item, with the queue attached. */
    fun route(q: PlayQueue): Route.Video {
        val item = q.current
        return Route.Video(
            bvid = item.bvid,
            cid = q.cidOf(q.index.value),
            aid = item.aid,
            cover = item.cover,
            title = item.title,
            playlistId = q.id,
        )
    }
}

/** First page's cid of a video (queue items from lists that do not carry one). */
internal suspend fun resolveFirstCid(item: QueueItem): Long {
    val params = if (item.bvid.isNotBlank()) mapOf("bvid" to item.bvid) else mapOf("aid" to item.aid)
    return BiliClient.getData("/x/player/pagelist", params).asArr?.objects()?.firstOrNull()?.long("cid")
        ?: BiliClient.getData("/x/web-interface/view", params).asObj.long("cid")
}

/** Attaches [queue] to the player behind this state (null detaches). */
fun BiliPlayerState.attachQueue(queue: PlayQueue?) {
    (this as? PiliPlayerController)?.attachQueue(queue)
}

/** Plays item [index] of the attached queue. */
fun BiliPlayerState.playQueueItem(index: Int) {
    (this as? PiliPlayerController)?.playQueueIndex(index)
}
