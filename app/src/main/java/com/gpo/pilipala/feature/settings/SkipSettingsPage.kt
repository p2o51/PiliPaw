package com.gpo.pilipala.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.source.Sources
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.feature.player.BiliSkipKind
import com.gpo.pilipala.feature.player.BiliSkips
import com.gpo.pilipala.feature.player.SkipMode
import com.gpo.pilipala.yt.feature.player.SbCategory
import com.gpo.pilipala.yt.feature.player.Segments

private val biliModes = SkipMode.entries.map { Choice(it.name, it.label) }
private val ytModes = listOf(Choice(true, SkipMode.Skip.label), Choice(false, SkipMode.Mark.label))

/**
 * 跳过片段: one page for both sites. B 站 first (番剧片头片尾 from Bilibili, then 空降助手's
 * kinds), YouTube's SponsorBlock after; each kind is 自动跳过, 提示我 or 只标出 on the bar.
 */
@Composable
internal fun ColumnScope.SkipBody() {
    val sources by Sources.enabled.collectAsState()
    if (VideoSource.Bilibili in sources) {
        val community by BiliSkips.community.state.collectAsState()
        SettingsGroup("B 站番剧") {
            BiliSkipKind.entries.filter { it.official }.forEach { kind -> item(key = kind) { BiliKindRow(kind, enabled = true) } }
        }
        SettingsGroup("空降助手") {
            item(key = "bsb") {
                SettingsSwitchItem(
                    title = "空降助手",
                    summary = "观众标出的片段，来自 bsbsb.top",
                    checked = community,
                    onCheckedChange = { AppSettings.set(BiliSkips.community, it) },
                )
            }
            BiliSkipKind.community.forEach { kind -> item(key = kind) { BiliKindRow(kind, enabled = community) } }
        }
    }
    if (VideoSource.YouTube in sources) {
        val sb by Segments.enabled.state.collectAsState()
        SettingsGroup("YouTube") {
            item(key = "sb") {
                SettingsSwitchItem(
                    title = "SponsorBlock",
                    summary = "观众标出的片段，来自 sponsor.ajay.app",
                    checked = sb,
                    onCheckedChange = {
                        AppSettings.set(Segments.enabled, it)
                        Segments.configure()
                    },
                )
            }
            SbCategory.entries.forEach { c ->
                item(key = c) {
                    val skip by c.skip.state.collectAsState()
                    SettingsChoiceItem(
                        title = c.label,
                        choices = ytModes,
                        selected = skip,
                        enabled = sb,
                        onSelect = { AppSettings.set(c.skip, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BiliKindRow(kind: BiliSkipKind, enabled: Boolean) {
    val mode by kind.modeKey.state.collectAsState()
    SettingsChoiceItem(
        title = kind.label,
        choices = biliModes,
        selected = mode,
        enabled = enabled,
        fallbackLabel = kind.default.label,
        onSelect = { AppSettings.set(kind.modeKey, it) },
    )
}

/** The value 设置 shows beside 跳过片段: 开启 while any source looks for segments. */
@Composable
internal fun skipSummary(): String {
    val community by BiliSkips.community.state.collectAsState()
    val sb by Segments.enabled.state.collectAsState()
    val op by BiliSkipKind.BangumiOp.modeKey.state.collectAsState()
    val ed by BiliSkipKind.BangumiEd.modeKey.state.collectAsState()
    val official = listOf(op, ed).any { it != SkipMode.Mark.name }
    return if (community || sb || official) "开启" else "关闭"
}
