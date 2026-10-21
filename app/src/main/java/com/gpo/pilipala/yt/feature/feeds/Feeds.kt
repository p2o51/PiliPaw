package com.gpo.pilipala.yt.feature.feeds

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.yt.core.extractor.ListItem
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.yt.feature.mute.Mutes
import com.gpo.pilipala.yt.ui.components.Page
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// PiliPaw's 自建 Feed (feature/feeds), the same model with YouTube's sources: a keyword (its
// video search) or a channel (its uploads), mixed into one stream that is a Home tab.

const val MaxFeeds = 10
const val MaxSources = 10
const val MaxFeedName = 16

/** How a feed interleaves its sources: one from each in turn, or newest first across all. */
@Serializable
enum class FeedMix(val label: String) {
    @SerialName("mixed") Mixed("混合"),
    @SerialName("latest") Latest("最新"),
}

@Serializable
sealed interface FeedSource {
    val key: String
    val label: String

    @Immutable
    @Serializable
    @SerialName("keyword")
    data class Keyword(val term: String) : FeedSource {
        override val key: String get() = "k:" + term.trim().lowercase()
        override val label: String get() = term
    }

    @Immutable
    @Serializable
    @SerialName("channel")
    data class Channel(val url: String, val name: String, val avatar: String = "") : FeedSource {
        override val key: String get() = "c:" + url.trimEnd('/').substringAfterLast('/')
        override val label: String get() = name
    }
}

@Immutable
@Serializable
data class Feed(val id: String, val name: String, val sources: List<FeedSource>, val mix: FeedMix = FeedMix.Mixed)

object FeedStore {
    private val stored = AppSettings.register("custom_feeds", "")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Feed.serializer())

    private val _feeds = MutableStateFlow(decode(stored.value))
    val feeds: StateFlow<List<Feed>> = _feeds.asStateFlow()

    val canCreate: Boolean get() = _feeds.value.size < MaxFeeds

    fun get(id: String): Feed? = _feeds.value.firstOrNull { it.id == id }

    /** Creates or replaces [id] (null: a new feed). Returns the feed, or null when it has no sources. */
    fun save(id: String?, name: String, sources: List<FeedSource>, mix: FeedMix): Feed? {
        val clean = sources.distinctBy { it.key }.take(MaxSources)
        if (clean.isEmpty()) return null
        val n = name.trim().take(MaxFeedName).ifEmpty { clean.first().label.take(MaxFeedName) }
        val feed = Feed(id ?: ("f" + System.currentTimeMillis().toString(36)), n, clean, mix)
        commit(if (id == null) _feeds.value + feed else _feeds.value.map { if (it.id == id) feed else it })
        return feed
    }

    fun delete(id: String) = commit(_feeds.value.filterNot { it.id == id })

    private fun commit(list: List<Feed>) {
        _feeds.value = list
        AppSettings.set(stored, json.encodeToString(serializer, list))
    }

    private fun decode(raw: String): List<Feed> =
        raw.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}

/**
 * Mixes a feed's sources into one paged stream (PiliPaw's FeedMixer): each source a lane with
 * its own cursor and a buffer; a page refills the lanes that ran dry (in parallel), then emits
 * [FeedMix.Mixed] round robin, or [FeedMix.Latest] newest first across the lanes' heads.
 * Videos are de-duplicated and muted ones dropped; one failing lane does not fail the page.
 */
class FeedMixer(private val feed: Feed) {
    private class Lane(val source: FeedSource) {
        val buffer = ArrayDeque<VideoItem>()
        var cursor: String? = null
        var started = false
        var exhausted = false
        var failures = 0
    }

    private val lanes = feed.sources.map(::Lane)
    private val seen = HashSet<String>()
    private val mutex = Mutex()

    suspend fun load(reset: Boolean): Page<VideoItem> = mutex.withLock {
        if (reset) {
            lanes.forEach {
                it.buffer.clear(); it.cursor = null; it.started = false; it.exhausted = false; it.failures = 0
            }
            seen.clear()
        }
        var lastError: Throwable? = null
        val out = ArrayList<VideoItem>()
        repeat(3) {
            coroutineScope {
                lanes.filter { it.buffer.size < 4 && !it.exhausted && it.failures < 3 }.map { lane ->
                    async {
                        runCatching { fill(lane) }.onFailure {
                            lane.failures++
                            lastError = it
                        }
                    }
                }.awaitAll()
            }
            emit(out)
            if (out.size >= PageSize || lanes.all { it.buffer.isEmpty() && (it.exhausted || it.failures >= 3) }) return@repeat
        }
        if (out.isEmpty() && lastError != null) throw lastError!!
        val more = lanes.any { it.buffer.isNotEmpty() || (!it.exhausted && it.failures < 3) }
        Page(out, hasMore = more, nextCursor = if (more) "more" else null)
    }

    private suspend fun fill(lane: Lane) {
        val (items, next) = when (val s = lane.source) {
            is FeedSource.Keyword -> {
                val page = Yt.search(s.term, lane.cursor.takeIf { lane.started }, Yt.SearchFilter(type = Yt.SearchFilter.Type.Videos))
                page.items.filterIsInstance<ListItem.Video>().map { it.video } to page.nextCursor
            }
            is FeedSource.Channel -> {
                if (!lane.started) {
                    val info = Yt.channel(s.url)
                    val page = Yt.channelFirstPage(info)
                    page.items.map { if (it.channelAvatar.isEmpty()) it.copy(channelAvatar = s.avatar) else it } to page.nextCursor
                } else {
                    val page = Yt.channelVideos(s.url, lane.cursor!!)
                    page.items.map { if (it.channelAvatar.isEmpty()) it.copy(channelAvatar = s.avatar) else it } to page.nextCursor
                }
            }
        }
        lane.started = true
        lane.cursor = next
        if (next == null) lane.exhausted = true
        val fresh = items.filter { !it.isShort && !Mutes.hides(it) }
        lane.buffer.addAll(if (feed.mix == FeedMix.Latest) fresh.sortedByDescending { it.uploadedAt } else fresh)
    }

    private fun emit(out: MutableList<VideoItem>) {
        fun take(v: VideoItem) {
            if (seen.add(v.url)) out += v
        }
        when (feed.mix) {
            FeedMix.Mixed -> while (out.size < PageSize && lanes.any { it.buffer.isNotEmpty() }) {
                for (lane in lanes) lane.buffer.removeFirstOrNull()?.let(::take)
            }
            FeedMix.Latest -> while (out.size < PageSize) {
                // A live lane that ran dry may still hold newer videos: wait for it.
                if (lanes.any { it.buffer.isEmpty() && !it.exhausted && it.failures < 3 }) break
                val lane = lanes.filter { it.buffer.isNotEmpty() }.maxByOrNull { it.buffer.first().uploadedAt } ?: break
                take(lane.buffer.removeFirst())
            }
        }
    }

    private companion object {
        const val PageSize = 20
    }
}
