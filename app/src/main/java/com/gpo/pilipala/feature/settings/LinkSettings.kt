package com.gpo.pilipala.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols

/**
 * Which of the bilibili hosts the manifest declares the user has let PiliPaw open
 * (Android 12+ 默认打开: unverified hosts start off). [linkHandling] false: the app's
 * whole 打开支持的链接 switch is off.
 */
internal class LinkHostState(val enabled: Int, val total: Int, val linkHandling: Boolean) {
    val on: Int get() = if (linkHandling) enabled else 0
}

internal object OpenByDefault {
    /** The once-only card at the top of 设置 when no host is on; gone for good once closed. */
    val cardDismissed = AppSettings.register("open_links_card_dismissed", false)

    /** Null below Android 12 (links aren't gated there) or when the system won't say. */
    fun state(context: Context): LinkHostState? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val dvm = context.getSystemService(DomainVerificationManager::class.java) ?: return null
            val user = dvm.getDomainVerificationUserState(context.packageName) ?: return null
            val hosts = user.hostToStateMap
            val enabled = hosts.values.count {
                it == DomainVerificationUserState.DOMAIN_STATE_SELECTED || it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
            }
            LinkHostState(enabled = enabled, total = hosts.size, linkHandling = user.isLinkHandlingAllowed)
        }.getOrNull()
    }

    /** 默认打开 for this app on 12+, the app's details below (or when 默认打开 is missing). */
    fun open(context: Context) {
        val pkg = Uri.parse("package:${context.packageName}")
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        try {
            context.startActivity(primary)
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
        }
    }
}

/** Re-reads the host state whenever the page resumes (back from 默认打开). */
@Composable
internal fun rememberLinkHostState(): LinkHostState? {
    val context = LocalContext.current
    var state by remember { mutableStateOf(OpenByDefault.state(context)) }
    LifecycleResumeEffect(Unit) {
        state = OpenByDefault.state(context)
        onPauseOrDispose { }
    }
    return state
}

/** 打开 B 站链接: the enabled count (bold) after a quiet label; opens 默认打开. */
@Composable
internal fun OpenLinksItem(state: LinkHostState?, selectable: Boolean) {
    val context = LocalContext.current
    SelectableRowSurface(selected = false, unselectedColor = if (selectable) settingsRowColor() else Color.Transparent) {
        SettingsItem(
            title = "打开 B 站链接",
            icon = YoinSymbols.Link,
            onClick = { OpenByDefault.open(context) },
            trailing = {
                if (state != null) {
                    if (!state.linkHandling) {
                        ValueLabel("已关闭")
                    } else {
                        ValueLabel("已启用")
                        Text(
                            "${state.on}/${state.total}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (state.on > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Icon(YoinSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            },
        )
    }
}

/** No host on yet: a tonal card with the count, a button to 默认打开 and a close. Shown until closed once. */
@Composable
internal fun OpenLinksCard(state: LinkHostState?) {
    val context = LocalContext.current
    val dismissed by OpenByDefault.cardDismissed.state.collectAsState()
    val visible = state != null && state.on == 0 && !dismissed
    AnimatedVisibility(
        visible = visible,
        enter = YoinMotion.fadeIn(com.gpo.pilipala.ui.theme.YoinMotionRole.Standard) +
            androidx.compose.animation.expandVertically(YoinMotion.defaultSpatialSpec()),
        exit = YoinMotion.fadeOut(com.gpo.pilipala.ui.theme.YoinMotionRole.Standard) +
            androidx.compose.animation.shrinkVertically(YoinMotion.defaultSpatialSpec()),
    ) {
        val colors = MaterialTheme.colorScheme
        Row(
            Modifier
                .fillMaxWidth()
                .clip(YoinContainerShapes.Card)
                .background(colors.secondaryContainer)
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(YoinSymbols.Link, null, tint = colors.onSecondaryContainer, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("打开 B 站链接", style = MaterialTheme.typography.titleMedium, color = colors.onSecondaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已启用", style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer.copy(alpha = 0.72f))
                    Text(
                        "${state?.on ?: 0}/${state?.total ?: 0}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSecondaryContainer,
                    )
                }
            }
            Button(onClick = { OpenByDefault.open(context) }) { Text("启用") }
            IconButton(
                onClick = { AppSettings.set(OpenByDefault.cardDismissed, true) },
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(YoinSymbols.Close, contentDescription = "关闭", tint = colors.onSecondaryContainer)
            }
        }
    }
}
