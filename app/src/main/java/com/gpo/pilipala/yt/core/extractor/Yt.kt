package com.gpo.pilipala.yt.core.extractor

import android.content.Context
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.yt.ui.components.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.extractor.Page as ExtractorPage

/**
 * The app's only door to PipePipeExtractor. Every call is a suspend function on [Dispatchers.IO];
 * the UI never touches an extractor type except [StreamInfo], which the player resolves itself.
 * Paging cursors are opaque tokens for the extractor's own [ExtractorPage]s ([Cursors]).
 */
object Yt {
    lateinit var downloader: PawDownloader
        private set

    private val service: StreamingService get() = ServiceList.YouTube

    fun init(context: Context, http: OkHttpClient) {
        downloader = PawDownloader(http)
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val country = AppSettings.contentCountry.value.ifEmpty { locale.country.ifEmpty { "US" } }
        NewPipe.init(downloader, localization(locale), ContentCountry(country))
        // Signed out: the client PipePipe defaults to (plain stream URLs, no SABR, no PO token).
        NewPipe.setYoutubePlayerClient("visionos")
        // The signed-in account's cookies go with the extractor's account calls (it builds the
        // SAPISIDHASH authorization from them); signed out there are none.
        accountScope.launch {
            Accounts.youtube.collect { profile -> service.tokens = (profile as? Profile.SignedIn)?.cookies }
        }
    }

    private val accountScope = MainScope()

    /**
     * The device language as YouTube's `hl`. youtubei refuses (400 INVALID_ARGUMENT, on every
     * signed-in browse) a language-region pair it has no variant for: "en-JP", "zh-JP", "zh-SG",
     * and "zh-" from a region-less locale. A bare language is always accepted, so that is sent
     * unless the region picks one of YouTube's own variants.
     */
    internal fun localization(locale: Locale): Localization {
        val lang = locale.language
        val region = locale.country
        val variant = when (lang) {
            "zh" -> when {
                region == "HK" || region == "MO" -> "HK"
                region == "TW" || locale.script == "Hant" -> "TW"
                else -> "CN"
            }
            "en" -> region.takeIf { it == "GB" || it == "IN" }
            "pt" -> if (region == "BR") "BR" else "PT"
            "es" -> if (region.isEmpty() || region == "ES") null else "419"
            "fr" -> region.takeIf { it == "CA" }
            else -> null
        }
        return if (variant == null) Localization(lang) else Localization(lang, variant)
    }

    // ── Streams ─────────────────────────────────────────────────────────────

    suspend fun stream(url: String): StreamInfo = io { StreamInfo.getInfo(service, url) }

    // ── Search ──────────────────────────────────────────────────────────────

    /** What a search asks for (搜索筛选); the defaults are plain YouTube search. */
    @kotlinx.serialization.Serializable
    data class SearchFilter(
        val type: Type = Type.All,
        val sort: Sort = Sort.Relevance,
        val date: Date = Date.Any,
        val length: Length = Length.Any,
    ) {
        /** Content filter ids of PipePipe's YoutubeFilters, in its order. */
        enum class Type(val label: String, val id: Int) { All("全部", 0), Videos("视频", 1), Channels("频道", 2), Playlists("播放列表", 3) }
        /** Items of its "sortby" group, by position. */
        enum class Sort(val label: String) { Relevance("相关"), Rating("评分"), Views("观看次数") }
        /** Items of its "upload_date" group, by position. */
        enum class Date(val label: String) { Any("不限"), Hour("一小时内"), Day("今天"), Week("本周"), Month("本月"), Year("今年") }
        /** Items of its "duration" group, by position. */
        enum class Length(val label: String) { Any("不限"), Short("4 分钟以内"), Long("20 分钟以上") }

        val isDefault: Boolean get() = this == SearchFilter()
    }

    suspend fun search(query: String, cursor: String?, filter: SearchFilter = SearchFilter()): Page<ListItem> = io {
        // PipePipe's YouTube filters throw without a content filter (item 0 is "all").
        val factory = service.searchQHFactory
        val content = factory.getFilterItem(filter.type.id)
        val groups = factory.getContentFilterSortFilterVariant(filter.type.id)?.filterGroups.orEmpty()
        fun pick(group: String, index: Int) = groups.firstOrNull { it.groupName == group }?.filterItems?.getOrNull(index)
        val sort = listOfNotNull(
            pick("sortby", filter.sort.ordinal),
            pick("upload_date", filter.date.ordinal).takeIf { filter.date != SearchFilter.Date.Any },
            pick("duration", filter.length.ordinal).takeIf { filter.length != SearchFilter.Length.Any },
        )
        val handler = factory.fromQuery(query, listOf(content), sort.takeIf { !filter.isDefault })
        if (cursor == null) {
            val info = SearchInfo.getInfo(service, handler)
            page(info.relatedItems.mapNotNull { it.toListItem() }.filterShorts(), info.nextPage)
        } else {
            val more = SearchInfo.getMoreItems(service, handler, Cursors.take(cursor))
            page(more.items.mapNotNull { it.toListItem() }.filterShorts(), more.nextPage)
        }
    }

    suspend fun suggestions(query: String): List<String> = io {
        if (query.isBlank()) emptyList() else service.suggestionExtractor.suggestionList(query)
    }

    // ── Channels ────────────────────────────────────────────────────────────

    suspend fun channel(url: String): ChannelInfo = io { ChannelInfo.getInfo(service, url) }

    /** A playlist's title and its videos in order, every page (up to [maxPages]). */
    suspend fun playlist(url: String, maxPages: Int = 20): Pair<String, List<VideoItem>> {
        // The account's 赞过 (LL) and 稍后再看 (WL) are private lists the extractor refuses by URL
        // ("URL not accepted"); they load as the signed-in account's browse feeds instead.
        val special = when (android.net.Uri.parse(url).getQueryParameter("list")) {
            "LL" -> YtFeeds.Feed.Liked to "赞过的视频"
            "WL" -> YtFeeds.Feed.WatchLater to "稍后再看"
            else -> null
        }
        if (special != null) {
            val all = ArrayList<VideoItem>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = YtFeeds.videos(special.first, cursor)
                all += page.items
                cursor = page.nextCursor.takeIf { page.hasMore }
                pages++
            } while (cursor != null && pages < maxPages)
            return special.second to all.distinctBy { it.url }
        }
        return publicPlaylist(url, maxPages)
    }

    private suspend fun publicPlaylist(url: String, maxPages: Int): Pair<String, List<VideoItem>> = io {
        val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(service, url)
        val all = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideo() }.toMutableList()
        var next = info.nextPage
        var pages = 1
        while (next != null && pages < maxPages) {
            val more = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getMoreItems(service, url, next)
            all += more.items.filterIsInstance<StreamInfoItem>().map { it.toVideo() }
            next = more.nextPage
            pages++
        }
        info.name.orEmpty() to all
    }

    /** The uploads already on a loaded [ChannelInfo] (its first page). */
    fun channelFirstPage(info: ChannelInfo): Page<VideoItem> =
        page(info.relatedItems.map { it.toVideo() }.filterShortVideos(), info.nextPage)

    suspend fun channelVideos(url: String, cursor: String): Page<VideoItem> = io {
        val more: InfoItemsPage<StreamInfoItem> = ChannelInfo.getMoreItems(service, url, Cursors.take(cursor))
        page(more.items.map { it.toVideo() }.filterShortVideos(), more.nextPage)
    }

    /**
     * One of a channel's own tabs ([org.schabi.newpipe.extractor.linkhandler.ChannelTabs]: Shorts,
     * 直播…) as videos, page by page; anything that is not a video (playlists) is left out.
     */
    suspend fun channelTab(tab: org.schabi.newpipe.extractor.linkhandler.ListLinkHandler, cursor: String?): Page<VideoItem> = io {
        if (cursor == null) {
            val info = org.schabi.newpipe.extractor.channel.ChannelTabInfo.getInfo(service, tab)
            page(info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideo() }, info.nextPage)
        } else {
            val more = org.schabi.newpipe.extractor.channel.ChannelTabInfo.getMoreItems(service, tab, Cursors.take(cursor))
            page(more.items.filterIsInstance<StreamInfoItem>().map { it.toVideo() }, more.nextPage)
        }
    }

    /**
     * A channel's latest uploads: its RSS feed first (one cheap request, no paging), the channel
     * page's first uploads when the feed fails or comes back empty, as PipePipe's FeedLoadManager
     * falls back. YouTube's RSS endpoint answers 404 / 500 for whole days at a time.
     */
    suspend fun latest(channelUrl: String): List<VideoItem> = io {
        val feed = runCatching { FeedInfo.getInfo(service, channelUrl).relatedItems }.getOrNull()
        if (!feed.isNullOrEmpty()) feed.map { it.toVideo() }
        else ChannelInfo.getInfo(service, channelUrl).relatedItems.map { it.toVideo() }
    }

    // ── Comments ────────────────────────────────────────────────────────────

    /**
     * A page of [videoUrl]'s comments, 热门 (the extractor's own first page) or [newest] (最新:
     * the comments header's second sort, whose continuation the extractor then pages like any other).
     */
    suspend fun comments(videoUrl: String, cursor: String?, newest: Boolean = false): Page<CommentsInfoItem> = io {
        if (cursor == null && !newest) {
            val info = CommentsInfo.getInfo(service, videoUrl)
            page(info.relatedItems, info.nextPage)
        } else {
            val next = if (cursor != null) Cursors.take(cursor) else ExtractorPage(videoUrl, newestCommentsToken(videoUrl) ?: return@io Page(emptyList(), hasMore = false))
            val more = CommentsInfo.getMoreItems(service, videoUrl, next)
            page(more.items, more.nextPage)
        }
    }

    /** A thread's replies (the item's own replies page, then its continuations). */
    suspend fun replies(videoUrl: String, cursor: String): Page<CommentsInfoItem> = io {
        val more = CommentsInfo.getMoreItems(service, videoUrl, Cursors.take(cursor))
        page(more.items, more.nextPage)
    }

    /** The opaque cursor for a comment's replies page, or null when it has none. */
    fun repliesCursor(item: CommentsInfoItem): String? = item.replies?.takeIf { ExtractorPage.isValid(it) }?.let { Cursors.put(it) }

    /** The 最新 sort's continuation: the watch page's comments token, then the header's second sort item. */
    private fun newestCommentsToken(videoUrl: String): String? {
        val id = service.streamLHFactory.fromUrl(videoUrl).id
        val loc = NewPipe.getPreferredLocalization()
        val country = NewPipe.getPreferredContentCountry()
        fun next(build: com.grack.nanojson.JsonBuilder<com.grack.nanojson.JsonObject>.() -> Unit): com.grack.nanojson.JsonObject {
            val builder = YoutubeParsingHelper.prepareDesktopJsonBuilder(loc, country)
            builder.build()
            return YoutubeParsingHelper.getJsonPostResponse("next", com.grack.nanojson.JsonWriter.string(builder.done()).toByteArray(), loc)
        }
        var first: String? = null
        YtFeeds.walk(next { value("videoId", id) }) { key, v ->
            if (first == null && key == "itemSectionRenderer" && v.getString("targetId") == "comments-section") {
                first = v.getArray("contents").getObject(0).getObject("continuationItemRenderer")
                    .getObject("continuationEndpoint").getObject("continuationCommand").getString("token")
            }
        }
        val token = first ?: return null
        var newest: String? = null
        YtFeeds.walk(next { value("continuation", token) }) { key, v ->
            if (newest == null && key == "sortFilterSubMenuRenderer") {
                newest = v.getArray("subMenuItems").getObject(1).getObject("serviceEndpoint")
                    .getObject("continuationCommand").getString("token")
            }
        }
        return newest
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun <T> page(items: List<T>, next: ExtractorPage?): Page<T> {
        val more = ExtractorPage.isValid(next)
        return Page(items, hasMore = more, nextCursor = if (more) Cursors.put(next!!) else null)
    }

    private fun List<ListItem>.filterShorts(): List<ListItem> =
        if (!AppSettings.hideShorts.value) this else filterNot { it is ListItem.Video && it.video.isShort }

    private fun List<VideoItem>.filterShortVideos(): List<VideoItem> =
        if (!AppSettings.hideShorts.value) this else filterNot { it.isShort }
}

/** Opaque string tokens for extractor pages, so [com.gpo.pilipala.yt.ui.components.Pager] stays string-keyed. */
private object Cursors {
    private val next = AtomicLong()
    private val pages = ConcurrentHashMap<String, ExtractorPage>()

    fun put(page: ExtractorPage): String = next.incrementAndGet().toString().also { pages[it] = page }

    /** Kept (not removed): a failed load-more retries with the same cursor. */
    fun take(token: String): ExtractorPage = pages[token] ?: error("Unknown page token $token")
}
