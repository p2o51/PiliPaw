package com.gpo.pilipala.feature.videomenu

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.feature.download.CachePage
import com.gpo.pilipala.feature.feeds.FeedSourceSheet
import com.gpo.pilipala.feature.home.FeedActions
import com.gpo.pilipala.feature.player.PlayTarget
import com.gpo.pilipala.feature.player.QueueItem
import com.gpo.pilipala.feature.player.resolveFirstCid
import com.gpo.pilipala.feature.video.shareText
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A list's own entry in the video card menu (从这里播放, 取消收藏, 删除记录…). Plain ones sit
 * with the play rows; [destructive] ones join 不感兴趣 / 拉黑 at the end, in the error colour.
 */
@Immutable
internal data class VideoMenuAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Whether a card has the video card menu (a plain UGC video, not a live / bangumi / article target). */
internal val VideoItem.isPlainVideo: Boolean
    get() = target == null && (bvid.isNotEmpty() || aid > 0)

/** `https://www.bilibili.com/video/BV…` for a plain video, else "". */
internal fun VideoItem.webUrl(): String {
    val bv = bvid.ifEmpty { if (aid > 0) IdUtils.av2bv(aid) else "" }
    return if (bv.isEmpty()) "" else "https://www.bilibili.com/video/$bv"
}

private enum class MenuPage { Actions, Block, Cache }

private val CoverShape = ContinuousRoundedCornerShape(12.dp)

/**
 * The one long-press menu for a video card, wherever a card can be long-pressed (home 推荐 /
 * 热门 / Feed tabs, 排行榜, search results, related videos, UP uploads, 历史记录, 收藏夹,
 * 稍后再看, 订阅). Rows, most used first, in colour-block groups:
 * 仅听声音 (+ the list's own play rows) › 稍后再看, 缓存, 分享, 复制链接 › 查看 UP 主,
 * 添加到 Feed › 不感兴趣, the list's destructive rows, 拉黑 UP.
 *
 * [onRemove] marks a feed-like list: it brings 不感兴趣 (through [dislike] when the feed can
 * tell the server) and 拉黑 UP, and drops the card afterwards. [watchLater] = false hides
 * 稍后再看 (the 稍后再看 list itself).
 */
@Composable
internal fun VideoCardMenu(
    item: VideoItem,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    dislike: (suspend () -> Unit)? = null,
    extras: List<VideoMenuAction> = emptyList(),
    watchLater: Boolean = true,
    isVideo: Boolean = item.isPlainVideo,
) {
    item.youtubeUrl?.let { yt ->
        YoutubeCardMenu(item, yt, onDismiss, onRemove, extras, watchLater)
        return
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    var page by remember { mutableStateOf(MenuPage.Actions) }
    var feedOpen by remember { mutableStateOf(false) }
    var mixOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val loggedIn = BiliClient.isLoggedIn
    val url = if (isVideo) item.webUrl() else ""
    val enterSpec = YoinMotion.defaultEffectsSpec<Float>()
    val exitSpec = YoinMotion.fastEffectsSpec<Float>()

    // 添加到 Feed hands over to the feed sheet once this one is down.
    if (feedOpen) {
        FeedSourceSheet(upMid = item.ownerMid, upName = item.ownerName, upFace = item.ownerFace, onDismiss = onDismiss)
        return
    }

    // 收藏 likewise, once this sheet is down: the account's 收藏夹 and the Mix lists in one sheet.
    if (mixOpen) {
        val scope2 = rememberCoroutineScope()
        val actions = remember { com.gpo.pilipala.feature.video.ArchiveActions(scope2).also { it.bind(item.aid, item.bvid, 0, 0, 0, 0) } }
        com.gpo.pilipala.feature.video.FavSheet(
            actions,
            onDismiss = onDismiss,
            mixItem = com.gpo.pilipala.feature.sources.MixItems.bili(item.bvid, item.title, item.cover, item.ownerName, item.ownerMid, item.duration),
            accountFolders = loggedIn,
        )
        return
    }

    fun hide(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }

    fun close(then: () -> Unit = {}) = hide {
        onDismiss()
        then()
    }

    fun runWrite(action: suspend () -> Unit, onDone: () -> Unit) {
        busy = true
        scope.launch {
            val result = runCatching { action() }
            busy = false
            result.onSuccess { onDone() }
                .onFailure { if (it is CancellationException) throw it else toast(it.friendlyMessage()) }
        }
    }

    val groups: List<List<MenuRow>> = buildList {
        if (isVideo) {
            add(
                buildList {
                    add(
                        MenuRow(YoinSymbols.Headphones, "仅听声音") {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            close {
                                nav.push(
                                    Route.Video(
                                        bvid = item.bvid,
                                        aid = item.aid,
                                        cid = item.cid,
                                        cover = item.cover,
                                        title = item.title,
                                        progress = item.progress.coerceAtLeast(0),
                                        audioOnly = true,
                                    ),
                                )
                            }
                        },
                    )
                    extras.filterNot { it.destructive }.forEach { a -> add(MenuRow(a.icon, a.label) { close(a.onClick) }) }
                },
            )
            add(
                buildList {
                    if (watchLater && loggedIn) {
                        add(
                            MenuRow(PiliSymbols.WatchLater, "稍后再看") {
                                runWrite({ FeedActions.watchLater(item) }) {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    toast("已添加到稍后再看")
                                    close()
                                }
                            },
                        )
                    }
                    add(MenuRow(YoinSymbols.Star, "收藏") { hide { mixOpen = true } })
                    add(MenuRow(YoinSymbols.Download, "缓存") { page = MenuPage.Cache })
                    if (url.isNotEmpty()) {
                        add(MenuRow(YoinSymbols.Share, "分享") { close { shareText(context, item.title, url) } })
                        add(
                            MenuRow(PiliSymbols.Link, "复制链接") {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText(item.title, url))
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                toast("已复制")
                                close()
                            },
                        )
                    }
                },
            )
        } else {
            extras.filterNot { it.destructive }.takeIf { it.isNotEmpty() }?.let { list ->
                add(list.map { a -> MenuRow(a.icon, a.label) { close(a.onClick) } })
            }
        }
        if (item.ownerMid > 0) {
            add(
                listOf(
                    MenuRow(YoinSymbols.Person, "查看 UP 主") {
                        close { nav.push(Route.Member(item.ownerMid, item.ownerFace, item.ownerName)) }
                    },
                    MenuRow(YoinSymbols.LibraryAdd, "添加到 Feed") { hide { feedOpen = true } },
                ),
            )
        }
        add(
            buildList {
                if (onRemove != null) {
                    add(
                        MenuRow(PiliSymbols.ThumbDown, "不感兴趣") {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            if (dislike == null) close(onRemove) else runWrite(dislike) { close(onRemove) }
                        },
                    )
                }
                extras.filter { it.destructive }.forEach { a -> add(MenuRow(a.icon, a.label, destructive = true) { close(a.onClick) }) }
                if (onRemove != null && loggedIn && item.ownerMid > 0 && item.ownerMid != BiliClient.mid) {
                    add(
                        MenuRow(BlockGlyph, "拉黑 UP", destructive = true) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            page = MenuPage.Block
                        },
                    )
                }
            },
        )
    }.filter { it.isNotEmpty() }

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
                .padding(horizontal = 16.dp)
                .padding(bottom = sheetBottomPadding(16.dp)),
        ) {
            MenuHeader(item)
            Spacer(Modifier.height(16.dp))
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(enterSpec).togetherWith(fadeOut(exitSpec)) },
                label = "videoMenu",
            ) { p ->
                when (p) {
                    MenuPage.Actions -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        groups.forEach { rows -> RowGroup(rows, enabled = !busy) }
                    }
                    MenuPage.Cache -> CacheStep(item, close = { close() }, onFail = { msg ->
                        toast(msg)
                        page = MenuPage.Actions
                    })
                    MenuPage.Block -> BlockConfirm(
                        name = item.ownerName,
                        busy = busy,
                        onCancel = { page = MenuPage.Actions },
                        onConfirm = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            runWrite({ FeedActions.block(item.ownerMid) }) {
                                toast("已拉黑")
                                close { onRemove?.invoke() }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The menu for a YouTube card, in the same groups and blocks: 仅听声音 › 稍后再看, 收藏 (the
 * account's playlists and the Mix lists), 分享, 复制链接 › 查看频道 › 不感兴趣, 不看此频道.
 */
@Composable
private fun YoutubeCardMenu(
    item: VideoItem,
    url: String,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)?,
    extras: List<VideoMenuAction>,
    watchLater: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val ytNav = com.gpo.pilipala.yt.navigation.LocalNavigator.current
    val context = LocalContext.current
    var saveOpen by remember { mutableStateOf(false) }
    val channelUrl = item.youtubeChannelUrl
    val video = remember(item, url) {
        com.gpo.pilipala.yt.core.extractor.VideoItem(
            url = url, title = item.title, thumbnail = item.cover, channelName = item.ownerName, channelUrl = channelUrl.orEmpty(),
            channelAvatar = item.ownerFace, durationSec = item.duration, views = item.views, uploaded = "",
            uploadedAt = item.pubdate * 1000, isLive = false, isShort = false, channelVerified = false,
        )
    }
    // 收藏 hands over to the list sheet once this one is down.
    if (saveOpen) {
        com.gpo.pilipala.feature.sources.SaveToListSheet(com.gpo.pilipala.yt.feature.library.Library.itemOf(video), onDismiss = onDismiss)
        return
    }
    fun hide(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }
    fun close(then: () -> Unit = {}) = hide {
        onDismiss()
        then()
    }
    val groups: List<List<MenuRow>> = buildList {
        add(
            buildList {
                add(
                    MenuRow(YoinSymbols.Headphones, "仅听声音") {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        close {
                            com.gpo.pilipala.yt.feature.player.Player.play(url, video)
                            com.gpo.pilipala.yt.feature.player.Player.changeAudioOnly(true)
                        }
                    },
                )
                extras.filterNot { it.destructive }.forEach { a -> add(MenuRow(a.icon, a.label) { close(a.onClick) }) }
            },
        )
        add(
            buildList {
                if (watchLater) {
                    val inLater = com.gpo.pilipala.yt.feature.library.Library.inWatchLater(url)
                    add(
                        MenuRow(PiliSymbols.WatchLater, if (inLater) "移出稍后再看" else "稍后再看") {
                            val added = com.gpo.pilipala.yt.feature.library.Library.toggleWatchLater(video)
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            toast(if (added) "已添加到稍后再看" else "已移出稍后再看")
                            close()
                        },
                    )
                }
                add(MenuRow(YoinSymbols.Star, "收藏") { hide { saveOpen = true } })
                add(MenuRow(YoinSymbols.Share, "分享") { close { shareText(context, item.title, url) } })
                add(
                    MenuRow(PiliSymbols.Link, "复制链接") {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(item.title, url))
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        toast("已复制")
                        close()
                    },
                )
            },
        )
        if (channelUrl != null) {
            add(
                listOf(
                    MenuRow(YoinSymbols.Person, "查看频道") {
                        close { ytNav.push(com.gpo.pilipala.yt.navigation.Route.Channel(channelUrl, item.ownerName, item.ownerFace)) }
                    },
                ),
            )
        }
        add(
            buildList {
                if (onRemove != null) {
                    add(
                        MenuRow(PiliSymbols.ThumbDown, "不感兴趣") {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            close(onRemove)
                        },
                    )
                }
                extras.filter { it.destructive }.forEach { a -> add(MenuRow(a.icon, a.label, destructive = true) { close(a.onClick) }) }
                if (channelUrl != null) {
                    add(
                        MenuRow(YoinSymbols.VisibilityOff, "不看此频道", destructive = true) {
                            com.gpo.pilipala.yt.feature.mute.Mutes.muteChannel(channelUrl, item.ownerName, item.ownerFace)
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            toast("已屏蔽")
                            close { onRemove?.invoke() }
                        },
                    )
                }
            },
        )
    }.filter { it.isNotEmpty() }
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
                .padding(horizontal = 16.dp)
                .padding(bottom = sheetBottomPadding(16.dp)),
        ) {
            MenuHeader(item)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                groups.forEach { rows -> RowGroup(rows, enabled = true) }
            }
        }
    }
}

private class MenuRow(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun MenuHeader(item: VideoItem) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NetImage(item.cover, Modifier.width(112.dp).aspectRatio(16f / 10f), shape = CoverShape, thumbWidth = 224.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.ownerName.isNotEmpty()) {
                Text(
                    item.ownerName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One group of rows as segmented colour blocks (big outer corners, small inner ones). */
@Composable
private fun RowGroup(rows: List<MenuRow>, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, r ->
            val top = if (i == 0) 20.dp else 4.dp
            val bottom = if (i == rows.lastIndex) 20.dp else 4.dp
            val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
            val tint = if (r.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(enabled = enabled, onClick = r.onClick)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(r.icon, null, Modifier.size(22.dp), tint = if (r.destructive) tint else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.label, style = MaterialTheme.typography.bodyLarge, color = tint)
            }
        }
    }
}

/** 缓存: the first part's cid (resolved when the card lacks it), then the player's 缓存 page. */
@Composable
private fun CacheStep(item: VideoItem, close: () -> Unit, onFail: (String) -> Unit) {
    var cid by remember { mutableLongStateOf(item.cid) }
    LaunchedEffect(item.bvid, item.aid) {
        if (cid > 0) return@LaunchedEffect
        try {
            val c = resolveFirstCid(QueueItem(bvid = item.bvid, aid = item.aid))
            if (c > 0) cid = c else onFail("加载失败")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFail(e.friendlyMessage())
        }
    }
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    AnimatedContent(cid > 0, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "cacheStep") { ready ->
        if (!ready) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
        } else {
            val target = remember(cid) {
                PlayTarget(bvid = item.bvid, aid = item.aid, cid = cid, title = item.title, cover = item.cover)
            }
            CachePage(target, item.ownerName, null, close)
        }
    }
}

@Composable
private fun BlockConfirm(name: String, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("拉黑", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp),
                shapes = ButtonDefaults.shapes(),
            ) { Text("取消", style = MaterialTheme.typography.titleSmall) }
            Button(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp),
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("拉黑", style = MaterialTheme.typography.titleSmall) }
        }
    }
}

/** Circle with a slash (24 viewport, 1.5 stroke). */
private val BlockGlyph: ImageVector by lazy {
    ImageVector.Builder("Block", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 3.25f)
            arcTo(8.75f, 8.75f, 0f, true, true, 11.99f, 3.25f)
            close()
            moveTo(5.8f, 5.8f)
            lineTo(18.2f, 18.2f)
        }
    }.build()
}
