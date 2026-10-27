package com.gpo.pilipala.feature.feeds

import android.util.Log
import com.gpo.pilipala.feature.home.Blacklist
import com.gpo.pilipala.feature.member.ArchiveOrder
import com.gpo.pilipala.feature.member.MemberRepo
import com.gpo.pilipala.feature.search.SearchApi
import com.gpo.pilipala.feature.search.SearchTab
import com.gpo.pilipala.feature.search.VideoHit
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val TAG = "FeedMixer"

/** Parallel requests per round; more trips bilibili's risk control (-412). */
private const val Parallel = 4

/** A source that fails this many rounds in a row is dropped until the next refresh. */
private const val MaxFailures = 3

/** Refill passes per [FeedMixer.load] before it returns what it has. */
private const val MaxRounds = 3

/**
 * Mixes a feed's sources into one infinite stream.
 *
 * Every source is a lane with its own cursor and a buffer of fetched,
 * not-yet-shown videos. A [load] refills the lanes that ran dry (the next page
 * of each, in parallel), then emits:
 * - [FeedMix.Mixed]: round-robin, one video per lane per turn;
 * - [FeedMix.Latest]: a k-way merge by pubdate — the newest buffered head
 *   wins, and emission pauses as soon as a live lane runs dry, so an older
 *   video never jumps a newer one still on the server. Each fetched page is
 *   sorted newest first before it joins its lane (综合 pages are not).
 *
 * Videos are de-duplicated by bvid across lanes and pages; [exclude] drops
 * blocked UPs (the home blacklist by default). One failing lane does not fail
 * the page; only a page with nothing to show and an error throws. Calls are
 * serialised by a mutex, and a `reset` load starts every lane over (pull to
 * refresh, edited sources).
 */
internal class FeedMixer(
    private val sources: List<FeedSource>,
    private val mix: FeedMix,
    private val exclude: (VideoItem) -> Boolean = { Blacklist.blocks(it.ownerMid) },
) {
    private class Lane(val source: FeedSource) {
        val buffer = ArrayDeque<VideoItem>()
        var cursor: String? = null
        var exhausted = false
        var failures = 0
        var failedThisLoad = false

        /** Still able to produce: has buffered videos or more pages on the server. */
        val live: Boolean get() = buffer.isNotEmpty() || (!exhausted && !failedThisLoad)

        /** Live but empty: must be refilled before a fair emission may go on. */
        val dry: Boolean get() = buffer.isEmpty() && !exhausted && !failedThisLoad
    }

    private val mutex = Mutex()
    private val gate = Semaphore(Parallel)
    private var lanes: List<Lane> = emptyList()
    private val seen = HashSet<String>()
    private var turn = 0

    suspend fun load(reset: Boolean, target: Int = 20): Page<VideoItem> = mutex.withLock {
        if (reset || lanes.isEmpty()) {
            lanes = sources.map(::Lane)
            seen.clear()
            turn = 0
        }
        lanes.forEach { it.failedThisLoad = false }
        val out = ArrayList<VideoItem>(target)
        var error: Throwable? = null
        var rounds = 0
        while (out.size < target && rounds < MaxRounds) {
            rounds++
            val dry = lanes.filter { it.dry }
            if (dry.isNotEmpty()) fill(dry)?.let { if (error == null) error = it }
            when (mix) {
                FeedMix.Mixed -> emitRoundRobin(out, target)
                FeedMix.Latest -> emitLatest(out, target)
            }
            if (lanes.none { it.live }) break
        }
        val hasMore = lanes.any { it.buffer.isNotEmpty() || !it.exhausted }
        val err = error
        if (out.isEmpty() && err != null) throw err
        Page(out, hasMore = hasMore, nextCursor = if (hasMore) "next" else null)
    }

    private fun blocked(): Boolean = lanes.any { it.dry }

    private fun emitRoundRobin(out: MutableList<VideoItem>, target: Int) {
        if (lanes.isEmpty()) return
        while (out.size < target && !blocked()) {
            var progressed = false
            for (i in lanes.indices) {
                val lane = lanes[(turn + i) % lanes.size]
                val item = takeNext(lane) ?: continue
                out += item
                progressed = true
                if (out.size >= target) {
                    turn = (turn + i + 1) % lanes.size
                    return
                }
            }
            if (!progressed) return
        }
    }

    private fun emitLatest(out: MutableList<VideoItem>, target: Int) {
        while (out.size < target && !blocked()) {
            val lane = lanes.filter { it.buffer.isNotEmpty() }.maxByOrNull { it.buffer.first().pubdate } ?: return
            val item = lane.buffer.removeFirst()
            if (accept(item)) out += item
        }
    }

    /** Next acceptable video of [lane], skipping duplicates and blocked UPs. */
    private fun takeNext(lane: Lane): VideoItem? {
        while (lane.buffer.isNotEmpty()) {
            val item = lane.buffer.removeFirst()
            if (accept(item)) return item
        }
        return null
    }

    private fun accept(item: VideoItem): Boolean {
        if (exclude(item)) return false
        val key = item.bvid.ifEmpty { if (item.aid > 0) "av${item.aid}" else "" }
        if (key.isEmpty()) return false
        return seen.add(key)
    }

    /** Fetches the next page of every lane in [dry]; returns the first error, if any. */
    private suspend fun fill(dry: List<Lane>): Throwable? {
        val results = coroutineScope {
            dry.map { lane ->
                async { lane to runCatching { gate.withPermit { fetch(lane.source, lane.cursor) } } }
            }.awaitAll()
        }
        var first: Throwable? = null
        for ((lane, result) in results) {
            result.onSuccess { chunk ->
                val items = if (mix == FeedMix.Latest) chunk.items.sortedByDescending { it.pubdate } else chunk.items
                lane.buffer.addAll(items)
                lane.cursor = chunk.next
                lane.exhausted = !chunk.more || chunk.items.isEmpty()
                lane.failures = 0
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "source ${lane.source.key} failed", e)
                lane.failures++
                lane.failedThisLoad = true
                if (lane.failures >= MaxFailures) lane.exhausted = true
                if (first == null) first = e
            }
        }
        return first
    }

    private class Chunk(val items: List<VideoItem>, val next: String?, val more: Boolean)

    private suspend fun fetch(source: FeedSource, cursor: String?): Chunk = when (source) {
        is FeedSource.Keyword -> {
            // Both sources ticked, the keyword may search YouTube too: cursor "biliPage|ytCursor".
            val mixing = com.gpo.pilipala.core.source.Sources.enabled.value.size > 1
            val where = if (mixing) source.where else KeywordWhere.Bili
            val (pagePart, ytPart) = (cursor ?: "1|").split('|', limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
            val page = pagePart.toIntOrNull() ?: 1
            var biliMore = false
            val bili = if (where != KeywordWhere.YouTube && page > 0) {
                val res = SearchApi.search(SearchTab.Video, source.term, page, source.order.key, duration = 0, tids = -1)
                biliMore = page < res.numPages
                res.items.filterIsInstance<VideoHit>().map { it.item }
            } else {
                emptyList()
            }
            var ytNext: String? = null
            val yt = if (where != KeywordWhere.Bili && ytPart != "end") {
                val res = com.gpo.pilipala.yt.core.extractor.Yt.search(source.term, ytPart.ifEmpty { null })
                ytNext = res.nextCursor
                res.items.filterIsInstance<com.gpo.pilipala.yt.core.extractor.ListItem.Video>()
                    .map { with(com.gpo.pilipala.feature.sources.Cards) { it.video.card() } }
            } else {
                emptyList()
            }
            val items = ArrayList<VideoItem>()
            val a = bili.iterator()
            val b = yt.iterator()
            while (a.hasNext() || b.hasNext()) {
                if (a.hasNext()) items += a.next()
                if (b.hasNext()) items += b.next()
            }
            val nextBili = if (biliMore) page + 1 else 0
            val nextYt = ytNext ?: "end"
            Chunk(items, "$nextBili|$nextYt", more = biliMore || ytNext != null)
        }
        is FeedSource.Up -> {
            val res = MemberRepo.archive(source.mid, cursor, ArchiveOrder.Pubdate)
            val items = res.page.items.map {
                it.copy(
                    ownerMid = it.ownerMid.takeIf { m -> m > 0 } ?: source.mid,
                    ownerName = it.ownerName.ifEmpty { source.name },
                    ownerFace = it.ownerFace.ifEmpty { source.face },
                )
            }
            Chunk(items, res.page.nextCursor, more = res.page.hasMore)
        }
    }
}
