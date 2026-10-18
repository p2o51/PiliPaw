package com.gpo.pilipala.feature.feeds

import androidx.compose.runtime.Immutable
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Images
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

// ── Model ───────────────────────────────────────────────────────────────────

/** Most feeds a user keeps (each one is a home tab). */
internal const val MaxFeeds = 10

/** Most sources in one feed: every source costs one request per page. */
internal const val MaxSources = 10

internal const val MaxFeedName = 16

/** Search order of one keyword source (`order` of search/type). */
@Serializable
internal enum class KeywordOrder(val key: String, val label: String) {
    @SerialName("totalrank") Totalrank("totalrank", "综合"),
    @SerialName("pubdate") Pubdate("pubdate", "最新"),
}

/** How a feed interleaves its sources: one from each in turn, or newest first across all. */
@Serializable
internal enum class FeedMix(val label: String) {
    @SerialName("mixed") Mixed("混合"),
    @SerialName("latest") Latest("最新"),
}

/** One input of a feed: a search keyword or an UP's uploads. */
@Serializable
internal sealed interface FeedSource {
    /** Identity inside a feed (a keyword once, an UP once). */
    val key: String
    val label: String

    @Immutable
    @Serializable
    @SerialName("keyword")
    data class Keyword(val term: String, val order: KeywordOrder = KeywordOrder.Totalrank) : FeedSource {
        override val key: String get() = keywordKey(term)
        override val label: String get() = term
    }

    @Immutable
    @Serializable
    @SerialName("up")
    data class Up(val mid: Long, val name: String, val face: String = "") : FeedSource {
        override val key: String get() = upKey(mid)
        override val label: String get() = name.ifEmpty { "UID $mid" }
    }

    companion object {
        fun keywordKey(term: String): String = "k:" + term.trim().lowercase()
        fun upKey(mid: Long): String = "u:$mid"
    }
}

/**
 * A user-built feed (Threads' custom feeds): a name and up to [MaxSources]
 * sources mixed into one stream, shown as a home tab.
 */
@Immutable
@Serializable
internal data class Feed(
    val id: String,
    val name: String,
    val sources: List<FeedSource>,
    val mix: FeedMix = FeedMix.Mixed,
    val createdAt: Long = 0,
) {
    fun contains(source: FeedSource): Boolean = sources.any { it.key == source.key }

    /** What the mixed stream depends on (a rename does not reload the tab). */
    val content: Pair<List<FeedSource>, FeedMix> get() = sources to mix

    /** Home tab key. */
    val tabKey: String get() = "feed:$id"
}

/** Name a feed gets when the user leaves the field empty. */
internal fun defaultFeedName(sources: List<FeedSource>): String =
    sources.firstOrNull()?.label?.take(MaxFeedName).orEmpty().ifEmpty { "Feed" }

// ── Store ───────────────────────────────────────────────────────────────────

internal enum class AddResult { Added, AlreadyIn, Full, Missing }

/**
 * The user's feeds, persisted as one JSON array in the settings store and
 * observable as [feeds] (list order = tab order). Writes come from UI actions
 * on the main thread; the lock only keeps read-modify-write atomic.
 */
internal object FeedStore {
    private val stored = AppSettings.register("custom_feeds", "")
    private val serializer = ListSerializer(Feed.serializer())

    private val _feeds = MutableStateFlow(decode(stored.value))
    val feeds: StateFlow<List<Feed>> = _feeds.asStateFlow()

    /** A feed home should switch to the next time it is on screen (set after creating one). */
    private val _focus = MutableStateFlow<String?>(null)
    val focusRequest: StateFlow<String?> = _focus.asStateFlow()

    val canCreate: Boolean get() = _feeds.value.size < MaxFeeds

    fun get(id: String): Feed? = _feeds.value.firstOrNull { it.id == id }

    /** True when some feed already mixes [source]. */
    fun inAnyFeed(source: FeedSource): Boolean = _feeds.value.any { it.contains(source) }

    fun create(name: String, sources: List<FeedSource>, mix: FeedMix): Feed? = synchronized(this) {
        if (!canCreate) return null
        val clean = normalize(sources)
        if (clean.isEmpty()) return null
        val feed = Feed(
            id = newId(),
            name = cleanName(name, clean),
            sources = clean,
            mix = mix,
            createdAt = System.currentTimeMillis(),
        )
        commit(_feeds.value + feed)
        feed
    }

    fun update(id: String, name: String, sources: List<FeedSource>, mix: FeedMix): Boolean = synchronized(this) {
        val clean = normalize(sources)
        if (clean.isEmpty() || get(id) == null) return false
        commit(_feeds.value.map { if (it.id != id) it else it.copy(name = cleanName(name, clean), sources = clean, mix = mix) })
        true
    }

    fun rename(id: String, name: String) = synchronized(this) {
        val n = name.trim().take(MaxFeedName)
        if (n.isEmpty()) return
        commit(_feeds.value.map { if (it.id == id) it.copy(name = n) else it })
    }

    fun delete(id: String) = synchronized(this) {
        commit(_feeds.value.filterNot { it.id == id })
        if (_focus.value == id) _focus.value = null
    }

    /** Moves a feed [by] places (negative = towards the front). */
    fun move(id: String, by: Int) = synchronized(this) {
        val list = _feeds.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + by).coerceIn(0, list.lastIndex)
        if (to == from) return
        list.add(to, list.removeAt(from))
        commit(list)
    }

    fun addSource(id: String, source: FeedSource): AddResult = synchronized(this) {
        val feed = get(id) ?: return AddResult.Missing
        if (feed.contains(source)) return AddResult.AlreadyIn
        if (feed.sources.size >= MaxSources) return AddResult.Full
        val clean = normalize(listOf(source)).firstOrNull() ?: return AddResult.Missing
        commit(_feeds.value.map { if (it.id == id) it.copy(sources = it.sources + clean) else it })
        AddResult.Added
    }

    /** False when the feed would be left without sources (delete the feed instead). */
    fun removeSource(id: String, sourceKey: String): Boolean = synchronized(this) {
        val feed = get(id) ?: return false
        val kept = feed.sources.filterNot { it.key == sourceKey }
        if (kept.isEmpty() || kept.size == feed.sources.size) return false
        commit(_feeds.value.map { if (it.id == id) it.copy(sources = kept) else it })
        true
    }

    fun requestFocus(id: String) {
        _focus.value = id
    }

    fun consumeFocus(id: String) {
        if (_focus.value == id) _focus.value = null
    }

    private fun cleanName(name: String, sources: List<FeedSource>): String =
        name.trim().take(MaxFeedName).ifEmpty { defaultFeedName(sources) }

    private fun normalize(sources: List<FeedSource>): List<FeedSource> =
        sources
            .mapNotNull { s ->
                when (s) {
                    is FeedSource.Keyword -> s.term.trim().takeIf { it.isNotEmpty() }?.let { s.copy(term = it) }
                    is FeedSource.Up -> if (s.mid > 0) s.copy(name = s.name.trim(), face = Images.normalize(s.face)) else null
                }
            }
            .distinctBy { it.key }
            .take(MaxSources)

    private fun commit(list: List<Feed>) {
        _feeds.value = list
        AppSettings.set(stored, if (list.isEmpty()) "" else BiliJson.encodeToString(serializer, list))
    }

    private fun decode(raw: String): List<Feed> {
        if (raw.isBlank()) return emptyList()
        return runCatching { BiliJson.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotEmpty() && it.sources.isNotEmpty() }
            .distinctBy { it.id }
            .take(MaxFeeds)
    }

    private fun newId(): String {
        val taken = _feeds.value.mapTo(HashSet()) { it.id }
        while (true) {
            val id = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
            if (id !in taken) return id
        }
    }
}
