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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.gpo.pilipala.ui.components.SheetEmphasis
import com.gpo.pilipala.ui.components.SheetFinisher
import com.gpo.pilipala.ui.components.SheetGroupButton
import com.gpo.pilipala.ui.components.SheetGroupSpacing
import com.gpo.pilipala.ui.components.SheetIconAction
import com.gpo.pilipala.ui.components.SheetIconRow
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberSheetFinisher
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.ui.components.sheetStagger
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

/**
 * One choice under 不感兴趣 (不想看 UP 主…, or a [feedback] one such as 标题党); [send] tells the
 * feed. A card with any gets a reason step in its menu instead of the plain 不感兴趣.
 */
@Immutable
internal class DeclineReason(val label: String, val feedback: Boolean = false, val send: suspend () -> Unit)

/** Whether a card has the video card menu (a plain UGC video, not a live / bangumi / article target). */
internal val VideoItem.isPlainVideo: Boolean
    get() = target == null && (bvid.isNotEmpty() || aid > 0)

/** `https://www.bilibili.com/video/BV…` for a plain video, else "". */
internal fun VideoItem.webUrl(): String {
    val bv = bvid.ifEmpty { if (aid > 0) IdUtils.av2bv(aid) else "" }
    return if (bv.isEmpty()) "" else "https://www.bilibili.com/video/$bv"
}

private enum class MenuPage { Actions, Block, Cache, Share, Forward, Decline }

private val CoverShape = ContinuousRoundedCornerShape(12.dp)
private val OwnerShape = ContinuousRoundedCornerShape(20.dp)

/**
 * The one long-press menu for a video card, wherever a card can be long-pressed (home 推荐 /
 * 热门 / Feed tabs, 排行榜, search results, related videos, UP uploads, 历史记录, 收藏夹,
 * 稍后再看, 订阅). Groups by M3's button rules, most used first: 稍后再看 / 收藏 / 仅听声音 as a
 * standard button group, round 分享 / 缓存, the UP (查看 UP 主, 加入 Feed, 拉黑 UP), and last,
 * quiet outlined 不感兴趣 and the list's destructive rows.
 *
 * A write that lands (稍后再看, 复制链接) turns its block into a tick in the sheet, then the
 * sheet slides down ([SheetFinisher]).
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
    declineReasons: List<DeclineReason> = emptyList(),
) {
    item.youtubeUrl?.let { yt ->
        YoutubeCardMenu(item, yt, onDismiss, onRemove, extras, watchLater)
        return
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val finisher = rememberSheetFinisher(sheetState, onDismiss)
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

    fun close(then: () -> Unit = {}) = finisher.close(then)

    fun runWrite(action: suspend () -> Unit, onDone: () -> Unit) {
        busy = true
        scope.launch {
            val result = runCatching { action() }
            busy = false
            result.onSuccess { onDone() }
                .onFailure { if (it is CancellationException) throw it else toast(it.friendlyMessage()) }
        }
    }

    val shareTarget = remember(item) {
        ShareTarget(item.aid.takeIf { it > 0 } ?: item.bvid.takeIf { it.isNotEmpty() }?.let(IdUtils::bv2av) ?: 0L, item.title, item.cover, url)
    }
    val listen = if (!isVideo) null else MenuRow(YoinSymbols.Headphones, "仅听声音") {
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
    }
    val model = menuModel(
        lead = extras.filterNot { it.destructive }.map { a -> MenuRow(a.icon, a.label) { close(a.onClick) } },
        keep = if (!isVideo) emptyList() else buildList {
            if (watchLater && loggedIn) {
                add(
                    MenuRow(PiliSymbols.WatchLater, "稍后再看") {
                        runWrite({ FeedActions.watchLater(item) }) { finisher.finish("稍后再看", "已添加") }
                    },
                )
            }
            add(MenuRow(YoinSymbols.Star, "收藏") { hide { mixOpen = true } })
        },
        decline = buildList {
            if (onRemove != null) {
                add(
                    MenuRow(PiliSymbols.ThumbDown, "不感兴趣") {
                        if (declineReasons.isNotEmpty()) {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            page = MenuPage.Decline
                        } else {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            if (dislike == null) close(onRemove) else runWrite(dislike) { close(onRemove) }
                        }
                    },
                )
            }
            extras.filter { it.destructive }.forEach { a -> add(MenuRow(a.icon, a.label, destructive = true) { close(a.onClick) }) }
        },
        listen = listen,
        small = if (!isVideo) emptyList() else buildList {
            // 分享 opens its choices in place: 转发到动态, 复制链接, 更多方式.
            if (url.isNotEmpty()) add(MenuRow(YoinSymbols.Share, "分享") { page = MenuPage.Share })
            add(MenuRow(YoinSymbols.Download, "缓存") { page = MenuPage.Cache })
        },
        owner = if (item.ownerMid <= 0) null else OwnerGroup(
            face = item.ownerFace,
            name = item.ownerName,
            caption = "UP 主",
            onOpen = { close { nav.push(Route.Member(item.ownerMid, item.ownerFace, item.ownerName)) } },
            rows = buildList {
                add(MenuRow(YoinSymbols.LibraryAdd, "加入 Feed") { hide { feedOpen = true } })
                if (onRemove != null && loggedIn && item.ownerMid != BiliClient.mid) {
                    add(
                        MenuRow(BlockGlyph, "拉黑 UP", destructive = true) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            page = MenuPage.Block
                        },
                    )
                }
            },
        ),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // 转发到动态 has a text field: the menu clears the keyboard.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = sheetBottomPadding(24.dp)),
        ) {
            MenuHeader(item, showOwner = model.owner == null, modifier = Modifier.sheetStagger(0))
            Spacer(Modifier.height(20.dp))
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(enterSpec).togetherWith(fadeOut(exitSpec)) },
                label = "videoMenu",
            ) { p ->
                when (p) {
                    MenuPage.Actions -> MenuBody(model, finisher, enabled = !busy && !finisher.finishing)
                    MenuPage.Share -> ShareChoices(shareTarget, finisher, enabled = !finisher.finishing) { page = MenuPage.Forward }
                    MenuPage.Forward -> ForwardStep(shareTarget, finisher) { page = MenuPage.Share }
                    MenuPage.Cache -> CacheStep(item, close = { close() }, onFail = { msg ->
                        toast(msg)
                        page = MenuPage.Actions
                    })
                    MenuPage.Decline -> DeclineStep(
                        reasons = declineReasons,
                        enabled = !busy && !finisher.finishing,
                        doneLabel = { finisher.doneLabel(it) },
                        onBack = { page = MenuPage.Actions },
                        onPick = { r ->
                            runWrite(r.send) { finisher.finish(r.label, if (r.feedback) "已反馈" else "将减少推荐") { onRemove?.invoke() } }
                        },
                    )
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
 * The menu for a YouTube card, in the same groups: 稍后再看, 收藏 (the account's playlists and
 * the Mix lists), 仅听声音 › 分享, 复制链接 › the channel, 不看此频道 › 不感兴趣.
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
    val finisher = rememberSheetFinisher(sheetState, onDismiss)
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
    fun close(then: () -> Unit = {}) = finisher.close(then)
    // As the sheet opened: the tile keeps its label (and its tick) after the toggle lands.
    val inLater = remember(url) { com.gpo.pilipala.yt.feature.library.Library.inWatchLater(url) }
    val model = menuModel(
        lead = extras.filterNot { it.destructive }.map { a -> MenuRow(a.icon, a.label) { close(a.onClick) } },
        keep = buildList {
            if (watchLater) {
                add(
                    MenuRow(PiliSymbols.WatchLater, if (inLater) "移出稍后再看" else "稍后再看") {
                        val added = com.gpo.pilipala.yt.feature.library.Library.toggleWatchLater(video)
                        finisher.finish(if (inLater) "移出稍后再看" else "稍后再看", if (added) "已添加" else "已移出")
                    },
                )
            }
            add(MenuRow(YoinSymbols.Star, "收藏") { hide { saveOpen = true } })
        },
        decline = buildList {
            if (onRemove != null) {
                add(
                    MenuRow(PiliSymbols.ThumbDown, "不感兴趣") {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        close(onRemove)
                    },
                )
            }
            extras.filter { it.destructive }.forEach { a -> add(MenuRow(a.icon, a.label, destructive = true) { close(a.onClick) }) }
        },
        listen = MenuRow(YoinSymbols.Headphones, "仅听声音") {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            close {
                com.gpo.pilipala.yt.feature.player.Player.play(url, video)
                com.gpo.pilipala.yt.feature.player.Player.changeAudioOnly(true)
            }
        },
        small = listOf(
            MenuRow(YoinSymbols.Share, "分享") { close { shareText(context, item.title, url) } },
            MenuRow(PiliSymbols.Link, "复制链接") {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(item.title, url))
                finisher.finish("复制链接", "已复制")
            },
        ),
        owner = channelUrl?.let { ch ->
            OwnerGroup(
                face = item.ownerFace,
                name = item.ownerName,
                caption = "频道",
                onOpen = { close { ytNav.push(com.gpo.pilipala.yt.navigation.Route.Channel(ch, item.ownerName, item.ownerFace)) } },
                rows = listOf(
                    MenuRow(YoinSymbols.VisibilityOff, "不看此频道", destructive = true) {
                        com.gpo.pilipala.yt.feature.mute.Mutes.muteChannel(ch, item.ownerName, item.ownerFace)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        toast("已屏蔽")
                        close { onRemove?.invoke() }
                    },
                ),
            )
        },
    )
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
                .padding(bottom = sheetBottomPadding(24.dp)),
        ) {
            MenuHeader(item, showOwner = model.owner == null, modifier = Modifier.sheetStagger(0))
            Spacer(Modifier.height(20.dp))
            MenuBody(model, finisher, enabled = !finisher.finishing)
        }
    }
}

private class MenuRow(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** The UP / channel: its own row (avatar, name: opens the page), then [rows] about it. */
private class OwnerGroup(
    val face: String,
    val name: String,
    val caption: String,
    val onOpen: () -> Unit,
    val rows: List<MenuRow>,
)

/**
 * A menu's groups, most used first: [primary] (what a long press is usually for), [small]
 * round actions, the [owner] group, then [decline] (不感兴趣, the list's destructive rows) last
 * and quiet. An empty group is left out.
 */
private class MenuModel(
    val primary: List<MenuRow>,
    val small: List<MenuRow>,
    val owner: OwnerGroup?,
    val decline: List<MenuRow>,
)

/** Most one row of the primary group holds; past that it wraps. */
private const val PrimaryMax = 4

/**
 * Orders a menu by how often each action is the reason for the long press: the list's own
 * [lead] actions (从这里播放…), then [keep] (稍后再看, 收藏), then [listen] (仅听声音).
 * [decline] (不感兴趣, 删除记录…) goes to the end, out of the way of the rest.
 */
private fun menuModel(
    lead: List<MenuRow>,
    keep: List<MenuRow>,
    decline: List<MenuRow>,
    listen: MenuRow?,
    small: List<MenuRow>,
    owner: OwnerGroup?,
): MenuModel = MenuModel(lead + keep + listOfNotNull(listen), small, owner, decline)

/**
 * The groups, by M3's button rules and spaced by belonging (tight inside a group, [GroupGap]
 * between groups): the primary actions as a standard button group, the first tonal and the
 * rest outlined; 分享 / 缓存 as round icon buttons; the UP as a list; the declines as outlined
 * buttons at the very end.
 */
@Composable
private fun MenuBody(model: MenuModel, finisher: SheetFinisher, enabled: Boolean) {
    Column {
        var gap = false
        if (model.primary.isNotEmpty()) {
            Column(Modifier.sheetStagger(1), verticalArrangement = Arrangement.spacedBy(SheetGroupSpacing)) {
                model.primary.chunked(PrimaryMax).forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SheetGroupSpacing)) {
                        row.forEachIndexed { i, t ->
                            SheetGroupButton(
                                t.icon,
                                t.label,
                                t.onClick,
                                Modifier.weight(1f),
                                done = finisher.doneLabel(t.label),
                                enabled = enabled,
                                emphasis = if (r == 0 && i == 0) SheetEmphasis.Tonal else SheetEmphasis.Outlined,
                            )
                        }
                    }
                }
            }
            gap = true
        }
        if (model.small.isNotEmpty()) {
            if (gap) Spacer(Modifier.height(GroupGap))
            SheetIconRow(Modifier.sheetStagger(2)) {
                model.small.forEach { s ->
                    SheetIconAction(s.icon, s.label, s.onClick, done = finisher.doneLabel(s.label), enabled = enabled)
                }
            }
            gap = true
        }
        model.owner?.let {
            if (gap) Spacer(Modifier.height(GroupGap))
            OwnerSection(it, enabled, Modifier.sheetStagger(3))
            gap = true
        }
        if (model.decline.isNotEmpty()) {
            if (gap) Spacer(Modifier.height(GroupGap))
            Row(Modifier.fillMaxWidth().sheetStagger(4), horizontalArrangement = Arrangement.spacedBy(SheetGroupSpacing)) {
                model.decline.forEach { d -> DeclineButton(d, enabled, Modifier.weight(1f)) }
            }
        }
    }
}

/** A turning-away action: an outlined button, icon beside label; destructive ones in red. */
@Composable
private fun DeclineButton(r: MenuRow, enabled: Boolean, modifier: Modifier = Modifier) {
    val tint = if (r.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedButton(
        onClick = r.onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier.height(48.dp),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
    ) {
        Icon(r.icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(r.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

private val GroupGap = 24.dp

@Composable
private fun MenuHeader(item: VideoItem, showOwner: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NetImage(item.cover, Modifier.width(112.dp).aspectRatio(16f / 10f), shape = CoverShape, thumbWidth = 224.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            // The owner group below names the UP; without one, the header does.
            if (showOwner && item.ownerName.isNotEmpty()) {
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

/** The owner's row and the rows about the owner, as one segmented block. */
@Composable
private fun OwnerSection(owner: OwnerGroup, enabled: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val count = owner.rows.size + 1
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(segmentShape(0, count))
                .background(scheme.surfaceContainerHigh)
                .clickable(enabled = enabled, onClick = owner.onOpen)
                .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (owner.face.isNotEmpty()) {
                NetImage(owner.face, Modifier.size(40.dp), shape = CircleShape, thumbWidth = 80.dp)
            } else {
                Box(Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(YoinSymbols.Person, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(owner.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(owner.caption, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(YoinSymbols.ChevronRight, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        }
        owner.rows.forEachIndexed { i, r -> SegmentRow(r, segmentShape(i + 1, count), enabled) }
    }
}

private fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) 20.dp else 4.dp
    val bottom = if (index == count - 1) 20.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun SegmentRow(r: MenuRow, shape: RoundedCornerShape, enabled: Boolean) {
    val tint = if (r.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = r.onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(r.icon, null, Modifier.size(22.dp), tint = if (r.destructive) tint else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(r.label, style = MaterialTheme.typography.bodyLarge, color = tint)
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

/**
 * 不感兴趣, with why: the feed's own reasons as pills (不感兴趣, 不想看 UP 主 / 分区 / 频道…),
 * then its 反馈 ones (内容引起不适, 标题党…) under their own label. The picked pill turns into a
 * tick and its done label before the sheet goes and the card leaves the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeclineStep(
    reasons: List<DeclineReason>,
    enabled: Boolean,
    doneLabel: (String) -> String?,
    onBack: () -> Unit,
    onPick: (DeclineReason) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        listOf(
            "不想看到" to reasons.filterNot { it.feedback },
            "告诉 B站" to reasons.filter { it.feedback },
        ).filter { it.second.isNotEmpty() }.forEachIndexed { i, (title, group) ->
            if (i > 0) Spacer(Modifier.height(GroupGap))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEach { r ->
                    val done = doneLabel(r.label)
                    com.gpo.pilipala.ui.components.SheetOutlinedAction(
                        icon = if (done != null) YoinSymbols.Check else if (r.feedback) YoinSymbols.Info else PiliSymbols.ThumbDown,
                        label = done ?: r.label,
                        onClick = { onPick(r) },
                        enabled = enabled,
                    )
                }
            }
        }
        Spacer(Modifier.height(GroupGap))
        TextButton(onClick = onBack, enabled = enabled) { Text("返回") }
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
