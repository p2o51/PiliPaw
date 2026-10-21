package com.gpo.pilipala.yt.core.extractor

import com.gpo.pilipala.yt.ui.components.Page
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItemsCollector
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeLockupStreamInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector

/**
 * The signed-in account's own lists, from youtubei `browse` with the account's cookies
 * (getLoggedJsonPostResponse). Neither PipePipe's extractor nor its client has these; the
 * items themselves are parsed by the extractor's own renderers, found anywhere in the
 * response (the page layouts move, the renderers do not).
 */
object YtFeeds {
    enum class Feed(val browseId: String) {
        /** 首页 recommendations. */
        Home("FEwhat_to_watch"),
        /** The subscriptions' newest uploads. */
        Subscriptions("FEsubscriptions"),
        WatchLater("VLWL"),
        Liked("VLLL"),
    }

    /** One page of [feed]; [cursor] is the previous page's continuation token. */
    suspend fun videos(feed: Feed, cursor: String?): Page<VideoItem> = withContext(Dispatchers.IO) {
        val json = browse(feed.browseId, cursor)
        val collector = StreamInfoItemsCollector(ServiceList.YouTube.serviceId)
        val parser = ServiceList.YouTube.getTimeAgoParser(NewPipe.getPreferredLocalization())
        walk(json) { key, value ->
            when (key) {
                "videoRenderer", "gridVideoRenderer", "playlistVideoRenderer" ->
                    runCatching { collector.commit(YoutubeStreamInfoItemExtractor(value, parser)) }
                "lockupViewModel" -> if (value.getString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") {
                    runCatching { collector.commit(YoutubeLockupStreamInfoItemExtractor(value, parser)) }
                }
            }
        }
        val items = collector.items.map { it.toVideo() }.distinctBy { it.url }
        val next = continuation(json)
        Page(items, hasMore = next != null, nextCursor = next)
    }

    /** The channels the account subscribes to (FEchannels), all pages. */
    suspend fun channels(): List<ChannelItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<ChannelItem>()
        var cursor: String? = null
        var pages = 0
        do {
            val json = browse("FEchannels", cursor)
            val collector = ChannelInfoItemsCollector(ServiceList.YouTube.serviceId)
            walk(json) { key, value ->
                if (key == "channelRenderer" || key == "gridChannelRenderer") {
                    runCatching { collector.commit(YoutubeChannelInfoItemExtractor(value)) }
                }
            }
            out += collector.items.map { it.toChannel() }
            cursor = continuation(json)
        } while (cursor != null && ++pages < 20)
        out.distinctBy { it.url }
    }

    private fun browse(browseId: String, continuation: String?): JsonObject {
        val builder = YoutubeParsingHelper.prepareDesktopJsonBuilder(
            NewPipe.getPreferredLocalization() ?: Localization.DEFAULT,
            NewPipe.getPreferredContentCountry() ?: ContentCountry.DEFAULT,
        )
        if (continuation != null) builder.value("continuation", continuation) else builder.value("browseId", browseId)
        val body = JsonWriter.string(builder.done()).toByteArray()
        return YoutubeParsingHelper.getLoggedJsonPostResponse("browse", body, Localization.DEFAULT)
    }

    /** Every object under a key anywhere in [json], depth first (renderers nest at varying depths). */
    internal fun walk(json: Any?, visit: (String, JsonObject) -> Unit) {
        when (json) {
            is JsonObject -> for ((k, v) in json) {
                if (v is JsonObject) visit(k, v)
                walk(v, visit)
            }
            is JsonArray -> json.forEach { walk(it, visit) }
        }
    }

    /** The page's continuation token (its continuationItemRenderer), if there is one. */
    private fun continuation(json: JsonObject): String? {
        var token: String? = null
        walk(json) { key, value ->
            if (token == null && key == "continuationItemRenderer") {
                token = value.getObject("continuationEndpoint").getObject("continuationCommand").getString("token")
            }
        }
        return token
    }
}

/** One YouTube notification (signed in): what happened, when, and the video it points at. */
data class YtNotification(val text: String, val time: String, val thumbnail: String, val videoUrl: String?, val read: Boolean)

/** The signed-in account's notification inbox (the bell), from youtubei with its cookies. */
object YtNotifications {
    suspend fun load(): List<YtNotification> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val builder = YoutubeParsingHelper.prepareDesktopJsonBuilder(
            NewPipe.getPreferredLocalization() ?: Localization.DEFAULT,
            NewPipe.getPreferredContentCountry() ?: ContentCountry.DEFAULT,
        )
        builder.value("notificationsMenuRequestType", "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX")
        val body = JsonWriter.string(builder.done()).toByteArray()
        val json = YoutubeParsingHelper.getLoggedJsonPostResponse("notification/get_notification_menu", body, Localization.DEFAULT)
        val out = ArrayList<YtNotification>()
        YtFeeds.walk(json) { key, value ->
            if (key != "notificationRenderer") return@walk
            fun text(o: JsonObject): String = o.getString("simpleText")
                ?: o.getArray("runs").joinToString("") { (it as? JsonObject)?.getString("text").orEmpty() }
            val videoId = value.getObject("navigationEndpoint").getObject("watchEndpoint").getString("videoId")
            out += YtNotification(
                text = text(value.getObject("shortMessage")),
                time = text(value.getObject("sentTimeText")),
                thumbnail = value.getObject("videoThumbnail").getArray("thumbnails").let { a -> (a.lastOrNull() as? JsonObject)?.getString("url").orEmpty() },
                videoUrl = videoId?.let { "https://www.youtube.com/watch?v=$it" },
                read = value.getBoolean("read", false),
            )
        }
        out
    }
}
