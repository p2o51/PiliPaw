package com.gpo.pilipala.feature.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saving a picture: the original (no `@` size) downloaded with the CDN's
 * media headers and written to MediaStore under Pictures/PiliPaw. Android
 * 8–9 need WRITE_EXTERNAL_STORAGE for that, which the app does not declare,
 * so saving is skipped there ([supported]); sharing then falls back to the link.
 */
internal object PictureSaver {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** The original's URL: hdslb serves it without the `@…` size suffix. */
    fun original(url: String): String = Images.normalize(url).substringBefore('@')

    suspend fun save(context: Context, url: String): Uri = withContext(Dispatchers.IO) {
        check(supported) { "unsupported" }
        val clean = original(url)
        val bytes = BiliClient.getBytes(clean, BiliClient.mediaHeaders())
        val ext = clean.substringBefore('?').substringAfterLast('.', "jpg").lowercase().let {
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
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PiliPaw")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("保存失败")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("保存失败")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        uri
    }

    fun shareUri(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(uri) ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareLink(context: Context, url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, original(url))
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private val SheetTop = ContinuousRoundedCornerShape(20.dp, 20.dp, 6.dp, 6.dp)
private val SheetBottom = ContinuousRoundedCornerShape(6.dp, 6.dp, 20.dp, 20.dp)

/**
 * The long-press menu of a picture: 保存 and 分享 as one connected block
 * (outer corners large, inner 6dp, 3dp apart). The sheet is a window of its
 * own and the system bars follow the focused window: over the [immersive]
 * viewer it keeps them hidden too, so they neither flash in when it rises nor
 * out when it goes.
 */
@Composable
internal fun PictureSheet(
    immersive: Boolean,
    onAction: suspend (share: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val run: (Boolean) -> Unit = { share ->
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    onAction(share)
                } finally {
                    busy = false
                    // Slide away first: removing a sheet from composition would cut it off.
                    runCatching { state.hide() }
                    onDismiss()
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        // Composed inside the sheet: acts on the sheet's own window.
        SystemBarsHidden(immersive)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SheetRow(YoinSymbols.Download, "保存", SheetTop, busy) { run(false) }
            SheetRow(YoinSymbols.Share, "分享", SheetBottom, busy) { run(true) }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, shape: Shape, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (busy) LoadingIndicator(Modifier.size(28.dp))
    }
}
