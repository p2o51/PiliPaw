package com.gpo.pilipala.ui.theme

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Rich haptics for the few moments that deserve more than a click: a charge that
 * builds under the finger, its landing, a celebration. Built from the platform's
 * composition primitives (API 30+, tuned per actuator on Pixel) and played with touch
 * attributes, so the system's touch-feedback switch still governs them.
 *
 * Beats are placed on absolute times ([Builder.at]) so they land on the frames of the
 * animation they accompany: on API 31+ each primitive's real duration on this device is
 * subtracted from the gap before the next one.
 *
 * Every pattern degrades quietly: no vibrator (the Pixel Tablet has none, so every
 * pattern here has a visual twin), a vibrator without the primitives, or Android < 11
 * → the closest [HapticFeedbackType], or nothing for the purely decorative ramps.
 */
@Stable
class RichHaptics internal constructor(
    private val context: Context,
    private val vibrator: Vibrator?,
    private val fallback: HapticFeedback,
) {
    private val hasVibrator: Boolean = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)

    /** A rising hum while a finger holds a charge; [cancel] stops it if the hold ends early. */
    fun charge() {
        compose(fallback = null) {
            at(0, VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.6f)
        }
    }

    /** The charge is full: one deep knock. */
    fun chargeFull() {
        compose(fallback = HapticFeedbackType.LongPress) {
            val knock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supports(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                VibrationEffect.Composition.PRIMITIVE_THUD
            } else {
                VibrationEffect.Composition.PRIMITIVE_CLICK
            }
            at(0, knock, 1f)
        }
    }

    /**
     * Three beats rising in strength, [beatMs] apart, in step with three things
     * lighting up one after another, then a soft fall as they settle.
     */
    fun celebrate(beatMs: Int = CelebrateBeatMs) {
        compose(fallback = HapticFeedbackType.Confirm) {
            at(0, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f)
            at(beatMs, VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
            at(beatMs * 2, VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
            if (supports(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)) {
                at(beatMs * 2 + 90, VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.45f)
            }
        }
    }

    /** A light lift-off: the picture leaving the page for its own window. */
    fun liftOff() {
        compose(fallback = HapticFeedbackType.GestureThresholdActivate) {
            at(0, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.5f)
            at(110, VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)
        }
    }

    fun cancel() {
        if (hasVibrator) runCatching { vibrator?.cancel() }
    }

    internal class Beat(val atMs: Int, val primitive: Int, val scale: Float)

    inner class Builder internal constructor() {
        internal val beats = mutableListOf<Beat>()

        /** [primitive] at [atMs] from the start, at [scale] strength. */
        fun at(atMs: Int, primitive: Int, scale: Float) {
            beats += Beat(atMs, primitive, scale.coerceIn(0f, 1f))
        }

        fun supports(primitive: Int): Boolean = this@RichHaptics.supports(primitive)
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    private fun canCompose(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !hasVibrator) return false
        // Before the touch usage existed (API 33) the touch-feedback switch would not mute a
        // composed effect, so honour it here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val on = runCatching {
                @Suppress("DEPRECATION")
                Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
            }.getOrDefault(true)
            if (!on) return false
        }
        return true
    }

    private fun supports(primitive: Int): Boolean =
        canCompose() && runCatching { vibrator!!.areAllPrimitivesSupported(primitive) }.getOrDefault(false)

    private fun compose(fallback: HapticFeedbackType?, block: Builder.() -> Unit) {
        val played = canCompose() && play(Builder().apply(block).beats)
        if (!played && fallback != null) this.fallback.performHapticFeedback(fallback)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun play(beats: List<Beat>): Boolean {
        val v = vibrator ?: return false
        if (beats.isEmpty()) return false
        val sorted = beats.sortedBy { it.atMs }
        val ids = sorted.map { it.primitive }.distinct().toIntArray()
        if (!runCatching { v.areAllPrimitivesSupported(*ids) }.getOrDefault(false)) return false
        val durations: Map<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { ids.zip(v.getPrimitiveDurations(*ids).toList()).toMap() }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        return runCatching {
            val composition = VibrationEffect.startComposition()
            // addPrimitive's delay counts from the end of the previous primitive.
            var cursor = 0
            sorted.forEach { b ->
                val delay = (b.atMs - cursor).coerceAtLeast(0)
                composition.addPrimitive(b.primitive, b.scale, delay)
                cursor += delay + (durations[b.primitive] ?: 0)
            }
            val effect = composition.compose()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            true
        }.getOrDefault(false)
    }

    companion object {
        /** Gap between the celebration's beats; the visuals stagger by the same step. */
        const val CelebrateBeatMs = 110

        internal fun vibrator(context: Context): Vibrator? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
    }
}

/** [RichHaptics] for this composition; falls back to [LocalHapticFeedback]. */
@Composable
fun rememberRichHaptics(): RichHaptics {
    val context = LocalContext.current.applicationContext
    val fallback = LocalHapticFeedback.current
    return remember(context, fallback) { RichHaptics(context, RichHaptics.vibrator(context), fallback) }
}
