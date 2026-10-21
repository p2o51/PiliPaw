package com.gpo.pilipala.feature.player.lyrics

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.gpo.pilipala.feature.player.SubtitleLine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lyrics picked from 网易云 / QQ 音乐, remembered per video part (cid) as LRC files, so the
 * next listen shows them at once. They take the place of the video's own subtitles while
 * listening; [clear] goes back to the subtitles.
 */
internal object LyricsStore {
    private var dir: File? = null

    /** cid → lines; an empty list means "looked on disk, nothing there". */
    private val cache = mutableStateMapOf<Long, List<SubtitleLine>>()

    fun init(context: Context) {
        dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    }

    /** The picked lyrics for [cid], or null. Reads the disk once per cid (call [load] first). */
    fun lines(cid: Long): List<SubtitleLine>? = cache[cid]?.takeIf { it.isNotEmpty() }

    suspend fun load(cid: Long) {
        if (cid <= 0 || cache.containsKey(cid)) return
        val lines = withContext(Dispatchers.IO) {
            runCatching { file(cid)?.takeIf { it.exists() }?.readText()?.let(Lrc::parse) }.getOrNull()
        }
        cache[cid] = lines.orEmpty()
    }

    suspend fun save(cid: Long, lines: List<SubtitleLine>) {
        if (cid <= 0) return
        cache[cid] = lines
        withContext(Dispatchers.IO) { runCatching { file(cid)?.writeText(Lrc.format(lines)) } }
    }

    suspend fun clear(cid: Long) {
        cache[cid] = emptyList()
        withContext(Dispatchers.IO) { runCatching { file(cid)?.delete() } }
    }

    private fun file(cid: Long): File? = dir?.let { File(it, "$cid.lrc") }
}
