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

    private val _front = MutableStateFlow(VideoSource.Bilibili)

    /**
     * The source whose part of the app is on screen. With one ticked it is that one; with two,
     * the one picked last in the panel (Home does not mix them yet).
     */
    val front: StateFlow<VideoSource> = _front.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("paw_sources", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(Key, null)
            ?.mapNotNull { k -> VideoSource.entries.firstOrNull { it.key == k } }
            ?.toSet()
        if (!saved.isNullOrEmpty()) _enabled.value = saved
        val f = prefs.getString(FrontKey, null)?.let { k -> VideoSource.entries.firstOrNull { it.key == k } }
        _front.value = f?.takeIf { it in _enabled.value } ?: _enabled.value.first()
    }

    /** What the panel left: [ticks] (never empty) and the one on screen. */
    fun apply(ticks: Set<VideoSource>, front: VideoSource) {
        if (ticks.isEmpty()) return
        save(ticks)
        setFront(front.takeIf { it in ticks } ?: ticks.first())
    }

    private fun setFront(source: VideoSource) {
        _front.value = source
        prefs.edit().putString(FrontKey, source.key).apply()
    }

    fun isOn(source: VideoSource): Boolean = source in _enabled.value

    /** A link from outside opened [source]: tick it and bring it to the front. */
    fun show(source: VideoSource) {
        if (source !in _enabled.value) save(_enabled.value + source)
        if (_front.value != source) setFront(source)
    }

    private fun save(set: Set<VideoSource>) {
        _enabled.value = set
        prefs.edit().putStringSet(Key, set.map { it.key }.toSet()).apply()
    }

    private const val Key = "enabled"
    private const val FrontKey = "front"
}
