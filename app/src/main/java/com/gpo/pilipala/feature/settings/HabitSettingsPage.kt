package com.gpo.pilipala.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.FameScope
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
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
        item(key = "famePreview") { FamePreview() }
    }
}

/**
 * Two sample profiles (the user's own and someone else's) drawn with the real
 * [FameCount], so a scope change shows at once which numbers turn into the line, and a
 * tap on the line rolls the number in as it does on the pages.
 */
@Composable
private fun FamePreview() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FameSample("自己", self = true, fans = "1.2万", likes = "8.6万", modifier = Modifier.weight(1f))
        FameSample("他人", self = false, fans = "326.5万", likes = "2.1亿", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FameSample(owner: String, self: Boolean, fans: String, likes: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(YoinContainerShapes.ListRow)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(owner, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            FameSampleStat(fans, "粉丝", self)
            FameSampleStat(likes, "获赞", self)
        }
    }
}

/** Number over label, like the member page's stat blocks; the number rolls between shown and hidden. */
@Composable
private fun FameSampleStat(value: String, label: String, self: Boolean) {
    val hidden = FameMode.hides(self)
    val offsetSpec = YoinMotion.defaultSpatialSpec<IntOffset>()
    val sizeSpec = YoinMotion.defaultSpatialSpec<IntSize>()
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()
    Column {
        AnimatedContent(
            targetState = hidden,
            transitionSpec = {
                (slideInVertically(offsetSpec) { it } + fadeIn(fadeInSpec)) togetherWith
                    (slideOutVertically(offsetSpec) { -it } + fadeOut(fadeOutSpec)) using
                    SizeTransform(clip = true) { _, _ -> sizeSpec }
            },
            contentAlignment = Alignment.CenterStart,
            label = "famePreview",
        ) { hide ->
            // A fresh reveal state per scope: each change starts hidden again.
            val revealed = remember(hide) { mutableStateOf(false) }
            FameCount(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                hide = hide,
                revealState = revealed,
                self = self,
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
