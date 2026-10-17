package com.gpo.pilipala.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable app settings (SharedPreferences-backed). Read with
 * `AppSettings.themeMode.collectAsState()`, write with `AppSettings.set(AppSettings.themeMode, v)`.
 * Feature code may add keys here (append only).
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

    /**
     * Feature-owned settings: declare them in YOUR package instead of editing this
     * file — `val MyKey = AppSettings.register("feature_x", true)`. Loaded on first access.
     */
    fun <T> register(name: String, default: T): Key<T> = Key(name, default).also {
        keys += it
        if (::prefs.isInitialized) load(it)
    }

    // ── Appearance ─────────────────────────────────────────────────────────
    /** 0 = follow system, 1 = light, 2 = dark */
    val themeMode = key("theme_mode", 0)
    val dynamicColor = key("dynamic_color", true)
    /** ARGB seed used when dynamic color is off. Bilibili pink by default. */
    val seedColor = key("seed_color", 0xFFFB7299.toInt())
    /** Tint video detail pages from the cover art (Yoin cover-driven color). */
    val coverColor = key("cover_color", true)

    // ── Home / feed ────────────────────────────────────────────────────────
    /** "web" or "app" recommendation source. */
    val rcmdSource = key("rcmd_source", "web")
    val homeDefaultTab = key("home_default_tab", 1)

    // ── Playback ───────────────────────────────────────────────────────────
    /** qn: 16=360P 32=480P 64=720P 80=1080P 112=1080P+ 116=1080P60 120=4K */
    val defaultVideoQuality = key("video_qn", 80)
    /** 30216=64K 30232=132K 30280=192K 30250=Dolby 30251=Hi-Res */
    val defaultAudioQuality = key("audio_qn", 30280)
    /** 0=avc 1=hevc 2=av1 */
    val preferredCodec = key("codec", 0)
    val defaultSpeed = key("speed", 1.0f)
    val autoPlay = key("auto_play", true)
    val autoPlayNext = key("auto_play_next", true)
    val autoFullscreenLandscape = key("auto_fs_landscape", true)
    val backgroundPlay = key("background_play", true)
    val autoPip = key("auto_pip", false)

    // ── Danmaku ────────────────────────────────────────────────────────────
    val danmakuEnabled = key("dm_enabled", true)
    val danmakuOpacity = key("dm_opacity", 0.85f)
    val danmakuFontScale = key("dm_font_scale", 1.0f)
    /** Fraction of the player height danmaku may occupy. */
    val danmakuArea = key("dm_area", 0.5f)
    val danmakuSpeed = key("dm_speed", 1.0f)
    val danmakuHideTop = key("dm_hide_top", false)
    val danmakuHideBottom = key("dm_hide_bottom", false)
    val danmakuHideScroll = key("dm_hide_scroll", false)

    // ── Privacy / misc ─────────────────────────────────────────────────────
    val pauseHistory = key("pause_history", false)
    val searchHistory = key("search_history", "")
    val replySort = key("reply_sort", 1)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("pili_settings", Context.MODE_PRIVATE)
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
