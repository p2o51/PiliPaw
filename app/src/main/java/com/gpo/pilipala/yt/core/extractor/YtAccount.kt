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
        Me(
            name = header.getObject("accountName").text(),
            handle = header.getObject("channelHandle").text(),
            avatar = header.getObject("accountPhoto").getArray("thumbnails")
                .mapNotNull { (it as? JsonObject)?.getString("url") }
                .lastOrNull().orEmpty(),
        )
    }

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

    /** A youtubei POST as the signed-in account. */
    internal fun post(endpoint: String, body: JsonObject): JsonObject {
        val headers = YoutubeParsingHelper.getYouTubeHeaders()
        headers["Content-Type"] = listOf("application/json")
        YoutubeParsingHelper.addLoggedInHeaders(headers)
        val url = "${YoutubeParsingHelper.YOUTUBEI_V1_URL}$endpoint?${YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER}"
        val response = NewPipe.getDownloader().post(url, headers, JsonWriter.string(body).toByteArray(), Localization.DEFAULT)
        return JsonParser.`object`().from(response.responseBody())
    }

    /** `{simpleText}` or `{runs: [{text}]}`. */
    internal fun JsonObject.text(): String =
        getString("simpleText") ?: getArray("runs").joinToString("") { (it as? JsonObject)?.getString("text").orEmpty() }
}
