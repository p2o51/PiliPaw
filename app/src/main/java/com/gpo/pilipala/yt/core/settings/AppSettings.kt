package com.gpo.pilipala.yt.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable app settings (SharedPreferences-backed), the Paw kit's settings store.
 * Read with `AppSettings.themeMode.state.collectAsState()`, write with
 * `AppSettings.set(AppSettings.themeMode, v)`. Feature code declares its own keys with
 * [register] in its own package instead of editing this file.
 */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    class Key<T>(val name: String, val default: T) {
        internal val flow = MutableStateFlow(default)
        val state: StateFlow<T> get() = flow.asStateFlow()
        val value: T get() = flow.value
    }

    private val keys = mutableListOf<Key<*>>()
    private fun <T> key(name: String, default: T): Key<T> = Key(name, default).also { keys += it }

    /** Feature-owned settings: `val MyKey = AppSettings.register("feature_x", true)`. Loaded on first access. */
    fun <T> register(name: String, default: T): Key<T> = Key(name, default).also {
        keys += it
        if (::prefs.isInitialized) load(it)
    }

    // ── Appearance ─────────────────────────────────────────────────────────
    /** 0 = follow system, 1 = light, 2 = dark */
    val themeMode = key("theme_mode", 0)
    val dynamicColor = key("dynamic_color", true)
    /** ARGB seed used when dynamic color is off. A warm red that is not YouTube's. */
    val seedColor = key("seed_color", 0xFFE5484D.toInt())
    /** Tint the player and channel pages from the thumbnail / avatar (Yoin cover-driven color). */
    val coverColor = key("cover_color", true)

    // ── Content ────────────────────────────────────────────────────────────
    /** ISO 3166 country for trending and search, empty = the system's. */
    val contentCountry = key("content_country", "")
    /** Shorts never show up in lists. */
    val hideShorts = key("hide_shorts", true)

    // ── Playback ───────────────────────────────────────────────────────────
    /** Preferred height in px (2160, 1440, 1080, 720, 480, 360); 0 = best available. */
    val preferredHeight = key("preferred_height", 1080)
    val autoPlay = key("auto_play", true)
    val backgroundPlay = key("background_play", true)
    val autoPip = key("auto_pip", false)
    val defaultSpeed = key("speed", 1.0f)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("paw_settings", Context.MODE_PRIVATE)
        keys.forEach { load(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> load(k: Key<T>) {
        val v: Any? = when (val d = k.default) {
            is Boolean -> prefs.getBoolean(k.name, d)
            is Int -> prefs.getInt(k.name, d)
            is Long -> prefs.getLong(k.name, d)
            is Float -> prefs.getFloat(k.name, d)
            is String -> prefs.getString(k.name, d)
            else -> d
        }
        k.flow.value = v as T
    }

    fun <T> set(k: Key<T>, value: T) {
        k.flow.value = value
        prefs.edit().apply {
            when (value) {
                is Boolean -> putBoolean(k.name, value)
                is Int -> putInt(k.name, value)
                is Long -> putLong(k.name, value)
                is Float -> putFloat(k.name, value)
                is String -> putString(k.name, value)
            }
        }.apply()
    }
}
