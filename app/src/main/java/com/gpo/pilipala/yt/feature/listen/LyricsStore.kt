package com.gpo.pilipala.yt.feature.listen

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.gpo.pilipala.yt.feature.player.Cue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lyrics picked from 网易云 / QQ 音乐, remembered per video ("yt:<id>") as LRC files, so the
 * next listen shows them at once. They take the place of the video's own subtitles while
 * listening; [clear] goes back to the subtitles.
 */
internal object LyricsStore {
    private var dir: File? = null

    /** key → lines; an empty list means "looked on disk, nothing there". */
    private val cache = mutableStateMapOf<String, List<Cue>>()

    fun init(context: Context) {
        dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    }

    /** The picked lyrics for [key], or null. Reads the disk once per key (call [load] first). */
    fun lines(key: String): List<Cue>? = cache[key]?.takeIf { it.isNotEmpty() }

    suspend fun load(key: String) {
        if (key.isEmpty() || cache.containsKey(key)) return
        val lines = withContext(Dispatchers.IO) {
            runCatching { file(key)?.takeIf { it.exists() }?.readText()?.let(Lrc::parse) }.getOrNull()
        }
        cache[key] = lines.orEmpty()
    }

    suspend fun save(key: String, lines: List<Cue>) {
        if (key.isEmpty()) return
        cache[key] = lines
        withContext(Dispatchers.IO) { runCatching { file(key)?.writeText(Lrc.format(lines)) } }
    }

    suspend fun clear(key: String) {
        cache[key] = emptyList()
        withContext(Dispatchers.IO) { runCatching { file(key)?.delete() } }
    }

    private fun file(key: String): File? = dir?.let { File(it, "${key.replace(":", "_")}.lrc") }
}
