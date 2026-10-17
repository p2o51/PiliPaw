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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import com.gpo.pilipala.core.settings.AppSettings
import androidx.compose.runtime.LaunchedEffect
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
 * [lockLandscape] is for phones only; large screens go through
 * [rememberLargeScreenFullscreen] (try a real rotation, else turn the picture).
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

/** How a large screen shows a landscape picture fullscreen while its window is portrait. */
internal enum class LargeFullscreen {
    /** Not applicable (phone, portrait video, PiP, multi-window, not fullscreen). */
    Off,
    /** Landscape requested; waiting to see whether the display really turns. */
    Trying,
    /** The display turned: plain landscape fullscreen, nothing to do in software. */
    Rotated,
    /** The request was ignored or only letterboxed the app: turn the picture itself. */
    Turn,
}

/**
 * Remembered per device: asking for landscape here only letterboxes the app into a
 * landscape box (displays with ignoreOrientationRequest, e.g. the Pixel Tablet), so
 * later fullscreens go straight to the turned picture without that flash.
 */
private val LargeScreenRotationLetterboxes = AppSettings.register("player_large_rotation_letterboxes", false)

/** True when the display itself is drawn landscape (a real rotation, not a letterbox). */
internal fun displayIsLandscape(activity: Activity): Boolean {
    @Suppress("DEPRECATION")
    val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay)
        ?: return false
    val mode = display.mode
    val naturalLandscape = mode.physicalWidth > mode.physicalHeight
    val quarterTurn = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
    return naturalLandscape != quarterTurn
}

/**
 * Large-screen fullscreen for a landscape picture. Android 16+ ignores orientation
 * requests on sw ≥ 600dp displays for apps targeting SDK 36 unless they opt out
 * (the manifest sets PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY), and some
 * tablets letterbox instead of rotating. So: ask for sensor-landscape, and if the
 * display really turns within [TryRotationMs], that is fullscreen. Otherwise give
 * the request back and turn the picture in software ([turnedContent]). A device
 * that letterboxed is remembered so it skips the attempt next time.
 *
 * [want] must not depend on the window's current orientation, or the rotation it
 * causes would end the attempt.
 */
@Composable
internal fun rememberLargeScreenFullscreen(activity: Activity?, want: Boolean): LargeFullscreen {
    var mode by remember { mutableStateOf(LargeFullscreen.Off) }
    LaunchedEffect(activity, want) {
        if (activity == null || !want) {
            mode = LargeFullscreen.Off
            return@LaunchedEffect
        }
        if (displayIsLandscape(activity)) {
            // Already landscape (the device was turned): plain fullscreen.
            mode = LargeFullscreen.Rotated
            return@LaunchedEffect
        }
        if (LargeScreenRotationLetterboxes.value) {
            mode = LargeFullscreen.Turn
            return@LaunchedEffect
        }
        val previous = activity.requestedOrientation
        mode = LargeFullscreen.Trying
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Local, not [mode]: on exit the next effect run may reset [mode] before this finally runs.
        var holding = true
        try {
            val rotated = withTimeoutOrNull(TryRotationMs) {
                while (!displayIsLandscape(activity)) delay(40)
                true
            } == true
            if (rotated) {
                mode = LargeFullscreen.Rotated
                awaitCancellation()
            } else {
                activity.requestedOrientation = previous
                holding = false
                if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    // The app went landscape but the display did not: a letterbox.
                    AppSettings.set(LargeScreenRotationLetterboxes, true)
                }
                mode = LargeFullscreen.Turn
            }
        } finally {
            if (holding) activity.requestedOrientation = previous
        }
    }
    return mode
}

private const val TryRotationMs = 900L

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
