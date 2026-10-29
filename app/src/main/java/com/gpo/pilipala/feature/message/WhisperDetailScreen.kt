package com.gpo.pilipala.feature.message

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LoadingIndicator
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.gpo.pilipala.feature.reply.EmotePanel
import com.gpo.pilipala.feature.reply.EmotePeekOverlay
import com.gpo.pilipala.feature.reply.EmotePeekState
import com.gpo.pilipala.feature.reply.PictureRow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.gpo.pilipala.feature.reply.crossfade
import com.gpo.pilipala.feature.reply.replyMotion
import com.gpo.pilipala.ui.theme.YoinMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.double
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.feature.media.LocalImageViewer
import com.gpo.pilipala.feature.media.ViewerImage
import com.gpo.pilipala.feature.media.imageViewerOrigin
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.BackButton
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.seam.seamRemainingPx
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.feature.sources.miniPlayerFloatOnly
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

@Composable
fun WhisperDetailScreen(route: Route.WhisperDetail) {
    val nav = LocalNavigator.current
    WhisperConversation(route, showBack = true, onRemoved = { nav.pop() })
}

/**
 * The chat itself: a pushed page on Compact/Medium, the detail pane of [MessagesScreen]
 * on Expanded. Polling only runs while it is on screen.
 */
@Composable
internal fun WhisperConversation(
    route: Route.WhisperDetail,
    showBack: Boolean,
    onRemoved: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    val nav = LocalNavigator.current
    val user by Account.user.collectAsState()
    val loggedIn = user != null || BiliClient.isLoggedIn
    val vm = viewModel(key = "whisper-${route.talkerId}") { WhisperDetailViewModel(route.talkerId) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(loggedIn) { if (loggedIn) vm.ensureLoaded(needPeer = route.name.isBlank() || route.face.isBlank()) }
    DisposableEffect(vm) {
        vm.setActive(true)
        onDispose { vm.setActive(false) }
    }
    val peek = remember { EmotePeekState() }
    val copy: (String) -> Unit = remember(clipboard, haptics, toast, scope) {
        { text ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text))) }
            toast("已复制")
        }
    }
    val peerName = route.name.ifBlank { vm.peer?.name.orEmpty() }
    val peerFace = route.face.ifBlank { vm.peer?.face.orEmpty() }
    @Suppress("NAME_SHADOWING")
    val route = route.copy(name = peerName, face = peerFace)

    Scaffold(
        containerColor = containerColor,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = { if (showBack) BackButton() },
                title = {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .clickable { nav.push(Route.Member(route.talkerId, route.face, route.name)) }
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(4.dp))
                        Avatar(route.face, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }, shapes = IconButtonDefaults.shapes()) {
                            Icon(YoinSymbols.MoreVertical, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            shape = ContinuousRoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            DropdownMenuItem(
                                text = { Text("个人空间") },
                                leadingIcon = { Icon(YoinSymbols.Person, null) },
                                onClick = {
                                    menu = false
                                    nav.push(Route.Member(route.talkerId, route.face, route.name))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除会话") },
                                leadingIcon = { Icon(YoinSymbols.Delete, null) },
                                onClick = {
                                    menu = false
                                    confirmRemove = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor),
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
    ) { padding ->
        // The emote hold bubble floats over the whole conversation, so it can rise above the panel.
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .readingWidth(760.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !loggedIn -> LoginPrompt()
                    vm.loading && vm.messages.isEmpty() -> LoadingState()
                    vm.error != null && vm.messages.isEmpty() -> ErrorState(vm.error!!, onRetry = vm::load)
                    else -> MessageList(vm, route, onCopy = copy)
                }
            }
            if (loggedIn) {
                InputBar(vm, peek) { err -> toast(err) }
            }
        }
        EmotePeekOverlay(peek, Modifier.matchParentSize())
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("删除会话") },
            text = { Text("清空会话内容并移除会话") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        val err = vm.removeSession()
                        if (err == null) {
                            toast("已删除")
                            onRemoved()
                        } else {
                            toast(err)
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun MessageList(
    vm: WhisperDetailViewModel,
    route: Route.WhisperDetail,
    onCopy: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, threshold = 4) { vm.loadOlder() }
    val newest = vm.messages.firstOrNull()?.key
    LaunchedEffect(newest) {
        if (newest != null && listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }
    // Recomputed only when the message list changes, not on every recomposition.
    val visible by remember(vm) { derivedStateOf { vm.messages.filter { it.type != 5 } } }
    if (visible.isEmpty() && !vm.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无消息", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        // Older messages scroll up under the top bar and sink under the tide line there.
        // Reversed, the distance to the top is how far the list can still go back.
        modifier = Modifier.fillMaxSize().seamViewport(scrolledPx = { listState.seamRemainingPx() }, remainingPx = null),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visible.forEachIndexed { i, m ->
            item(key = m.key) {
                ChatItem(m, vm.emotes, route, onCopy, Modifier.animateItem())
            }
            val older = visible.getOrNull(i + 1)
            if (older == null || m.timestamp - older.timestamp > 300) {
                item(key = "t-${m.key}") {
                    Text(
                        chatTime(m.timestamp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (vm.loadingMore) item(key = "more") { LoadingFooter() }
    }
}

// ── Chat items ──────────────────────────────────────────────────────────────

/** Today → HH:mm, yesterday → 昨天 HH:mm, this year → MM-dd HH:mm, else full date. */
private fun chatTime(ts: Long): String {
    if (ts <= 0) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts * 1000 }
    val now = java.util.Calendar.getInstance()
    val sameYear = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
    val dayDiff = now.get(java.util.Calendar.DAY_OF_YEAR) - cal.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> Fmt.date(ts, "HH:mm")
        sameYear && dayDiff == 1 -> "昨天 " + Fmt.date(ts, "HH:mm")
        sameYear -> Fmt.date(ts, "MM-dd HH:mm")
        else -> Fmt.date(ts, "yyyy-MM-dd HH:mm")
    }
}

private val SystemTypes = setOf(10, 13, 16, 18)

private val PictureShape = ContinuousRoundedCornerShape(16.dp)

@Composable
private fun ChatItem(
    m: WhisperMsg,
    emotes: Map<String, String>,
    route: Route.WhisperDetail,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (m.type in SystemTypes) {
        SystemItem(m, onCopy, modifier)
        return
    }
    val nav = LocalNavigator.current
    val mine = m.senderUid == BiliClient.mid
    val container = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val shape = if (mine) {
        ContinuousRoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    } else {
        ContinuousRoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }
    val bare = m.type == 2 || m.type == 6 // images sit without a bubble
    // Text bubbles copy on long press (one gesture detector instead of a SelectionContainer per bubble).
    val copyText = if (m.type == 1) m.content.str("content") else ""
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine) {
            Avatar(route.face, 32.dp) { nav.push(Route.Member(route.talkerId, route.face, route.name)) }
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .then(if (bare) Modifier else Modifier.clip(shape).background(container))
                    .then(
                        if (copyText.isEmpty()) Modifier
                        else Modifier.pointerInput(copyText) { detectTapGestures(onLongPress = { onCopy(copyText) }) },
                    )
                    .then(if (bare) Modifier else Modifier.padding(horizontal = 14.dp, vertical = 10.dp)),
            ) {
                MessageBody(m, emotes, onContainer)
            }
            if (m.status == 1) {
                Text(
                    "已撤回",
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageBody(m: WhisperMsg, emotes: Map<String, String>, color: Color) {
    val nav = LocalNavigator.current
    val viewer = LocalImageViewer.current
    val c = m.content
    when (m.type) {
        1 -> EmojiText(c.str("content"), emotes, color)
        2 -> {
            val url = c.str("url")
            val w = c.double("width").takeIf { it > 0 } ?: 1.0
            val h = c.double("height").takeIf { it > 0 } ?: 1.0
            val key = "whisper:${m.key}"
            NetImage(
                url,
                Modifier
                    .width(200.dp)
                    .aspectRatio((w / h).toFloat().coerceIn(0.4f, 2.5f))
                    .imageViewerOrigin(key, PictureShape)
                    .clickable { viewer.open(listOf(ViewerImage(url, c.double("width").toInt(), c.double("height").toInt())), 0, listOf(key)) },
                shape = PictureShape,
                thumbWidth = 400.dp,
            )
        }
        6 -> NetImage(c.str("url"), Modifier.size(96.dp), shape = ContinuousRoundedCornerShape(12.dp), thumbWidth = 192.dp, contentScale = ContentScale.Fit)
        7 -> ShareCard(
            cover = c.str("thumb"),
            title = c.str("title"),
            sub = c.str("author"),
            color = color,
        ) {
            val bvid = c.str("bvid")
            val url = c.str("url")
            when {
                c.int("source") == 5 && bvid.isNotEmpty() -> nav.push(Route.Video(bvid, cover = c.str("thumb"), title = c.str("title")))
                url.isNotEmpty() -> nav.openUrl(url)
                bvid.isNotEmpty() -> nav.push(Route.Video(bvid))
            }
        }
        11 -> ShareCard(
            cover = c.str("cover"),
            title = c.str("title"),
            sub = c.long("times").takeIf { it > 0 }?.let { Fmt.duration(it) }.orEmpty(),
            color = color,
        ) {
            val bvid = c.str("bvid").ifEmpty { c.long("rid").takeIf { it > 0 }?.let { IdUtils.av2bv(it) }.orEmpty() }
            if (bvid.isNotEmpty()) nav.push(Route.Video(bvid, cover = c.str("cover"), title = c.str("title")))
        }
        12 -> ShareCard(
            cover = c?.get("image_urls")?.asArr?.firstOrNull().asString(),
            title = c.str("title"),
            sub = c.str("summary"),
            color = color,
        ) {
            val cv = c.long("rid")
            if (cv > 0) nav.push(Route.Read(cv, c.str("title")))
        }
        14 -> ShareCard(
            cover = c.str("cover").ifEmpty { c.str("thumb") },
            title = c.str("title"),
            sub = c.str("sub_title").ifEmpty { c.str("desc") },
            color = color,
        ) {
            val url = c.str("url").ifEmpty { c.str("jump_url") }
            if (url.isNotEmpty()) nav.openUrl(url)
        }
        else -> Text(
            c.str("content").ifEmpty { c.str("title") }.ifEmpty { "[不支持的消息类型]" },
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun EmojiText(text: String, emotes: Map<String, String>, color: Color) {
    val re = remember { Regex("""\[[^\[\]]+]""") }
    val used = remember(text, emotes) { re.findAll(text).map { it.value }.filter { it in emotes }.toSet() }
    val annotated = remember(text, used) {
        buildAnnotatedString {
            if (used.isEmpty()) {
                append(text)
            } else {
                var last = 0
                re.findAll(text).forEach { m ->
                    append(text.substring(last, m.range.first))
                    if (m.value in used) appendInlineContent(m.value, m.value) else append(m.value)
                    last = m.range.last + 1
                }
                append(text.substring(last))
            }
        }
    }
    val inline = remember(used, emotes) {
        used.associateWith { key ->
            InlineTextContent(Placeholder(22.sp, 22.sp, PlaceholderVerticalAlign.TextCenter)) {
                NetImage(emotes[key], Modifier.fillMaxSize(), shape = RectangleShape, thumbWidth = 48.dp, contentScale = ContentScale.Fit)
            }
        }
    }
    Text(annotated, inlineContent = inline, style = MaterialTheme.typography.bodyLarge, color = color)
}

@Composable
private fun ShareCard(cover: String, title: String, sub: String, color: Color, onClick: () -> Unit) {
    Column(Modifier.width(240.dp).clip(ContinuousRoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        if (cover.isNotEmpty()) {
            NetImage(cover, Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = ContinuousRoundedCornerShape(12.dp), thumbWidth = 480.dp)
            Spacer(Modifier.size(8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) {
            Text(
                sub,
                style = MaterialTheme.typography.labelMedium,
                color = color.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SystemItem(m: WhisperMsg, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val c = m.content
    when (m.type) {
        18 -> {
            // content.content is a JSON array of {text}; parsed once in the ViewModel.
            Text(
                m.systemText.ifEmpty { c.str("content") },
                modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        13 -> {
            val link = c.str("jump_url")
            NetImage(
                c.str("pic_url"),
                modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .aspectRatio(320f / 150f)
                    .then(if (link.isNotEmpty()) Modifier.clickable { nav.openUrl(link) } else Modifier),
                shape = ContinuousRoundedCornerShape(16.dp),
                thumbWidth = 640.dp,
            )
        }
        16 -> Column(
            modifier
                .widthIn(max = 340.dp)
                .clip(ContinuousRoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                c.str("main_title"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            c.arr("sub_cards").objects().forEach { card ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedCornerShape(12.dp))
                        .clickable { card.str("jump_url").takeIf { it.isNotEmpty() }?.let(nav::openUrl) },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NetImage(card.str("cover_url"), Modifier.width(112.dp).aspectRatio(16f / 9f), shape = ContinuousRoundedCornerShape(10.dp), thumbWidth = 224.dp)
                    Column(Modifier.weight(1f)) {
                        Text(card.str("field1"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(card.str("field2"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f), maxLines = 1)
                        Text(card.str("field3"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f), maxLines = 1)
                    }
                }
            }
        }
        else -> { // 10 notify_msg
            val (text, link) = stripLinkMarkup(c.str("text"))
            val jump = link ?: c.str("jump_uri").ifEmpty { c.str("jump_url") }
            Column(
                modifier
                    .widthIn(max = 340.dp)
                    .clip(ContinuousRoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .then(
                        if (jump.isNotEmpty()) {
                            Modifier.combinedClickable(onClick = { nav.openUrl(jump) }, onLongClick = { onCopy(text) })
                        } else {
                            Modifier.pointerInput(text) { detectTapGestures(onLongPress = { onCopy(text) }) }
                        },
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    c.str("title"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    Fmt.relativeTime(m.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

// ── Input ───────────────────────────────────────────────────────────────────

/** Pictures one message batch can carry; each goes out as its own message. */
private const val MaxPictures = 9

private val UriListSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/**
 * Text field with the emote toggle (leading) and the photo picker (trailing),
 * and the send button. The emote panel swaps with the keyboard, as in the
 * comment composer. Picked pictures wait above the field (removable) until
 * send; then they go out first, one message each, with the send button
 * showing their progress, and the text after them.
 */
@Composable
private fun InputBar(vm: WhisperDetailViewModel, peek: EmotePeekState, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val motion = replyMotion()
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var panel by rememberSaveable { mutableStateOf(false) }
    val pictures = rememberSaveable(saver = UriListSaver) { mutableStateListOf<String>() }
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
    val send: () -> Unit = {
        val msg = value.text.trim()
        val picked = pictures.toList()
        if ((msg.isNotEmpty() || picked.isNotEmpty()) && !vm.sending) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch {
                if (picked.isNotEmpty()) {
                    val err = vm.sendPictures(context.contentResolver, context.cacheDir, picked.map(Uri::parse)) { sent ->
                        pictures.remove(sent.toString())
                    }
                    if (err != null) {
                        onError(err)
                        return@launch
                    }
                }
                if (msg.isNotEmpty()) {
                    val err = vm.send(msg)
                    if (err == null) value = TextFieldValue() else onError(err)
                }
            }
        }
    }
    fun insert(text: String) {
        val start = value.selection.min.coerceIn(0, value.text.length)
        val end = value.selection.max.coerceIn(0, value.text.length)
        value = TextFieldValue(value.text.substring(0, start) + text + value.text.substring(end), TextRange(start + text.length))
    }
    fun togglePanel() {
        if (panel) {
            panel = false
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            panel = true
        }
    }
    val progress = vm.picturesProgress
    // The mini player floats up top while this chat is on screen, clear of the composer.
    Column(Modifier.fillMaxWidth().miniPlayerFloatOnly()) {
        AnimatedVisibility(
            visible = pictures.isNotEmpty(),
            enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(YoinMotion.defaultEffectsSpec()),
        ) {
            PictureRow(
                pictures = pictures,
                removable = !vm.sending,
                onRemove = { key ->
                    if (!vm.sending) {
                        haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
                        pictures.remove(key)
                    }
                },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) panel = false },
                placeholder = { Text("发消息") },
                leadingIcon = {
                    IconButton(onClick = ::togglePanel, shapes = IconButtonDefaults.shapes()) {
                        AnimatedContent(
                            targetState = panel,
                            transitionSpec = {
                                scaleIn(motion.scale) + fadeIn(motion.fade) togetherWith scaleOut(motion.scale) + fadeOut(motion.fade)
                            },
                            label = "whisperEmoteToggle",
                        ) { showingPanel ->
                            Icon(
                                if (showingPanel) YoinSymbols.Keyboard else YoinSymbols.Emoji,
                                contentDescription = if (showingPanel) "键盘" else "表情",
                            )
                        }
                    }
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            keyboard?.hide()
                            panel = false
                            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            when {
                                remaining >= 2 -> pickMany.launch(request)
                                remaining == 1 -> pickOne.launch(request)
                            }
                        },
                        enabled = !vm.sending && remaining > 0,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(YoinSymbols.Image, contentDescription = "图片")
                    }
                },
                maxLines = 5,
                shape = ContinuousRoundedCornerShape(26.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            FilledIconButton(
                onClick = send,
                enabled = (value.text.isNotBlank() || pictures.isNotEmpty()) && !vm.sending,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(56.dp),
            ) {
                AnimatedContent(
                    targetState = progress != null,
                    transitionSpec = { motion.crossfade() },
                    label = "whisperSend",
                ) { uploading ->
                    if (uploading) {
                        val p = vm.picturesProgress
                        val shown by animateFloatAsState(
                            if (p == null || p.second == 0) 0f else p.first.toFloat() / p.second,
                            YoinMotion.defaultSpatialSpec(),
                            label = "whisperPictures",
                        )
                        LoadingIndicator(progress = { shown }, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(YoinSymbols.Send, contentDescription = "发送")
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = panel,
            enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(YoinMotion.defaultEffectsSpec()),
        ) {
            EmotePanel(
                peek = peek,
                modifier = Modifier.fillMaxWidth().height(300.dp),
                onPick = { e ->
                    vm.noteEmote(e.text, e.url)
                    insert(e.text)
                },
            )
        }
    }
}
