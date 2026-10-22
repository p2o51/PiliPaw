package com.gpo.pilipala.yt.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.settings.GlyphTile
import com.gpo.pilipala.feature.settings.SettingsGroupScope
import com.gpo.pilipala.feature.settings.SettingsItem
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.account.Source
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

/**
 * The profile circle at the top left of Home: the account's avatar signed in, a quiet person
 * glyph signed out. (The merged Paw app turns this into a circle of dots, one per source.)
 */
@Composable
fun ProfileButton(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    com.gpo.pilipala.feature.sources.SourceDots(modifier = modifier, size = size)
}

/**
 * The account panel, for callers outside 设置: who is signed in and 退出登录 (asks first), or
 * 未登录 and 登录.
 */
@Composable
fun AccountPanel(onDismiss: () -> Unit) {
    val profile by Accounts.youtube.collectAsState()
    val nav = LocalNavigator.current
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, contentWindowInsets = SheetInsets, properties = rememberSheetProperties()) {
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
                        GlyphTile(YoinSymbols.Person, MaterialShapes.Cookie12Sided.toShape(), size = 48.dp)
                        Text("未登录", style = MaterialTheme.typography.titleLarge)
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            nav.push(Route.SignIn)
                        },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("登录") }
                }
                is Profile.SignedIn -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NetImage(p.avatar.ifEmpty { null }, Modifier.size(48.dp), shape = MaterialShapes.Cookie12Sided.toShape(), thumbWidth = 96.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.name.ifEmpty { "已登录" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.handle.isNotEmpty()) {
                                Text(p.handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    FilledTonalButton(
                        onClick = { confirm = true },
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("退出登录") }
                }
            }
        }
    }
    if (confirm) {
        SignOutDialog(
            onConfirm = {
                confirm = false
                SignIn.signOut()
                onDismiss()
            },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * 设置's account block, as PiliPaw's: signed out a 登录 row with the cookie glyph; signed in the
 * account (cookie avatar, name, handle) and 退出登录 in the error colour, which asks first.
 * Items of the enclosing settings group.
 */
internal fun SettingsGroupScope.accountItems(profile: Profile, onSignIn: () -> Unit) {
    val cookie = MaterialShapes.Cookie12Sided
    when (profile) {
        Profile.SignedOut -> item {
            SettingsItem(
                title = "登录",
                summary = "未登录",
                onClick = onSignIn,
                leading = { GlyphTile(YoinSymbols.Person, cookie.toShape()) },
                trailing = { Chevron() },
            )
        }
        is Profile.SignedIn -> {
            item {
                SettingsItem(
                    title = profile.name.ifEmpty { "已登录" },
                    summary = profile.handle.ifEmpty { null },
                    leading = { NetImage(profile.avatar.ifEmpty { null }, Modifier.size(56.dp), shape = cookie.toShape(), thumbWidth = 112.dp) },
                )
            }
            item {
                var confirm by remember { mutableStateOf(false) }
                SettingsItem(
                    title = "退出登录",
                    icon = PiliSymbols.Logout,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirm = true },
                )
                if (confirm) {
                    SignOutDialog(
                        onConfirm = {
                            confirm = false
                            SignIn.signOut()
                        },
                        onDismiss = { confirm = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun Chevron() {
    Icon(YoinSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
}

@Composable
private fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退出 YouTube 登录") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("退出登录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
