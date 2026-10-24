package com.gpo.pilipala.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gpo.pilipala.core.net.str
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.gpo.pilipala.BuildConfig
import com.gpo.pilipala.R
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.theme.MorphShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

private const val RepoUrl = "https://github.com/guozhigq/pilipala"
private const val LicenseUrl = "https://www.gnu.org/licenses/gpl-3.0.html"

@Composable
fun AboutScreen() {
    AboutContent(showBack = true)
}

@Composable
internal fun AboutContent(showBack: Boolean) {
    val context = LocalContext.current
    val toolbar = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val open: (String) -> Unit = { openCustomTab(context, it, toolbar) }
    SettingsScaffold(title = "关于", showBack = showBack) {
        AboutHero()
        SettingsGroup("致谢") {
            item {
                SettingsItem(
                    title = "基于 guozhigq/pilipala（PiliPala）重写",
                    icon = YoinSymbols.Code,
                    summary = "github.com/guozhigq/pilipala",
                    onClick = { open(RepoUrl) },
                    trailing = { LaunchGlyph() },
                )
            }
        }
        SettingsGroup("设计") {
            item {
                SettingsItem(
                    title = "Yoin 设计语言",
                    icon = YoinSymbols.Sparkle,
                    summary = "界面、动效与图标",
                )
            }
            item {
                SettingsItem(
                    title = "Material 3 Expressive",
                    icon = PiliSymbols.Palette,
                    summary = "m3.material.io",
                    onClick = { open("https://m3.material.io") },
                    trailing = { LaunchGlyph() },
                )
            }
        }
        SettingsGroup("应用") {
            item {
                SettingsItem(
                    title = "版本",
                    icon = YoinSymbols.Info,
                    trailing = { ValueLabel("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
                )
            }
            item { UpdateItem(open) }
            item { CrashLogItem(com.gpo.pilipala.ui.components.rememberToast()) }
            item {
                SettingsItem(
                    title = "开源许可",
                    icon = PiliSymbols.Article,
                    onClick = { open(LicenseUrl) },
                    trailing = { ValueLabel("GPL-3.0") },
                )
            }
        }
    }
}

/** 检查更新: asks GitHub for PiliPaw's latest release; a newer one opens its page. */
@Composable
private fun UpdateItem(open: (String) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateCheck.State>(UpdateCheck.State.Idle) }
    val s = state
    SettingsItem(
        title = if (s is UpdateCheck.State.Newer) "有新版本 ${s.version}" else "检查更新",
        icon = YoinSymbols.Refresh,
        summary = if (s is UpdateCheck.State.Newer) "点开去 GitHub 下载" else null,
        onClick = {
            when (s) {
                is UpdateCheck.State.Newer -> open(s.url)
                UpdateCheck.State.Checking -> Unit
                else -> scope.launch {
                    state = UpdateCheck.State.Checking
                    state = UpdateCheck.latest()
                }
            }
        },
        trailing = {
            when (s) {
                UpdateCheck.State.Checking -> ValueLabel("检查中…")
                UpdateCheck.State.Latest -> ValueLabel("已是最新")
                UpdateCheck.State.Failed -> ValueLabel("检查失败")
                is UpdateCheck.State.Newer -> LaunchGlyph()
                UpdateCheck.State.Idle -> Unit
            }
        },
    )
}

internal object UpdateCheck {
    private const val LatestUrl = "https://api.github.com/repos/p2o51/PiliPaw/releases/latest"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object Latest : State
        data object Failed : State
        data class Newer(val version: String, val url: String) : State
    }

    suspend fun latest(): State = try {
        val o = com.gpo.pilipala.core.net.BiliClient.getText(LatestUrl, headers = mapOf("Accept" to "application/vnd.github+json"))
            .let { com.gpo.pilipala.core.net.BiliJson.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
        val tag = o.str("tag_name").removePrefix("v")
        if (tag.isNotBlank() && newer(tag, BuildConfig.VERSION_NAME)) State.Newer(tag, o.str("html_url")) else State.Latest
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Throwable) {
        State.Failed
    }

    /** 0.5.12 > 0.5.11 by numbers; a suffix after '-' is ignored. */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

@Composable
private fun LaunchGlyph() {
    Icon(YoinSymbols.Launch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
}

/**
 * The launcher icon, large: its forest field clipped to a cookie that morphs and turns on each
 * tap, the mark counter-turning so the P stays upright. Name, 霹雳啵 and version under it.
 */
@Composable
private fun AboutHero() {
    val haptics = LocalHapticFeedback.current
    var taps by remember { mutableIntStateOf(0) }
    val turn by animateFloatAsState(
        targetValue = taps.toFloat(),
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "aboutTurn",
    )
    // Shallow-lobed shapes only: the mark (r 29.4 of the 108 viewport) never touches a valley.
    val shapes = remember {
        listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Cookie12Sided, MaterialShapes.Sunny, MaterialShapes.Circle)
    }
    val base = turn.toInt().coerceAtLeast(0)
    val frac = (turn - base).coerceIn(0f, 1f)
    val morph = remember(base) { Morph(shapes[base % shapes.size], shapes[(base + 1) % shapes.size]) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(HeroSize)
                .graphicsLayer { rotationZ = turn * 45f }
                .clip(MorphShape(morph, frac))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    taps++
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_launcher_background),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(HeroSize * MarkScale).graphicsLayer { rotationZ = -turn * 45f },
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PiliPaw", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "霹雳啵",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Tag("Compose", container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

private val HeroSize = 144.dp

/** The 108-unit foreground across the cookie: a touch under the launcher's 1.5 so lobes clear it. */
private const val MarkScale = 1.36f

/** Opens [url] in a Custom Tab (falls back to any browser). */
internal fun openCustomTab(context: Context, url: String, toolbarColor: Int? = null) {
    val uri = Uri.parse(url)
    runCatching {
        val builder = CustomTabsIntent.Builder().setShowTitle(true)
        if (toolbarColor != null) {
            builder.setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
        }
        builder.build().launchUrl(context, uri)
    }.onFailure {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
}

