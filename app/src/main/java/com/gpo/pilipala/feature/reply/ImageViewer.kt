package com.gpo.pilipala.feature.reply

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen picture viewer: pager, pinch / double-tap zoom, save to MediaStore. */
@Composable
internal fun ImageViewerDialog(urls: List<String>, initial: Int, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, (urls.size - 1).coerceAtLeast(0))) { urls.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toast = { msg: String -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }
    val haptic = LocalHapticFeedback.current
    var saving by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                ZoomableImage(Images.normalize(urls[page]), onTap = onDismiss)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val chrome = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.45f),
                    contentColor = Color.White,
                )
                FilledTonalIconButton(onClick = onDismiss, colors = chrome) {
                    Icon(YoinSymbols.Close, contentDescription = "关闭")
                }
                Spacer(Modifier.weight(1f))
                if (urls.size > 1) {
                    Text(
                        "${pager.currentPage + 1} / ${urls.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    FilledTonalIconButton(
                        onClick = {
                            if (saving) return@FilledTonalIconButton
                            saving = true
                            val url = Images.normalize(urls[pager.currentPage])
                            scope.launch {
                                val result = runCatching { saveToGallery(context, url) }
                                saving = false
                                result.onSuccess {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    toast("已保存到相册")
                                }.onFailure { toast(it.friendlyMessage()) }
                            }
                        },
                        colors = chrome,
                    ) {
                        if (saving) {
                            LoadingIndicator(color = Color.White)
                        } else {
                            Icon(YoinSymbols.Download, contentDescription = "保存")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(url: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val spec = YoinMotion.defaultSpatialSpec<Float>()

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(url) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            val newOffset = clamp(offset + pan, newScale)
                            // At the horizontal edge with one finger, let the pager take over.
                            val atEdge = !multi && newOffset.x == offset.x && pan.x != 0f && kotlin.math.abs(pan.x) > kotlin.math.abs(pan.y)
                            scale = newScale
                            offset = if (newScale <= 1f) Offset.Zero else newOffset
                            if (!atEdge) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(url) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        val from = scale
                        val to = if (scale > 1.01f) 1f else 2.5f
                        val fromOffset = offset
                        val target = if (to == 1f) Offset.Zero else clamp((Offset(size.width / 2f, size.height / 2f) - tap) * (to - 1f), to)
                        scope.launch {
                            animate(0f, 1f, animationSpec = spec) { t, _ ->
                                scale = from + (to - from) * t
                                offset = fromOffset + (target - fromOffset) * t
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(color = Color.White) }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

private suspend fun saveToGallery(context: android.content.Context, url: String) = withContext(Dispatchers.IO) {
    val bytes = BiliClient.getBytes(url)
    val ext = url.substringAfterLast('.', "jpg").substringBefore('@').substringBefore('?').lowercase()
        .takeIf { it in setOf("jpg", "jpeg", "png", "gif", "webp") } ?: "jpg"
    val mime = when (ext) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "pilipaw_${System.currentTimeMillis()}.$ext")
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PiliPaw")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("保存失败")
    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("保存失败")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
