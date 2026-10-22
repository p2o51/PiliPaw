package com.gpo.pilipala.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets

// Settings' visual system, after Yoin (which follows Pixel Settings on Android 16
// Expressive): one flat page colour, rows lifted onto the brighter surface, each row its
// own segment with M3 Expressive's 2dp gap (no dividers), 20dp outer / 4dp inner corners,
// plain monochrome line icons, values as quiet trailing labels.

internal val LocalSettingsRowShape = staticCompositionLocalOf<Shape> { RoundedCornerShape(20.dp) }

private val SegmentOuter = 20.dp
private val SegmentInner = 4.dp
private val SegmentGap = 2.dp
private val SelectedRowShape = RoundedCornerShape(28.dp)

private val RowMinHeight = 72.dp
private val RowIconGap = 12.dp

/** Where a row's text starts: 16dp inset + 40dp icon column + the gap. */
internal val SettingsRowTextInset = 16.dp + 40.dp + RowIconGap

internal fun segmentShape(index: Int, count: Int): Shape {
    val top = if (index == 0) SegmentOuter else SegmentInner
    val bottom = if (index == count - 1) SegmentOuter else SegmentInner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

/** Flat page colour (Pixel / Yoin: page = surfaceContainer, rows = surfaceBright). */
@Composable
internal fun settingsPageColor(): Color = MaterialTheme.colorScheme.surfaceContainer

@Composable
internal fun settingsRowColor(): Color = MaterialTheme.colorScheme.surfaceBright

// ── Page scaffold ───────────────────────────────────────────────────────────

/** Yoin's back affordance: the arrow on a tonal circle. */
@Composable
internal fun SettingsBackButton(onClick: (() -> Unit)? = null) {
    val nav = LocalNavigator.current
    FilledTonalIconButton(
        onClick = { onClick?.invoke() ?: nav.pop() },
        modifier = Modifier.padding(start = 8.dp, end = 8.dp).size(40.dp),
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(YoinSymbols.Back, contentDescription = "返回", modifier = Modifier.size(22.dp))
    }
}

/**
 * A settings page: flat page colour, LargeFlexibleTopAppBar big title that collapses
 * into the bar, and a scrolling column of groups (28dp apart) at reading width.
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    pageColor: Color = settingsPageColor(),
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val page by animateColorAsState(pageColor, YoinMotion.defaultEffectsSpec(), label = "settingsPage")
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = page,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (showBack) SettingsBackButton() },
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = page,
                    scrolledContainerColor = page,
                ),
            )
        },
    ) { inner ->
        val dir = LocalLayoutDirection.current
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = inner.calculateTopPadding(),
                        start = inner.calculateStartPadding(dir),
                        end = inner.calculateEndPadding(dir),
                    )
                    // Groups sink under the tide line at the bar instead of being cut there.
                    .seamViewport(scroll, pageColor = page)
                    .verticalScroll(scroll)
                    .readingWidth(760.dp)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp + inner.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                content = content,
            )
            overlay()
        }
    }
}

// ── Groups / segments ───────────────────────────────────────────────────────

@DslMarker
internal annotation class SettingsDsl

@SettingsDsl
internal class SettingsGroupScope {
    internal class Item(val key: Any, val paintsOwn: Boolean, val content: @Composable () -> Unit)

    internal val items = mutableListOf<Item>()

    fun item(key: Any? = null, paintsOwnSegment: Boolean = false, content: @Composable () -> Unit) {
        items += Item(key ?: "i${items.size}", paintsOwnSegment, content)
    }
}

/** A labelled group: small primary label, then its rows as segments. */
@Composable
internal fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    rowColor: Color = settingsRowColor(),
    content: SettingsGroupScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) SettingsGroupLabel(title, trailingLabel = trailingLabel)
        SettingsSegments(rowColor = rowColor, content = content)
    }
}

@Composable
internal fun SettingsGroupLabel(title: String, modifier: Modifier = Modifier, trailingLabel: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp).seamText(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailingLabel != null) {
            Text(trailingLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Rows as segments; when a row's position changes its corners settle on the spatial spring. */
@Composable
internal fun SettingsSegments(
    modifier: Modifier = Modifier,
    rowColor: Color = settingsRowColor(),
    content: SettingsGroupScope.() -> Unit,
) {
    val items = SettingsGroupScope().apply(content).items
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
        items.forEachIndexed { index, item ->
            key(item.key) {
                val shape = animateShapeAsState(remember(index, items.size) { segmentShape(index, items.size) })
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape,
                    color = if (item.paintsOwn) Color.Transparent else rowColor,
                ) {
                    CompositionLocalProvider(LocalSettingsRowShape provides shape, content = item.content)
                }
            }
        }
    }
}

// ── Rows ────────────────────────────────────────────────────────────────────

@Composable
internal fun SettingsRowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** One row: icon, title over an optional summary, optional trailing content. */
@Composable
internal fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, YoinMotion.defaultEffectsSpec(), label = "rowAlpha")
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .alpha(alpha)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(RowIconGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                icon != null -> SettingsRowIcon(icon)
            }
            Column(Modifier.weight(1f).seamText(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
                if (!summary.isNullOrEmpty()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            shape = LocalSettingsRowShape.current,
            color = Color.Transparent,
        ) { body() }
    } else {
        Box(modifier.fillMaxWidth()) { body() }
    }
}

/** Quiet trailing value label (Pixel: the current value sits right, in the variant colour). */
@Composable
internal fun ValueLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * A navigation row: icon, title, summary, chevron. In a two-pane list pass
 * [selectable] (and `paintsOwnSegment = true` on its item) so the open row can detach.
 */
@Composable
internal fun SettingsLinkItem(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    value: String? = null,
    selectable: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    SelectableRowSurface(selected = selected, unselectedColor = if (selectable) settingsRowColor() else Color.Transparent) {
        SettingsItem(
            title = title,
            icon = icon,
            summary = summary,
            onClick = onClick,
            trailing = {
                if (value != null) ValueLabel(value)
                Icon(YoinSymbols.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            },
        )
    }
}

/**
 * A row that can be "open" in a two-pane list: selected, it detaches from its
 * neighbours (all corners round) and takes the secondary container.
 */
@Composable
internal fun SelectableRowSurface(
    selected: Boolean,
    unselectedColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val fraction by animateFloatAsState(if (selected) 1f else 0f, YoinMotion.defaultSpatialSpec(), label = "rowSelected")
    val base = LocalSettingsRowShape.current
    val shape = when (fraction) {
        0f -> base
        1f -> SelectedRowShape
        else -> LerpShape(base, SelectedRowShape, fraction)
    }
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else unselectedColor,
        YoinMotion.defaultEffectsSpec(),
        label = "rowSelectedColor",
    )
    Surface(shape = shape, color = color, modifier = Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalSettingsRowShape provides shape, content = content)
    }
}

/** Switch row: the whole row toggles; the switch thumb carries a check / close glyph. */
@Composable
internal fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, YoinMotion.defaultEffectsSpec(), label = "switchAlpha")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onCheckedChange(it)
                },
            )
            .heightIn(min = RowMinHeight)
            .alpha(alpha)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(RowIconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SettingsRowIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (!summary.isNullOrEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ThumbSwitch(checked = checked, enabled = enabled)
    }
}

@Composable
internal fun ThumbSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: ((Boolean) -> Unit)? = null) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = {
            Icon(
                if (checked) YoinSymbols.Check else YoinSymbols.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

// ── Single choice ───────────────────────────────────────────────────────────

internal data class Choice<T>(val value: T, val label: String, val icon: ImageVector? = null)

/**
 * Connected ToggleButtons (M3 Expressive button group) under a row title — for 2–4
 * options that read best side by side.
 */
@Composable
internal fun <T> SettingsToggleItem(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, YoinMotion.defaultEffectsSpec(), label = "toggleAlpha")
    Column(
        Modifier.fillMaxWidth().alpha(alpha).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(RowIconGap), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) SettingsRowIcon(icon)
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        ConnectedToggleRow(
            choices = choices,
            selected = selected,
            enabled = enabled,
            onSelect = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onSelect(it)
            },
        )
    }
}

@Composable
internal fun <T> ConnectedToggleRow(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        choices.forEachIndexed { index, choice ->
            ToggleButton(
                checked = choice.value == selected,
                onCheckedChange = { if (choice.value != selected) onSelect(choice.value) },
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            ) {
                if (choice.icon != null) {
                    Icon(choice.icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                }
                Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Row whose value is picked in a ModalBottomSheet; the current value is a quiet trailing label. */
@Composable
internal fun <T> SettingsChoiceItem(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
    fallbackLabel: String = "",
) {
    var open by remember { mutableStateOf(false) }
    SettingsItem(
        title = title,
        icon = icon,
        summary = summary,
        enabled = enabled,
        onClick = { open = true },
        trailing = { ValueLabel(choices.firstOrNull { it.value == selected }?.label ?: fallbackLabel) },
    )
    if (open) {
        ChoiceSheet(title = title, choices = choices, selected = selected, onSelect = onSelect, onDismiss = { open = false })
    }
}

/**
 * The choices as a sheet of rows; picking one closes it. [selected] may match none (a pick
 * with no current value). [summary] and [leading] dress a row (a line under it, an avatar
 * in place of the icon).
 */
@Composable
internal fun <T> ChoiceSheet(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    summary: (T) -> String? = { null },
    leading: (@Composable (T) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = sheetBottomPadding(16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            SettingsSegments(rowColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                choices.forEach { choice ->
                    item(key = choice.value ?: "null", paintsOwnSegment = true) {
                        val isSelected = choice.value == selected
                        SelectableRowSurface(selected = isSelected, unselectedColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            SettingsItem(
                                title = choice.label,
                                icon = choice.icon,
                                summary = summary(choice.value),
                                leading = leading?.let { { it(choice.value) } },
                                titleColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onSelect(choice.value)
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                    }
                                },
                                trailing = {
                                    if (isSelected) {
                                        Icon(YoinSymbols.Check, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Slider ──────────────────────────────────────────────────────────────────

/** Title + live value label over an M3 Expressive slider. */
@Composable
internal fun SettingsSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    icon: ImageVector? = null,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    var local by remember { mutableStateOf<Float?>(null) }
    val shown = local ?: value
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, YoinMotion.defaultEffectsSpec(), label = "sliderAlpha")
    Column(
        Modifier.fillMaxWidth().alpha(alpha).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(RowIconGap), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) SettingsRowIcon(icon)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                label(shown),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = shown,
            onValueChange = {
                if (steps > 0 && it != shown) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                local = it
            },
            onValueChangeFinished = {
                local?.let(onValueChange)
                local = null
            },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.padding(start = if (icon != null) 52.dp else 0.dp),
        )
    }
}

// ── Shapes ──────────────────────────────────────────────────────────────────

/** [target], but when it changes the corners travel there on the spatial spring. */
@Composable
internal fun animateShapeAsState(target: Shape): Shape {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val spec = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        from = if (progress.value >= 1f) to else LerpShape(from, to, progress.value)
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    val value = progress.value
    return if (value >= 1f) to else LerpShape(from, to, value)
}

internal class LerpShape(private val start: Shape, private val end: Shape, private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val a = start.createOutline(size, layoutDirection, density)
        val b = end.createOutline(size, layoutDirection, density)
        if (a is Outline.Rounded && b is Outline.Rounded) {
            val ra = a.roundRect
            val rb = b.roundRect
            return Outline.Rounded(
                RoundRect(
                    left = rb.left, top = rb.top, right = rb.right, bottom = rb.bottom,
                    topLeftCornerRadius = lerp(ra.topLeftCornerRadius, rb.topLeftCornerRadius, fraction),
                    topRightCornerRadius = lerp(ra.topRightCornerRadius, rb.topRightCornerRadius, fraction),
                    bottomRightCornerRadius = lerp(ra.bottomRightCornerRadius, rb.bottomRightCornerRadius, fraction),
                    bottomLeftCornerRadius = lerp(ra.bottomLeftCornerRadius, rb.bottomLeftCornerRadius, fraction),
                ),
            )
        }
        return if (fraction < 0.5f) a else b
    }
}

/** A [Morph] at [progress], scaled from the unit square to the layout bounds. */
internal class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val matrix = Matrix()
        matrix.scale(size.width, size.height, 1f)
        val path = morph.toPath(progress).asComposePath()
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

/** Leading avatar-like glyph tile used by the account row when signed out. */
@Composable
internal fun GlyphTile(icon: ImageVector, shape: Shape, size: Dp = 56.dp, container: Color = MaterialTheme.colorScheme.primaryContainer, content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Box(Modifier.size(size).background(container, shape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.46f))
    }
}
