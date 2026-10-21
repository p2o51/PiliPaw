package com.gpo.pilipala.feature.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 截图, for the settings sheet: the player provides it while a picture is on screen
 * (null otherwise, and the row is left out).
 */
internal val LocalPlayerShot = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The capture's feedback on the picture: a white flash and a small kick of the frame. */
@Stable
internal class ShotFeedback {
    val flash = Animatable(0f)
    val kick = Animatable(1f)
}

/**
 * The view the video is drawn into: the TextureView or SurfaceView under [root] that
 * overlaps [picture] (window px) the most. Other players on the page (a feed preview) lose
 * to the one actually showing; with no bounds the largest one wins.
 */
internal fun findVideoView(root: View, picture: Rect?): View? {
    var best: View? = null
    var bestScore = -1f
    val loc = IntArray(2)
    fun visit(v: View) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextureView || v is SurfaceView) {
            if (v.width > 0 && v.height > 0) {
                v.getLocationInWindow(loc)
                val r = Rect(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + v.width).toFloat(), (loc[1] + v.height).toFloat())
                val score = if (picture != null) {
                    val i = r.intersect(picture)
                    if (i.width > 0f && i.height > 0f) i.width * i.height else 0f
                } else {
                    r.width * r.height
                }
                if (score > bestScore) {
                    bestScore = score
                    best = v
                }
            }
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
    }
    visit(root)
    return best
}

/**
 * The frame on [view] as a bitmap of [width] x [height] (the video's own size where known).
 * A TextureView hands out its texture; a SurfaceView's buffer is read with PixelCopy.
 */
internal suspend fun captureVideoFrame(view: View, width: Int, height: Int): Bitmap? {
    val w = width.takeIf { it > 0 } ?: view.width
    val h = height.takeIf { it > 0 } ?: view.height
    if (w <= 0 || h <= 0) return null
    return when (view) {
        // A 4K frame is a 33 MB bitmap: out of memory means no frame, not a crash.
        is TextureView -> if (view.isAvailable) runCatching { view.getBitmap(w, h) }.getOrNull() else null
        is SurfaceView -> {
            val holder = view.holder
            if (holder.surface == null || !holder.surface.isValid) return null
            val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
            val ok = suspendCancellableCoroutine { cont ->
                runCatching {
                    PixelCopy.request(view, bitmap, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
                }.onFailure { cont.resume(false) }
            }
            if (ok) {
                bitmap
            } else {
                bitmap.recycle()
                null
            }
        }
        else -> null
    }
}

/** Writes [bitmap] to Pictures/PiliPaw through MediaStore (as the picture viewers save). */
internal suspend fun saveScreenshot(context: Context, bitmap: Bitmap, name: String) = withContext(Dispatchers.IO) {
    val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).trim('_')
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "pilipaw_${safe.ifEmpty { "shot" }}_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PiliPaw")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("保存失败")
    try {
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: error("保存失败")
    } catch (e: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
