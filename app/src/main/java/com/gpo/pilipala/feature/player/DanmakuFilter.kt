package com.gpo.pilipala.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.yoin.symbols.YoinSymbols

// ── Settings (feature-owned keys) ────────────────────────────────────────────

/** Block rules, one per line. `/…/` is a regular expression, anything else a keyword. */
internal val DanmakuBlockRules = AppSettings.register("dm_block_rules", "")

/** 屏蔽等级 0..10: hide danmaku whose weight is below it (0 = off). */
internal val DanmakuBlockLevel = AppSettings.register("dm_block_level", 0)

/** Fold identical lines that repeat within a few seconds into one "×N" line. */
internal val DanmakuMergeDuplicates = AppSettings.register("dm_merge_dup", false)

internal fun danmakuLevelLabel(level: Int): String = if (level <= 0) "关闭" else "$level 级"

// ── Rules ───────────────────────────────────────────────────────────────────

internal object DanmakuRules {
    fun parse(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun join(rules: List<String>): String = rules.joinToString("\n")

    fun isRegex(rule: String): Boolean = rule.length >= 3 && rule.startsWith('/') && rule.endsWith('/')

    /** What the list shows: the pattern without its slashes. */
    fun display(rule: String): String = if (isRegex(rule)) rule.substring(1, rule.length - 1) else rule

    fun add(rule: String) {
        val r = rule.trim()
        if (r.isEmpty() || r == "//") return
        val list = parse(DanmakuBlockRules.value)
        if (r in list) return
        AppSettings.set(DanmakuBlockRules, join(list + r))
    }

    fun remove(rule: String) {
        AppSettings.set(DanmakuBlockRules, join(parse(DanmakuBlockRules.value) - rule))
    }

    fun validRegex(pattern: String): Boolean = runCatching { Regex(pattern) }.isSuccess
}

@Immutable
internal class DanmakuFilterSpec(
    val keywords: List<String>,
    val regexes: List<Regex>,
    val level: Int,
    val merge: Boolean,
) {
    val isNoop: Boolean get() = keywords.isEmpty() && regexes.isEmpty() && level <= 0 && !merge

    companion object {
        fun of(raw: String, level: Int, merge: Boolean): DanmakuFilterSpec {
            val keywords = ArrayList<String>()
            val regexes = ArrayList<Regex>()
            for (rule in DanmakuRules.parse(raw)) {
                if (DanmakuRules.isRegex(rule)) {
                    runCatching { Regex(rule.substring(1, rule.length - 1), RegexOption.IGNORE_CASE) }
                        .getOrNull()?.let(regexes::add)
                } else {
                    keywords += rule.lowercase()
                }
            }
            return DanmakuFilterSpec(keywords, regexes, level, merge)
        }
    }
}

@Composable
internal fun rememberDanmakuFilterSpec(): DanmakuFilterSpec {
    val rules by DanmakuBlockRules.state.collectAsState()
    val level by DanmakuBlockLevel.state.collectAsState()
    val merge by DanmakuMergeDuplicates.state.collectAsState()
    return remember(rules, level, merge) { DanmakuFilterSpec.of(rules, level, merge) }
}

internal object DanmakuFilter {
    private const val MERGE_WINDOW_MS = 10_000L

    /** Applies block rules, the weight level and duplicate merging. The viewer's own lines always pass. */
    fun apply(items: List<DanmakuItem>, spec: DanmakuFilterSpec): List<DanmakuItem> {
        if (spec.isNoop || items.isEmpty()) return items
        val kept = ArrayList<DanmakuItem>(items.size)
        for (it in items) {
            if (it.mine) {
                kept += it
                continue
            }
            if (spec.level > 0 && it.weight in 1 until spec.level) continue
            if (spec.keywords.isNotEmpty()) {
                val lower = it.text.lowercase()
                if (spec.keywords.any { k -> lower.contains(k) }) continue
            }
            if (spec.regexes.isNotEmpty() && spec.regexes.any { r -> r.containsMatchIn(it.text) }) continue
            kept += it
        }
        return if (spec.merge) merge(kept) else kept
    }

    private fun merge(items: List<DanmakuItem>): List<DanmakuItem> {
        val out = ArrayList<DanmakuItem>(items.size)
        val counts = ArrayList<Int>(items.size)
        val lastIndex = HashMap<String, Int>()
        for (it in items) {
            if (it.mine) {
                out += it
                counts += 1
                continue
            }
            val key = it.text.lowercase().filterNot { c -> c.isWhitespace() }
            val idx = lastIndex[key]
            if (idx != null && it.timeMs - out[idx].timeMs <= MERGE_WINDOW_MS) {
                counts[idx] = counts[idx] + 1
                continue
            }
            lastIndex[key] = out.size
            out += it
            counts += 1
        }
        for (i in out.indices) {
            val n = counts[i]
            if (n > 1) out[i] = out[i].withText("${out[i].text} ×$n")
        }
        return out
    }
}

// ── Block list editor ───────────────────────────────────────────────────────

private fun ruleShape(index: Int, count: Int): Shape {
    val big = 20.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) big else small,
        topEnd = if (index == 0) big else small,
        bottomStart = if (index == count - 1) big else small,
        bottomEnd = if (index == count - 1) big else small,
    )
}

/**
 * The 屏蔽词 list: add a keyword or a regular expression, remove with one tap.
 * Shared by the danmaku settings page and the player's danmaku sheet.
 */
@Composable
internal fun DanmakuBlockRulesSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "屏蔽词",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            DanmakuBlockRulesEditor()
        }
    }
}

@Composable
internal fun DanmakuBlockRulesEditor(modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val raw by DanmakuBlockRules.state.collectAsState()
    val rules = remember(raw) { DanmakuRules.parse(raw) }
    var input by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    val valid = input.isNotBlank() && (!regex || DanmakuRules.validRegex(input.trim()))
    val submit = {
        if (valid) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            DanmakuRules.add(if (regex) "/${input.trim()}/" else input.trim())
            input = ""
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            listOf(false to "关键词", true to "正则").forEachIndexed { i, (isRegex, label) ->
                ToggleButton(
                    checked = regex == isRegex,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        regex = isRegex
                    },
                    shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(label)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = input,
                onValueChange = { input = it.replace("\n", "") },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (regex) "正则表达式" else "关键词") },
                singleLine = true,
                isError = regex && input.isNotBlank() && !valid,
                shape = CircleShape,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    errorContainerColor = MaterialTheme.colorScheme.errorContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            FilledIconButton(
                onClick = submit,
                enabled = valid,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(YoinSymbols.Add, contentDescription = "添加")
            }
        }
        AnimatedVisibility(
            visible = rules.isNotEmpty(),
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.defaultSpatialSpec()),
        ) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).animateContentSize(YoinMotion.defaultSpatialSpec()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(rules, key = { _, r -> r }) { i, rule ->
                    Surface(
                        shape = ruleShape(i, rules.size),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().animateItem(
                            fadeInSpec = YoinMotion.defaultEffectsSpec(),
                            placementSpec = YoinMotion.defaultSpatialSpec(),
                            fadeOutSpec = YoinMotion.fastEffectsSpec(),
                        ),
                    ) {
                        Row(
                            Modifier.heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                DanmakuRules.display(rule),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (DanmakuRules.isRegex(rule)) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = YoinShapeTokens.Full,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ) {
                                    Text(
                                        "正则",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    DanmakuRules.remove(rule)
                                },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(YoinSymbols.Close, contentDescription = "移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
