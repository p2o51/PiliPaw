package com.gpo.pilipala.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.gpo.pilipala.BuildConfig
import com.gpo.pilipala.R
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

private const val RepoUrl = "https://github.com/guozhigq/pilipala"

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
        SettingsGroup("项目") {
            item {
                SettingsItem(
                    title = "开源地址",
                    icon = YoinSymbols.Code,
                    summary = "github.com/guozhigq/pilipala",
                    onClick = { open(RepoUrl) },
                    trailing = { LaunchGlyph() },
                )
            }
            item {
                SettingsItem(
                    title = "问题反馈",
                    icon = PiliSymbols.Comment,
                    summary = "GitHub Issues",
                    onClick = { open("$RepoUrl/issues") },
                    trailing = { LaunchGlyph() },
                )
            }
            item {
                SettingsItem(
                    title = "版本发布",
                    icon = YoinSymbols.Download,
                    summary = "GitHub Releases",
                    onClick = { open("$RepoUrl/releases") },
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
        }
    }
}

@Composable
private fun LaunchGlyph() {
    Icon(YoinSymbols.Launch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
}

/** App mark on a cookie that morphs and turns on each tap; name and version under it. */
@Composable
private fun AboutHero() {
    val haptics = LocalHapticFeedback.current
    var taps by remember { mutableIntStateOf(0) }
    val turn by animateFloatAsState(
        targetValue = taps.toFloat(),
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "aboutTurn",
    )
    val shapes = remember {
        listOf(MaterialShapes.Cookie12Sided, MaterialShapes.Clover8Leaf, MaterialShapes.Sunny, MaterialShapes.Cookie9Sided)
    }
    val base = turn.toInt().coerceAtLeast(0)
    val frac = (turn - base).coerceIn(0f, 1f)
    val morph = remember(base) { Morph(shapes[base % shapes.size], shapes[(base + 1) % shapes.size]) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .graphicsLayer { rotationZ = turn * 45f }
                .clip(MorphShape(morph, frac))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    taps++
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(104.dp).graphicsLayer { rotationZ = -turn * 45f },
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PiliPala", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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

