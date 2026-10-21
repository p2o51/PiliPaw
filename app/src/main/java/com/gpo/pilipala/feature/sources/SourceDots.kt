package com.gpo.pilipala.feature.sources

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetBottomPadding
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
    // Only this part's own source ticked: the part keeps its own avatar button (PiliPaw's lands
    // the launch animation in it), which opens the same sheet.
    if (single != null && enabled == setOf(current)) single { open = true } else IconButton(
        onClick = { open = true },
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.size(size + 8.dp),
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
        SourcesSheet(
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
 * The tick page: one row per source. The checkbox ticks a source; with two ticked, tapping a row
 * picks the one on screen (until Home mixes them). Changes are kept here and handed to [Sources]
 * when the sheet closes, so Home refreshes once, on the way back. The last ticked source cannot
 * be unticked.
 */
@Composable
private fun SourcesSheet(current: VideoSource, onAccount: () -> Unit, onDismiss: () -> Unit) {
    val savedTicks by Sources.enabled.collectAsState()
    val savedFront by Sources.front.collectAsState()
    var ticks by remember { mutableStateOf(savedTicks) }
    var front by remember { mutableStateOf(savedFront) }
    val toast = rememberToast()
    val close = {
        Sources.apply(ticks, front)
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = close, properties = rememberSheetProperties()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = sheetBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "看哪些内容",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
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
                            else -> {
                                // Just asked for: it comes to the front.
                                ticks = ticks + source
                                front = source
                            }
                        }
                    },
                    onRow = {
                        if (!on) {
                            ticks = ticks + source
                            front = source
                        } else {
                            front = source
                        }
                    },
                    onAccount = if (source == current) onAccount else null,
                )
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
        when {
            showing -> MaterialTheme.colorScheme.primaryContainer
            ticked -> MaterialTheme.colorScheme.secondaryContainer
            else -> Color.Transparent
        },
        YoinMotion.defaultEffectsSpec(),
        label = "rowBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(bg)
            .clickable(onClick = onRow)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
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
        Checkbox(checked = ticked, onCheckedChange = { onTick() })
    }
}
