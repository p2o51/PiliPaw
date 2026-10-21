package com.gpo.pilipala.feature.download

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.feature.library.ConfirmDialog
import com.gpo.pilipala.feature.library.ItemActionSheet
import com.gpo.pilipala.feature.library.LibraryScaffold
import com.gpo.pilipala.feature.library.RowGridMinCell
import com.gpo.pilipala.feature.library.SheetAction
import com.gpo.pilipala.feature.library.librarySeam
import com.gpo.pilipala.feature.library.sectionHeader
import com.gpo.pilipala.feature.player.BiliVideoPlayer
import com.gpo.pilipala.feature.player.rememberBiliPlayerState
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ExpressivePageBackground
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.CoverTheme
import com.gpo.pilipala.ui.theme.LightBarIcons
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinShapeTokens
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    return when {
        b >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        b >= 1024.0 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        b >= 1024.0 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "${bytes.coerceAtLeast(0)} B"
    }
}

private val CoverShape = ContinuousRoundedCornerShape(12.dp)

/** The page a cached item opens on: its video / bangumi page (online extras). */
private fun DownloadItem.detailRoute(): Route =
    if (isPgc) Route.Bangumi(seasonId = seasonId, epId = epId, cover = cover)
    else Route.Video(bvid = bvid, cid = cid, aid = aid, cover = cover, title = title)

// ── 离线缓存 ────────────────────────────────────────────────────────────────

@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        // Writes the index: a full disk is no reason to crash the page.
        try {
            DownloadStore.reconcile(context.applicationContext)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    val items by DownloadStore.items.collectAsState()
    val progress by DownloadStore.progress.collectAsState()
    var sheet by remember { mutableStateOf<DownloadItem?>(null) }
    var confirm by remember { mutableStateOf<DownloadItem?>(null) }
    val gridState = rememberLazyGridState()
    val app = context.applicationContext

    val active = items.filter { it.state != DownloadState.Done }
    val done = items.filter { it.state == DownloadState.Done }
    val toggle: (DownloadItem) -> Unit = { item ->
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        DownloadStore.scope.launch {
            if (item.active) DownloadStore.pause(app, item.id) else DownloadStore.resume(app, item.id)
        }
    }
    val open: (DownloadItem) -> Unit = { item ->
        if (item.state == DownloadState.Done) nav.push(Route.OfflinePlayer(item.id)) else toggle(item)
    }

    LibraryScaffold(
        title = "离线缓存",
        subtitle = if (items.isNotEmpty()) "${items.size}个视频" else null,
    ) { padding ->
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val dir = LocalLayoutDirection.current
        val listPadding = PaddingValues(
            start = padding.calculateStartPadding(dir),
            end = padding.calculateEndPadding(dir),
            top = padding.calculateTopPadding() + 8.dp,
            bottom = navBottom + 24.dp,
        )
        val enter = YoinMotion.defaultEffectsSpec<Float>()
        val exit = YoinMotion.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = items.isEmpty(),
            transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
            label = "downloadsBody",
        ) { empty ->
            if (empty) {
                EmptyState("暂无缓存", Modifier.padding(padding), icon = YoinSymbols.Download)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(RowGridMinCell),
                    state = gridState,
                    modifier = Modifier.librarySeam(gridState, listPadding),
                    contentPadding = listPadding,
                ) {
                    if (active.isNotEmpty()) {
                        sectionHeader("__active", "缓存中")
                        items(active, key = { it.id }) { item ->
                            DownloadRow(
                                item = item,
                                live = progress[item.id],
                                onClick = { open(item) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    sheet = item
                                },
                                onToggle = { toggle(item) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    if (done.isNotEmpty()) {
                        sectionHeader("__done", "已缓存")
                        items(done, key = { it.id }) { item ->
                            DownloadRow(
                                item = item,
                                live = null,
                                onClick = { open(item) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    sheet = item
                                },
                                onToggle = {},
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    sheet?.let { item ->
        ItemActionSheet(
            title = item.title,
            cover = item.cover,
            actions = listOfNotNull(
                if (item.state == DownloadState.Done) SheetAction("播放", YoinSymbols.PlayArrow) { nav.push(Route.OfflinePlayer(item.id)) } else null,
                when {
                    item.state == DownloadState.Done -> null
                    item.active -> SheetAction("暂停", YoinSymbols.Pause) { toggle(item) }
                    else -> SheetAction("继续", YoinSymbols.PlayArrow) { toggle(item) }
                },
                SheetAction(if (item.isPgc) "剧集详情" else "视频详情", YoinSymbols.Launch) { nav.push(item.detailRoute()) },
                SheetAction("删除", YoinSymbols.Delete, destructive = true) { confirm = item },
            ),
            onDismiss = { sheet = null },
        )
    }
    confirm?.let { item ->
        ConfirmDialog(
            title = "删除缓存",
            text = item.title,
            confirmLabel = "删除",
            onConfirm = { DownloadStore.scope.launch { DownloadStore.delete(app, item.id) } },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    live: LiveProgress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The saved cover first (offline), the CDN one until it exists.
    val cover by produceState<Any?>(Images.normalize(item.cover), item.id, item.state) {
        val f = withContext(Dispatchers.IO) { DownloadStore.coverFile(context, item.id).takeIf { it.exists() } }
        if (f != null) value = f
    }
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(148.dp)
                .aspectRatio(16f / 9f)
                .clip(CoverShape)
                .background(scheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            if (item.durationMs > 0) {
                Surface(
                    shape = YoinShapeTokens.Full,
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                ) {
                    Text(
                        Fmt.durationMs(item.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.title.ifBlank { "视频" },
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.partTitle.isNotBlank()) {
                Text(
                    item.partTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.qualityLabel.isNotBlank()) {
                    Text(item.qualityLabel, style = MaterialTheme.typography.labelMedium, color = scheme.primary, maxLines = 1)
                }
                val total = live?.total?.takeIf { it > 0 } ?: item.totalBytes
                if (total > 0) {
                    Text(formatBytes(total), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                if (item.state != DownloadState.Done) {
                    val status = when (item.state) {
                        DownloadState.Queued -> "等待中"
                        DownloadState.Downloading -> live?.bytesPerSecond?.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" }
                        DownloadState.Muxing -> "合并中"
                        DownloadState.Paused -> "已暂停"
                        DownloadState.Failed -> item.error.ifBlank { "缓存失败" }
                        DownloadState.Done -> null
                    }
                    if (status != null) {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (item.state == DownloadState.Failed) scheme.error else scheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = item.state != DownloadState.Done,
                enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
                exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.fastSpatialSpec()),
            ) {
                val target = when {
                    item.state == DownloadState.Muxing -> 1f
                    live != null && live.total > 0 -> live.done.toFloat() / live.total
                    item.totalBytes > 0 -> item.doneBytes.toFloat() / item.totalBytes
                    else -> 0f
                }.coerceIn(0f, 1f)
                val shown by animateFloatAsState(target, YoinMotion.defaultSpatialSpec(), label = "downloadProgress")
                val moving = item.state == DownloadState.Downloading || item.state == DownloadState.Muxing
                LinearWavyProgressIndicator(
                    progress = { shown },
                    amplitude = { if (moving) 1f else 0f },
                    color = if (item.state == DownloadState.Failed) scheme.error else scheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        if (item.state != DownloadState.Done) {
            Spacer(Modifier.width(4.dp))
            val enter = YoinMotion.defaultEffectsSpec<Float>()
            val exit = YoinMotion.fastEffectsSpec<Float>()
            IconButton(
                onClick = onToggle,
                enabled = item.state != DownloadState.Muxing,
                shapes = IconButtonDefaults.shapes(),
            ) {
                AnimatedContent(
                    targetState = item.active,
                    transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                    label = "downloadToggle",
                ) { running ->
                    Icon(
                        when {
                            running -> YoinSymbols.Pause
                            item.state == DownloadState.Failed -> YoinSymbols.Refresh
                            else -> YoinSymbols.PlayArrow
                        },
                        contentDescription = if (running) "暂停" else "继续",
                    )
                }
            }
        }
    }
}

// ── Offline player ──────────────────────────────────────────────────────────

/**
 * A finished cache played with the app's own player (the controller swaps the network
 * streams for the file, see [LocalMedia]), on a page that needs nothing online: the video
 * page itself waits for the view API before it can show anything useful offline, and a
 * bangumi page cannot even resolve its episode. Online extras are one tap away (视频详情).
 */
@Composable
fun OfflinePlayerScreen(route: Route.OfflinePlayer) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    LaunchedEffect(Unit) { DownloadStore.init(context) }
    val items by DownloadStore.items.collectAsState()
    val item = items.firstOrNull { it.id == route.id }
    val ready = item?.state == DownloadState.Done
    val target = remember(item?.id, ready) { item?.takeIf { ready }?.playTarget() }
    val state = rememberBiliPlayerState()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state, item?.owner) { state.artist = item?.owner.orEmpty() }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    val expanded = widthClass() == WidthClass.Expanded
    val dark = LocalPiliDark.current
    LightBarIcons(enabled = fullscreen || !expanded || dark, status = true)

    val player: @Composable (Modifier) -> Unit = { m ->
        BiliVideoPlayer(
            target = target,
            state = state,
            fullscreen = fullscreen,
            onFullscreenChange = { fullscreen = it },
            onBack = { if (fullscreen) fullscreen = false else nav.pop() },
            modifier = m,
        )
    }

    CoverTheme(item?.cover) {
        ExpressivePageBackground(Modifier.fillMaxSize(), belowPlayer = !expanded, animated = !fullscreen) {
            when {
                fullscreen -> Box(Modifier.fillMaxSize().background(Color.Black)) { player(Modifier.fillMaxSize()) }
                expanded -> Row(
                    Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp)) {
                        Box(Modifier.fillMaxWidth().clip(YoinContainerShapes.Card)) { player(Modifier.fillMaxWidth()) }
                    }
                    OfflineInfo(
                        item = item,
                        onDetail = { item?.let { nav.push(it.detailRoute()) } },
                        modifier = Modifier
                            .width(380.dp)
                            .fillMaxHeight()
                            .padding(top = 8.dp)
                            .clip(YoinContainerShapes.Panel)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.fillMaxWidth().background(Color.Black).windowInsetsTopHeight(WindowInsets.statusBars))
                    player(Modifier.fillMaxWidth())
                    OfflineInfo(
                        item = item,
                        onDetail = { item?.let { nav.push(it.detailRoute()) } },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineInfo(item: DownloadItem?, onDetail: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (item == null) {
            Text("缓存已删除", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
            return@Column
        }
        Text(item.title, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
        if (item.partTitle.isNotBlank()) {
            Text(item.partTitle, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
        }
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.owner.isNotBlank()) {
                Text(item.owner, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            }
            if (item.qualityLabel.isNotBlank()) {
                Text(item.qualityLabel, style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
            if (item.totalBytes > 0) {
                Text(formatBytes(item.totalBytes), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onDetail) {
            Icon(YoinSymbols.Launch, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (item.isPgc) "剧集详情" else "视频详情")
        }
    }
}
