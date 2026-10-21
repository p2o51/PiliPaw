package com.gpo.pilipala.yt.feature.account

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.account.Source
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols

/** What signing in to YouTube adds, as one line (the suggestion, never a prompt). */
private const val SignInGain = "登录后，首页是你的订阅和推荐，还有稍后再看和赞过的视频"

/**
 * The profile circle at the top left of Home: the account's avatar signed in, a quiet person
 * glyph signed out. Tapping opens [AccountPanel]. (The merged Paw app turns this into a
 * circle of dots, one per source.)
 */
@Composable
fun ProfileButton(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    com.gpo.pilipala.feature.sources.SourceDots(
        current = com.gpo.pilipala.core.source.VideoSource.YouTube,
        onAccount = { open = true },
        modifier = modifier,
        size = size,
    )
    if (open) AccountPanel(onDismiss = { open = false })
}

/**
 * The account panel: one row per source (YouTube today). Signed out: the gain in one line and
 * 登录. Signed in: who, and 退出登录.
 */
@Composable
fun AccountPanel(onDismiss: () -> Unit) {
    val profile by Accounts.youtube.collectAsState()
    val nav = LocalNavigator.current
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = sheetBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Source.YouTube.label, style = MaterialTheme.typography.titleMedium)
            when (val p = profile) {
                Profile.SignedOut -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SignedOutMark(48.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("未登录", style = MaterialTheme.typography.titleLarge)
                            Text(SignInGain, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            nav.push(Route.SignIn)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("登录") }
                }
                is Profile.SignedIn -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Avatar(p.avatar.ifEmpty { null }, 48.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.name.ifEmpty { "已登录" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.handle.isNotEmpty()) {
                                Text(p.handle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            SignIn.signOut()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("退出登录") }
                }
            }
        }
    }
}

/**
 * 我的's quieter form of the suggestion: a row that opens the panel. Signed in, the account
 * itself (avatar, name, handle).
 */
@Composable
fun AccountRow(modifier: Modifier = Modifier) {
    val profile by Accounts.youtube.collectAsState()
    var open by remember { mutableStateOf(false) }
    val signedIn = profile as? Profile.SignedIn
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (signedIn != null) Avatar(signedIn.avatar.ifEmpty { null }, 48.dp) else SignedOutMark(48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                signedIn?.name?.ifEmpty { "已登录" } ?: "未登录",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (signedIn != null) signedIn.handle.ifEmpty { Source.YouTube.label } else "登录 YouTube",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(YoinSymbols.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AccountPanel(onDismiss = { open = false })
}

@Composable
private fun SignedOutMark(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(YoinSymbols.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
