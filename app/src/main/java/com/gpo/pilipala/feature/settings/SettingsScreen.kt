package com.gpo.pilipala.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.BuildConfig
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.account.UserInfo
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

/**
 * Settings home. Compact/Medium: one list, rows push their sub-page. Expanded: Yoin's
 * list-detail — the list on the left, the open page on the right (its row detaches
 * and takes the secondary container), sub-pages swap in place.
 */
@Composable
fun SettingsScreen() {
    if (widthClass() == WidthClass.Expanded) {
        SettingsTwoPane()
    } else {
        val nav = LocalNavigator.current
        SettingsList(
            selected = null,
            onOpen = { id ->
                if (id == SettingsPageId.About) nav.push(Route.About) else nav.push(Route.SettingsPage(id.key))
            },
        )
    }
}

@Composable
private fun SettingsTwoPane() {
    var open by rememberSaveable { mutableStateOf(SettingsPageId.Style.key) }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(400.dp).fillMaxHeight()) {
            SettingsList(
                selected = SettingsPageId.of(open),
                onOpen = { open = it.key },
                pageColor = MaterialTheme.colorScheme.surfaceDim,
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AnimatedContent(
                targetState = open,
                transitionSpec = {
                    (YoinMotion.fadeIn(YoinMotionRole.Standard) + YoinMotion.slideInVertically(YoinMotionRole.Standard) { it / 16 }) togetherWith
                        YoinMotion.fadeOut(YoinMotionRole.Standard)
                },
                label = "settingsPane",
            ) { page ->
                SettingsPageContent(page = page, showBack = false)
            }
        }
    }
}

@Composable
private fun SettingsList(
    selected: SettingsPageId?,
    onOpen: (SettingsPageId) -> Unit,
    pageColor: androidx.compose.ui.graphics.Color = settingsPageColor(),
) {
    val nav = LocalNavigator.current
    val user by Account.user.collectAsState()
    val themeMode by AppSettings.themeMode.state.collectAsState()
    val qn by AppSettings.defaultVideoQuality.state.collectAsState()
    val dmOn by AppSettings.danmakuEnabled.state.collectAsState()
    val rcmd by AppSettings.rcmdSource.state.collectAsState()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }
    val twoPane = selected != null
    val linkState = rememberLinkHostState()

    SettingsScaffold(title = "设置", pageColor = pageColor) {
        AccountBlock(
            user = user,
            onLogin = { nav.push(Route.Login) },
            onProfile = { u -> nav.push(Route.Member(u.mid, u.face, u.name)) },
        )
        YouTubeAccountBlock()
        OpenLinksCard(linkState)

        SettingsGroup("偏好") {
            listOf(
                SettingsPageId.Style to SettingsChoices.themeMode.firstOrNull { it.value == themeMode }?.label,
                SettingsPageId.Play to SettingsChoices.videoQuality.firstOrNull { it.value == qn }?.label,
                SettingsPageId.Danmaku to if (dmOn) "开启" else "关闭",
                SettingsPageId.Recommend to SettingsChoices.rcmdSource.firstOrNull { it.value == rcmd }?.label,
                SettingsPageId.Habit to null,
                SettingsPageId.Network to when (com.gpo.pilipala.feature.player.cdn.CdnSettings.mode.value) {
                    "fixed" -> "固定"
                    "native" -> "原生"
                    else -> "自动"
                },
            ).forEach { (id, value) ->
                item(key = id, paintsOwnSegment = twoPane) {
                    SettingsLinkItem(
                        icon = id.icon,
                        title = id.label,
                        value = value,
                        selectable = twoPane,
                        selected = selected == id,
                        onClick = { onOpen(id) },
                    )
                }
            }
        }

        SettingsGroup("隐私与存储") {
            listOf(SettingsPageId.Privacy, SettingsPageId.Extra).forEach { id ->
                item(key = id, paintsOwnSegment = twoPane) {
                    SettingsLinkItem(
                        icon = id.icon,
                        title = id.label,
                        selectable = twoPane,
                        selected = selected == id,
                        onClick = { onOpen(id) },
                    )
                }
            }
        }

        SettingsGroup("链接") {
            item(key = "open_links", paintsOwnSegment = twoPane) {
                OpenLinksItem(linkState, selectable = twoPane)
            }
        }

        SettingsGroup("关于") {
            item(key = SettingsPageId.About, paintsOwnSegment = twoPane) {
                SettingsLinkItem(
                    icon = YoinSymbols.Info,
                    title = "关于 PiliPaw",
                    value = BuildConfig.VERSION_NAME,
                    selectable = twoPane,
                    selected = selected == SettingsPageId.About,
                    onClick = { onOpen(SettingsPageId.About) },
                )
            }
        }

        if (user != null) {
            SettingsGroup(null) {
                item {
                    SettingsItem(
                        title = "退出登录",
                        icon = PiliSymbols.Logout,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { confirmLogout = true },
                    )
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            icon = { Icon(PiliSymbols.Logout, null) },
            title = { Text("退出登录") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    clearAllCookies()
                    toast("已退出登录")
                    scope.launch { BiliClient.ensureBuvid() }
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

/**
 * The YouTube account beside the Bilibili one, once YouTube is ticked: signed in, its avatar,
 * name and handle (opens its page: subscriptions, library, its own options); signed out, 登录.
 */
@Composable
private fun YouTubeAccountBlock() {
    val sources by com.gpo.pilipala.core.source.Sources.enabled.collectAsState()
    if (com.gpo.pilipala.core.source.VideoSource.YouTube !in sources) return
    val profile by com.gpo.pilipala.yt.core.account.Accounts.youtube.collectAsState()
    val ytNav = com.gpo.pilipala.yt.navigation.LocalNavigator.current
    val p = profile as? com.gpo.pilipala.yt.core.account.Profile.SignedIn
    val circle = androidx.compose.foundation.shape.CircleShape
    SettingsSegments {
        item {
            SettingsItem(
                title = p?.name?.ifEmpty { "YouTube" } ?: "登录 YouTube",
                summary = p?.handle?.ifEmpty { null } ?: if (p == null) "未登录" else "YouTube",
                onClick = { ytNav.push(if (p == null) com.gpo.pilipala.yt.navigation.Route.SignIn else com.gpo.pilipala.yt.navigation.Route.Settings) },
                leading = {
                    if (p != null && p.avatar.isNotEmpty()) NetImage(p.avatar, Modifier.size(56.dp), shape = circle, thumbWidth = 112.dp)
                    else GlyphTile(YoinSymbols.Person, circle)
                },
                trailing = { Icon(YoinSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
            )
        }
        if (p != null) {
            item {
                // What each signed-in list gets back from YouTube, copied to send (cookie names only).
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val toast = com.gpo.pilipala.ui.components.rememberToast()
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                SettingsItem(
                    title = "复制 YouTube 诊断信息",
                    summary = if (busy) "正在问 YouTube…" else "首页、订阅、赞过的视频各自返回了什么",
                    onClick = {
                        if (busy) return@SettingsItem
                        busy = true
                        scope.launch {
                            val text = runCatching { com.gpo.pilipala.yt.core.extractor.YtDiag.report() }.getOrElse { "诊断失败: ${it.message}" }
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                            busy = false
                            toast("已复制诊断信息")
                        }
                    },
                    leading = { GlyphTile(YoinSymbols.Info, circle) },
                )
            }
        }
    }
}

/** Signed in: avatar (cookie-clipped), name, level and UID. Signed out: the login row. */
@Composable
private fun AccountBlock(user: UserInfo?, onLogin: () -> Unit, onProfile: (UserInfo) -> Unit) {
    val cookie = MaterialShapes.Cookie12Sided.toShape()
    AnimatedContent(
        targetState = user,
        contentKey = { it?.mid },
        transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard) },
        label = "account",
    ) { u ->
        SettingsSegments {
            item {
                if (u == null) {
                    SettingsItem(
                        title = "登录",
                        summary = "未登录",
                        onClick = onLogin,
                        leading = { GlyphTile(YoinSymbols.Person, cookie) },
                        trailing = { Icon(YoinSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                    )
                } else {
                    SettingsItem(
                        title = u.name,
                        onClick = { onProfile(u) },
                        summary = "UID ${u.mid}",
                        leading = {
                            NetImage(u.face, Modifier.size(56.dp), shape = cookie, thumbWidth = 112.dp)
                        },
                        trailing = {
                            Tag("LV${u.level}", container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer)
                            if (u.vipLabel.isNotEmpty()) {
                                Tag(u.vipLabel, container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Icon(YoinSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        },
                    )
                }
            }
            if (u != null) {
                item { AccountStats(u) }
            }
        }
    }
}

@Composable
private fun AccountStats(u: UserInfo) {
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stat(Fmt.count(u.dynamicCount.toLong()), "动态") { nav.push(Route.MemberDynamics(u.mid, u.name)) }
        Stat(Fmt.count(u.following.toLong()), "关注") { nav.push(Route.Follow(u.mid, u.name)) }
        Stat(Fmt.count(u.follower.toLong()), "粉丝", fame = true) { nav.push(Route.Fans(u.mid, u.name)) }
        Stat(if (u.coins % 1.0 == 0.0) u.coins.toInt().toString() else u.coins.toString(), "硬币", onClick = null)
    }
}

/**
 * Number over label. [fame] (粉丝): a line under 名声模式 (the signed-in user's own, so
 * 只隐藏自己的 too), kept a line since the block opens the list.
 */
@Composable
private fun Stat(value: String, label: String, fame: Boolean = false, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .width(80.dp)
            .clip(YoinContainerShapes.ListRow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
