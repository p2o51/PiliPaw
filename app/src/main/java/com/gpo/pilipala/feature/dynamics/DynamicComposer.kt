package com.gpo.pilipala.feature.dynamics

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.feature.reply.EmotePanel
import com.gpo.pilipala.feature.reply.EmotePeekOverlay
import com.gpo.pilipala.feature.reply.EmotePeekState
import com.gpo.pilipala.feature.reply.ReplyIcons
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.transition.LocalPageSettled
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Text limit of a plain dynamic. */
private const val MaxChars = 1000

/** The count shows once this many characters are typed (the last hundred). */
private const val CountFrom = 900

private const val MaxImages = 9

private val EditorMaxWidth = 640.dp
private val ThumbSize = 88.dp
private val ThumbGap = 8.dp
private val ThumbShape = ContinuousRoundedCornerShape(12.dp)

/** Over-image scrim (the remove chip, the upload veil): images are the one place for a fixed colour. */
private val ImageScrim = Color.Black

@Immutable
internal data class PickedImage(val uri: Uri) {
    val key: String get() = uri.toString()
}

internal sealed interface ComposeOutcome {
    data object Posted : ComposeOutcome
    data class Failed(val message: String) : ComposeOutcome
}

/**
 * The draft (text, picked pictures) and the send. Lives per nav entry, so a
 * rotation keeps it; the pictures already uploaded survive a failed send, a
 * retry only uploads the rest.
 */
internal class DynamicComposeViewModel(private val app: Context) : ViewModel() {
    var text by mutableStateOf(TextFieldValue())
    val images = mutableStateListOf<PickedImage>()

    var sending by mutableStateOf(false)
        private set

    /** The picture uploading right now; null during the text step or at rest. */
    var uploadingKey by mutableStateOf<String?>(null)
        private set

    /** One-shot result for the screen (toast, close); [consumeOutcome] clears it. */
    var outcome by mutableStateOf<ComposeOutcome?>(null)
        private set

    /** The editor took focus once, on landing; a rotation doesn't raise the keyboard again. */
    var focusedOnce = false

    private val uploaded = mutableStateMapOf<String, UploadedPic>()

    val length: Int get() = text.text.length
    val hasDraft: Boolean get() = text.text.isNotBlank() || images.isNotEmpty()
    val canSend: Boolean get() = !sending && hasDraft && length <= MaxChars

    fun isUploaded(key: String): Boolean = key in uploaded

    fun insert(snippet: String) {
        val v = text
        val start = v.selection.min.coerceIn(0, v.text.length)
        val end = v.selection.max.coerceIn(0, v.text.length)
        text = TextFieldValue(v.text.substring(0, start) + snippet + v.text.substring(end), TextRange(start + snippet.length))
    }

    fun addImages(uris: List<Uri>) {
        val room = MaxImages - images.size
        if (room <= 0) return
        uris.distinct()
            .filter { uri -> images.none { it.uri == uri } }
            .take(room)
            .forEach { images += PickedImage(it) }
    }

    /** False while sending: the picture stays. */
    fun remove(image: PickedImage): Boolean {
        if (sending) return false
        images.remove(image)
        uploaded.remove(image.key)
        return true
    }

    fun move(from: Int, to: Int) {
        if (sending || from == to || from !in images.indices || to !in images.indices) return
        images.add(to, images.removeAt(from))
    }

    fun consumeOutcome() {
        outcome = null
    }

    /** The send button's action, and the only path that publishes. */
    fun send() {
        if (!canSend) return
        if (!BiliClient.isLoggedIn) {
            outcome = ComposeOutcome.Failed("未登录")
            return
        }
        sending = true
        val body = text.text.trim()
        val pics = images.toList()
        viewModelScope.launch {
            var step = Step.Upload
            try {
                val ready = pics.map { image ->
                    uploaded[image.key] ?: run {
                        uploadingKey = image.key
                        DynamicsApi.uploadImage(app.contentResolver, image.uri).also { uploaded[image.key] = it }
                    }
                }
                uploadingKey = null
                step = Step.Create
                val id = DynamicsApi.createDynamic(body, ready)
                DynamicsEvents.posted.tryEmit(id)
                outcome = ComposeOutcome.Posted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                outcome = ComposeOutcome.Failed(e.publishMessage(step))
            } finally {
                uploadingKey = null
                sending = false
            }
        }
    }

    private enum class Step { Upload, Create }

    private fun Throwable.publishMessage(step: Step): String {
        val server = (this as? BiliException)?.message?.takeIf { it.isNotBlank() && !it.startsWith("HTTP") }
        return when {
            server != null -> server
            this is UnknownHostException -> "网络不可用"
            this is SocketTimeoutException -> if (step == Step.Upload) "图片上传超时" else "请求超时"
            this is SecurityException -> "图片无法读取"
            step == Step.Upload -> "图片上传失败"
            else -> "发布失败"
        }
    }
}

/**
 * 发动态: a filled editor (centred at reading width on Medium/Expanded) with
 * the picked pictures under the text, and a toolbar that rides the keyboard —
 * emotes (the comment composer's panel, swapping with the keyboard), the
 * system photo picker, the count near the limit, send. Back with a draft asks
 * in place.
 */
@Composable
fun DynamicComposeScreen() {
    val context = LocalContext.current
    val vm: DynamicComposeViewModel = viewModel { DynamicComposeViewModel(context.applicationContext) }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val peek = remember { EmotePeekState() }
    val settled = LocalPageSettled.current
    var panel by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    // The page keeps showing what it showed (the confirm, the text) while it closes.
    fun leave() {
        leaving = true
        keyboard?.hide()
        nav.pop()
    }

    fun requestClose() {
        when {
            leaving || vm.sending -> Unit
            vm.hasDraft -> {
                keyboard?.hide()
                panel = false
                confirming = true
            }
            else -> leave()
        }
    }

    fun toggleEmotes() {
        if (panel) {
            panel = false
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            confirming = false
            panel = true
        }
    }

    // The keyboard comes up once the page has landed: opening it mid-grow would
    // resize the container under the transform.
    LaunchedEffect(settled) {
        if (settled && !vm.focusedOnce) {
            vm.focusedOnce = true
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    // The keyboard and the emote panel take turns: whenever the keyboard comes up
    // (a tap on the text, 继续编辑), the panel and the confirm give way.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            panel = false
            confirming = false
        }
    }

    LaunchedEffect(vm.outcome) {
        when (val o = vm.outcome) {
            ComposeOutcome.Posted -> {
                vm.consumeOutcome()
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                toast("发布成功")
                leave()
            }
            is ComposeOutcome.Failed -> {
                vm.consumeOutcome()
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                toast(o.message)
            }
            null -> Unit
        }
    }

    // In-page back outranks the host's page back while there is something to settle here.
    BackHandler(enabled = !leaving && (vm.sending || panel || confirming || vm.hasDraft)) {
        when {
            vm.sending -> Unit
            confirming -> confirming = false
            panel -> panel = false
            else -> requestClose()
        }
    }

    val remaining = MaxImages - vm.images.size
    val pickMany = rememberLauncherForActivityResult(
        remember(remaining) { ActivityResultContracts.PickMultipleVisualMedia(remaining.coerceAtLeast(2)) },
    ) { uris -> vm.addImages(uris) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addImages(listOf(uri))
    }
    val pickImages: () -> Unit = {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        when {
            vm.sending -> Unit
            remaining >= 2 -> pickMany.launch(request)
            remaining == 1 -> pickOne.launch(request)
        }
    }

    val bottomColor by animateColorAsState(
        if (panel) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        YoinMotion.defaultEffectsSpec(),
        label = "composerBottom",
    )
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()
    val scaleSpec = YoinMotion.fastSpatialSpec<Float>()
    val sizeSpec = YoinMotion.defaultSpatialSpec<IntSize>()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding(),
        ) {
            ComposerTopBar(enabled = !vm.sending, onClose = ::requestClose)
            Column(
                Modifier
                    .weight(1f)
                    .readingWidth(EditorMaxWidth)
                    .padding(horizontal = 16.dp)
                    // Resized every frame while the keyboard moves: the circular twin keeps the clip native.
                    .clip(YoinContainerShapes.PanelAnimated)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                TextField(
                    value = vm.text,
                    onValueChange = { vm.text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRequester(focus)
                        .onFocusChanged {
                            if (it.isFocused) {
                                panel = false
                                confirming = false
                            }
                        },
                    readOnly = vm.sending,
                    placeholder = { Text("动态内容") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                AnimatedVisibility(
                    visible = vm.images.isNotEmpty(),
                    enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(fadeInSpec),
                    exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(fadeOutSpec),
                ) {
                    ThumbStrip(vm = vm, canAdd = remaining > 0, onAdd = pickImages)
                }
            }
            AnimatedContent(
                targetState = confirming,
                transitionSpec = {
                    (fadeIn(fadeInSpec) + scaleIn(scaleSpec, initialScale = 0.96f))
                        .togetherWith(fadeOut(fadeOutSpec) + scaleOut(scaleSpec, targetScale = 0.96f))
                        .using(SizeTransform(clip = false) { _, _ -> sizeSpec })
                },
                contentAlignment = Alignment.Center,
                label = "composerBar",
                modifier = Modifier.readingWidth(EditorMaxWidth),
            ) { asking ->
                if (asking) {
                    DiscardBar(
                        onKeep = {
                            confirming = false
                            runCatching { focus.requestFocus() }
                            keyboard?.show()
                        },
                        onDiscard = ::leave,
                    )
                } else {
                    ComposerToolbar(
                        vm = vm,
                        panel = panel,
                        canPick = remaining > 0,
                        onToggleEmotes = ::toggleEmotes,
                        onPick = pickImages,
                        onSend = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            keyboard?.hide()
                            panel = false
                            vm.send()
                        },
                    )
                }
            }
            // The panel and the navigation bar strip share one block, so the emote
            // panel runs edge to edge under the gesture bar.
            Column(Modifier.fillMaxWidth().background(bottomColor)) {
                AnimatedVisibility(
                    visible = panel,
                    enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(fadeInSpec),
                    exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(fadeOutSpec),
                ) {
                    EmotePanel(
                        peek = peek,
                        modifier = Modifier.readingWidth(EditorMaxWidth).height(280.dp),
                        onPick = { vm.insert(it.text) },
                    )
                }
                Spacer(Modifier.fillMaxWidth().navigationBarsPadding())
            }
        }
        EmotePeekOverlay(peek, Modifier.matchParentSize())
    }
}

@Composable
private fun ComposerTopBar(enabled: Boolean, onClose: () -> Unit) {
    // Aligned with the editor: on a wide window the close button sits at the card's edge.
    Row(
        Modifier.readingWidth(EditorMaxWidth).height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Close, contentDescription = "关闭")
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "发动态",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ComposerToolbar(
    vm: DynamicComposeViewModel,
    panel: Boolean,
    canPick: Boolean,
    onToggleEmotes: () -> Unit,
    onPick: () -> Unit,
    onSend: () -> Unit,
) {
    val scaleSpec = YoinMotion.fastSpatialSpec<Float>()
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()
    val sizeSpec = YoinMotion.fastSpatialSpec<IntSize>()
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleEmotes, enabled = !vm.sending, shapes = IconButtonDefaults.shapes()) {
            AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    (scaleIn(scaleSpec) + fadeIn(fadeInSpec)).togetherWith(scaleOut(scaleSpec) + fadeOut(fadeOutSpec))
                },
                label = "emoteToggle",
            ) { showingPanel ->
                Icon(
                    if (showingPanel) ReplyIcons.Keyboard else ReplyIcons.Emoji,
                    contentDescription = if (showingPanel) "键盘" else "表情",
                )
            }
        }
        IconButton(onClick = onPick, enabled = canPick && !vm.sending, shapes = IconButtonDefaults.shapes()) {
            Icon(PiliSymbols.Image, contentDescription = "图片")
        }
        Spacer(Modifier.weight(1f))
        CharCount(vm.length)
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = onSend,
            enabled = vm.canSend,
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            AnimatedContent(
                targetState = vm.sending,
                transitionSpec = {
                    fadeIn(fadeInSpec).togetherWith(fadeOut(fadeOutSpec)).using(SizeTransform { _, _ -> sizeSpec })
                },
                contentAlignment = Alignment.Center,
                label = "sendState",
            ) { busy ->
                if (busy) {
                    LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(YoinSymbols.Send, null, Modifier.size(18.dp))
                        Text("发布")
                    }
                }
            }
        }
    }
}

/** Near the limit only: quiet label, bold number (the remainder, or the overflow in error). */
@Composable
private fun CharCount(length: Int) {
    val left = MaxChars - length
    AnimatedVisibility(
        visible = length >= CountFrom,
        enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandHorizontally(YoinMotion.fastSpatialSpec()),
        exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkHorizontally(YoinMotion.fastSpatialSpec()),
    ) {
        val over = left < 0
        val numberColor by animateColorAsState(
            if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            YoinMotion.defaultEffectsSpec(),
            label = "countColor",
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (over) "超出" else "剩余",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                abs(left).toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = numberColor,
            )
        }
    }
}

/** The in-page confirm for leaving with a draft: a filled block in the toolbar's place. */
@Composable
private fun DiscardBar(onKeep: () -> Unit, onDiscard: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(YoinContainerShapes.Panel)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "草稿未发送",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onKeep, shapes = ButtonDefaults.shapes()) { Text("继续编辑") }
        Button(
            onClick = onDiscard,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { Text("舍弃") }
    }
}

// ── Picked pictures ─────────────────────────────────────────────────────────

/**
 * The picked pictures in pick order (the order they post in), then an add tile.
 * Long-press and drag a picture to reorder it: it follows the finger while the
 * others spring aside; the chip on its corner removes it.
 */
@Composable
private fun ThumbStrip(vm: DynamicComposeViewModel, canAdd: Boolean, onAdd: () -> Unit) {
    val slot = with(LocalDensity.current) { (ThumbSize + ThumbGap).toPx() }
    val addVisible = canAdd && !vm.sending
    val count = vm.images.size + if (addVisible) 1 else 0
    val width = ThumbSize * count + ThumbGap * (count - 1).coerceAtLeast(0)
    var dragKey by remember { mutableStateOf<String?>(null) }
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Box(Modifier.width(width).height(ThumbSize)) {
            vm.images.forEachIndexed { index, image ->
                key(image.key) {
                    PickedThumb(
                        vm = vm,
                        image = image,
                        index = index,
                        slot = slot,
                        dragging = dragKey == image.key,
                        onDragState = { dragging -> dragKey = if (dragging) image.key else null },
                    )
                }
            }
            AddTile(index = vm.images.size, slot = slot, visible = addVisible, onAdd = onAdd)
        }
    }
}

/** The strip's last tile: springs to the slot after the pictures, fades out at nine or while sending. */
@Composable
private fun AddTile(index: Int, slot: Float, visible: Boolean, onAdd: () -> Unit) {
    val x = remember { Animatable(index * slot) }
    val shown = remember { Animatable(0f) }
    val placement = YoinMotion.fastSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(index, slot) { x.animateTo(index * slot, placement) }
    LaunchedEffect(visible) { shown.animateTo(if (visible) 1f else 0f, effects) }
    Box(
        Modifier
            .graphicsLayer {
                translationX = x.value
                alpha = shown.value.coerceIn(0f, 1f)
                val s = 0.8f + 0.2f * shown.value
                scaleX = s
                scaleY = s
            }
            .size(ThumbSize)
            .clip(ThumbShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = visible, onClick = onAdd),
        contentAlignment = Alignment.Center,
    ) {
        Icon(YoinSymbols.Add, contentDescription = "添加图片", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PickedThumb(
    vm: DynamicComposeViewModel,
    image: PickedImage,
    index: Int,
    slot: Float,
    dragging: Boolean,
    onDragState: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val x = remember { Animatable(index * slot) }
    val presence = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    val dragOrigin = remember { mutableFloatStateOf(0f) }
    val dragDelta = remember { mutableFloatStateOf(0f) }
    var removing by remember { mutableStateOf(false) }
    val placement = YoinMotion.fastSpatialSpec<Float>()
    val pop = YoinMotion.fastSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    val fastEffects = YoinMotion.fastEffectsSpec<Float>()
    val sending = vm.sending
    val uploading = vm.uploadingKey == image.key
    val pending = sending && !vm.isUploaded(image.key)

    // Not while dragged: the finger owns the position, and the release springs it home from there.
    LaunchedEffect(index, slot, dragging) { if (!dragging) x.animateTo(index * slot, placement) }
    LaunchedEffect(Unit) { presence.animateTo(1f, effects) }
    LaunchedEffect(dragging) { lift.animateTo(if (dragging) 1f else 0f, pop) }

    Box(
        Modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationX = if (dragging) dragOrigin.floatValue + dragDelta.floatValue else x.value
                val s = (0.8f + 0.2f * presence.value) * (1f + 0.06f * lift.value)
                scaleX = s
                scaleY = s
                alpha = presence.value.coerceIn(0f, 1f)
            }
            .size(ThumbSize)
            .pointerInput(image.key, slot, sending) {
                if (sending) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        dragOrigin.floatValue = x.value
                        dragDelta.floatValue = 0f
                        onDragState(true)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val last = (vm.images.size - 1).coerceAtLeast(0)
                        val maxX = last * slot
                        val raw = dragOrigin.floatValue + dragDelta.floatValue + amount.x
                        dragDelta.floatValue = raw.coerceIn(0f, maxX) - dragOrigin.floatValue
                        val from = vm.images.indexOfFirst { it.key == image.key }
                        val to = ((dragOrigin.floatValue + dragDelta.floatValue) / slot).roundToInt().coerceIn(0, last)
                        if (from >= 0 && to != from) {
                            vm.move(from, to)
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            x.snapTo(dragOrigin.floatValue + dragDelta.floatValue)
                            onDragState(false)
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            x.snapTo(dragOrigin.floatValue + dragDelta.floatValue)
                            onDragState(false)
                        }
                    },
                )
            },
    ) {
        AsyncImage(
            model = image.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().clip(ThumbShape),
        )
        AnimatedVisibility(
            visible = pending,
            enter = fadeIn(effects),
            exit = fadeOut(fastEffects),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                Modifier.fillMaxSize().clip(ThumbShape).background(ImageScrim.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(visible = uploading, enter = fadeIn(effects), exit = fadeOut(fastEffects)) {
                    LoadingIndicator(Modifier.size(36.dp), color = Color.White)
                }
            }
        }
        AnimatedVisibility(
            visible = !sending && !removing,
            enter = fadeIn(effects),
            exit = fadeOut(fastEffects),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                Modifier
                    .padding(4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(ImageScrim.copy(alpha = 0.55f))
                    .clickable {
                        if (removing) return@clickable
                        removing = true
                        haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
                        scope.launch {
                            presence.animateTo(0f, fastEffects)
                            // A send that started meanwhile keeps the picture: bring it back.
                            if (!vm.remove(image)) {
                                removing = false
                                presence.animateTo(1f, effects)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(YoinSymbols.Close, contentDescription = "移除", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}
