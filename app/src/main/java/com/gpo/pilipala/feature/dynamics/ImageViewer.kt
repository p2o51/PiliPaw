package com.gpo.pilipala.feature.dynamics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.components.CoverBadge
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ── Viewer state ────────────────────────────────────────────────────────────

@Stable
internal class ImageViewerState {
    var request by mutableStateOf<Pair<List<String>, Int>?>(null)
        private set

    fun open(urls: List<String>, index: Int = 0) {
        if (urls.isNotEmpty()) request = urls to index.coerceIn(0, urls.lastIndex)
    }

    fun close() {
        request = null
    }
}

@Composable
internal fun rememberImageViewerState(): ImageViewerState = remember { ImageViewerState() }

@Composable
internal fun ImageViewerHost(state: ImageViewerState) {
    val req = state.request ?: return
    ImageViewerDialog(urls = req.first, initial = req.second, onDismiss = state::close)
}

// ── Picture grid (1 / 2 / 3 columns, Flutter pic_panel) ────────────────────

private val PicShape = ContinuousRoundedCornerShape(12.dp)

@Composable
internal fun PicGrid(
    pics: List<DynPic>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pics.isEmpty()) return
    val density = LocalDensity.current
    if (pics.size == 1) {
        val p = pics.first()
        val raw = if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height else 1f
        val ratio = raw.coerceIn(0.66f, 1.78f)
        val isLong = p.height > p.width * 2.5f && p.width > 0
        // Ask the CDN for the slot's crop (width AND height): a 长图 would otherwise
        // arrive at ~960×8000 and be decoded whole just to show its center.
        val wPx = with(density) { 480.dp.roundToPx() }
        val model = remember(p.url, wPx, ratio) { Images.thumb(p.url, wPx, (wPx / ratio).roundToInt()) }
        Box(
            modifier
                .fillMaxWidth(if (ratio < 1f) 0.66f else 1f)
                .widthIn(max = 520.dp)
                .aspectRatio(ratio)
                .clip(PicShape)
                .clickable { onOpen(0) },
        ) {
            NetImage(model, Modifier.matchParentSize(), shape = PicShape, thumbWidth = null)
            if (isLong) CoverBadge("长图", Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        return
    }
    val tilePx = with(density) { 240.dp.roundToPx() }
    val cols = if (pics.size == 2 || pics.size == 4) 2 else 3
    Column(
        modifier.widthIn(max = if (cols == 2) 420.dp else 560.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        pics.chunked(cols).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { i, p ->
                    val index = rowIndex * cols + i
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(PicShape)
                            .clickable { onOpen(index) },
                    ) {
                        // Square tiles: let the CDN crop to a square instead of decoding a whole long image.
                        NetImage(Images.thumb(p.url, tilePx, tilePx), Modifier.matchParentSize(), shape = PicShape, thumbWidth = null)
                        if (p.width > 0 && p.height > p.width * 2.5f) {
                            CoverBadge("长图", Modifier.align(Alignment.BottomStart).padding(6.dp))
                        }
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ── Full-screen viewer ──────────────────────────────────────────────────────

private val ViewerTopScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.55f),
    0.6f to Color.Black.copy(alpha = 0.3f),
    1f to Color.Transparent,
)

@Composable
internal fun ImageViewerDialog(urls: List<String>, initial: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = initial) { urls.size }
        var chrome by remember { mutableStateOf(true) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        var busy by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { it },
            ) { page ->
                ZoomableImage(urls[page], onTap = { chrome = !chrome })
            }

            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                exit = fadeOut(YoinMotion.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                // Scrim behind the white close button and counter so they stay legible over bright pictures.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(ViewerTopScrim)
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                        Icon(YoinSymbols.Close, contentDescription = "关闭", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (urls.size > 1) {
                        Text(
                            "${pager.currentPage + 1}/${urls.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(YoinMotion.defaultEffectsSpec()),
                exit = fadeOut(YoinMotion.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
                ) {
                    IconButton(
                        enabled = !busy,
                        shapes = IconButtonDefaults.shapes(),
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { ImageSaver.save(context, urls[pager.currentPage]) }
                                    .onSuccess {
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                    }
                                    .onFailure { Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show() }
                                busy = false
                            }
                        },
                    ) { Icon(YoinSymbols.Download, contentDescription = "保存") }
                    IconButton(
                        enabled = !busy,
                        shapes = IconButtonDefaults.shapes(),
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { ImageSaver.save(context, urls[pager.currentPage]) }
                                    .onSuccess { ImageSaver.share(context, it) }
                                    .onFailure { shareText(context, Images.normalize(urls[pager.currentPage])) }
                                busy = false
                            }
                        },
                    ) { Icon(YoinSymbols.Share, contentDescription = "分享") }
                }
            }
        }
    }
}

/** Pinch / double-tap zoom. Panning only while zoomed so the pager keeps its swipe. */
@Composable
private fun ZoomableImage(url: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val zoomSpec = YoinMotion.defaultSpatialSpec<Float>()

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val state = rememberTransformableState { centroid, zoom, pan, _ ->
        val s = (scale * zoom).coerceIn(1f, 5f)
        // Zoom around the pinch centroid (coordinates relative to the center).
        val c = centroid - Offset(size.width / 2f, size.height / 2f)
        val zoomed = c - (c - offset) * (s / scale)
        scale = s
        offset = clamp(zoomed + pan, s)
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(url) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        val from = scale
                        val to = if (scale > 1.05f) 1f else 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val targetOffset = if (to == 1f) Offset.Zero else clamp((center - tap) * (to - 1f), to)
                        val startOffset = offset
                        scope.launch {
                            animate(0f, 1f, animationSpec = zoomSpec) { f, _ ->
                                scale = from + (to - from) * f
                                offset = startOffset + (targetOffset - startOffset) * f
                            }
                        }
                    },
                )
            }
            .transformable(state, canPan = { scale > 1.01f }),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = Images.thumb(url, 1920),
            contentDescription = null,
            contentScale = ContentScale.Fit,
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

// ── MediaStore ──────────────────────────────────────────────────────────────

internal object ImageSaver {
    /** Downloads the original and inserts it into Pictures/PiliPaw. */
    suspend fun save(context: Context, url: String): Uri = withContext(Dispatchers.IO) {
        val clean = Images.normalize(url).substringBefore('@')
        val bytes = BiliClient.getBytes(clean, BiliClient.mediaHeaders())
        val ext = clean.substringAfterLast('.', "jpg").lowercase().let {
            if (it in setOf("jpg", "jpeg", "png", "gif", "webp")) it else "jpg"
        }
        val mime = when (ext) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "pilipaw_${System.currentTimeMillis()}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PiliPaw")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("insert failed")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("open failed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }

    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
