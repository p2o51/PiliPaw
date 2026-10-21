package com.gpo.pilipala.core.source

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A site the app reads from. Each has its own account and its own part of the app. */
enum class VideoSource(val key: String, val label: String) {
    Bilibili("bili", "哔哩哔哩"),
    YouTube("yt", "YouTube"),
}

/**
 * Which sources are ticked in the account panel (the dots at Home's top left). At least one is
 * always on. An upgrading PiliPaw starts with Bilibili alone, so nothing changes until YouTube
 * is ticked.
 */
object Sources {
    private lateinit var prefs: SharedPreferences

    private val _enabled = MutableStateFlow(setOf(VideoSource.Bilibili))
    val enabled: StateFlow<Set<VideoSource>> = _enabled.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("paw_sources", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(Key, null)
            ?.mapNotNull { k -> VideoSource.entries.firstOrNull { it.key == k } }
            ?.toSet()
        if (!saved.isNullOrEmpty()) _enabled.value = saved
    }

    /** What the panel left: [ticks], never empty. */
    fun apply(ticks: Set<VideoSource>) {
        if (ticks.isEmpty() || ticks == _enabled.value) return
        save(ticks)
    }

    fun isOn(source: VideoSource): Boolean = source in _enabled.value


    private fun save(set: Set<VideoSource>) {
        _enabled.value = set
        prefs.edit().putStringSet(Key, set.map { it.key }.toSet()).apply()
    }

    private const val Key = "enabled"
}
