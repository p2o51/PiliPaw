package com.gpo.pilipala.feature.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols

/** 投币: 1 or 2 coins (capped by [maxCoins]), optional like. */
@Composable
internal fun CoinSheet(
    maxCoins: Int,
    liked: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (multiply: Int, alsoLike: Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var count by remember { mutableIntStateOf(maxCoins.coerceIn(1, 2)) }
    var alsoLike by remember { mutableStateOf(!liked) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("投币", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                listOf(1, 2).forEachIndexed { i, n ->
                    val enabled = n <= maxCoins
                    ToggleButton(
                        checked = count == n,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            count = n
                        },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).height(88.dp),
                        shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row {
                                repeat(n) {
                                    Icon(if (count == n) PiliSymbols.CoinFilled else PiliSymbols.Coin, null, Modifier.size(26.dp))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("$n 枚", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(YoinContainerShapes.Card)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = !liked) { alsoLike = !alsoLike }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PiliSymbols.ThumbUp, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Text("同时点赞", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = alsoLike, onCheckedChange = { alsoLike = it }, enabled = !liked)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onConfirm(count, alsoLike && !liked)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = maxCoins > 0,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("投币", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** 收藏: my folders with checkboxes; only the changed folders are sent. */
@Composable
internal fun FavSheet(actions: ArchiveActions, onDismiss: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var folders by remember { mutableStateOf<List<FavFolder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val checked = remember { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(attempt) {
        error = null
        runCatching { actions.loadFolders() }
            .onSuccess { list ->
                folders = list
                list.forEach { checked[it.id] = it.faved }
            }
            .onFailure { error = it.friendlyMessage() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text("收藏到", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            val list = folders
            val effects = YoinMotion.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = when {
                    list != null -> 2
                    error != null -> 1
                    else -> 0
                },
                transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
                label = "favSheet",
            ) { state ->
                when (state) {
                    0 -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    1 -> Box(Modifier.fillMaxWidth().height(240.dp)) { ErrorState(error.orEmpty(), onRetry = { attempt++ }) }
                    else -> LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        items(list.orEmpty(), key = { it.id }) { f ->
                            val on = checked[f.id] == true
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(YoinContainerShapes.ListRow)
                                    .clickable {
                                        haptic.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                                        checked[f.id] = !on
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(YoinContainerShapes.ListRow)
                                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (f.isPrivate) PiliSymbols.Lock else YoinSymbols.Folder,
                                        null,
                                        Modifier.size(20.dp),
                                        tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.size(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.SemiBold else null)
                                    val delta = (if (on) 1 else 0) - (if (f.faved) 1 else 0)
                                    Text(
                                        "${f.count + delta} 个内容",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Checkbox(checked = on, onCheckedChange = { checked[f.id] = it })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val all = folders.orEmpty()
                    val add = all.filter { checked[it.id] == true && !it.faved }.map { it.id }
                    val del = all.filter { checked[it.id] != true && it.faved }.map { it.id }
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    actions.saveFav(add, del, onDone = onDismiss)
                },
                enabled = folders != null && !actions.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(52.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("完成", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * 三连 spends coins, which cannot be undone — so the long press only opens this
 * sheet; the request goes out from the confirm button.
 */
@Composable
internal fun TripleSheet(
    liked: Boolean,
    coined: Int,
    favored: Boolean,
    coinsLeft: Double?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("三连", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier.fillMaxWidth().clip(YoinContainerShapes.Card).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                TripleRow(PiliSymbols.ThumbUp, "点赞", if (liked) "已赞" else null)
                TripleRow(PiliSymbols.Coin, "投 2 枚硬币", when {
                    coined > 0 -> "已投 $coined 枚"
                    coinsLeft != null -> "余额 ${if (coinsLeft % 1.0 == 0.0) coinsLeft.toLong().toString() else coinsLeft.toString()}"
                    else -> null
                })
                TripleRow(com.gpo.yoin.symbols.YoinSymbols.Star, "收藏到默认收藏夹", if (favored) "已收藏" else null)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onConfirm()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("三连", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun TripleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, state: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (state != null) {
            Text(state, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
