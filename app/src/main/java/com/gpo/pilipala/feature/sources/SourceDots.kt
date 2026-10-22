package com.gpo.pilipala.feature.sources

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.foundation.layout.width
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
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.yoin.symbols.YoinSymbols

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
 * [single]: with only [singleFor] ticked, that shell's own avatar button stands in for the dots
 * (PiliPaw's lands the launch animation in it) and opens the same panel.
 */
@Composable
fun SourceDots(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    singleFor: VideoSource? = null,
    single: (@Composable (onClick: () -> Unit) -> Unit)? = null,
) {
    val biliNav = com.gpo.pilipala.navigation.LocalNavigator.current
    val ytNav = com.gpo.pilipala.yt.navigation.LocalNavigator.current
    // Each account's own page: Bilibili's 我的 (or its sign-in), YouTube's account, subscriptions and Mix.
    val openHome: (VideoSource) -> Unit = { source ->
        when (source) {
            VideoSource.Bilibili -> biliNav.push(if (Account.user.value != null) Route.MyHome else Route.Login)
            VideoSource.YouTube -> ytNav.push(com.gpo.pilipala.yt.navigation.Route.Settings)
        }
    }
    val openSettings = { biliNav.push(Route.Settings) }
    val enabled by Sources.enabled.collectAsState()
    var open by remember { mutableStateOf(false) }
    // Where the panel grows from (and shrinks back to): the dots' centre on screen.
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    val hold = rememberHoldMenu(onHome = openHome, onSettings = openSettings)
    val track = Modifier
        .onGloballyPositioned { c ->
            origin = c.positionOnScreen() + Offset(c.size.width / 2f, c.size.height / 2f)
        }
        // The avatar sits in the left edge's back-gesture zone; a slide that starts on it is ours.
        .systemGestureExclusion()
        // Press and hold: the accounts fan out; slide to one and let go (HoldMenu.kt).
        .then(hold.modifier)
    Box(modifier.then(track)) {
    // Only this shell's own source ticked: the shell keeps its own avatar button (PiliPaw's lands
    // the launch animation in it), which opens the same panel.
    if (single != null && singleFor != null && enabled == setOf(singleFor)) single { open = true } else IconButton(
        onClick = { open = true },
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(size + 8.dp),
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
    HoldFan(hold)
    }
    if (open) {
        SourcesPanel(
            origin = origin,
            onAccount = { source ->
                open = false
                openHome(source)
            },
            onSettings = {
                open = false
                openSettings()
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
internal fun SourceDotForMenu(source: VideoSource, size: Dp) = SourceDot(source, size)

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
            modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).then(ring),
            contentAlignment = Alignment.Center,
        ) {
            Icon(YoinSymbols.Person, contentDescription = source.label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/**
 * The tick page, a card in the middle of the screen that grows out of the dots and shrinks back
 * into them. The sources are peers: a row (or its round tick) switches one on or off, and Home
 * shows what is ticked. Changes are handed to [Sources] once the card is gone, so Home refreshes
 * once, on the way back. The last ticked source stays on.
 */
@Composable
private fun SourcesPanel(origin: Offset, onAccount: (VideoSource) -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val savedTicks by Sources.enabled.collectAsState()
    var ticks by remember { mutableStateOf(savedTicks) }
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
            Sources.apply(ticks)
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
                    Text("账号", style = MaterialTheme.typography.headlineSmall)
                }
                VideoSource.entries.forEach { source ->
                    val on = source in ticks
                    val tick = {
                        when {
                            on && ticks.size == 1 -> toast("至少留一个")
                            on -> ticks = ticks - source
                            else -> ticks = ticks + source
                        }
                    }
                    // Peers: a row only ticks; each account is managed from its own button.
                    SourceRow(
                        source = source,
                        ticked = on,
                        showing = false,
                        onTick = tick,
                        onRow = tick,
                        onAccount = { leave { onAccount(source) } },
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { leave(onSettings) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Icon(YoinSymbols.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(YoinSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        // One hue with the card (the theme's own tones), no second colour family.
        if (ticked) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
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
        // The site's tag over the avatar, level with the name; a fixed width keeps both rows' names in line.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                Modifier.width(56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tag(
                    source.label,
                    modifier = Modifier.padding(top = 2.dp),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                SourceDot(source, 40.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    account.name ?: "未登录",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AccountStats(source)
            }
        }
        if (onAccount != null) {
            TextButton(onClick = onAccount) { Text(if (account.name == null) "登录" else "主页") }
        }
        TickMark(ticked, MaterialTheme.colorScheme.primary, onTick)
    }
}

/**
 * The account at a glance, under its name: Bilibili's 关注, 粉丝, 硬币 and level; YouTube's handle.
 * Signed out, nothing (the site's tag names the row). Numbers go through
 * [com.gpo.pilipala.ui.components.FameCount], so 隐藏数字 hides them here too.
 */
@Composable
private fun AccountStats(source: VideoSource) {
    when (source) {
        VideoSource.Bilibili -> {
            val user by Account.user.collectAsState()
            val u = user ?: return
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Stat(com.gpo.pilipala.core.util.Fmt.count(u.following.toLong()), "关注")
                Stat(com.gpo.pilipala.core.util.Fmt.count(u.follower.toLong()), "粉丝")
                Stat(if (u.coins % 1.0 == 0.0) u.coins.toLong().toString() else "%.1f".format(u.coins), "硬币")
                Stat("LV${u.level}", "等级", hide = false)
            }
        }
        VideoSource.YouTube -> {
            val profile by Accounts.youtube.collectAsState()
            val handle = (profile as? Profile.SignedIn)?.handle
            if (!handle.isNullOrEmpty()) StatCaption(handle)
        }
    }
}

@Composable
private fun Stat(value: String, label: String, hide: Boolean = true) {
    Column {
        com.gpo.pilipala.ui.components.FameCount(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            hide = hide,
            self = true,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatCaption(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                tint = MaterialTheme.colorScheme.onPrimary,
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
