package com.gpo.pilipala.feature.widget

import com.gpo.pilipala.core.util.ScopeErrors
import android.content.Context
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.feature.feeds.Feed
import com.gpo.pilipala.feature.feeds.FeedMixer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File

/** One video of a feed widget. */
@Serializable
internal data class FeedWidgetItem(
    val bvid: String,
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val owner: String = "",
    /** Seconds. */
    val duration: Long = 0,
)

/** The latest mixed videos of one feed; [contentKey] names the sources and mix they came from. */
@Serializable
internal data class FeedWidgetCache(
    val feedId: String = "",
    val contentKey: String = "",
    val fetchedAt: Long = 0,
    val items: List<FeedWidgetItem> = emptyList(),
)

/**
 * The feed widget's data: which feed each placed widget shows (by appWidgetId), and per
 * feed the first mixed page cached under `filesDir/widget/feed_<id>.json`, so a widget
 * renders offline and after a reboot. A fetch is FeedMixer's first page (read-only GETs).
 */
internal object FeedWidgetStore {
    private const val Prefs = "feed_widget"
    private const val Dir = "widget"

    /** The most any layout shows (3 columns × 3 rows). */
    const val Keep = 9

    /** Shown as is when younger than this (a widget update, the app opening). */
    const val MaxAgeMs = 30 * 60_000L

    /** How long one widget update waits for a fetch before it renders the cached page. */
    private const val FetchTimeoutMs = 12_000L

    private val lock = Any()
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeErrors)
    private val inflight = HashMap<String, Deferred<FeedWidgetCache?>>()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)

    fun bind(context: Context, appWidgetId: Int, feedId: String) {
        prefs(context).edit().putString("w$appWidgetId", feedId).apply()
    }

    fun boundFeed(context: Context, appWidgetId: Int): String? =
        prefs(context).getString("w$appWidgetId", null)?.takeIf { it.isNotEmpty() }

    fun unbind(context: Context, appWidgetIds: IntArray) {
        val e = prefs(context).edit()
        appWidgetIds.forEach { e.remove("w$it") }
        e.apply()
    }

    /** Feed ids some placed widget shows. */
    fun boundFeeds(context: Context): Set<String> =
        prefs(context).all.values.mapNotNullTo(HashSet()) { (it as? String)?.takeIf { s -> s.isNotEmpty() } }

    fun contentKey(feed: Feed): String = feed.sources.joinToString(",") { it.key } + "|" + feed.mix.name

    private fun file(context: Context, feedId: String): File =
        File(context.applicationContext.filesDir, Dir).apply { mkdirs() }.resolve("feed_$feedId.json")

    fun read(context: Context, feedId: String): FeedWidgetCache? = synchronized(lock) {
        runCatching {
            file(context, feedId).takeIf { it.isFile }?.readText()
                ?.let { BiliJson.decodeFromString(FeedWidgetCache.serializer(), it) }
        }.getOrNull()
    }

    private fun write(context: Context, cache: FeedWidgetCache) = synchronized(lock) {
        runCatching {
            val target = file(context, cache.feedId)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(BiliJson.encodeToString(FeedWidgetCache.serializer(), cache))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    /** Drops cached pages no placed widget shows any more. */
    fun prune(context: Context) {
        val keep = boundFeeds(context)
        synchronized(lock) {
            runCatching {
                File(context.applicationContext.filesDir, Dir).listFiles()?.forEach { f ->
                    val id = f.name.removePrefix("feed_").removeSuffix(".json")
                    if (f.name.startsWith("feed_") && f.name.endsWith(".json") && id !in keep) f.delete()
                }
            }
        }
    }

    /** Usable for [feed]: same sources and mix, whatever its age. */
    fun cached(context: Context, feed: Feed): FeedWidgetCache? =
        read(context, feed.id)?.takeIf { it.contentKey == contentKey(feed) }

    fun isFresh(cache: FeedWidgetCache?): Boolean =
        cache != null && System.currentTimeMillis() - cache.fetchedAt in 0..MaxAgeMs

    /**
     * The page for a widget update: the cache when fresh (and not [force]d), else a fetch
     * that may take [FetchTimeoutMs] before the cached page is used; a fetch that lands
     * later still fills the cache and re-renders the widgets.
     */
    suspend fun forWidget(context: Context, feed: Feed, force: Boolean = false): FeedWidgetCache? {
        val cached = cached(context, feed)
        if (!force && isFresh(cached)) return cached
        val job = fetch(context.applicationContext, feed)
        val fetched = withTimeoutOrNull(FetchTimeoutMs) { job.await() }
        if (fetched != null) return fetched
        if (!job.isCompleted) {
            fetchScope.async { if (job.await() != null) FeedWidget.refresh(context) }
        }
        return cached
    }

    /** Fetches [feed] now and waits for it (the periodic worker). */
    suspend fun refetch(context: Context, feed: Feed): FeedWidgetCache? = fetch(context.applicationContext, feed).await()

    /** One fetch per feed at a time; later callers wait on the same one. */
    private fun fetch(context: Context, feed: Feed): Deferred<FeedWidgetCache?> = synchronized(lock) {
        inflight[feed.id]?.takeIf { it.isActive }
            ?: fetchScope.async {
                runCatching {
                    BiliClient.ensureBuvid()
                    val page = FeedMixer(feed.sources, feed.mix).load(reset = true, target = Keep)
                    val items = page.items.filter { it.bvid.isNotBlank() }.take(Keep).map {
                        FeedWidgetItem(
                            bvid = it.bvid,
                            cid = it.cid,
                            title = it.title,
                            cover = it.cover,
                            owner = it.ownerName,
                            duration = it.duration,
                        )
                    }
                    FeedWidgetCache(feed.id, contentKey(feed), System.currentTimeMillis(), items)
                }.getOrNull()
                    ?.takeIf { it.items.isNotEmpty() }
                    ?.also { write(context, it) }
            }.also { inflight[feed.id] = it }
    }
}
