package com.gpo.pilipala.yt.feature.settings

import com.gpo.pilipala.feature.settings.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.BuildConfig
import com.gpo.pilipala.yt.core.util.CrashLog
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.yt.ui.components.FameMode
import com.gpo.pilipala.yt.ui.components.FameScope
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

/** 设置 as a pushed page (the 我的 tab shows the same list in place). */
@Composable
fun SettingsScreen(title: String = "设置") {
    SettingsList(title = title, showBack = true)
}

/**
 * Every setting the skeleton has. Labels only, no explanations (Paw copy rule); a summary
 * appears only where the choice is not self-evident.
 */
@Composable
fun SettingsList(title: String, showBack: Boolean = false, bottom: Dp = 0.dp) {
    val context = LocalContext.current
    val toast = rememberToast()
    val themeMode by AppSettings.themeMode.state.collectAsState()
    val dynamic by AppSettings.dynamicColor.state.collectAsState()
    val cover by AppSettings.coverColor.state.collectAsState()
    val hideShorts by AppSettings.hideShorts.state.collectAsState()
    val autoPlay by AppSettings.autoPlay.state.collectAsState()
    val backgroundPlay by AppSettings.backgroundPlay.state.collectAsState()
    val autoPip by AppSettings.autoPip.state.collectAsState()
    val height by AppSettings.preferredHeight.state.collectAsState()
    val fame by FameMode.scopeKey.state.collectAsState()
    val sbEnabled by com.gpo.pilipala.yt.feature.player.Segments.enabled.state.collectAsState()
    val signedIn = com.gpo.pilipala.yt.core.account.Accounts.youtube.collectAsState().value is com.gpo.pilipala.yt.core.account.Profile.SignedIn
    val nav = com.gpo.pilipala.yt.navigation.LocalNavigator.current

    SettingsScaffold(title = title, showBack = showBack) {
        SettingsGroup("账号") {
            item { com.gpo.pilipala.yt.feature.account.AccountRow() }
            if (signedIn) {
                com.gpo.pilipala.yt.feature.account.AccountList.entries.forEach { list ->
                    item {
                        SettingsItem(
                            title = list.title,
                            icon = if (list == com.gpo.pilipala.yt.feature.account.AccountList.WatchLater) PiliSymbols.WatchLater else PiliSymbols.ThumbUp,
                            onClick = { nav.push(com.gpo.pilipala.yt.navigation.Route.AccountVideos(list.name)) },
                        )
                    }
                }
            }
        }
        SettingsGroup("本机") {
            item {
                SettingsItem(title = "历史记录", icon = PiliSymbols.History, onClick = { nav.push(com.gpo.pilipala.yt.navigation.Route.LocalList("History")) })
            }
            // Signed in, 稍后再看 is the account's (above); signed out it is this device's.
            if (!signedIn) {
                item {
                    SettingsItem(title = "稍后再看", icon = PiliSymbols.WatchLater, onClick = { nav.push(com.gpo.pilipala.yt.navigation.Route.LocalList("WatchLater")) })
                }
            }
            item {
                SettingsItem(title = "Mix", icon = YoinSymbols.Playlist, onClick = { nav.push(com.gpo.pilipala.yt.navigation.Route.Playlists) })
            }
        }
        // 外观 lives in the app's own 设置 (one theme for both sources).
        SettingsGroup("内容") {
            item { SettingsSwitchItem("隐藏 Shorts", hideShorts, { AppSettings.set(AppSettings.hideShorts, it) }, icon = PiliSymbols.Video) }
            item { SettingsItem(title = "屏蔽", icon = YoinSymbols.VisibilityOff, onClick = { nav.push(com.gpo.pilipala.yt.navigation.Route.Mutes) }) }
            item {
                SettingsToggleItem(
                    title = "名声模式",
                    icon = YoinSymbols.VisibilityOff,
                    choices = FameScope.entries.filter { it != FameScope.Self }.map { Choice(it.key, it.label) },
                    selected = FameScope.of(fame).let { if (it == FameScope.Self) FameScope.Off else it }.key,
                    onSelect = { FameMode.select(FameScope.of(it)) },
                )
            }
        }
        SettingsGroup("播放") {
            item { SettingsSwitchItem("自动播放", autoPlay, { AppSettings.set(AppSettings.autoPlay, it) }, icon = YoinSymbols.PlayCircle) }
            item { SettingsSwitchItem("后台播放", backgroundPlay, { AppSettings.set(AppSettings.backgroundPlay, it) }, icon = YoinSymbols.VolumeUp) }
            item { SettingsSwitchItem("自动画中画", autoPip, { AppSettings.set(AppSettings.autoPip, it) }, icon = PiliSymbols.Pip) }
            item {
                SettingsChoiceItem(
                    title = "默认画质",
                    icon = PiliSymbols.Hd,
                    choices = listOf(2160, 1440, 1080, 720, 480, 360).map { Choice(it, "${it}p") } + Choice(0, "最高"),
                    selected = height,
                    onSelect = { AppSettings.set(AppSettings.preferredHeight, it) },
                )
            }
        }
        SettingsGroup("SponsorBlock") {
            item {
                SettingsSwitchItem("标出片段", sbEnabled, {
                    AppSettings.set(com.gpo.pilipala.yt.feature.player.Segments.enabled, it)
                    com.gpo.pilipala.yt.feature.player.Segments.configure()
                }, icon = PiliSymbols.Video)
            }
            com.gpo.pilipala.yt.feature.player.SbCategory.entries.forEach { c ->
                item {
                    val on by c.skip.state.collectAsState()
                    SettingsSwitchItem("跳过${c.label}", on, { AppSettings.set(c.skip, it) }, enabled = sbEnabled)
                }
            }
        }
        SettingsGroup("关于") {
            item { SettingsItem(title = "PipePaw", icon = YoinSymbols.Info, summary = BuildConfig.VERSION_NAME) }
            val crash = CrashLog.read()
            if (crash != null) {
                item {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    SettingsItem(
                        title = "上次崩溃",
                        icon = YoinSymbols.Info,
                        summary = crash.lineSequence().drop(2).firstOrNull { it.isNotBlank() }?.take(80),
                        onClick = {
                            clipboard?.setPrimaryClip(ClipData.newPlainText("PipePaw crash", crash))
                            toast("已复制崩溃日志")
                        },
                    )
                }
            }
            item {
                SettingsLinkItem(
                    icon = PiliSymbols.Link,
                    title = "PipePipe",
                    summary = "GPL-3.0",
                    onClick = { openTab(context, "https://github.com/InfinityLoop1308/PipePipe") },
                )
            }
        }
        Spacer(Modifier.height(bottom))
    }
}

private fun openTab(context: Context, url: String) {
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
}
