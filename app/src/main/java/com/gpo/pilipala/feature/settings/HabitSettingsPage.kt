package com.gpo.pilipala.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.FameScope
import com.gpo.yoin.symbols.YoinSymbols

private val fameScopeChoices = FameScope.entries.map { Choice(it, it.label) }

/** 名声模式's quiet second line: what the chosen scope covers. */
private fun fameScopeSummary(scope: FameScope): String = when (scope) {
    FameScope.Off -> "粉丝、获赞、播放等数字"
    FameScope.Self -> "自己的粉丝、获赞、播放等数字"
    FameScope.All -> "所有人的粉丝、获赞、播放等数字"
}

/**
 * 习惯: everyday-behaviour preferences that have no other home. Settings that belong to a
 * page (播放, 外观, 其他) live only there, so nothing here duplicates them.
 */
@Composable
internal fun ColumnScope.HabitBody() {
    // The effective scope: an unset key still follows the old on/off switch (FameMode.scope
    // resolves it), so the picker never shows 关闭 while numbers are hidden.
    val fame = FameMode.scope

    SettingsGroup("浏览") {
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
