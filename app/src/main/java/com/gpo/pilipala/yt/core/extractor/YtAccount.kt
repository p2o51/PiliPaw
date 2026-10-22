package com.gpo.pilipala.yt.core.extractor

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * The signed-in account's own calls (who it is), on youtubei with the account's cookies and the
 * SAPISIDHASH authorization the extractor builds from them (addLoggedInHeaders).
 */
object YtAccount {
    class Me(val name: String, val handle: String, val avatar: String)

    /** Who the cookies belong to (`account/account_menu`'s active account header), or null if they are not a session. */
    suspend fun me(): Me? = withContext(Dispatchers.IO) {
        val json = post("account/account_menu", YoutubeParsingHelper.prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT).done())
        val header = json.getArray("actions").getObject(0)
            .getObject("openPopupAction").getObject("popup")
            .getObject("multiPageMenuRenderer").getObject("header")
            .getObject("activeAccountHeaderRenderer")
        if (header.isEmpty()) return@withContext null
        val handle = header.getObject("channelHandle").text()
        val named = header.getObject("accountName").text().trim()
        // The header's name line has come back as a stats line ("0 个人想看"); then the
        // account's own channel, found by its handle, says who it is.
        val name = if (named.isEmpty() || StatsLine.containsMatchIn(named)) {
            handle.takeIf { it.startsWith("@") }
                ?.let { h -> runCatching { Yt.channel("https://www.youtube.com/$h").name }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?: ""
        } else {
            named
        }
        Me(
            name = name,
            handle = handle,
            avatar = header.getObject("accountPhoto").getArray("thumbnails")
                .mapNotNull { (it as? JsonObject)?.getString("url") }
                .lastOrNull().orEmpty(),
        )
    }

    /** A count line rather than a name: "0 个人想看", "1.2万 位订阅者", "12 subscribers". */
    private val StatsLine = Regex("""^[\d.,]+\s*(万|亿|[KMB])?\s*(个|位|人|次|subscriber|view|video|watching)""", RegexOption.IGNORE_CASE)

    /** Subscribes the account to [channelIds] (UC… ids). A write: call only through AccountWrites. */
    suspend fun subscribe(channelIds: List<String>) = withContext(Dispatchers.IO) {
        if (channelIds.isEmpty()) return@withContext
        val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT)
            .array("channelIds").apply { channelIds.forEach { value(it) } }.end()
            .done()
        post("subscription/subscribe", body)
    }

    /** Unsubscribes the account from [channelIds]. A write: call only through AccountWrites. */
    suspend fun unsubscribe(channelIds: List<String>) = withContext(Dispatchers.IO) {
        if (channelIds.isEmpty()) return@withContext
        val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT)
            .array("channelIds").apply { channelIds.forEach { value(it) } }.end()
            .done()
        post("subscription/unsubscribe", body)
    }

    /** Adds [videoId] to the account's 稍后再看 or takes it out. A write: only through AccountWrites. */
    suspend fun watchLater(videoId: String, add: Boolean) = withContext(Dispatchers.IO) {
        val action = JsonObject().apply {
            if (add) {
                put("action", "ACTION_ADD_VIDEO")
                put("addedVideoId", videoId)
            } else {
                put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                put("removedVideoId", videoId)
            }
        }
        val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT)
            .value("playlistId", "WL")
            .array("actions").value(action).end()
            .done()
        post("browse/edit_playlist", body)
    }

    /** Likes [videoId] as the account, or takes the like back. A write: only through AccountWrites. */
    suspend fun like(videoId: String, like: Boolean) = withContext(Dispatchers.IO) {
        val target = JsonObject().apply { put("videoId", videoId) }
        val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT)
            .value("target", target)
            .done()
        post(if (like) "like/like" else "like/removelike", body)
    }

    /** One of the account's own playlists; [contains] only from [playlistOptions]. */
    class Playlist(val id: String, val title: String, val thumbnail: String = "", val count: String = "", val contains: Boolean = false) {
        val url: String get() = "https://www.youtube.com/playlist?list=$id"
    }

    /** The account's playlists that [videoId] can go in, each with whether it is there already (保存到…). */
    suspend fun playlistOptions(videoId: String): List<Playlist> = withContext(Dispatchers.IO) {
        val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT)
            .array("videoIds").value(videoId).end()
            .value("excludeWatchLater", true)
            .done()
        val json = post("playlist/get_add_to_playlist", body)
        val out = ArrayList<Playlist>()
        YtFeeds.walk(json) { key, v ->
            if (key == "playlistAddToOptionRenderer") {
                val id = v.getString("playlistId") ?: return@walk
                out += Playlist(id, v.getObject("title").text(), contains = v.getString("containsSelectedVideos") == "ALL")
            }
        }
        out
    }

    /** Puts [videoId] in playlist [playlistId] or takes it out. A write: only through AccountWrites. */
    suspend fun setInPlaylist(playlistId: String, videoId: String, add: Boolean) = withContext(Dispatchers.IO) {
        val action = JsonObject().apply {
            if (add) {
                put("action", "ACTION_ADD_VIDEO")
                put("addedVideoId", videoId)
            } else {
                put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                put("removedVideoId", videoId)
            }
        }
        val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT)
            .value("playlistId", playlistId)
            .array("actions").value(action).end()
            .done()
        post("browse/edit_playlist", body)
    }

    /** The playlists in the account's library (its own and the ones it saved), for 收藏. */
    suspend fun libraryPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(Localization.DEFAULT, ContentCountry.DEFAULT)
            .value("browseId", "FEplaylist_aggregation")
            .done()
        val json = post("browse", body)
        val out = ArrayList<Playlist>()
        YtFeeds.walk(json) { key, v ->
            when (key) {
                "lockupViewModel" -> if (v.getString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                    val id = v.getString("contentId") ?: return@walk
                    val meta = v.getObject("metadata").getObject("lockupMetadataViewModel")
                    val title = meta.getObject("title").getString("content").orEmpty()
                    var thumb = ""
                    var count = ""
                    YtFeeds.walk(v.getObject("contentImage")) { k2, v2 ->
                        if (k2 == "image" && thumb.isEmpty()) thumb = v2.getArray("sources").let { a -> (a.lastOrNull() as? JsonObject)?.getString("url").orEmpty() }
                        if (k2 == "thumbnailBadgeViewModel" && count.isEmpty()) count = v2.getString("text").orEmpty()
                    }
                    out += Playlist(id, title, thumb, count)
                }
                "gridPlaylistRenderer" -> {
                    val id = v.getString("playlistId") ?: return@walk
                    val thumb = v.getObject("thumbnail").getArray("thumbnails").let { a -> (a.lastOrNull() as? JsonObject)?.getString("url").orEmpty() }
                    out += Playlist(id, v.getObject("title").text(), thumb, v.getObject("videoCountShortText").text())
                }
            }
        }
        out.distinctBy { it.id }
    }

    /** A youtubei POST as the signed-in account. */
    internal fun post(endpoint: String, body: JsonObject): JsonObject {
        return YtDiag.post(endpoint, JsonWriter.string(body).toByteArray())
    }

    /** `{simpleText}` or `{runs: [{text}]}`. */
    internal fun JsonObject.text(): String =
        getString("simpleText") ?: getArray("runs").joinToString("") { (it as? JsonObject)?.getString("text").orEmpty() }
}
