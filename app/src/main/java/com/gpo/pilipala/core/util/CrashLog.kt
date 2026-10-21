package com.gpo.pilipala.core.util

import android.content.Context
import android.os.Build
import com.gpo.pilipala.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash's stack trace in the app's files (设置 › 应用 › 上次崩溃 copies it), so a
 * crash on a phone without adb can still be read. The system's own handler runs after it.
 */
object CrashLog {
    private const val FileName = "last_crash.txt"
    private lateinit var file: File

    fun install(context: Context) {
        file = File(context.filesDir, FileName)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.writeText(
                    "PiliPaw ${BuildConfig.VERSION_NAME} · $time · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n" +
                        "thread: ${thread.name}\n\n$trace",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash report, or null when there is none. */
    fun read(): String? = if (::file.isInitialized && file.exists()) runCatching { file.readText() }.getOrNull() else null

    fun clear() {
        if (::file.isInitialized) file.delete()
    }
}
