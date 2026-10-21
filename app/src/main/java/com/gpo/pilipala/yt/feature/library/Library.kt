package com.gpo.pilipala.yt.feature.library

import android.content.Context
import com.gpo.pilipala.yt.core.extractor.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One saved video. [key] is source-prefixed ("yt:<id>") so the merged Paw app can keep
 * Bilibili's ("bili:<bvid>") in the same lists; everything else is what a row draws.
 */
@Serializable
data class LibItem(
    val key: String,
    val url: String,
    val title: String,
    val thumbnail: String,
    val channelName: String,
    val channelUrl: String,
    val durationSec: Long,
    /** When it was added (watch later, playlists) or last watched (history), epoch millis. */
    val at: Long,
    /** History only: where it was left, and whether it was watched to the end. */
    val positionMs: Long = 0,
    /** Mix lists: the linked source list ([MixLink.id]) it came from; null when added by hand. */
    val from: String? = null,
) {
    fun toVideo(): VideoItem = VideoItem(
        url = url, title = title, thumbnail = thumbnail, channelName = channelName, channelUrl = channelUrl,
        channelAvatar = "", durationSec = durationSec, views = -1, uploaded = "", uploadedAt = 0,
        isLive = false, isShort = false, channelVerified = false,
    )
}

/**
 * A source list a Mix follows: a Bilibili 收藏夹 ([kind] "bili_fav", [ref] its media id) or a
 * YouTube playlist ("yt_playlist", [ref] its URL). [removed] holds the keys taken out of the Mix
 * by hand, which a sync never brings back.
 */
@Serializable
data class MixLink(
    val id: String,
    val kind: String,
    val ref: String,
    val title: String,
    val removed: List<String> = emptyList(),
    val syncedAt: Long = 0,
)

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val items: List<LibItem> = emptyList(),
    val links: List<MixLink> = emptyList(),
)

/**
 * 本机的资料库: history (recorded as videos play, with where they were left), watch later and
 * playlists. Each is a small JSON file in the app's files; nothing leaves the device.
 */
object Library {
    private lateinit var dir: File
    private val json = Json { ignoreUnknownKeys = true }
    private val items = ListSerializer(LibItem.serializer())
    private val lists = ListSerializer(LocalPlaylist.serializer())

    private val _history = MutableStateFlow<List<LibItem>>(emptyList())
    val history: StateFlow<List<LibItem>> = _history.asStateFlow()
    private val _watchLater = MutableStateFlow<List<LibItem>>(emptyList())
    val watchLater: StateFlow<List<LibItem>> = _watchLater.asStateFlow()
    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    /** History never grows past this; the oldest go first. */
    private const val HistoryCap = 500

    fun init(context: Context) {
        dir = File(context.filesDir, "library").apply { mkdirs() }
        _history.value = read("history.json", items).orEmpty()
        _watchLater.value = read("watch_later.json", items).orEmpty()
        _playlists.value = read("playlists.json", lists).orEmpty()
    }

    /** "yt:<id>" for a YouTube watch URL (the merged app adds its other sources here). */
    fun keyOf(url: String): String {
        val id = Regex("""(?:v=|youtu\.be/|shorts/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1) ?: url
        return "yt:$id"
    }

    fun itemOf(v: VideoItem, now: Long = System.currentTimeMillis()): LibItem = LibItem(
        key = keyOf(v.url), url = v.url, title = v.title, thumbnail = v.thumbnail, channelName = v.channelName,
        channelUrl = v.channelUrl, durationSec = v.durationSec, at = now,
    )

    // ── History ─────────────────────────────────────────────────────────────

    /** Moves [v] to the top of the history (keeping where it was left). */
    fun watched(v: VideoItem) {
        val key = keyOf(v.url)
        val old = _history.value.firstOrNull { it.key == key }
        val item = itemOf(v).copy(positionMs = old?.positionMs ?: 0)
        setHistory(listOf(item) + _history.value.filterNot { it.key == key })
    }

    /** Where [url] was left; 0 when never watched or watched to (near) the end. */
    fun resumeAt(url: String): Long {
        val item = _history.value.firstOrNull { it.key == keyOf(url) } ?: return 0
        val end = item.durationSec * 1000
        return if (end > 0 && item.positionMs > end - 10_000) 0 else item.positionMs
    }

    fun savePosition(url: String, positionMs: Long) {
        val key = keyOf(url)
        val list = _history.value
        val i = list.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return
        if (list[i].positionMs == positionMs) return
        setHistory(list.toMutableList().also { it[i] = it[i].copy(positionMs = positionMs) })
    }

    fun removeFromHistory(key: String) = setHistory(_history.value.filterNot { it.key == key })

    fun clearHistory() = setHistory(emptyList())

    private fun setHistory(list: List<LibItem>) {
        _history.value = list.take(HistoryCap)
        write("history.json", items, _history.value)
    }

    // ── Watch later ─────────────────────────────────────────────────────────

    fun inWatchLater(url: String): Boolean = _watchLater.value.any { it.key == keyOf(url) }

    /** Adds [v] (newest first) or takes it out; returns whether it is in now. */
    fun toggleWatchLater(v: VideoItem): Boolean {
        val key = keyOf(v.url)
        val now = _watchLater.value
        val inNow = now.none { it.key == key }
        _watchLater.value = if (inNow) listOf(itemOf(v)) + now else now.filterNot { it.key == key }
        write("watch_later.json", items, _watchLater.value)
        return inNow
    }

    // ── Playlists ───────────────────────────────────────────────────────────

    fun createPlaylist(name: String): LocalPlaylist {
        val p = LocalPlaylist(id = "local:" + System.currentTimeMillis().toString(36), name = name.trim())
        setPlaylists(_playlists.value + p)
        return p
    }

    fun renamePlaylist(id: String, name: String) =
        setPlaylists(_playlists.value.map { if (it.id == id) it.copy(name = name.trim()) else it })

    fun deletePlaylist(id: String) = setPlaylists(_playlists.value.filterNot { it.id == id })

    fun inPlaylist(id: String, url: String): Boolean =
        _playlists.value.firstOrNull { it.id == id }?.items?.any { it.key == keyOf(url) } == true

    /** Adds [v] to the end of playlist [id] or takes it out; returns whether it is in now. */
    fun togglePlaylist(id: String, v: VideoItem): Boolean {
        val key = keyOf(v.url)
        var inNow = false
        setPlaylists(_playlists.value.map { p ->
            if (p.id != id) return@map p
            inNow = p.items.none { it.key == key }
            p.copy(items = if (inNow) p.items + itemOf(v) else p.items.filterNot { it.key == key })
        })
        return inNow
    }

    fun inPlaylistKey(id: String, key: String): Boolean =
        _playlists.value.firstOrNull { it.id == id }?.items?.any { it.key == key } == true

    /**
     * Any source's [item] ("bili:…" or "yt:…") added to the end of playlist [id], or taken out;
     * returns whether it is in now. The merged app's Mix lists go through here.
     */
    fun togglePlaylistItem(id: String, item: LibItem): Boolean {
        var inNow = false
        setPlaylists(_playlists.value.map { p ->
            if (p.id != id) return@map p
            inNow = p.items.none { it.key == item.key }
            p.copy(items = if (inNow) p.items + item.copy(at = System.currentTimeMillis()) else p.items.filterNot { it.key == item.key })
        })
        return inNow
    }

    /** Takes [key] out of playlist [id]; one that came from a linked list is remembered as removed. */
    fun removeFromPlaylist(id: String, key: String) =
        setPlaylists(_playlists.value.map { p ->
            if (p.id != id) return@map p
            val from = p.items.firstOrNull { it.key == key }?.from
            p.copy(
                items = p.items.filterNot { i -> i.key == key },
                links = p.links.map { l -> if (l.id == from) l.copy(removed = (l.removed + key).distinct()) else l },
            )
        })

    /** Replaces playlist [id] wholesale (a Mix sync's result). */
    fun updatePlaylist(id: String, change: (LocalPlaylist) -> LocalPlaylist) =
        setPlaylists(_playlists.value.map { if (it.id == id) change(it) else it })

    private fun setPlaylists(list: List<LocalPlaylist>) {
        _playlists.value = list
        write("playlists.json", lists, list)
    }

    // ── Files ───────────────────────────────────────────────────────────────

    private fun <T> read(name: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { File(dir, name).takeIf { it.exists() }?.readText()?.let { json.decodeFromString(serializer, it) } }.getOrNull()

    private fun <T> write(name: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        tmp.renameTo(File(dir, name))
    }
}
