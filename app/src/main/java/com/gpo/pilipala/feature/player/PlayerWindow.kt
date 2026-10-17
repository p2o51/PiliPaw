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
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * with [lockLandscape], asks for sensor-landscape. Everything is restored when
 * fullscreen ends or the player leaves composition.
 *
 * [lockLandscape] is for phones only. On large screens (sw ≥ 600dp) Android 16+
 * ignores orientation requests from apps targeting SDK 36, and the opt-out
 * (PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY) does not rotate the display
 * either: devices like the Pixel Tablet set ignoreOrientationRequest, so a
 * landscape request only letterboxes the app into a landscape box inside the
 * portrait screen. Large screens turn the picture in software instead
 * ([turnedContent]).
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

/**
 * Which way a software-turned landscape picture faces: +90 (turned clockwise, the
 * device's right edge on top, the usual landscape) or -90. While [enabled] it follows
 * the accelerometer relative to the current display rotation, so turning the device
 * either way brings the picture upright; held upright it keeps the last side.
 */
@Composable
internal fun rememberLandscapeTurn(enabled: Boolean): Float {
    val context = LocalContext.current
    val view = LocalView.current
    var turn by remember { mutableFloatStateOf(90f) }
    DisposableEffect(enabled, context, view) {
        if (!enabled) return@DisposableEffect onDispose { }
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val displayDegrees = (view.display?.rotation ?: Surface.ROTATION_0) * 90
                // The device's clockwise turn relative to how the screen is drawn now.
                when ((orientation + displayDegrees) % 360) {
                    in 60..120 -> turn = -90f
                    in 240..300 -> turn = 90f
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return turn
}

/**
 * Lays the content out in a turned frame: with [turned] it is measured with width and
 * height swapped (a landscape frame inside a portrait window), and it is drawn rotated
 * by [angle] degrees, scaled down just enough that the turned box always fits. So an
 * angle springing 0 → 90 grows the picture from the inline size into the full window,
 * and pointer input follows the rotation (Compose hit-tests through the layer).
 */
internal fun Modifier.turnedContent(turned: Boolean, angle: () -> Float): Modifier = layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    if (w == Constraints.Infinity || h == Constraints.Infinity) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val placeable = measurable.measure(if (turned) Constraints.fixed(h, w) else Constraints.fixed(w, h))
    layout(w, h) {
        placeable.placeWithLayer((w - placeable.width) / 2, (h - placeable.height) / 2) {
            val a = angle()
            rotationZ = a
            val rad = Math.toRadians(a.toDouble())
            val c = abs(cos(rad)).toFloat()
            val s = abs(sin(rad)).toFloat()
            val bw = placeable.width * c + placeable.height * s
            val bh = placeable.width * s + placeable.height * c
            val fit = if (bw > 0f && bh > 0f) minOf(1f, w / bw, h / bh) else 1f
            scaleX = fit
            scaleY = fit
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
