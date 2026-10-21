package com.gpo.pilipala.feature.widget

import com.gpo.pilipala.core.util.ScopeErrors
import android.content.Context
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.feature.video.AppShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File

/** The video last left part-way (what AppShortcuts' 继续看 points at). */
@Serializable
internal data class ContinueEntry(
    val bvid: String,
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val positionMs: Long = 0,
    /** 0 when unknown (an entry recovered from an older shortcut). */
    val durationMs: Long = 0,
    /** A bangumi episode's id: 继续看 opens its bangumi page. */
    val epId: Long = 0,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val remainingMs: Long get() = if (durationMs > 0) (durationMs - positionMs).coerceAtLeast(0) else 0
}

/** One 稍后再看 entry. [epId] > 0: a bangumi episode (opened through its link). */
@Serializable
internal data class LaterEntry(
    val bvid: String,
    val aid: Long = 0,
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    /** Seconds watched; -1 = finished. */
    val progress: Long = 0,
    val duration: Long = 0,
    val epId: Long = 0,
)

@Serializable
internal data class LaterCache(
    val mid: Long = 0,
    val fetchedAt: Long = 0,
    val count: Int = 0,
    val items: List<LaterEntry> = emptyList(),
)

/**
 * The widget's data, cached in two small files under `filesDir/widget/` so the widget
 * renders offline and straight after a reboot. 继续看 is written by AppShortcuts as a
 * video page pauses or closes; 稍后再看 is fetched (one read-only GET) when the widget
 * updates and the cached copy is older than [LaterMaxAgeMs].
 */
internal object WidgetStore {
    private const val Dir = "widget"
    private const val ContinueFile = "continue.json"
    private const val LaterFile = "later.json"

    /** A cached 稍后再看 list younger than this is shown as is. */
    private const val LaterMaxAgeMs = 10 * 60_000L

    /** How long a widget update waits for the list before it renders the cached one. */
    private const val FetchTimeoutMs = 6_000L

    /** The widget never shows more than this many entries; no need to keep more. */
    private const val LaterKeep = 8

    private val lock = Any()

    /** Set by PiliApp: lets the app's 稍后再看 writes ([laterChanged]) reach the widget. */
    @Volatile var appContext: Context? = null

    /**
     * Bumped whenever what the widget shows may have changed ([WatchWidget.refresh]). A
     * running Glance session only recomposes on update and never reruns provideGlance, so
     * the widget reloads its data when this moves.
     */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun changed() {
        _version.update { it + 1 }
    }

    /**
     * The list's GET runs here, detached from the widget update: OkHttp's blocking call
     * ignores cancellation, so a timeout around it would still wait for the socket. The
     * update stops waiting after [FetchTimeoutMs]; a late answer still lands in the cache.
     */
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeErrors)
    private var inflight: Pair<Long, Deferred<LaterCache?>>? = null

    /** The signed-in account's mid, 0 when logged out. */
    fun currentMid(): Long = runCatching { if (BiliClient.isLoggedIn) BiliClient.mid else 0L }.getOrDefault(0L)

    private fun file(context: Context, name: String): File =
        File(context.applicationContext.filesDir, Dir).apply { mkdirs() }.resolve(name)

    fun writeContinue(context: Context, entry: ContinueEntry) {
        write(context, ContinueFile, BiliJson.encodeToString(ContinueEntry.serializer(), entry))
    }

    fun clearContinue(context: Context) {
        synchronized(lock) { runCatching { file(context, ContinueFile).delete() } }
    }

    /**
     * The cached entry; without one (the widget placed before anything was written here)
     * the 继续看 shortcut AppShortcuts already published, so the widget never starts empty
     * while the launcher has a video to resume.
     */
    fun readContinue(context: Context): ContinueEntry? {
        read(context, ContinueFile)?.let { text ->
            return runCatching { BiliJson.decodeFromString(ContinueEntry.serializer(), text) }.getOrNull()
                ?.takeIf { it.bvid.isNotBlank() }
        }
        val s = AppShortcuts.continueSnapshot(context) ?: return null
        return ContinueEntry(
            bvid = s.bvid,
            cid = s.cid,
            title = s.title,
            cover = s.cover,
            positionMs = s.positionSec * 1000,
            durationMs = s.durationSec * 1000,
            epId = s.epId,
        )
    }

    fun readLater(context: Context): LaterCache? =
        read(context, LaterFile)?.let { text ->
            runCatching { BiliJson.decodeFromString(LaterCache.serializer(), text) }.getOrNull()
        }

    /**
     * The 稍后再看 list for the widget: the cache when it is fresh, else one GET of
     * `/x/v2/history/toview` with a short timeout (falling back to the cache). Logged out,
     * or the cache belongs to another account: nothing.
     */
    suspend fun laterForWidget(context: Context, force: Boolean = false): LaterCache? {
        val mid = currentMid()
        if (mid <= 0L) {
            synchronized(lock) { runCatching { file(context, LaterFile).delete() } }
            return null
        }
        val cached = readLater(context)?.takeIf { it.mid == mid }
        val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAt in 0..LaterMaxAgeMs
        if (fresh && !force) return cached
        val job = fetch(context.applicationContext, mid)
        val fetched = withTimeoutOrNull(FetchTimeoutMs) { job.await() }
        if (fetched != null) return fetched
        if (!job.isCompleted) {
            // Too slow for this update: the widget shows the cached list now and the fresh one once it lands.
            fetchScope.launch { if (job.await() != null) WatchWidget.refresh(context) }
        }
        return cached
    }

    /**
     * The app added to or removed from 稍后再看: the cached list is stale whatever its age,
     * so placed widgets refetch it on the refresh that follows.
     */
    fun laterChanged() {
        val app = appContext ?: return
        fetchScope.launch {
            readLater(app)?.let { write(app, LaterFile, BiliJson.encodeToString(LaterCache.serializer(), it.copy(fetchedAt = 0))) }
            WatchWidget.refresh(app)
        }
    }

    /** One GET per account at a time; a widget update during a slow one waits on the same call. */
    private fun fetch(context: Context, mid: Long): Deferred<LaterCache?> = synchronized(lock) {
        inflight?.takeIf { it.first == mid && it.second.isActive }?.second
            ?: fetchScope.async {
                val fetched = runCatching { fetchLater(mid) }.getOrNull()
                // Signed out (or switched) while it was in flight: not this account's list any more.
                if (fetched != null && currentMid() == mid) {
                    write(context, LaterFile, BiliJson.encodeToString(LaterCache.serializer(), fetched))
                }
                fetched
            }.also { inflight = mid to it }
    }

    /** Read-only: the same GET the 稍后再看 page makes. */
    private suspend fun fetchLater(mid: Long): LaterCache {
        val data = BiliClient.getData("/x/v2/history/toview").asObj
        val items = data.arr("list").objects().take(LaterKeep).map { o ->
            LaterEntry(
                bvid = o.str("bvid"),
                aid = o.long("aid"),
                cid = o.long("cid"),
                title = o.str("title"),
                cover = o.str("pic"),
                progress = o.long("progress"),
                duration = o.long("duration"),
                epId = o.obj("bangumi").long("ep_id"),
            )
        }.filter { it.bvid.isNotBlank() || it.epId > 0 }
        return LaterCache(mid = mid, fetchedAt = System.currentTimeMillis(), count = data.int("count"), items = items)
    }

    private fun write(context: Context, name: String, text: String) {
        synchronized(lock) {
            runCatching {
                val target = file(context, name)
                val tmp = File(target.parentFile, "$name.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(target)) {
                    target.writeText(text)
                    tmp.delete()
                }
            }
        }
    }

    private fun read(context: Context, name: String): String? = synchronized(lock) {
        runCatching { file(context, name).takeIf { it.isFile }?.readText() }.getOrNull()
    }
}
