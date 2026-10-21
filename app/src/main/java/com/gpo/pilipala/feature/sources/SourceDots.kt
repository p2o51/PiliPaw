package com.gpo.pilipala.feature.sources

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.source.Sources
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.yoin.symbols.YoinSymbols

/** Each source's own colour: the dot when there is no avatar, and the ring around a ticked one. */
private fun VideoSource.tint(): Color = when (this) {
    VideoSource.Bilibili -> Color(0xFFFB7299)
    VideoSource.YouTube -> Color(0xFFFF0033)
}

/** What the panel shows for a source's account: its avatar and name, or signed out. */
private data class SourceAccount(val avatar: String?, val name: String?)

@Composable
private fun accountOf(source: VideoSource): SourceAccount = when (source) {
    VideoSource.Bilibili -> {
        val user by Account.user.collectAsState()
        SourceAccount(user?.face?.ifEmpty { null }, user?.name?.ifEmpty { null })
    }
    VideoSource.YouTube -> {
        val profile by Accounts.youtube.collectAsState()
        val p = profile as? Profile.SignedIn
        SourceAccount(p?.avatar?.ifEmpty { null }, p?.name?.ifEmpty { "已登录" })
    }
}

/**
 * The circle at Home's top left: one dot per ticked source, clustered. One source is just its
 * avatar; two sit side by side, overlapping. Tapping opens [SourcesSheet]; the ticks it leaves
 * take effect when it closes, and Home follows.
 *
 * [current]: the source whose part of the app is on screen; its row gets [onAccount]
 * (sign in, or the account's own page).
 */
@Composable
fun SourceDots(
    current: VideoSource,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    single: (@Composable (onClick: () -> Unit) -> Unit)? = null,
) {
    val enabled by Sources.enabled.collectAsState()
    var open by remember { mutableStateOf(false) }
    // Where the panel grows from (and shrinks back to): the dots' centre on screen.
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    val track = Modifier.onGloballyPositioned { c ->
        origin = c.positionOnScreen() + Offset(c.size.width / 2f, c.size.height / 2f)
    }
    // Only this part's own source ticked: the part keeps its own avatar button (PiliPaw's lands
    // the launch animation in it), which opens the same panel.
    if (single != null && enabled == setOf(current)) Box(track) { single { open = true } } else IconButton(
        onClick = { open = true },
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.then(track).size(size + 8.dp),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            val both = enabled.size > 1
            // Two dots: each shrinks to 70% and slides toward its corner, so the pair reads as one circle.
            val dot by animateDpAsState(if (both) size * 0.7f else size, YoinMotion.defaultSpatialSpec(), label = "dotSize")
            val shift by animateDpAsState(if (both) size * 0.15f else 0.dp, YoinMotion.defaultSpatialSpec(), label = "dotShift")
            VideoSource.entries.forEach { source ->
                val on = source in enabled
                val alpha by animateFloatAsState(if (on) 1f else 0f, YoinMotion.defaultEffectsSpec(), label = "dotAlpha")
                if (alpha > 0f) {
                    val sign = if (source == VideoSource.Bilibili) -1 else 1
                    SourceDot(
                        source,
                        dot,
                        Modifier
                            .offset(x = shift * sign, y = shift * sign)
                            .graphicsLayer { this.alpha = alpha },
                        ringed = both,
                    )
                }
            }
        }
    }
    if (open) {
        SourcesPanel(
            origin = origin,
            current = current,
            onAccount = {
                open = false
                onAccount()
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun SourceDot(source: VideoSource, size: Dp, modifier: Modifier = Modifier, ringed: Boolean = false) {
    val account = accountOf(source)
    val ring = if (ringed) Modifier.border(2.dp, MaterialTheme.colorScheme.surface, CircleShape) else Modifier
    if (account.avatar != null) {
        NetImage(
            account.avatar,
            modifier.size(size).clip(CircleShape).then(ring),
            contentDescription = source.label,
            shape = CircleShape,
            thumbWidth = size * 2,
        )
    } else {
        Box(
            modifier.size(size).clip(CircleShape).background(source.tint()).then(ring),
            contentAlignment = Alignment.Center,
        ) {
            Icon(YoinSymbols.Person, contentDescription = source.label, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/**
 * The tick page, a card in the middle of the screen that grows out of the dots and shrinks back
 * into them. The round tick switches a source on or off; with two ticked, tapping a row picks the
 * one on screen (until Home mixes them). Changes are kept here and handed to [Sources] once the
 * card is gone, so Home refreshes once, on the way back. The last ticked source stays on.
 */
@Composable
private fun SourcesPanel(origin: Offset, current: VideoSource, onAccount: () -> Unit, onDismiss: () -> Unit) {
    val savedTicks by Sources.enabled.collectAsState()
    val savedFront by Sources.front.collectAsState()
    var ticks by remember { mutableStateOf(savedTicks) }
    var front by remember { mutableStateOf(savedFront) }
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val fastSpatial = YoinMotion.fastSpatialSpec<Float>()
    LaunchedEffect(Unit) { progress.animateTo(1f, spatial) }
    var leaving by remember { mutableStateOf(false) }
    fun leave(then: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch {
            progress.animateTo(0f, fastSpatial)
            Sources.apply(ticks, front)
            then()
        }
    }
    Dialog(
        onDismissRequest = { leave(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Our own scrim, drawn with the card's progress; the window's dim would only fade.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        var rootOnScreen by remember { mutableStateOf(Offset.Zero) }
        var cardCenter by remember { mutableStateOf(Offset.Unspecified) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { rootOnScreen = it.positionOnScreen() }
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.32f * progress.value.coerceIn(0f, 1f))) }
                .pointerInput(Unit) { detectTapGestures { leave(onDismiss) } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned { c ->
                        cardCenter = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f)
                    }
                    .graphicsLayer {
                        val p = progress.value
                        val from = if (origin.isSpecified && cardCenter.isSpecified) origin - rootOnScreen - cardCenter else Offset.Zero
                        translationX = from.x * (1f - p)
                        translationY = from.y * (1f - p)
                        val scale = 0.12f + 0.88f * p
                        scaleX = scale
                        scaleY = scale
                        alpha = (p * 2.5f).coerceIn(0f, 1f)
                        shape = RoundedCornerShape(32.dp)
                        clip = true
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    // Taps on the card stay on the card.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
                    Text("看哪些内容", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "勾选后回到首页就会刷新",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VideoSource.entries.forEach { source ->
                    val on = source in ticks
                    SourceRow(
                        source = source,
                        ticked = on,
                        showing = on && ticks.size > 1 && front == source,
                        onTick = {
                            when {
                                on && ticks.size == 1 -> toast("至少留一个")
                                on -> {
                                    ticks = ticks - source
                                    if (front == source) front = ticks.first()
                                }
                                // Ticked on: Home gains it (the mixed 关注); the part on screen stays.
                                else -> ticks = ticks + source
                            }
                        },
                        onRow = {
                            if (!on) ticks = ticks + source
                            front = source
                        },
                        onAccount = if (source == current) ({ leave(onAccount) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: VideoSource,
    ticked: Boolean,
    showing: Boolean,
    onTick: () -> Unit,
    onRow: () -> Unit,
    onAccount: (() -> Unit)?,
) {
    val account = accountOf(source)
    val bg by animateColorAsState(
        if (showing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        YoinMotion.defaultEffectsSpec(),
        label = "rowBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .clickable(onClick = onRow)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SourceDot(source, 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                account.name ?: "未登录",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (showing) "${source.label} · 正在看" else source.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onAccount != null) {
            TextButton(onClick = onAccount) { Text(if (account.name == null) "登录" else "账号") }
        }
        TickMark(ticked, source.tint(), onTick)
    }
}

/**
 * The round tick: an outlined ring off; on, it fills with the source's colour and the check pops
 * in with a little overshoot.
 */
@Composable
internal fun TickMark(ticked: Boolean, tint: Color, onClick: () -> Unit) {
    val fill by animateFloatAsState(
        if (ticked) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "tick",
    )
    val ring = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .drawBehind {
                    val r = size.minDimension / 2f
                    // The ring fades out as the fill grows from the centre.
                    drawCircle(ring.copy(alpha = (1f - fill).coerceIn(0f, 1f)), r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                    drawCircle(tint, r * fill.coerceIn(0f, 1.15f))
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                YoinSymbols.Check,
                contentDescription = if (ticked) "已勾选" else "未勾选",
                tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        val k = fill.coerceAtLeast(0f)
                        scaleX = k
                        scaleY = k
                        alpha = k.coerceIn(0f, 1f)
                    },
            )
        }
    }
}
