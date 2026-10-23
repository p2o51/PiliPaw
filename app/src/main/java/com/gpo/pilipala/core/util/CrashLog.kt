package com.gpo.pilipala.core.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.gpo.pilipala.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few crashes in the app's files (设置 › 应用 › 崩溃日志 copies them), so a crash on a
 * phone without adb can still be read.
 *
 * A Java crash is written as it happens, with the app's own recent logcat. What kills the process
 * without a Java exception (a native crash, an ANR, the system running out of memory) is picked up
 * on the next launch from the system's record of how the last process ended. The system's own
 * handler runs after ours.
 */
object CrashLog {
    private const val Keep = 8
    /** Under the binder's 1 MB: a bigger clip fails to copy. */
    private const val MaxCopy = 400_000
    private lateinit var dir: File
    private lateinit var prefs: android.content.SharedPreferences

    fun install(context: Context) {
        dir = File(context.filesDir, "crashes").apply { mkdirs() }
        prefs = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
        // The single file an earlier build kept.
        File(context.filesDir, "last_crash.txt").let { old ->
            if (old.exists()) runCatching { old.copyTo(File(dir, "${old.lastModified()}.txt"), overwrite = true) }
            old.delete()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                write(System.currentTimeMillis(), "Java 崩溃", "thread: ${thread.name}\n\n$trace\n\n── logcat ──\n${logcat()}")
                // This exit is already written: the next launch skips it.
                prefs.edit().putLong("pid_written", Process.myPid().toLong()).commit()
            }
            previous?.uncaughtException(thread, error)
        }
        if (Build.VERSION.SDK_INT >= 30) Thread { runCatching { readExits(context) } }.start()
    }

    private fun readExits(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        // The first launch with this reads back three days: crashes from before it was installed.
        val seen = prefs.getLong("exit_seen", 0L).coerceAtLeast(System.currentTimeMillis() - 3 * 86_400_000L)
        val written = prefs.getLong("pid_written", -1L)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp > seen && it.processName == context.packageName }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("exit_seen", it).apply() }
        var logged = false
        for (e in exits.sortedBy { it.timestamp }) {
            val kind = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH -> if (e.pid.toLong() == written) null else "Java 崩溃"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
                ApplicationExitInfo.REASON_ANR -> "无响应 (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统结束"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高被系统结束"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "启动失败"
                ApplicationExitInfo.REASON_SIGNALED -> "被信号结束"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程退出"
                ApplicationExitInfo.REASON_OTHER -> "被系统结束"
                else -> null
            } ?: continue
            val trace = when (e.reason) {
                // Text for an ANR (every thread's stack); a native crash's is a binary tombstone.
                ApplicationExitInfo.REASON_ANR -> runCatching {
                    e.traceInputStream?.bufferedReader()?.use { it.readText().take(200_000) }
                }.getOrNull()
                else -> null
            }
            val body = buildString {
                append("description: ${e.description}\n")
                append("status: ${e.status} · importance: ${e.importance} · pss: ${e.pss / 1024} MB · rss: ${e.rss / 1024} MB\n")
                if (trace != null) append("\n$trace\n")
                if (!logged) {
                    // Only the newest exit's logcat is still this close; the previous process's lines
                    // are what was logged just before it ended.
                    append("\n── logcat ──\n${logcat()}")
                    logged = true
                }
            }
            write(e.timestamp, kind, body)
        }
    }

    /** The app's own recent log lines (an app reads only its own without a permission). */
    private fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "400").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.takeLast(120_000)
    }.getOrDefault("")

    private fun write(time: Long, kind: String, body: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
        File(dir, "$time.txt").writeText(
            "PiliPaw ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · $stamp · $kind\n" +
                "Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n$body",
        )
        files().drop(Keep).forEach { it.delete() }
    }

    private fun files(): List<File> =
        if (::dir.isInitialized) dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedByDescending { it.name.removeSuffix(".txt").toLongOrNull() ?: 0 }
        else emptyList()

    /** Newest first: each crash's first line (version, time, kind). */
    fun summaries(): List<String> = files().mapNotNull { f -> runCatching { f.useLines { it.firstOrNull() } }.getOrNull() }

    /** Every kept crash, newest first, cut to fit the clipboard; null when there is none. */
    fun read(): String? {
        val all = files().mapNotNull { runCatching { it.readText() }.getOrNull() }
        if (all.isEmpty()) return null
        val text = all.joinToString("\n\n════════════════\n\n")
        return if (text.length <= MaxCopy) text else text.take(MaxCopy) + "\n…（已截断）"
    }

    fun clear() {
        files().forEach { it.delete() }
    }
}
