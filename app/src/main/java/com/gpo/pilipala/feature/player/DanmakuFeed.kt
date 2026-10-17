package com.gpo.pilipala.feature.player

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * All danmaku known for one cid: the protobuf segments fetched so far, the
 * viewer's own sent lines, or — when seg.so is unavailable — the XML list.
 * Main-thread confined (mutated from [DanmakuSegmentLoader] and the send sheet).
 */
@Stable
internal class DanmakuFeed(val cid: Long) {
    /** Everything loaded, sorted by time. Replaced (never mutated) on every change. */
    var items by mutableStateOf<List<DanmakuItem>>(emptyList())
        private set

    private val segments = HashMap<Int, List<DanmakuItem>>()
    private val inFlight = HashSet<Int>()
    private val failedAt = HashMap<Int, Long>()
    private val local = ArrayList<DanmakuItem>()
    private var xmlItems: List<DanmakuItem>? = null
    private var xmlLoading = false
    private var anySegmentOk = false

    private val _sent = MutableSharedFlow<DanmakuItem>(extraBufferCapacity = 8)

    /** Lines the viewer just sent, for the engine to show immediately. */
    val sent: SharedFlow<DanmakuItem> = _sent.asSharedFlow()

    private val usingXml get() = xmlItems != null || xmlLoading

    private fun wants(index: Int): Boolean {
        if (index < 0 || usingXml) return false
        if (index in segments || index in inFlight) return false
        val failed = failedAt[index] ?: return true
        return SystemClock.elapsedRealtime() - failed > 15_000
    }

    /** Fetches segment [index] (0-based) unless it is loaded, loading or recently failed. */
    suspend fun ensure(index: Int) {
        if (!wants(index)) return
        inFlight += index
        try {
            val list = DanmakuApi.fetchSegment(cid, index)
            segments[index] = list
            failedAt.remove(index)
            anySegmentOk = true
            rebuild()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("Danmaku", "seg.so cid=$cid index=$index", e)
            failedAt[index] = SystemClock.elapsedRealtime()
            if (!anySegmentOk) loadXml()
        } finally {
            inFlight -= index
        }
    }

    private suspend fun loadXml() {
        if (usingXml) return
        xmlLoading = true
        try {
            xmlItems = DanmakuApi.fetchXml(cid)
            rebuild()
        } catch (e: CancellationException) {
            xmlLoading = false
            throw e
        } catch (e: Throwable) {
            Log.w("Danmaku", "list.so cid=$cid", e)
        } finally {
            if (xmlItems == null) xmlLoading = false
        }
    }

    /** Adds the viewer's own line to the timeline and shows it now. */
    fun addSent(item: DanmakuItem) {
        local += item
        rebuild()
        _sent.tryEmit(item)
    }

    private fun rebuild() {
        val xml = xmlItems
        val out = ArrayList<DanmakuItem>((xml?.size ?: segments.values.sumOf { it.size }) + local.size)
        if (xml != null) {
            out.addAll(xml)
        } else {
            segments.keys.sorted().forEach { out.addAll(segments.getValue(it)) }
        }
        out.addAll(local)
        // Segments are disjoint and pre-sorted, so this is a near-linear TimSort pass.
        out.sortBy { it.timeMs }
        items = out
    }
}

/** A few recent feeds, so inline ↔ fullscreen moves and quick back-and-forth keep their segments. */
internal object DanmakuFeeds {
    private val cache = object : LinkedHashMap<Long, DanmakuFeed>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, DanmakuFeed>?) = size > 3
    }

    fun get(cid: Long): DanmakuFeed = synchronized(cache) { cache.getOrPut(cid) { DanmakuFeed(cid) } }
}

/**
 * Loads the segment under the playhead plus the one after it, following
 * playback and seeks. Waits for the playurl (so a resume position is known)
 * before the first request.
 */
@Composable
internal fun DanmakuSegmentLoader(ctrl: PiliPlayerController, feed: DanmakuFeed?) {
    LaunchedEffect(feed) {
        feed ?: return@LaunchedEffect
        coroutineScope {
            // Retry a segment that failed (network blip) without waiting for the next boundary.
            launch {
                while (isActive) {
                    delay(20_000)
                    if (ctrl.info != null) feed.ensure((ctrl.positionMs / DanmakuApi.SEGMENT_MS).toInt())
                }
            }
            snapshotFlow {
                if (ctrl.info == null) -1 else (ctrl.positionMs / DanmakuApi.SEGMENT_MS).toInt()
            }
                .distinctUntilChanged()
                .collect { index ->
                    if (index < 0) return@collect
                    launch { feed.ensure(index) }
                    val duration = ctrl.durationMs
                    if (duration <= 0 || (index + 1) * DanmakuApi.SEGMENT_MS < duration) {
                        launch { feed.ensure(index + 1) }
                    }
                }
        }
    }
}
