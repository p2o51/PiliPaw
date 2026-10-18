package com.gpo.pilipala.ui.components.seam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.gpo.pilipala.core.settings.AppSettings

/**
 * How the top seam under fixed chrome looks ([SeamTop.Chrome]: a filter or
 * tab row, a large top bar, the status bar over a feed) — the user's choice
 * in 设置 › 外观 › 滚动边缘 (Yoin: Settings › Motion › Scroll edge). Only that
 * seam: the bottom field under the floating bar and the text fades are the
 * same in every style.
 */
enum class SeamTopStyle(
    /** Stable persisted key; never rename. */
    val key: String,
    /** The option's name in Settings. */
    val label: String,
) {
    /** The tide line (SeamTide.kt): graphics sink under two waves. The default. */
    Tide("tide", "潮线"),

    /** Curve C halftone: graphics break into dots over the band. */
    Dots("dots", "网点"),

    /** 曲奇浪口 (SeamCookie.kt): a wave lip that morphs Cookie → sine → SoftBurst over the exit. */
    Cookie("cookie", "曲奇"),
    ;

    /** Text inside a print is lifted out of it near this seam: only where the print itself breaks up. */
    val liftsText: Boolean get() = this != Tide

    companion object {
        val Default = Tide

        /** Unknown or missing keys fall back to [Default]. */
        fun fromKey(key: String?): SeamTopStyle = entries.firstOrNull { it.key == key } ?: Default
    }
}

/** The stored choice (SharedPreferences through [AppSettings]). */
internal val SeamTopStyleKey = AppSettings.register("seam_top_style", SeamTopStyle.Default.key)

/**
 * The process-wide top seam style. Snapshot state, read in draw and layer
 * blocks, so changing it in Settings repaints every open seam live.
 */
object SeamTopPreference {
    private val state = mutableStateOf(SeamTopStyle.Default)

    @Volatile
    private var loaded = false

    /** The current style. A snapshot read — call it from composition, draw or layer blocks. */
    val style: SeamTopStyle
        get() {
            ensureLoaded()
            return state.value
        }

    /**
     * Reads the stored style once per process; later calls are free. The
     * write goes to the global snapshot, so a discarded composition or a
     * draw pass that called this cannot drop the value while keeping [loaded].
     */
    internal fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = SeamTopStyle.fromKey(SeamTopStyleKey.value)
        if (stored != SeamTopStyle.Default) Snapshot.global { state.value = stored }
    }

    /** The user's choice: shown at once everywhere, and stored. */
    fun select(style: SeamTopStyle) {
        loaded = true
        state.value = style
        AppSettings.set(SeamTopStyleKey, style.key)
    }
}

/** The current top seam style for Settings (follows the stored key). */
@Composable
fun currentSeamTopStyle(): SeamTopStyle {
    val key by SeamTopStyleKey.state.collectAsState()
    return SeamTopStyle.fromKey(key)
}
