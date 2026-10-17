package com.gpo.pilipala.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.player.cdn.CdnHosts
import com.gpo.pilipala.feature.player.cdn.CdnRouter
import com.gpo.pilipala.feature.player.cdn.CdnSettings
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import java.util.Locale

private val modeChoices = listOf(
    Choice("auto", "自动"),
    Choice("fixed", "固定"),
    Choice("native", "原生"),
)

private val hostChoices = CdnHosts.POOL.map { Choice(it.host, "${it.vendor}（${it.region}）") }

private val mcdnChoices = listOf(
    Choice("proxy", "官方代理"),
    Choice("keep", "不处理"),
)

/** 网络与线路: overseas CDN line selection + an on-demand speed test. */
@Composable
internal fun ColumnScope.NetworkBody() {
    val mode by CdnSettings.mode.state.collectAsState()
    val fixed by CdnSettings.fixedHost.state.collectAsState()
    val mcdn by CdnSettings.mcdn.state.collectAsState()
    val avoidPcdn by CdnSettings.avoidPcdn.state.collectAsState()
    val autoSwitch by CdnSettings.autoSwitch.state.collectAsState()
    val live by CdnRouter.status.collectAsState()

    SettingsGroup("线路") {
        item {
            SettingsToggleItem(
                title = "线路选择",
                icon = YoinSymbols.Speed,
                choices = modeChoices,
                selected = mode,
                onSelect = { AppSettings.set(CdnSettings.mode, it) },
            )
        }
        if (mode == "fixed") {
            item(key = "fixed") {
                SettingsChoiceItem(
                    title = "固定线路",
                    icon = YoinSymbols.Storage,
                    choices = hostChoices,
                    selected = fixed,
                    onSelect = { AppSettings.set(CdnSettings.fixedHost, it) },
                    fallbackLabel = fixed,
                )
            }
        }
        if (mode == "auto") {
            item(key = "auto") {
                SettingsSwitchItem(
                    title = "自动切换线路",
                    summary = "速度不足时换到更快的线路",
                    icon = YoinSymbols.Repeat,
                    checked = autoSwitch,
                    onCheckedChange = { AppSettings.set(CdnSettings.autoSwitch, it) },
                )
            }
        }
    }

    SettingsGroup("P2P 节点") {
        item {
            SettingsChoiceItem(
                title = "MCDN 节点",
                icon = YoinSymbols.Devices,
                choices = mcdnChoices,
                selected = mcdn,
                onSelect = { AppSettings.set(CdnSettings.mcdn, it) },
            )
        }
        item {
            SettingsSwitchItem(
                title = "避开 PCDN 节点",
                summary = "直连 IP 和非标准端口换成 UPOS",
                icon = YoinSymbols.CloudOffline,
                checked = avoidPcdn,
                onCheckedChange = { AppSettings.set(CdnSettings.avoidPcdn, it) },
            )
        }
    }

    if (live.host.isNotEmpty()) {
        val (region, vendor) = CdnHosts.describe(live.host)
        SettingsGroup("正在播放", trailingLabel = if (live.switched) "已切换 ${live.switches} 次" else "原生线路") {
            item {
                SettingsItem(
                    title = "$vendor",
                    summary = region,
                    icon = YoinSymbols.PlayCircle,
                    trailing = { ValueLabel(if (live.goodputBps > 0) mbps(live.goodputBps / 1e6) else "测量中") },
                )
            }
        }
    }

    SpeedTestGroup(onPick = { host ->
        AppSettings.set(CdnSettings.fixedHost, host)
        AppSettings.set(CdnSettings.mode, "fixed")
    })
}

@Composable
private fun SpeedTestGroup(onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val results = remember { mutableStateListOf<CdnRouter.HostSpeed>() }
    var running by remember { mutableStateOf(false) }
    val best = results.mapNotNull { r -> r.mbps?.let { r.host to it } }.maxByOrNull { it.second }
    val top = (best?.second ?: 0.0).coerceAtLeast(1.0)

    SettingsGroup("测速", trailingLabel = if (running) "测试中" else null) {
        results.forEach { r ->
            item(key = r.host) {
                val (region, vendor) = CdnHosts.describe(r.host)
                SettingsItem(
                    title = vendor,
                    summary = region,
                    onClick = if (r.mbps != null && CdnHosts.find(r.host) != null) ({ onPick(r.host) }) else null,
                    trailing = {
                        SpeedBar(
                            fraction = ((r.mbps ?: 0.0) / top).toFloat(),
                            label = r.mbps?.let(::mbps) ?: "失败",
                            best = r.host == best?.first,
                        )
                    },
                )
            }
        }
        item(key = "run") {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (running) return@Button
                        running = true
                        results.clear()
                        scope.launch {
                            runCatching {
                                val issued = sampleUrls()
                                CdnRouter.speedTest(issued) { r -> scope.launch { results += r } }
                            }.onFailure { toast(it.friendlyMessage()) }
                            running = false
                        }
                    },
                    enabled = !running,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(if (results.isEmpty()) "测试全部线路" else "重新测试")
                }
                if (running) LoadingIndicator(Modifier.width(36.dp).height(36.dp))
            }
        }
    }
}

@Composable
private fun SpeedBar(fraction: Float, label: String, best: Boolean) {
    val f by animateFloatAsState(fraction.coerceIn(0.02f, 1f), YoinMotion.defaultSpatialSpec(), label = "speedBar")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.width(96.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(4.dp))
                    .background(if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (best) FontWeight.SemiBold else FontWeight.Normal,
            color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
    }
}

private fun mbps(v: Double): String = String.format(Locale.US, if (v >= 10) "%.0f Mbps" else "%.1f Mbps", v)

/** Issued URLs (base + backups) of a popular video's first video stream, as the test sample. */
private suspend fun sampleUrls(): List<String> {
    val item = BiliClient.getData("/x/web-interface/popular", mapOf("pn" to 1, "ps" to 5)).asObj
        .arr("list").objects().first()
    val data = BiliClient.getData(
        "/x/player/wbi/playurl",
        mapOf("bvid" to item.str("bvid"), "cid" to item.long("cid"), "qn" to 64, "fnval" to 16, "fnver" to 0, "fourk" to 1),
        wbi = true,
    ).asObj
    val v = data.obj("dash").arr("video").objects().first()
    return buildList {
        add(v.str("baseUrl").ifEmpty { v.str("base_url") })
        (v.arr("backupUrl").ifEmpty { v.arr("backup_url") }).forEach { add(it.asString()) }
    }.filter { it.isNotBlank() }
}
