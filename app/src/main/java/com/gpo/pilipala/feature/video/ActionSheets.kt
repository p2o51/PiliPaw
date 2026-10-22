package com.gpo.pilipala.feature.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.animation.animateContentSize
import kotlinx.coroutines.launch
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.long
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets

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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("投币", style = MaterialTheme.typography.titleLarge)
            CoinFlip(count, Modifier.padding(vertical = 8.dp))
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
                    val n = count
                    val like = alsoLike && !liked
                    // Slide out first: the parent's onConfirm also drops the sheet (once: a
                    // second tap while it slides must not send the coins again).
                    if (!confirmed) {
                        confirmed = true
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onConfirm(n, like) }
                    }
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

private val CoinSize = 72.dp

/** The coin's disc inside its 24-unit glyph (radius 9.25), plus a margin of the sheet's colour. */
private val CoinRim = CoinSize * (18.5f / 24f) + 6.dp

/** How far the second coin sits from the first once stacked. */
private val CoinStackX = 26.dp
private val CoinStackY = 10.dp

/**
 * The coins about to be given, purely a picture of [count]: every switch flips the coin
 * over in 3D (always the same way round, hopping a little as it turns edge-on), and for
 * 2 a second coin turns in and lands behind it, up and to the right.
 */
@Composable
private fun CoinFlip(count: Int, modifier: Modifier = Modifier) {
    val flipSpec = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Expressive)
    val flip = remember { Animatable(0f) }
    // [half turns so far, the count they were made for]
    val turns = remember { intArrayOf(0, count) }
    LaunchedEffect(count) {
        if (count != turns[1]) {
            turns[1] = count
            turns[0]++
        }
        // A switch mid-flip carries on from where the coin is, velocity and all.
        flip.animateTo(turns[0] * 180f, flipSpec)
    }
    val stacked by animateFloatAsState(
        if (count >= 2) 1f else 0f,
        YoinMotion.defaultSpatialSpec(YoinMotionRole.Expressive),
        label = "coinStack",
    )
    val tint = MaterialTheme.colorScheme.tertiary
    val rim = BottomSheetDefaults.ContainerColor
    Box(modifier.fillMaxWidth().height(CoinSize + 32.dp), contentAlignment = Alignment.Center) {
        CoinFace(
            tint = tint,
            rim = rim,
            modifier = Modifier.graphicsLayer {
                val s = stacked
                translationX = (CoinStackX * 0.5f).toPx() * s
                translationY = -CoinStackY.toPx() * s
                rotationY = (1f - s) * -150f
                cameraDistance = 12f * density
                alpha = s.coerceIn(0f, 1f)
                scaleX = 0.9f
                scaleY = 0.9f
            },
        )
        CoinFace(
            tint = tint,
            rim = rim,
            modifier = Modifier.graphicsLayer {
                val phase = flip.value / 180f
                // 0 at rest (a face to the front), 1 edge-on.
                val hop = (abs(phase - phase.roundToInt()) * 2f).coerceIn(0f, 1f)
                val s = stacked
                translationX = -(CoinStackX * 0.5f).toPx() * s
                translationY = -hop * 14.dp.toPx() + (CoinStackY * 0.4f).toPx() * s
                rotationY = flip.value
                cameraDistance = 12f * density
                val grow = 1f + 0.08f * hop
                scaleX = grow
                scaleY = grow
            },
        )
    }
}

/** One coin: the filled coin glyph on a disc of the sheet's colour that parts it from the coin behind. */
@Composable
private fun CoinFace(tint: Color, rim: Color, modifier: Modifier) {
    Box(modifier.size(CoinSize), contentAlignment = Alignment.Center) {
        Box(Modifier.size(CoinRim).clip(CircleShape).background(rim))
        Icon(PiliSymbols.CoinFilled, contentDescription = null, modifier = Modifier.size(CoinSize), tint = tint)
    }
}

/** 收藏: my folders with checkboxes; only the changed folders are sent. */
@Composable
internal fun FavSheet(
    actions: ArchiveActions,
    onDismiss: () -> Unit,
    mixItem: com.gpo.pilipala.yt.feature.library.LibItem? = null,
    /** Signed out: only the Mix lists (no account 收藏夹 to load). */
    accountFolders: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    // Mix ticks, kept until 完成 like the 收藏夹 ones.
    val mixTicks = remember { mutableStateMapOf<String, Boolean>() }
    var folders by remember { mutableStateOf<List<FavFolder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val checked = remember { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(attempt) {
        error = null
        if (!accountFolders) {
            folders = emptyList()
            return@LaunchedEffect
        }
        runCatching { actions.loadFolders() }
            .onSuccess { list ->
                folders = list
                list.forEach { checked[it.id] = it.faved }
            }
            .onFailure { error = it.friendlyMessage() }
    }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        // The 新建收藏夹 row has a text field: the column clears the keyboard too.
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
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
                        if (mixItem != null) {
                            item(key = "mix") { com.gpo.pilipala.feature.sources.MixTickRows(mixItem, mixTicks) }
                        }
                        if (accountFolders) item(key = "new") {
                            NewFolderRow(
                                onCreated = { folder ->
                                    folders = listOf(folder) + folders.orEmpty()
                                    checked[folder.id] = true
                                },
                            )
                        }
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
                    mixItem?.let { com.gpo.pilipala.feature.sources.applyMixTicks(it, mixTicks, add) }
                    if (add.isEmpty() && del.isEmpty()) close() else actions.saveFav(add, del, onDone = close)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
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
                    // Slide out first: the parent's onConfirm also drops the sheet (once).
                    if (!confirmed) {
                        confirmed = true
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onConfirm() }
                    }
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

/** "新建收藏夹" row: expands in place into a name field + 私密 switch; the new folder comes back checked. */
@Composable
private fun NewFolderRow(onCreated: (FavFolder) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val toast = com.gpo.pilipala.ui.components.rememberToast()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(YoinContainerShapes.Card)
            .background(if (open) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
            .animateContentSize(YoinMotion.defaultSpatialSpec()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(YoinContainerShapes.ListRow)
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(YoinContainerShapes.ListRow).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(YoinSymbols.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.size(14.dp))
            Text("新建收藏夹", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                androidx.compose.material3.TextField(
                    value = name,
                    onValueChange = { if (it.length <= 20) name = it },
                    placeholder = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = YoinContainerShapes.ListRow,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PiliSymbols.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(10.dp))
                    Text("私密", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = private, onCheckedChange = { private = it })
                    Spacer(Modifier.size(12.dp))
                    Button(
                        onClick = {
                            val title = name.trim()
                            if (title.isEmpty() || saving) return@Button
                            saving = true
                            scope.launch {
                                runCatching {
                                    com.gpo.pilipala.core.net.BiliClient.postData(
                                        "/x/v3/fav/folder/add",
                                        mapOf("title" to title, "intro" to "", "privacy" to if (private) 1 else 0, "cover" to ""),
                                    ).asObj.long("id")
                                }.onSuccess { id ->
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onCreated(FavFolder(id = id, title = title, count = 0, faved = false, isDefault = false, isPrivate = private))
                                    name = ""
                                    private = false
                                    open = false
                                }.onFailure { toast(it.friendlyMessage()) }
                                saving = false
                            }
                        },
                        enabled = name.isNotBlank() && !saving,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text("创建")
                    }
                }
            }
        }
    }
}
