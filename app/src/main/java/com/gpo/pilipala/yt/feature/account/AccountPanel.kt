package com.gpo.pilipala.yt.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.settings.GlyphTile
import com.gpo.pilipala.feature.settings.SettingsGroupScope
import com.gpo.pilipala.feature.settings.SettingsItem
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.yt.core.account.AccountSubscriptions
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.yt.feature.library.Library
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
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, contentWindowInsets = SheetInsets, properties = rememberSheetProperties()) {
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
                            close()
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
                close()
            },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * 设置's account block, as PiliPaw's: signed out a 登录 row with the cookie glyph; signed in two
 * connected blocks, the account (cookie avatar, name, @handle) and a stats row (订阅 / 历史 /
 * Mix, each opening its list; 订阅 is a line under 名声模式). Items of the enclosing group.
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
                    title = profile.name.ifEmpty { profile.handle.ifEmpty { "已登录" } },
                    summary = profile.handle.takeIf { it.isNotEmpty() && profile.name.isNotEmpty() },
                    leading = { NetImage(profile.avatar.ifEmpty { null }, Modifier.size(56.dp), shape = cookie.toShape(), thumbWidth = 112.dp) },
                )
            }
            item { AccountStats() }
        }
    }
}

/** 退出登录 in the error colour; asks first. An item of the enclosing group, signed in only. */
internal fun SettingsGroupScope.signOutItem() {
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

/** 订阅 / 历史 / Mix as PiliPaw's account stats: a number over its label, each opening its list. */
@Composable
private fun AccountStats() {
    val nav = LocalNavigator.current
    val subs by AccountSubscriptions.ids.collectAsState()
    val history by Library.history.collectAsState()
    val mixes by Library.playlists.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell(Fmt.count(subs.size.toLong()), "订阅", fame = true) { nav.push(Route.Subscriptions) }
        StatCell(Fmt.count(history.size.toLong()), "历史") { nav.push(Route.LocalList("History")) }
        StatCell(Fmt.count(mixes.size.toLong()), "Mix") { nav.push(Route.Playlists) }
    }
}

@Composable
private fun StatCell(value: String, label: String, fame: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .width(80.dp)
            .clip(YoinContainerShapes.ListRow)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (fame) {
            FameCount(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, revealable = false, self = true)
        } else {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
