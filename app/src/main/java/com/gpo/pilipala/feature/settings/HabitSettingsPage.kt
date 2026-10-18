package com.gpo.pilipala.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

private val replySortChoices = listOf(
    Choice(1, "最热"),
    Choice(0, "最新"),
)

/**
 * 习惯: everyday-behaviour preferences in one place. Reserved page — the
 * options here mirror existing keys; new habit settings land here first.
 */
@Composable
internal fun ColumnScope.HabitBody() {
    val homeTab by AppSettings.homeDefaultTab.state.collectAsState()
    val autoPlay by AppSettings.autoPlay.state.collectAsState()
    val autoNext by AppSettings.autoPlayNext.state.collectAsState()
    val autoFs by AppSettings.autoFullscreenLandscape.state.collectAsState()
    val background by AppSettings.backgroundPlay.state.collectAsState()
    val replySort by AppSettings.replySort.state.collectAsState()
    val fame by FameMode.key.state.collectAsState()

    SettingsGroup("启动") {
        item {
            SettingsChoiceItem(
                title = "默认首页",
                icon = YoinSymbols.Home,
                choices = SettingsChoices.homeTab,
                selected = homeTab,
                onSelect = { AppSettings.set(AppSettings.homeDefaultTab, it) },
            )
        }
    }
    SettingsGroup("播放") {
        item {
            SettingsSwitchItem("打开即播放", autoPlay, { AppSettings.set(AppSettings.autoPlay, it) }, icon = YoinSymbols.PlayArrow)
        }
        item {
            SettingsSwitchItem("自动连播", autoNext, { AppSettings.set(AppSettings.autoPlayNext, it) }, icon = YoinSymbols.SkipNext)
        }
        item {
            SettingsSwitchItem("横屏自动全屏", autoFs, { AppSettings.set(AppSettings.autoFullscreenLandscape, it) }, icon = PiliSymbols.Fullscreen)
        }
        item {
            SettingsSwitchItem("后台播放", background, { AppSettings.set(AppSettings.backgroundPlay, it) }, icon = YoinSymbols.Headphones)
        }
    }
    SettingsGroup("浏览") {
        item {
            SettingsChoiceItem(
                title = "评论默认排序",
                icon = PiliSymbols.Comment,
                choices = replySortChoices,
                selected = replySort,
                onSelect = { AppSettings.set(AppSettings.replySort, it) },
            )
        }
        item {
            SettingsSwitchItem(
                "名声模式",
                fame,
                { FameMode.set(it) },
                icon = YoinSymbols.VisibilityOff,
                summary = "隐藏粉丝、播放、点赞等数字",
            )
        }
    }
}
