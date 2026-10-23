package com.gpo.pilipala.core.util

import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/** One writer drains immutable snapshots; a newer pending snapshot replaces an older one. */
internal class LatestFileWriter(
    private val executor: Executor,
    private val onError: (File, Exception) -> Unit,
) {
    private val pending = LinkedHashMap<File, () -> String>()
    private var draining = false

    fun write(file: File, encode: () -> String) {
        synchronized(pending) {
            pending[file] = encode
            if (draining) return
            draining = true
        }
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val (file, encode) = synchronized(pending) {
                val entry = pending.entries.firstOrNull()
                if (entry == null) {
                    draining = false
                    return
                }
                (entry.key to entry.value).also { pending.remove(entry.key) }
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                // Encoding and disk I/O both run on the worker, never on the playback ticker.
                tmp.writeText(encode())
                if (!tmp.renameTo(file)) throw IOException("Cannot replace ${file.name}")
            } catch (e: Exception) {
                tmp.delete()
                onError(file, e)
            }
        }
    }
}
