package com.gpo.pilipala.feature.dynamics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.feature.reply.EmotePanel
import com.gpo.pilipala.feature.reply.EmotePeekOverlay
import com.gpo.pilipala.feature.reply.EmotePeekState
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.LocalPageSettled
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.feature.sources.miniPlayerFloatOnly
import com.gpo.yoin.symbols.YoinSymbols
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Text limit of a plain dynamic, in code points (an emoji is one). The 1000
 * and how the server counts an `[emote]` code are not verified; a code counts
 * as its characters here, the stricter reading.
 */
private const val MaxChars = 1000

/** The count shows once this many characters are typed (the last hundred). */
private const val CountFrom = 900

private const val MaxImages = 9

/** 发布 ignores taps this long after the toolbar comes back from the discard confirm. */
private const val SendGuardMs = 400L

/** A create that never answered is looked up in the account's own feed this many times, this far apart. */
private const val VerifyAttempts = 3
private const val VerifyBackoffMs = 2000L

/** A matching post this long (s) before the attempt still counts: the device clock is not the server's. */
private const val ClockSlackSeconds = 600L

private val EditorMaxWidth = 640.dp
private val EditorMinHeight = 96.dp
private val EmotePanelMaxHeight = 280.dp
private val ThumbSize = 88.dp
private val ThumbGap = 8.dp
private val StripPadding = 12.dp
private val StripHeight = ThumbSize + StripPadding * 2
private val ThumbShape = ContinuousRoundedCornerShape(12.dp)

/** Over-image scrim (the remove chip, the upload veil): images are the one place for a fixed colour. */
private val ImageScrim = Color.Black

// The draft lives in the saved state too: the process can be reclaimed while
// the photo picker is in front, and the picked pictures come back to a new
// ViewModel.
private const val KeyText = "text"
private const val KeySelStart = "sel_start"
private const val KeySelEnd = "sel_end"
private const val KeyImages = "images"
private const val KeyUploaded = "uploaded"
private const val KeyUploadId = "upload_id"
private const val KeyUploadSig = "upload_sig"
private const val KeyPending = "pending"
private const val KeyFinished = "finished"
private const val KeyFocused = "focused_once"

@Immutable
internal data class PickedImage(val uri: Uri) {
    val key: String get() = uri.toString()
}

internal sealed interface ComposeOutcome {
    data object Posted : ComposeOutcome
    data class Failed(val message: String) : ComposeOutcome
    data class Notice(val message: String) : ComposeOutcome
}

internal enum class SendPhase { Idle, Uploading, Publishing }

/** A create whose answer never came: what was sent, and when (device seconds). */
@Serializable
internal data class PendingPost(val text: String, val pics: Int, val sig: String, val startedAt: Long)

private sealed interface Lookup {
    data class Found(val id: String) : Lookup
    data object NotFound : Lookup
    data object Unreachable : Lookup
}

/** The create got no answer and the feed check could not confirm it: shown as is. */
private class UnconfirmedPost(override val message: String) : Exception(message)

/**
 * The draft (text, picked pictures) and the send. Lives per nav entry and in
 * its saved state, so neither a rotation nor a reclaimed process loses it;
 * the pictures already uploaded survive a failed send, a retry only uploads
 * the rest.
 *
 * Duplicate guard: the draft keeps one `upload_id` for as long as its content
 * stays the same, and a create that never answered is marked pending — before
 * anything is sent again the account's own latest dynamics are checked, and a
 * match counts as posted.
 */
internal class DynamicComposeViewModel(
    private val app: Context,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private var textValue by mutableStateOf(restoreText())

    /** The draft text; every change is mirrored into the saved state. */
    var text: TextFieldValue
        get() = textValue
        set(value) {
            textValue = value
            saved[KeyText] = value.text
            saved[KeySelStart] = value.selection.start
            saved[KeySelEnd] = value.selection.end
        }

    val images = mutableStateListOf<PickedImage>().apply {
        saved.get<ArrayList<String>>(KeyImages)?.forEach { add(PickedImage(Uri.parse(it))) }
    }

    var phase by mutableStateOf(SendPhase.Idle)
        private set

    val sending: Boolean get() = phase != SendPhase.Idle

    /** The picture uploading right now; null during the text step or at rest. */
    var uploadingKey by mutableStateOf<String?>(null)
        private set

    /** One-shot result for the screen (toast); [consumeOutcome] clears it. */
    var outcome by mutableStateOf<ComposeOutcome?>(null)
        private set

    /** Posted: the draft is gone and the page closes as soon as it is the top one. */
    var finished by mutableStateOf(saved.get<Boolean>(KeyFinished) == true)
        private set

    /** What the editor keeps showing while the page closes after a post; the draft itself is already cleared. */
    var closingText by mutableStateOf<TextFieldValue?>(null)
        private set

    /** The editor took focus once, on landing; a rotation doesn't raise the keyboard again. */
    var focusedOnce: Boolean
        get() = saved.get<Boolean>(KeyFocused) == true
        set(value) {
            saved[KeyFocused] = value
        }

    private val uploaded = mutableStateMapOf<String, UploadedPic>().apply {
        saved.get<String>(KeyUploaded)?.let { raw ->
            runCatching { BiliJson.decodeFromString<Map<String, UploadedPic>>(raw) }.getOrNull()?.let { putAll(it) }
        }
    }

    private var pending: PendingPost? = saved.get<String>(KeyPending)?.let { raw ->
        runCatching { BiliJson.decodeFromString<PendingPost>(raw) }.getOrNull()
    }
        set(value) {
            field = value
            saved[KeyPending] = value?.let { BiliJson.encodeToString(it) }
        }

    private var job: Job? = null

    /** Bumped by every send and cancel: a stale job's cleanup leaves a newer send alone. */
    private var generation = 0

    val length: Int get() = text.text.let { it.codePointCount(0, it.length) }
    val hasDraft: Boolean get() = !finished && (text.text.isNotBlank() || images.isNotEmpty())
    val canSend: Boolean get() = !sending && hasDraft && length <= MaxChars

    fun isUploaded(key: String): Boolean = key in uploaded

    fun insert(snippet: String) {
        val v = text
        val start = v.selection.min.coerceIn(0, v.text.length)
        val end = v.selection.max.coerceIn(0, v.text.length)
        text = TextFieldValue(v.text.substring(0, start) + snippet + v.text.substring(end), TextRange(start + snippet.length))
    }

    /** Picked pictures, with a lasting read grant so they still open after the process restarts. */
    fun addImages(uris: List<Uri>) {
        if (finished) return
        val room = MaxImages - images.size
        if (room <= 0) return
        val fresh = uris.distinct().filter { uri -> images.none { it.uri == uri } }.take(room)
        if (fresh.isEmpty()) return
        fresh.forEach { uri ->
            keepGrant(uri)
            images += PickedImage(uri)
        }
        saveImages()
    }

    /** False while sending: the picture stays. */
    fun remove(image: PickedImage): Boolean {
        if (sending) return false
        images.remove(image)
        uploaded.remove(image.key)
        dropGrant(image.uri)
        saveImages()
        saveUploaded()
        return true
    }

    fun move(from: Int, to: Int) {
        if (sending || from == to || from !in images.indices || to !in images.indices) return
        images.add(to, images.removeAt(from))
        saveImages()
    }

    fun consumeOutcome() {
        outcome = null
    }

    /**
     * Back or close while pictures upload: stops (the ones already up are
     * kept for the next send). False once the create has started — that one
     * is never abandoned half way.
     */
    fun cancelUpload(): Boolean {
        if (phase != SendPhase.Uploading) return false
        generation++
        job?.cancel()
        job = null
        uploadingKey = null
        phase = SendPhase.Idle
        outcome = ComposeOutcome.Notice("已取消上传")
        return true
    }

    /** The send button's action, and the only path that publishes. */
    fun send() {
        if (!canSend) return
        if (!BiliClient.isLoggedIn) {
            outcome = ComposeOutcome.Failed("未登录")
            return
        }
        val body = text.text.trim()
        val pics = images.toList()
        val sig = signature(body, pics)
        val gen = ++generation
        phase = if (pics.all { it.key in uploaded }) SendPhase.Publishing else SendPhase.Uploading
        job = viewModelScope.launch {
            var step = Step.Upload
            try {
                val ready = pics.map { image ->
                    uploaded[image.key] ?: run {
                        uploadingKey = image.key
                        DynamicsApi.uploadImage(app.contentResolver, app.cacheDir, image.uri).also {
                            uploaded[image.key] = it
                            saveUploaded()
                        }
                    }
                }
                uploadingKey = null
                ensureActive()
                phase = SendPhase.Publishing
                step = Step.Create
                // An earlier create never answered: look at the account's own
                // dynamics before anything is sent again.
                pending?.let { earlier ->
                    when (val found = findPosted(earlier, attempts = 1)) {
                        is Lookup.Found -> {
                            if (earlier.sig == sig) {
                                publishDone(found.id)
                            } else {
                                pending = null
                                DynamicsEvents.posted.tryEmit(found.id)
                                outcome = ComposeOutcome.Notice("上一条已发布")
                            }
                            return@launch
                        }
                        Lookup.NotFound -> pending = null
                        Lookup.Unreachable -> {
                            outcome = ComposeOutcome.Failed("网络不可用")
                            return@launch
                        }
                    }
                }
                val attempt = PendingPost(body, ready.size, sig, System.currentTimeMillis() / 1000)
                val uploadId = uploadIdFor(sig)
                // Marked before the request: a process reclaimed mid-create still checks first.
                pending = attempt
                val id = try {
                    DynamicsApi.createDynamic(body, ready, uploadId)
                } catch (e: PublishUnknownException) {
                    when (val found = findPosted(attempt, VerifyAttempts)) {
                        is Lookup.Found -> found.id
                        Lookup.NotFound -> throw UnconfirmedPost("发布失败")
                        Lookup.Unreachable -> throw UnconfirmedPost("网络不可用")
                    }
                } catch (e: BiliException) {
                    // The server answered with an error: nothing was posted.
                    pending = null
                    throw e
                }
                publishDone(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                outcome = ComposeOutcome.Failed(e.publishMessage(step))
            } finally {
                if (generation == gen) {
                    uploadingKey = null
                    phase = SendPhase.Idle
                    job = null
                }
            }
        }
    }

    override fun onCleared() {
        // The page is gone (posted, discarded or closed empty): the lasting grants can go.
        images.forEach { dropGrant(it.uri) }
    }

    private fun publishDone(id: String) {
        closingText = text
        pending = null
        clearDraft()
        finished = true
        saved[KeyFinished] = true
        DynamicsEvents.posted.tryEmit(id)
        outcome = ComposeOutcome.Posted
    }

    /** Posted: nothing of it is left to send again. */
    private fun clearDraft() {
        images.forEach { dropGrant(it.uri) }
        text = TextFieldValue()
        images.clear()
        uploaded.clear()
        saveImages()
        saveUploaded()
        saved.remove<String>(KeyUploadId)
        saved.remove<String>(KeyUploadSig)
    }

    /** The draft's `upload_id`: kept while its content stays the same, renewed when it changes. */
    private fun uploadIdFor(sig: String): String {
        val kept = saved.get<String>(KeyUploadId)
        if (kept != null && saved.get<String>(KeyUploadSig) == sig) return kept
        val fresh = "${BiliClient.mid}_${System.currentTimeMillis() / 1000}_${Random.nextInt(1000, 10000)}"
        saved[KeyUploadId] = fresh
        saved[KeyUploadSig] = sig
        return fresh
    }

    /**
     * Whether [post] is among the account's latest dynamics (read-only GETs),
     * asked [attempts] times since a fresh post can lag in the feed.
     */
    private suspend fun findPosted(post: PendingPost, attempts: Int): Lookup {
        var reached = false
        repeat(attempts) { i ->
            if (i > 0) delay(VerifyBackoffMs * i)
            val recent = try {
                DynamicsApi.ownRecent(BiliClient.mid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (recent != null) {
                reached = true
                recent.firstOrNull { it.isPostOf(post) }?.let { return Lookup.Found(it.id) }
            }
        }
        return if (reached) Lookup.NotFound else Lookup.Unreachable
    }

    private fun restoreText(): TextFieldValue {
        val t = saved.get<String>(KeyText).orEmpty()
        val start = (saved.get<Int>(KeySelStart) ?: t.length).coerceIn(0, t.length)
        val end = (saved.get<Int>(KeySelEnd) ?: t.length).coerceIn(0, t.length)
        return TextFieldValue(t, TextRange(start, end))
    }

    private fun saveImages() {
        saved[KeyImages] = ArrayList(images.map { it.key })
    }

    private fun saveUploaded() {
        saved[KeyUploaded] = BiliJson.encodeToString(uploaded.toMap())
    }

    private fun keepGrant(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun dropGrant(uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private enum class Step { Upload, Create }

    private fun Throwable.publishMessage(step: Step): String {
        if (this is UnconfirmedPost) return message
        val server = (this as? BiliException)?.message?.takeIf { it.isNotBlank() && !it.startsWith("HTTP") }
        return when {
            server != null -> server
            this is UnknownHostException -> "网络不可用"
            this is InterruptedIOException -> if (step == Step.Upload) "图片上传超时" else "请求超时"
            this is SecurityException -> "图片无法读取"
            this is OutOfMemoryError -> "图片过大"
            step == Step.Upload -> "图片上传失败"
            else -> "发布失败"
        }
    }
}

/** The content's identity for `upload_id`: the trimmed text and the pictures in order. */
private fun signature(body: String, pics: List<PickedImage>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(body.toByteArray())
    pics.forEach {
        digest.update(0)
        digest.update(it.key.toByteArray())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private val Whitespace = Regex("""\s+""")

private fun String.squashed(): String = replace(Whitespace, " ").trim()

/** A text or picture dynamic of this account carrying [post]'s text and picture count, not older than its attempt. */
private fun DynItem.isPostOf(post: PendingPost): Boolean {
    if (kind != "DYNAMIC_TYPE_WORD" && kind != "DYNAMIC_TYPE_DRAW") return false
    val published = pubTs
    if (published > 0 && published < post.startedAt - ClockSlackSeconds) return false
    if (pictures.size != post.pics) return false
    val shown = postedText().squashed()
    val sent = post.text.squashed()
    if (shown == sent) return true
    // A long text can come back cut, ending in an ellipsis.
    val cut = shown.trimEnd('…', '.', ' ')
    return cut.length >= 20 && cut.length < sent.length && sent.startsWith(cut)
}

/**
 * 发动态: a filled editor (centred at reading width on Medium/Expanded) with
 * the picked pictures under the text, and a toolbar that rides the keyboard —
 * emotes (the comment composer's panel, swapping with the keyboard), the
 * system photo picker, the count near the limit, send. Back with a draft asks
 * in place; back while pictures upload stops the upload.
 */
@Composable
fun DynamicComposeScreen() {
    val context = LocalContext.current
    val vm: DynamicComposeViewModel = viewModel {
        DynamicComposeViewModel(context.applicationContext, createSavedStateHandle())
    }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val peek = remember { EmotePeekState() }
    val settled = LocalPageSettled.current
    var panel by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // When the toolbar last came back from the confirm: 发布 sits out the next SendGuardMs.
    var toolbarBackAt by remember { mutableLongStateOf(0L) }
    var refocus by remember { mutableIntStateOf(0) }
    // Only this page is ever popped: a page pushed over it meanwhile (a link from outside) stays.
    val onTop by remember(nav) { derivedStateOf { nav.backStack.lastOrNull() == Route.DynamicCompose } }

    // The page keeps showing what it showed (the confirm, the text) while it closes.
    fun leave() {
        if (leaving || !onTop) return
        leaving = true
        keyboard?.hide()
        nav.pop()
    }

    fun startConfirm() {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        panel = false
        confirming = true
    }

    fun endConfirm() {
        toolbarBackAt = SystemClock.uptimeMillis()
        confirming = false
    }

    fun requestClose() {
        if (leaving) return
        when (vm.phase) {
            SendPhase.Uploading -> vm.cancelUpload()
            SendPhase.Publishing -> Unit
            SendPhase.Idle -> if (vm.hasDraft) startConfirm() else leave()
        }
    }

    fun toggleEmotes() {
        if (confirming) return
        if (panel) {
            panel = false
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            panel = true
        }
    }

    // The keyboard comes up once the page has landed: opening it mid-grow would
    // resize the container under the transform.
    LaunchedEffect(settled) {
        if (settled && !vm.focusedOnce && !confirming && !vm.finished) {
            vm.focusedOnce = true
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    // 继续编辑: focus once the editor is editable again (it is read-only under the confirm).
    LaunchedEffect(refocus) {
        if (refocus > 0) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }

    // The keyboard and the emote panel take turns. The confirm is left only by
    // its own buttons or back, never by the keyboard or focus moving.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) panel = false
    }

    LaunchedEffect(vm.outcome) {
        when (val o = vm.outcome) {
            ComposeOutcome.Posted -> {
                vm.consumeOutcome()
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                toast("发布成功")
            }
            is ComposeOutcome.Failed -> {
                vm.consumeOutcome()
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                toast(o.message)
            }
            is ComposeOutcome.Notice -> {
                vm.consumeOutcome()
                toast(o.message)
            }
            null -> Unit
        }
    }

    // Posted: close as soon as this page is the top one.
    LaunchedEffect(vm.finished, onTop) {
        if (vm.finished && onTop) leave()
    }

    // In-page back outranks the host's page back while there is something to settle here.
    BackHandler(enabled = !leaving && (vm.sending || panel || confirming || vm.hasDraft)) {
        when {
            vm.phase == SendPhase.Uploading -> vm.cancelUpload()
            vm.sending -> {
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                toast("正在发布")
            }
            confirming -> endConfirm()
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
            vm.sending || confirming || vm.finished -> Unit
            remaining >= 2 -> pickMany.launch(request)
            remaining == 1 -> pickOne.launch(request)
        }
    }
    val onSend: () -> Unit = {
        // A tap meant for the confirm that just left (or still leaving) never publishes.
        val calm = SystemClock.uptimeMillis() - toolbarBackAt >= SendGuardMs
        if (calm && !confirming && !leaving && vm.canSend) {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            keyboard?.hide()
            panel = false
            vm.send()
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
    // A short window (phone landscape, split screen, a small desktop window)
    // keeps room for the text under the panel.
    val panelHeight = min(EmotePanelMaxHeight, LocalConfiguration.current.screenHeightDp.dp * 0.4f)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding(),
        ) {
            ComposerTopBar(enabled = vm.phase != SendPhase.Publishing && !leaving, onClose = ::requestClose)
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .readingWidth(EditorMaxWidth)
                    .padding(horizontal = 16.dp)
                    // Resized every frame while the keyboard moves: the circular twin keeps the clip native.
                    .clip(YoinContainerShapes.PanelAnimated)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                val stripSpace by animateDpAsState(
                    if (vm.images.isNotEmpty()) StripHeight else 0.dp,
                    YoinMotion.defaultSpatialSpec(),
                    label = "stripSpace",
                )
                // The text fills what the pictures leave, never less than a few
                // lines; when the block is too short for both, it scrolls.
                val fieldMin = max(EditorMinHeight, maxHeight - stripSpace)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TextField(
                        value = vm.closingText ?: vm.text,
                        onValueChange = { if (!vm.finished) vm.text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = fieldMin)
                            .focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) panel = false },
                        readOnly = vm.sending || confirming || vm.finished,
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
                // The mini player floats up top while the editor is on screen, clear of the toolbar.
                modifier = Modifier.readingWidth(EditorMaxWidth).miniPlayerFloatOnly(),
            ) { asking ->
                // Each side acts only while it is the current one: the bar on its
                // way out ignores taps.
                if (asking) {
                    DiscardBar(
                        onKeep = {
                            if (confirming) {
                                endConfirm()
                                refocus++
                            }
                        },
                        onDiscard = { if (confirming) leave() },
                    )
                } else {
                    ComposerToolbar(
                        vm = vm,
                        panel = panel,
                        canPick = remaining > 0,
                        onToggleEmotes = ::toggleEmotes,
                        onPick = pickImages,
                        onSend = onSend,
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
                        modifier = Modifier.readingWidth(EditorMaxWidth).height(panelHeight),
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
                    if (showingPanel) YoinSymbols.Keyboard else YoinSymbols.Emoji,
                    contentDescription = if (showingPanel) "键盘" else "表情",
                )
            }
        }
        IconButton(onClick = onPick, enabled = canPick && !vm.sending, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Image, contentDescription = "图片")
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

/**
 * The in-page confirm for leaving with a draft: a filled block in the
 * toolbar's place. 舍弃 leads, at the opposite end from where 发布 sits in
 * the toolbar; the trailing slot, under a finger that was on 发布, is the
 * harmless 继续编辑.
 */
@Composable
private fun DiscardBar(onKeep: () -> Unit, onDiscard: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(YoinContainerShapes.Panel)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = onDiscard,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) { Text("舍弃") }
        Text(
            "草稿未发送",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        Button(onClick = onKeep, shapes = ButtonDefaults.shapes()) { Text("继续编辑") }
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
            .padding(StripPadding),
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
            // A 48dp target around the small chip.
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = null,
                        indication = ripple(bounded = false, radius = 18.dp, color = Color.White),
                    ) {
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
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(ImageScrim.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(YoinSymbols.Close, contentDescription = "移除", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
