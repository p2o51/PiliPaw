package com.gpo.pilipala.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.FameScope
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

private val replySortChoices = listOf(
    Choice(1, "最热"),
    Choice(0, "最新"),
)

private val fameScopeChoices = FameScope.entries.map { Choice(it, it.label) }

/** 名声模式's quiet second line: what the chosen scope covers. */
private fun fameScopeSummary(scope: FameScope): String = when (scope) {
    FameScope.Off -> "粉丝、获赞、播放等数字"
    FameScope.Self -> "自己的粉丝、获赞、播放等数字"
    FameScope.All -> "所有人的粉丝、获赞、播放等数字"
}

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
    val fameKey by FameMode.scopeKey.state.collectAsState()
    val fame = FameScope.of(fameKey)

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
            SettingsChoiceItem(
                title = "名声模式",
                icon = YoinSymbols.VisibilityOff,
                choices = fameScopeChoices,
                selected = fame,
                onSelect = { FameMode.select(it) },
                summary = fameScopeSummary(fame),
            )
        }
    }
}
