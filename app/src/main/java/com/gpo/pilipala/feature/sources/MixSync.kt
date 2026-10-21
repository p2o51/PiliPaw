package com.gpo.pilipala.feature.sources

import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.feature.library.LibItem
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.library.MixLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mix lists that follow a Bilibili 收藏夹 or a YouTube playlist. A sync reads the source list
 * whole: what is new there joins the end of the Mix (unless it was taken out of the Mix by hand),
 * what left it leaves the Mix too. Videos added by hand are never touched.
 */
object MixSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Bilibili 收藏夹 as a link: one per folder. */
    fun biliFolderLink(mediaId: Long, title: String) =
        MixLink(id = "bili_fav:$mediaId", kind = "bili_fav", ref = mediaId.toString(), title = title)

    fun youtubePlaylistLink(url: String, title: String = "") =
        MixLink(id = "yt_playlist:${playlistId(url)}", kind = "yt_playlist", ref = url, title = title)

    private fun playlistId(url: String): String =
        Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: url

    /** Links [link] to Mix [listId] and pulls it in now. */
    fun link(listId: String, link: MixLink, onDone: (Result<Int>) -> Unit = {}) {
        Library.updatePlaylist(listId) { p -> if (p.links.any { it.id == link.id }) p else p.copy(links = p.links + link) }
        sync(listId, onDone)
    }

    /** Stops following [linkId]; what it brought stays, as if added by hand. */
    fun unlink(listId: String, linkId: String) {
        Library.updatePlaylist(listId) { p ->
            p.copy(
                links = p.links.filterNot { it.id == linkId },
                items = p.items.map { if (it.from == linkId) it.copy(from = null) else it },
            )
        }
    }

    /** Syncs every Mix that follows something (app start). */
    fun syncAll() {
        Library.playlists.value.filter { it.links.isNotEmpty() }.forEach { sync(it.id) }
    }

    /** Syncs Mix [listId]; [onDone] gets how many videos joined (or the failure). */
    fun sync(listId: String, onDone: (Result<Int>) -> Unit = {}) {
        scope.launch {
            val result = runCatching {
                lock.withLock {
                    val list = Library.playlists.value.firstOrNull { it.id == listId } ?: return@withLock 0
                    var added = 0
                    for (link in list.links) {
                        val fresh = fetch(link).map { it.copy(from = link.id) }
                        val freshKeys = fresh.map { it.key }.toSet()
                        Library.updatePlaylist(listId) { p ->
                            val have = p.items.map { it.key }.toSet()
                            val kept = p.items.filter { it.from != link.id || it.key in freshKeys }
                            val joining = fresh.filter { it.key !in have && it.key !in link.removed }
                            added += joining.size
                            p.copy(
                                items = kept + joining,
                                links = p.links.map { l -> if (l.id == link.id) l.copy(syncedAt = System.currentTimeMillis()) else l },
                            )
                        }
                    }
                    added
                }
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    private suspend fun fetch(link: MixLink): List<LibItem> = when (link.kind) {
        "bili_fav" -> biliFolder(link.ref.toLong())
        "yt_playlist" -> Yt.playlist(link.ref).second.map { Library.itemOf(it) }
        else -> emptyList()
    }

    /** Every video of a 收藏夹, oldest first (the order it was collected in, so new ones go last). */
    private suspend fun biliFolder(mediaId: Long): List<LibItem> {
        val out = ArrayList<LibItem>()
        var pn = 1
        while (pn <= 50) {
            val data = BiliClient.getData(
                "/x/v3/fav/resource/list",
                mapOf("media_id" to mediaId, "pn" to pn, "ps" to 20, "order" to "mtime", "type" to 0, "platform" to "web"),
            ).asObj
            data.arr("medias").objects().forEach { m ->
                val bvid = m.str("bvid").ifEmpty { m.str("bv_id") }
                // Videos only (an episode or a removed video has no bvid / a 0 duration).
                if (bvid.isNotEmpty() && m.int("attr") == 0) {
                    out += MixItems.bili(bvid, m.str("title"), m.str("cover"), m.obj("upper").str("name"), m.obj("upper").long("mid"), m.long("duration"))
                }
            }
            if (!data.bool("has_more")) break
            pn++
        }
        return out.asReversed().toList()
    }
}
