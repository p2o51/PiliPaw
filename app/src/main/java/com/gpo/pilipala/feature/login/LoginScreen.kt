package com.gpo.pilipala.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.feature.settings.SettingsBackButton
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.LocalSnackbarHost
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Outlives the login page (it pops itself on success). */
private val DetachedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

@Composable
fun LoginScreen() {
    val vm = viewModel { LoginViewModel() }
    val nav = LocalNavigator.current
    val snackbar = LocalSnackbarHost.current
    val haptics = LocalHapticFeedback.current
    var web by rememberSaveable { mutableStateOf(false) }
    val done: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        // This page leaves composition right after, so the snackbar must not ride its scope.
        DetachedScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("登录成功")
        }
        nav.pop()
    }
    LaunchedEffect(vm.state) {
        if (vm.state == QrState.Success) done()
    }
    BackHandler(enabled = web) { web = false }
    AnimatedContent(
        targetState = web,
        transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard) },
        label = "loginMode",
    ) { isWeb ->
        if (isWeb) {
            WebLoginPane(onBack = { web = false }, onLoggedIn = done)
        } else {
            QrLoginPane(vm = vm, onWebLogin = { web = true })
        }
    }
}

@Composable
private fun QrLoginPane(vm: LoginViewModel, onWebLogin: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { SettingsBackButton() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { inner ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            val h = maxHeight
            val w = maxWidth
            val expanded = widthClass() == WidthClass.Expanded && h >= 420.dp
            if (expanded) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QrBlock(vm, side = minOf(h - 48.dp, 400.dp))
                    Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        LoginHeadline(TextAlign.Start)
                        StatusRow(vm, Alignment.Start)
                        LoginActions(vm, onWebLogin)
                    }
                }
            } else {
                val side = minOf(w - 48.dp, 340.dp)
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    LoginHeadline(TextAlign.Center)
                    QrBlock(vm, side = side)
                    StatusRow(vm, Alignment.CenterHorizontally)
                    Spacer(Modifier.height(4.dp))
                    LoginActions(vm, onWebLogin)
                }
            }
        }
    }
}

@Composable
private fun LoginHeadline(align: TextAlign) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "扫码登录",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "哔哩哔哩客户端",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The code on a light plate inside a primaryContainer frame (continuous corners).
 * The plate stays light in both themes so scanners always read dark-on-light.
 */
@Composable
private fun QrBlock(vm: LoginViewModel, side: Dp) {
    val dark = LocalPiliDark.current
    val scheme = MaterialTheme.colorScheme
    val plate = if (dark) scheme.inverseSurface else scheme.surfaceContainerLowest
    val modules = if (dark) scheme.inverseOnSurface else scheme.onSurface
    val state = vm.state
    val overlay by animateFloatAsState(
        targetValue = if (state == QrState.Scanned || state == QrState.Expired || state == QrState.Success) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "qrOverlay",
    )
    Surface(
        modifier = Modifier.size(side),
        shape = ContinuousRoundedCornerShape(40.dp),
        color = scheme.primaryContainer,
    ) {
        Box(
            Modifier
                .padding(20.dp)
                .clip(ContinuousRoundedCornerShape(24.dp))
                .background(plate)
                .clickable(enabled = state == QrState.Expired || state is QrState.Error) { vm.refresh() },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = when (state) {
                    QrState.Loading -> 0
                    is QrState.Error -> 1
                    else -> 2
                },
                transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard) },
                label = "qrBody",
            ) { kind ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (kind) {
                        0 -> LoadingIndicator(color = modules)
                        1 -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(YoinSymbols.CloudOffline, null, tint = modules, modifier = Modifier.size(40.dp))
                            Text(
                                (state as? QrState.Error)?.message.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = modules,
                                textAlign = TextAlign.Center,
                            )
                        }
                        else -> vm.qr?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = "登录二维码",
                                colorFilter = ColorFilter.tint(modules),
                                filterQuality = FilterQuality.None,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                                    .graphicsLayer { alpha = 1f - overlay * 0.88f },
                            )
                        }
                    }
                }
            }
            // Scanned / expired: the code fades back and a glyph on a cookie takes its place.
            if (overlay > 0f) {
                val glyph = when (state) {
                    QrState.Expired -> YoinSymbols.Refresh
                    else -> YoinSymbols.Check
                }
                Box(
                    Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            alpha = overlay
                            scaleX = 0.7f + 0.3f * overlay
                            scaleY = 0.7f + 0.3f * overlay
                            rotationZ = (1f - overlay) * -30f
                        }
                        .background(scheme.primary, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(glyph, null, tint = scheme.onPrimary, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusRow(vm: LoginViewModel, align: Alignment.Horizontal) {
    val state = vm.state
    val label = when (state) {
        QrState.Loading -> "加载中"
        QrState.Waiting -> "待扫码"
        QrState.Scanned -> "已扫码"
        QrState.Expired -> "二维码已失效"
        QrState.Success -> "登录成功"
        is QrState.Error -> "加载失败"
    }
    val tone = when (state) {
        QrState.Scanned, QrState.Success -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        QrState.Expired, is QrState.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = CircleShape, color = tone.first, contentColor = tone.second) {
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    (YoinMotion.fadeIn(YoinMotionRole.Standard) + YoinMotion.slideInVertically(YoinMotionRole.Standard) { it / 2 }) togetherWith
                        YoinMotion.fadeOut(YoinMotionRole.Standard)
                },
                label = "qrStatus",
            ) { text ->
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(PiliSymbols.Qr, null, modifier = Modifier.size(18.dp))
                    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val counting = state == QrState.Waiting || state == QrState.Scanned
        val countdownAlpha by animateFloatAsState(if (counting) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "countdown")
        Text(
            "%d:%02d".format(vm.secondsLeft / 60, vm.secondsLeft % 60),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { alpha = countdownAlpha },
        )
    }
}

@Composable
private fun LoginActions(vm: LoginViewModel, onWebLogin: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = onWebLogin,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(PiliSymbols.Link, null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("网页登录")
        }
        FilledTonalIconButton(
            onClick = { vm.refresh() },
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(YoinSymbols.Refresh, contentDescription = "刷新二维码")
        }
    }
}
