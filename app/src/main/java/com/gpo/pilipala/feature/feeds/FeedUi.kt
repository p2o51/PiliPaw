package com.gpo.pilipala.feature.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.pageSharedSource
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols

/** "关键词" / "UP主": the kind of a source, as a quiet second line. */
internal val FeedSource.kindLabel: String
    get() = when (this) {
        is FeedSource.Keyword -> "关键词"
        is FeedSource.Up -> "UP主"
    }

/**
 * Filled field (Yoin: color block, no outline, no indicator line) on
 * surfaceContainerHigh with continuous card corners.
 */
@Composable
internal fun FeedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingIcon = leading?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) } },
        trailingIcon = trailing,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = YoinContainerShapes.Card,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** Leading mark of a source: the UP's avatar, or a search glyph on a tonal disc. */
@Composable
internal fun SourceMark(source: FeedSource, size: Dp = 36.dp) {
    when (source) {
        is FeedSource.Up -> Avatar(source.face, size)
        is FeedSource.Keyword -> Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                YoinSymbols.Search,
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * One source of the feed being edited, as a large tonal chip: mark, label and
 * kind, a connected 综合 / 最新 pair for keywords, and a remove button.
 */
@Composable
internal fun SourceRow(
    source: FeedSource,
    onOrder: (KeywordOrder) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(YoinContainerShapes.ListRow)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceMark(source)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                source.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                source.kindLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (source is FeedSource.Keyword) {
            Spacer(Modifier.width(8.dp))
            ConnectedToggles(
                options = KeywordOrder.entries,
                selected = source.order,
                label = { it.label },
                onSelect = onOrder,
                compact = true,
            )
        }
        IconButton(onClick = onRemove, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Close, contentDescription = "移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Single choice as connected M3 Expressive ToggleButtons. [fill] spreads the
 * buttons over the row; [compact] is the 32dp variant used inside rows.
 */
@Composable
internal fun <T> ConnectedToggles(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    compact: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, option ->
            val checked = option == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = {
                    if (!checked) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(option)
                    }
                },
                modifier = Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .then(if (compact) Modifier.height(32.dp) else Modifier.heightIn(min = 44.dp)),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 16.dp),
            ) {
                Text(
                    label(option),
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A row inside a sheet's action block (icon, label, optional trailing). */
@Composable
internal fun FeedSheetRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                icon != null -> Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) tint else tint.copy(alpha = 0.38f))
            }
            if (leading != null || icon != null) Spacer(Modifier.width(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) labelColor else labelColor.copy(alpha = 0.38f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    }
}

/** A number and its label as two weights ("3" "来源"), never a joined string. */
@Composable
internal fun CountLabel(count: Int, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Section label in the editor and sheets, with an optional quiet trailing value. */
@Composable
internal fun FeedSectionTitle(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!trailing.isNullOrEmpty()) {
            Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Gap between blocks inside a sheet. */
internal val SheetGap = 12.dp

@Composable
internal fun SheetSpacer() = Spacer(Modifier.height(SheetGap))

private val StripChipShape = ContinuousRoundedCornerShape(12.dp)

/**
 * A feed's sources as a row of tonal chips (a keyword opens its search, an UP
 * its space) and a trailing 编辑 chip. Bleeds to the page edges; pass the
 * page margin as [contentPadding].
 */
@Composable
internal fun FeedSourceStrip(
    feed: Feed,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val nav = LocalNavigator.current
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(feed.sources, key = { it.key }) { source ->
            val route = when (source) {
                is FeedSource.Keyword -> Route.SearchResult(source.term)
                is FeedSource.Up -> Route.Member(source.mid, source.face, source.name)
            }
            Row(
                Modifier
                    .animateItem(fadeInSpec = YoinMotion.defaultEffectsSpec(), placementSpec = YoinMotion.defaultSpatialSpec(), fadeOutSpec = YoinMotion.fastEffectsSpec())
                    .pageSharedSource(route)
                    .height(36.dp)
                    .clip(StripChipShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { nav.push(route) }
                    .padding(start = 6.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceMark(source, 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    source.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
        }
        item(key = "edit") {
            Row(
                Modifier
                    .animateItem(fadeInSpec = YoinMotion.defaultEffectsSpec(), placementSpec = YoinMotion.defaultSpatialSpec(), fadeOutSpec = YoinMotion.fastEffectsSpec())
                    .height(36.dp)
                    .clip(StripChipShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { nav.push(Route.FeedEditor(feedId = feed.id)) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(YoinSymbols.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(6.dp))
                Text("编辑", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
