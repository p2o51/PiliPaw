package com.gpo.pilipala.feature.reply

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.LocalContext
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.feature.dynamics.DynamicsApi
import com.gpo.pilipala.feature.dynamics.UploadedPic
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.yoin.symbols.PiliSymbols
import java.io.InterruptedIOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets

private val FieldShape = ContinuousRoundedCornerShape(18.dp)
private const val MaxPictures = 9
private val PictureSize = 72.dp
private val PictureShape = ContinuousRoundedCornerShape(12.dp)

/** Over-image scrim for the remove chip: images are the one place for a fixed colour. */
private val PictureScrim = Color.Black

/** The send button's face: at rest, pictures going up (with progress), the comment going out. */
private enum class SendStage { Idle, Uploading, Posting }

private val UriListSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/**
 * New comment / reply sheet: text field lifted by the IME, an emote panel that
 * swaps with the keyboard, up to nine pictures from the system photo picker,
 * and send. Pictures go up to bilibili's image host first (the dynamic
 * composer's upload), then [onSend] posts the text with them and returns true
 * when the comment landed. Pictures already up survive a failed post, so a
 * retry only uploads the rest.
 */
@Composable
internal fun ReplyComposerSheet(
    target: ComposeTarget,
    onDismiss: () -> Unit,
    onSend: suspend (String, List<UploadedPic>) -> Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var panel by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(SendStage.Idle) }
    val sending = stage != SendStage.Idle
    val motion = replyMotion()
    val peek = remember { EmotePeekState() }
    val context = LocalContext.current
    val toast = rememberToast()
    val pictures = rememberSaveable(saver = UriListSaver) { mutableStateListOf<String>() }
    val uploaded = remember { mutableStateMapOf<String, UploadedPic>() }
    var uploadTotal by remember { mutableIntStateOf(0) }
    var uploadDone by remember { mutableIntStateOf(0) }

    val remaining = MaxPictures - pictures.size
    fun addPictures(uris: List<Uri>) {
        val fresh = uris.map { it.toString() }.distinct().filter { it !in pictures }
        pictures += fresh.take((MaxPictures - pictures.size).coerceAtLeast(0))
    }
    val pickMany = rememberLauncherForActivityResult(
        remember(remaining) { ActivityResultContracts.PickMultipleVisualMedia(remaining.coerceAtLeast(2)) },
    ) { uris -> addPictures(uris) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addPictures(listOf(uri))
    }
    fun pickPictures() {
        if (sending) return
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        when {
            remaining >= 2 -> pickMany.launch(request)
            remaining == 1 -> pickOne.launch(request)
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    fun close() {
        scope.launch {
            keyboard?.hide()
            sheetState.hide()
        }.invokeOnCompletion { onDismiss() }
    }

    fun send() {
        if (sending) return
        val text = value.text.trim()
        val picked = pictures.toList()
        if (text.isEmpty() && picked.isEmpty()) return
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        val missing = picked.count { it !in uploaded }
        uploadTotal = missing
        uploadDone = 0
        stage = if (missing == 0) SendStage.Posting else SendStage.Uploading
        scope.launch {
            val ready = try {
                picked.map { key ->
                    uploaded[key] ?: DynamicsApi.uploadImage(context.contentResolver, context.cacheDir, Uri.parse(key)).also {
                        uploaded[key] = it
                        uploadDone++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(e.uploadMessage())
                stage = SendStage.Idle
                return@launch
            }
            stage = SendStage.Posting
            val ok = onSend(text, ready)
            stage = SendStage.Idle
            if (ok) close()
        }
    }

    fun insert(text: String) {
        val sel = value.selection
        val start = sel.min.coerceIn(0, value.text.length)
        val end = sel.max.coerceIn(0, value.text.length)
        val next = value.text.substring(0, start) + text + value.text.substring(end)
        value = TextFieldValue(next, TextRange(start + text.length))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        // The hold bubble floats over the whole sheet, so the top row's can rise above the panel.
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                if (target.replyTo.isNotEmpty()) {
                    Text(
                        "回复 @${target.replyTo}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    )
                }
                TextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .heightIn(min = 112.dp, max = 220.dp)
                        .focusRequester(focus)
                        .onFocusChanged { if (it.isFocused) panel = false },
                    placeholder = { Text("评论") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = FieldShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                AnimatedVisibility(
                    visible = pictures.isNotEmpty(),
                    enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
                    exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(YoinMotion.defaultEffectsSpec()),
                ) {
                    PictureRow(
                        pictures = pictures,
                        removable = !sending,
                        onRemove = { key ->
                            if (!sending) {
                                haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                pictures.remove(key)
                                uploaded.remove(key)
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (panel) {
                                panel = false
                                runCatching { focus.requestFocus() }
                                keyboard?.show()
                            } else {
                                keyboard?.hide()
                                panel = true
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        AnimatedContent(
                            targetState = panel,
                            transitionSpec = {
                                androidx.compose.animation.scaleIn(motion.scale) +
                                    androidx.compose.animation.fadeIn(motion.fade) togetherWith
                                    androidx.compose.animation.scaleOut(motion.scale) +
                                    androidx.compose.animation.fadeOut(motion.fade)
                            },
                            label = "composerToggle",
                        ) { showingPanel ->
                            Icon(
                                if (showingPanel) ReplyIcons.Keyboard else ReplyIcons.Emoji,
                                contentDescription = if (showingPanel) "键盘" else "表情",
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            keyboard?.hide()
                            panel = false
                            pickPictures()
                        },
                        enabled = remaining > 0 && !sending,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(PiliSymbols.Image, contentDescription = "图片")
                    }
                    Spacer(Modifier.weight(1f))
                    val progress by animateFloatAsState(
                        if (uploadTotal == 0) 0f else uploadDone.toFloat() / uploadTotal,
                        YoinMotion.defaultSpatialSpec(),
                        label = "uploadProgress",
                    )
                    Button(
                        onClick = { send() },
                        shapes = ButtonDefaults.shapes(),
                        enabled = (value.text.isNotBlank() || pictures.isNotEmpty()) && !sending,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        AnimatedContent(
                            targetState = stage,
                            transitionSpec = {
                                motion.crossfade()
                            },
                            label = "sendState",
                        ) { busy ->
                            if (busy == SendStage.Uploading) {
                                LoadingIndicator(
                                    progress = { progress },
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else if (busy == SendStage.Posting) {
                                LoadingIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(YoinSymbols.Send, null, Modifier.size(18.dp))
                                    Text("发送")
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = panel,
                    enter = expandVertically(YoinMotion.defaultSpatialSpec()) + androidx.compose.animation.fadeIn(YoinMotion.defaultEffectsSpec()),
                    exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + androidx.compose.animation.fadeOut(YoinMotion.defaultEffectsSpec()),
                ) {
                    EmotePanel(
                        peek = peek,
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        onPick = { insert(it.text) },
                    )
                }
            }
            EmotePeekOverlay(peek, Modifier.matchParentSize())
        }
    }
}

/**
 * The picked pictures in pick order (the order they post in): bare thumbnails
 * that spring in and out of place, each with a small chip that removes it.
 */
@Composable
internal fun PictureRow(pictures: List<String>, removable: Boolean, onRemove: (String) -> Unit) {
    val motion = replyMotion()
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pictures, key = { it }) { key ->
            Box(
                Modifier
                    .animateItem(fadeInSpec = motion.fade, placementSpec = motion.offset, fadeOutSpec = motion.fadeOut)
                    .size(PictureSize),
            ) {
                AsyncImage(
                    model = Uri.parse(key),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().clip(PictureShape),
                )
                AnimatedVisibility(
                    visible = removable,
                    enter = fadeIn(motion.fade),
                    exit = fadeOut(motion.fadeOut),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    // A 36dp target around the small chip.
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onRemove(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(PictureScrim.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(YoinSymbols.Close, contentDescription = "移除", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun Throwable.uploadMessage(): String {
    val server = (this as? BiliException)?.message?.takeIf { it.isNotBlank() && !it.startsWith("HTTP") }
    return when {
        server != null -> server
        this is UnknownHostException -> "网络不可用"
        this is InterruptedIOException -> "图片上传超时"
        this is SecurityException || this is java.io.FileNotFoundException -> "图片无法读取"
        this is OutOfMemoryError -> "图片过大"
        else -> "图片上传失败"
    }
}

/**
 * Packages as a tab row under their grid. A tap inserts; press and hold
 * previews ([emotePeek]) without inserting. Shared with the dynamic composer
 * (feature/dynamics), which pairs it with its own [EmotePeekOverlay].
 */
@Composable
internal fun EmotePanel(peek: EmotePeekState, modifier: Modifier = Modifier, onPick: (Emote) -> Unit) {
    var packages by remember { mutableStateOf(EmoteCache.packages) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    val motion = replyMotion()
    LaunchedEffect(Unit) {
        if (packages == null) {
            runCatching { ReplyApi.emotePanel() }
                .onSuccess {
                    EmoteCache.packages = it
                    packages = it
                }
                .onFailure { error = it.friendlyMessage() }
        }
    }
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        val list = packages
        when {
            list == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            list.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    error ?: "暂无表情",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val pkg = list[selected.coerceIn(0, list.lastIndex)]
                AnimatedContent(
                    targetState = pkg,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        motion.crossfade()
                    },
                    label = "emotePackage",
                ) { p ->
                    val big = p.emotes.firstOrNull()?.size ?: 1
                    val kaomoji = p.type == 4
                    val gridState = rememberLazyGridState()
                    val cells = remember(p) { HashMap<String, LayoutCoordinates>() }
                    val cellPx = with(LocalDensity.current) { (if (big >= 2) 64.dp else 44.dp).roundToPx() * 2 }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (kaomoji) 88.dp else if (big >= 2) 64.dp else 44.dp),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .emotePeek(
                                peek = peek,
                                gridState = gridState,
                                cells = cells,
                                emotes = p.emotes,
                                kaomoji = kaomoji,
                                placeholder = { Images.thumb(it.url, cellPx) },
                                haptic = haptic,
                            ),
                    ) {
                        items(p.emotes, key = { it.text }) { e ->
                            Box(
                                Modifier
                                    .onPlaced { cells[e.text] = it }
                                    .clip(ContinuousRoundedCornerShape(10.dp))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                        onPick(e)
                                    }
                                    .padding(4.dp)
                                    .then(if (p.type == 4) Modifier.height(36.dp) else Modifier.aspectRatio(1f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (kaomoji || e.url.isEmpty()) {
                                    Text(e.text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Clip)
                                } else {
                                    AsyncImage(
                                        model = Images.thumb(e.url, cellPx),
                                        contentDescription = e.text,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(list, key = { _, p -> p.id }) { i, p ->
                        val on = i == selected
                        val bg by androidx.compose.animation.animateColorAsState(
                            if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            YoinMotion.defaultEffectsSpec(),
                            label = "pkgBg",
                        )
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(bg)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    selected = i
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (p.icon.isNotEmpty() && p.type != 4) {
                                NetImage(
                                    p.icon,
                                    Modifier.size(24.dp),
                                    contentDescription = p.title,
                                    shape = ContinuousRoundedCornerShape(4.dp),
                                    thumbWidth = 48.dp,
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Text(p.title, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
