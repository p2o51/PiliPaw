package com.gpo.pilipala.feature.live

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.ExpressivePageBackground
import com.gpo.pilipala.ui.components.PiliTopBar
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

@Composable
fun LiveRoomScreen(route: Route.LiveRoom) {
    val context = LocalContext.current
    val vm = viewModel { LiveRoomViewModel(route.roomId, context.applicationContext) }
    val cover = route.cover.ifEmpty { vm.info?.cover.orEmpty() }
    CoverTheme(cover) {
        LiveRoomContent(route, vm)
    }
}

@Composable
private fun LiveRoomContent(route: Route.LiveRoom, vm: LiveRoomViewModel) {
    val nav = LocalNavigator.current
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val wc = widthClass()
    val activity = LocalActivity.current
    val pip = rememberIsInPip()
    val enterPip: (() -> Unit)? = activity?.takeIf {
        it.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }?.let { act ->
        {
            runCatching {
                act.enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(if (vm.isPortraitStream) android.util.Rational(9, 16) else android.util.Rational(16, 9))
                        .build(),
                )
            }
        }
    }

    FullscreenEffect(fullscreen, portrait = vm.isPortraitStream)
    DarkStatusBarEffect(enabled = !fullscreen && wc != WidthClass.Expanded)
    BackHandler(enabled = fullscreen) { fullscreen = false }
    // App to background: background play keeps the audio (the PiP window closing does not).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (pip || activity?.isInPictureInPictureMode == true) vm.pauseForBackground() else vm.onAppStopped()
    }
    // Covered by another page (the entry leaves composition) → pause; back on top → resume.
    DisposableEffect(Unit) {
        vm.resumeFromBackground()
        onDispose { vm.pauseForBackground() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.resumeFromBackground() }

    ExpressivePageBackground(
        Modifier.fillMaxSize(),
        belowPlayer = wc != WidthClass.Expanded,
        animated = !pip && !fullscreen,
    ) {
        if (vm.info == null && vm.infoError != null) {
            Column(Modifier.fillMaxSize()) {
                PiliTopBar(route.title.ifEmpty { "直播间" })
                ErrorState(vm.infoError!!, onRetry = vm::refresh)
            }
            return@ExpressivePageBackground
        }
        val onBack: () -> Unit = { if (fullscreen) fullscreen = false else nav.pop() }
        val toggleFs: () -> Unit = { fullscreen = !fullscreen }
        when {
            pip -> LivePlayerView(vm, true, toggleFs, onBack, Modifier.fillMaxSize(), pip = true)
            fullscreen -> LivePlayerView(vm, true, toggleFs, onBack, Modifier.fillMaxSize(), onPip = enterPip)
            wc == WidthClass.Expanded -> Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    LivePlayerView(
                        vm, false, toggleFs, onBack,
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(YoinContainerShapes.Card),
                        onPip = enterPip,
                    )
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        AnchorBlock(vm, route, Modifier.padding(top = 8.dp))
                        RoomInfoPanel(vm.info)
                    }
                }
                Column(
                    Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                        .clip(YoinContainerShapes.Panel)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    ChatList(vm, Modifier.weight(1f))
                    InputBar(vm, Modifier.windowInsetsPadding(WindowInsets.ime))
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.background(Color.Black).statusBarsPadding()) {
                    LivePlayerView(vm, false, toggleFs, onBack, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onPip = enterPip)
                }
                AnchorBlock(vm, route)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(YoinContainerShapes.Panel)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    ChatList(vm, Modifier.fillMaxSize())
                }
                InputBar(vm, Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)))
            }
        }
    }
}

// ── Anchor ─────────────────────────────────────────────────────────────────

@Composable
private fun AnchorBlock(vm: LiveRoomViewModel, route: Route.LiveRoom, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val info = vm.info
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val haptic = LocalHapticFeedback.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            info?.title?.ifEmpty { null } ?: route.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(info?.anchorFace, 44.dp) {
                info?.let { nav.push(Route.Member(it.uid, it.anchorFace, it.anchorName)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    info?.anchorName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val watched = info?.watchedText.orEmpty()
                    if (watched.isNotEmpty()) Stat(PiliSymbols.Views, watched)
                    if ((info?.attention ?: 0) > 0) Stat(PiliSymbols.PersonFilled, Fmt.count(info!!.attention))
                    val area = info?.areaName.orEmpty()
                    if (area.isNotEmpty()) Tag(area)
                }
            }
            if (info != null && info.uid > 0 && info.uid != BiliClient.mid) {
                Spacer(Modifier.width(8.dp))
                ToggleButton(
                    checked = vm.following,
                    enabled = !vm.followBusy,
                    onCheckedChange = {
                        if (!BiliClient.isLoggedIn) {
                            nav.push(Route.Login)
                            return@ToggleButton
                        }
                        haptic.performHapticFeedback(if (vm.following) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        scope.launch { vm.toggleFollow()?.let(toast) }
                    },
                ) {
                    Icon(
                        if (vm.following) PiliSymbols.PersonCheck else PiliSymbols.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (vm.following) "已关注" else "关注")
                }
            }
        }
    }
}

/** 简介, 分区, 标签 and 公告 — fills the Expanded left column under the anchor. */
@Composable
private fun RoomInfoPanel(info: LiveRoomInfo?) {
    if (info == null) return
    val areas = listOf(info.parentAreaName, info.areaName).filter { it.isNotEmpty() }.distinct()
    val hasAny = info.description.isNotEmpty() || info.news.isNotEmpty() || areas.isNotEmpty() || info.tags.isNotEmpty()
    AnimatedVisibility(
        visible = hasAny,
        enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
        exit = fadeOut(YoinMotion.defaultEffectsSpec()) + shrinkVertically(YoinMotion.defaultSpatialSpec()),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .clip(YoinContainerShapes.Card)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (info.description.isNotEmpty()) {
                InfoSection("简介") {
                    Text(info.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (info.news.isNotEmpty()) {
                InfoSection("公告") {
                    Text(info.news, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (areas.isNotEmpty()) {
                InfoSection("分区") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        areas.forEach { InfoChip(it, primary = true) }
                    }
                }
            }
            if (info.tags.isNotEmpty()) {
                InfoSection("标签") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        info.tags.forEach { InfoChip(it, primary = false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun InfoChip(text: String, primary: Boolean) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        modifier = Modifier
            .clip(ContinuousRoundedCornerShape(10.dp))
            .background(if (primary) cs.secondaryContainer else cs.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (primary) cs.onSecondaryContainer else cs.onSurfaceVariant,
    )
}

// ── Chat ───────────────────────────────────────────────────────────────────

@Composable
private fun ChatList(vm: LiveRoomViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    var stick by remember { mutableStateOf(true) }
    var seenCount by remember { mutableIntStateOf(0) }
    val count = vm.messages.size

    // Only the user's own drag (and its fling) decides whether we follow new messages;
    // our own auto-scroll ending short of the bottom must not unstick the list.
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) { if (dragged) userScrolled = true }
    LaunchedEffect(listState.isScrollInProgress, dragged) {
        if (!listState.isScrollInProgress && !dragged && userScrolled) {
            userScrolled = false
            stick = !listState.canScrollForward
            if (stick) seenCount = vm.messages.size
        }
    }
    LaunchedEffect(count, stick) {
        if (stick && count > 0) {
            listState.animateScrollToItem(count - 1)
            seenCount = count
        }
    }

    Box(modifier) {
        if (count == 0) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(PiliSymbols.Danmaku, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
                Text("暂无弹幕", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(vm.messages, key = { it.key }) { chat ->
                LiveChatItem(chat, vm.roomEmotes, Modifier.animateItem())
            }
        }
        // Join / follow tip: over the oldest rows, never over the newest.
        AnimatedVisibility(
            visible = vm.tipVisible,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + slideInVertically(YoinMotion.defaultSpatialSpec()) { -it },
            exit = fadeOut(YoinMotion.defaultEffectsSpec()) + slideOutVertically(YoinMotion.defaultSpatialSpec()) { -it },
            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
        ) {
            Text(
                vm.tip,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val unseen = count - seenCount
        AnimatedVisibility(
            visible = !stick && unseen > 0,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + scaleIn(YoinMotion.defaultSpatialSpec()),
            exit = fadeOut(YoinMotion.defaultEffectsSpec()) + scaleOut(YoinMotion.defaultSpatialSpec()),
            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
        ) {
            val scope = rememberCoroutineScope()
            FilledTonalButton(
                onClick = {
                    stick = true
                    scope.launch { listState.animateScrollToItem((vm.messages.size - 1).coerceAtLeast(0)) }
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(YoinSymbols.ChevronDown, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新消息 ${unseen.coerceAtMost(99)}")
            }
        }
    }
}

// ── Input ──────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(vm: LiveRoomViewModel, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    var text by rememberSaveable { mutableStateOf("") }

    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (!BiliClient.isLoggedIn) {
            FilledTonalButton(onClick = { nav.push(Route.Login) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("登录")
            }
            return@Box
        }
        val send: () -> Unit = {
            val msg = text.trim()
            if (msg.isNotEmpty() && !vm.sending) {
                scope.launch {
                    val err = vm.send(msg)
                    if (err == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        text = ""
                        keyboard?.hide()
                    } else {
                        toast(err)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = text,
                onValueChange = { text = it.take(40) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("发个弹幕") },
                singleLine = true,
                shape = CircleShape,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            FilledIconButton(
                onClick = send,
                enabled = text.isNotBlank() && !vm.sending,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(YoinSymbols.Send, contentDescription = "发送")
            }
        }
    }
}

// ── Window effects ─────────────────────────────────────────────────────────

@Composable
private fun rememberIsInPip(): Boolean {
    val activity = LocalActivity.current as? androidx.activity.ComponentActivity ?: return false
    var pip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> {
            pip = it.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return pip
}

@Composable
private fun FullscreenEffect(fullscreen: Boolean, portrait: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(fullscreen, portrait) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = if (portrait) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        onDispose {
            if (fullscreen) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

/** Light status-bar icons while the black player sits under the status bar. */
@Composable
private fun DarkStatusBarEffect(enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(enabled) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val before = controller.isAppearanceLightStatusBars
        if (enabled) controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}
