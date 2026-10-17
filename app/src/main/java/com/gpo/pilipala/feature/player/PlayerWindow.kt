package com.gpo.pilipala.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

/** True while the hosting activity is in Picture-in-Picture. */
@Composable
internal fun rememberIsInPip(activity: ComponentActivity?): Boolean {
    var inPip by remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

internal fun pipSupported(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

internal fun pipParams(aspect: Float, autoEnter: Boolean): PictureInPictureParams {
    val clamped = if (aspect.isFinite() && aspect > 0f) aspect.coerceIn(0.42f, 2.38f) else 16f / 9f
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(Rational((clamped * 1000).roundToInt(), 1000))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
        builder.setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

internal fun enterPip(activity: Activity?, aspect: Float): Boolean {
    activity ?: return false
    if (!pipSupported(activity)) return false
    return runCatching { activity.enterPictureInPictureMode(pipParams(aspect, autoEnter = false)) }.getOrDefault(false)
}

/**
 * Immersive fullscreen: hides system bars (swipe shows them transiently) and,
 * for landscape videos, asks for sensor-landscape. Everything is restored when
 * fullscreen ends or the player leaves composition.
 */
@Composable
internal fun FullscreenWindowEffect(activity: Activity?, fullscreen: Boolean, lockLandscape: Boolean) {
    DisposableEffect(activity, fullscreen, lockLandscape) {
        if (activity == null || !fullscreen) return@DisposableEffect onDispose { }
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousOrientation = activity.requestedOrientation
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        if (lockLandscape) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (lockLandscape) activity.requestedOrientation = previousOrientation
        }
    }
}

@Composable
internal fun KeepScreenOnEffect(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Media volume as a 0..1 fraction (STREAM_MUSIC). */
internal class VolumeControl(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max: Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    val index: Int get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun fraction(): Float = index.toFloat() / max

    /** Returns the applied step index. */
    fun set(fraction: Float): Int {
        val idx = (fraction.coerceIn(0f, 1f) * max).roundToInt()
        if (idx != index) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
        return idx
    }
}

/** Window brightness override (does not touch the system setting). */
internal object BrightnessControl {
    fun current(activity: Activity?): Float {
        activity ?: return 0.5f
        val own = activity.window.attributes.screenBrightness
        if (own >= 0f) return own
        return runCatching {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    fun set(activity: Activity?, value: Float) {
        activity ?: return
        val attrs = activity.window.attributes
        attrs.screenBrightness = value.coerceIn(0.01f, 1f)
        activity.window.attributes = attrs
    }

    fun reset(activity: Activity?) {
        activity ?: return
        val attrs = activity.window.attributes
        if (attrs.screenBrightness < 0f) return
        attrs.screenBrightness = -1f
        activity.window.attributes = attrs
    }
}
