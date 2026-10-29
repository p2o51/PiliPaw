package com.gpo.pilipala.yt.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.gpo.pilipala.feature.sources.SaveToListSheet
import com.gpo.pilipala.feature.sources.toCard
import com.gpo.pilipala.feature.video.ActionSpec
import com.gpo.pilipala.feature.video.ConnectedActionGroup
import com.gpo.pilipala.feature.video.EpisodeEntry
import com.gpo.pilipala.feature.video.EpisodeRow
import com.gpo.pilipala.feature.video.ExpandableText
import com.gpo.pilipala.feature.video.FollowToggle
import com.gpo.pilipala.feature.video.SectionTitle
import com.gpo.pilipala.feature.video.shareText
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.VideoCardH
import com.gpo.pilipala.ui.components.centered
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.yt.core.account.AccountSubscriptions
import com.gpo.pilipala.yt.core.account.AccountWrites
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.extractor.YtAccount
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.subscriptions.Subscriptions
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.yt.ui.components.friendlyMessage
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo

/*
 * The page under the YouTube player, laid out as PiliPaw's video page (feature/video): the
 * header (title that unfolds, stat pairs, the description block), the channel card with the
 * 订阅 toggle, the connected action group, 章节 as episode cards, then 相关推荐 as row cards.
 * PlayerOverlay puts these in a grid (phones and tablet portrait) or the left column (wide).
 */

/** Narrowest cell for the 相关推荐 rows: one column on phones, two from tablet portrait. */
internal val RelatedMinCell = 360.dp

private fun LazyGridScope.full(key: String, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)

/**
 * The intro's grid items. [related] adds 相关推荐 at the end (phones; the wide layout shows it
 * in the side panel instead).
 */
internal fun LazyGridScope.ytIntroItems(
    session: Player.Session,
    info: StreamInfo?,
    related: List<VideoItem>,
    onSeek: (Long) -> Unit,
    includeRelated: Boolean,
) {
    full("header") { YtVideoHeader(session, info, onSeek, Modifier.padding(top = 14.dp)) }
    if (info == null) {
        full("loading") { LoadingFooter() }
        return
    }
    full("up") {
        Spacer(Modifier.height(12.dp))
        YtUpCard(channelOf(info))
    }
    full("actions") {
        Spacer(Modifier.height(16.dp))
        YtVideoActions(info)
    }
    if (Player.chapters.isNotEmpty()) {
        full("chapters") {
            Spacer(Modifier.height(20.dp))
            YtChapters(Player.chapters)
        }
    }
    if (includeRelated) {
        full("relatedGap") { Spacer(Modifier.height(20.dp)) }
        full("relatedTitle") { SectionTitle("相关推荐", Modifier.padding(bottom = 4.dp)) }
        ytRelatedItems(related)
    }
}

/** 相关推荐 as PiliPaw's row cards; tapping one plays it here. */
internal fun LazyGridScope.ytRelatedItems(related: List<VideoItem>) {
    if (related.isEmpty()) {
        full("relatedEmpty") { Box(Modifier.fillMaxWidth().height(200.dp)) { EmptyState("暂无相关推荐") } }
    } else {
        items(related, key = { "r_${it.url}" }) { v -> VideoCardH(v.toCard(), onClick = { Player.play(v.url, v) }) }
    }
}

private fun channelOf(info: StreamInfo) = ChannelItem(
    url = info.uploaderUrl.orEmpty(),
    name = info.uploaderName.orEmpty(),
    avatar = info.uploaderAvatarUrl.orEmpty(),
    subscribers = info.uploaderSubscriberCount,
    videos = -1,
    verified = info.isUploaderVerified,
)

/** The tabs over the page's panes (PiliPaw's DetailTabRow): 简介 / 评论, or 评论 / 相关 at the side. */
@Composable
internal fun YtDetailTabRow(labels: List<String>, state: PagerState, container: Color) {
    val scope = rememberCoroutineScope()
    PrimaryTabRow(
        selectedTabIndex = state.currentPage.coerceIn(0, (labels.size - 1).coerceAtLeast(0)),
        containerColor = container,
        divider = {},
    ) {
        labels.forEachIndexed { i, label ->
            Tab(
                selected = state.currentPage == i,
                onClick = { scope.launch { state.animateScrollToPage(i) } },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = { Text(label, style = MaterialTheme.typography.titleSmall) },
            )
        }
    }
}

/**
 * Title (folds to two lines, unfolds with the spatial spring, long press copies), the view
 * count and date, and the description block that unfolds together with the title (PiliPaw's
 * VideoHeader).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YtVideoHeader(session: Player.Session, info: StreamInfo?, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(session.url) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val toast = rememberToast()
    val haptic = LocalHapticFeedback.current
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .animateContentSize(YoinMotion.defaultSpatialSpec())
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        clipboard.setText(AnnotatedString(session.title))
                        toast("已复制标题")
                    },
                    onClick = { expanded = !expanded },
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                session.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val rotation by animateFloatAsState(if (expanded) 180f else 0f, YoinMotion.defaultSpatialSpec(), label = "titleChevron")
            Icon(
                YoinSymbols.ChevronDown,
                null,
                Modifier.padding(top = 4.dp).size(20.dp).graphicsLayer { rotationZ = rotation },
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        if (info == null) return@Column
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (info.viewCount >= 0) Stat(YoinSymbols.Views, Fmt.count(info.viewCount), revealable = true)
            val uploaded = info.uploadDate?.offsetDateTime()?.toEpochSecond() ?: 0L
            if (uploaded > 0) {
                Text(
                    com.gpo.pilipala.core.util.Fmt.date(uploaded, "yyyy-MM-dd"),
                    style = MaterialTheme.typography.labelSmall.centered(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val linkColor = MaterialTheme.colorScheme.primary
        val description = remember(info, linkColor) { descriptionText(info, linkColor, onSeek) }
        if (description.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(YoinContainerShapes.Card)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                ExpandableText(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    collapsedLines = 3,
                    expandedState = expanded,
                    onToggle = { expanded = !expanded },
                )
            }
        }
    }
}

private val UrlPattern = Regex("""https?://[^\s<>"]+""")
private val TimestampPattern = Regex("""(?<![\d:])(\d{1,2}:)?\d{1,2}:\d{2}(?![\d:])""")

/** The description as text: links open, "1:23" seeks there. */
private fun descriptionText(info: StreamInfo, color: Color, onSeek: (Long) -> Unit): AnnotatedString {
    val d = info.description ?: return AnnotatedString("")
    val text = when (d.type) {
        org.schabi.newpipe.extractor.stream.Description.HTML ->
            HtmlCompat.fromHtml(d.content, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        else -> d.content.orEmpty().trim()
    }
    if (text.isEmpty()) return AnnotatedString("")
    val links = TextLinkStyles(SpanStyle(color = color))
    val marks = (UrlPattern.findAll(text).map { it to true } + TimestampPattern.findAll(text).map { it to false })
        .sortedBy { it.first.range.first }
    return buildAnnotatedString {
        var last = 0
        for ((m, url) in marks) {
            if (m.range.first < last) continue
            append(text.substring(last, m.range.first))
            if (url) {
                pushLink(LinkAnnotation.Url(m.value, links))
            } else {
                val seconds = m.value.split(':').fold(0L) { acc, v -> acc * 60 + (v.toLongOrNull() ?: 0) }
                pushLink(LinkAnnotation.Clickable(m.value, links) { onSeek(seconds) })
            }
            append(m.value)
            pop()
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

/** The channel card (PiliPaw's UpCard): avatar, name, subscribers, the 订阅 toggle. */
@Composable
private fun YtUpCard(channel: ChannelItem) {
    val nav = LocalNavigator.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(YoinContainerShapes.ListRow)
            .clickable(enabled = channel.url.isNotEmpty()) {
                Player.expanded = false
                nav.push(Route.Channel(channel.url, channel.name, channel.avatar))
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(channel.avatar, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.subscribers >= 0) SubscriberCount(channel.subscribers)
        }
        SubscribeButton(channel)
    }
}

/** "N 订阅者" as PiliPaw's "N 粉丝": the number SemiBold, the word quiet. */
@Composable
internal fun SubscriberCount(count: Long, modifier: Modifier = Modifier) {
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    val size = YoinMotion.defaultSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = count,
        transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) using SizeTransform { _, _ -> size } },
        modifier = modifier,
        label = "subscribers",
    ) { n ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            FameCount(
                Fmt.count(n),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                revealable = false,
            )
            Spacer(Modifier.width(3.dp))
            Text("订阅者", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 订阅 / 已订阅 as PiliPaw's follow toggle (the same button, colours and haptics). Signed out
 * it is the local list; signed in it is the account's (a write, so a dry run in debug builds,
 * which says so). Taking a subscription back asks first, as 取消关注 does.
 */
@Composable
fun SubscribeButton(channel: ChannelItem) {
    val profile by Accounts.youtube.collectAsState()
    val subs by Subscriptions.channels.collectAsState()
    val accountIds by AccountSubscriptions.ids.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val signedIn = profile is Profile.SignedIn
    val subscribed = if (signedIn) AccountSubscriptions.channelId(channel.url) in accountIds else subs.any { it.url == channel.url }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val toggle: () -> Unit = {
        if (signedIn) {
            busy = true
            scope.launch {
                toast(runCatching { AccountSubscriptions.toggle(channel.url, channel.name) }.getOrElse { it.friendlyMessage() })
                busy = false
            }
        } else {
            Subscriptions.toggle(channel)
        }
    }
    FollowToggle(
        following = subscribed,
        busy = busy,
        onFollow = { if (subscribed) confirm = true else toggle() },
        followLabel = "订阅",
        followingLabel = "已订阅",
        enabled = channel.url.isNotEmpty(),
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("取消订阅") },
            text = { Text(channel.name) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    toggle()
                }) { Text("取消订阅") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("保留") } },
        )
    }
}

/**
 * The connected action group (PiliPaw's 点赞 投币 收藏 稍后再看 分享): 赞 / 稍后再看 / Mix / 分享.
 * Signed out there is no 赞 (YouTube likes belong to an account), and 仅听 takes its slot.
 * Holding 赞 likes it and opens the list sheet. Account writes are dry runs in debug builds.
 */
@Composable
private fun YtVideoActions(info: StreamInfo) {
    val profile by Accounts.youtube.collectAsState()
    val later by Library.watchLater.collectAsState()
    val mixes by Library.playlists.collectAsState()
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val video = remember(info) {
        VideoItem(
            url = info.url, title = info.name.orEmpty(), thumbnail = info.thumbnailUrl.orEmpty(),
            channelName = info.uploaderName.orEmpty(), channelUrl = info.uploaderUrl.orEmpty(),
            channelAvatar = info.uploaderAvatarUrl.orEmpty(), durationSec = info.duration, views = info.viewCount,
            uploaded = "", uploadedAt = 0, isLive = false, isShort = false, channelVerified = false,
        )
    }
    val key = Library.keyOf(info.url)
    val signedIn = profile is Profile.SignedIn
    val liked = YtLikes.liked(info.url)
    var inAccountLater by rememberSaveable(info.url) { mutableStateOf(false) }
    var listSheet by remember { mutableStateOf(false) }
    val inLater = if (signedIn) inAccountLater else later.any { it.key == key }
    val inMix = mixes.any { p -> p.items.any { it.key == key } }
    // 名声模式: the 赞 slot takes its name instead of a count, as 点赞 does.
    val likeLabel = if (info.likeCount > 0 && !FameMode.hides(false)) Fmt.count(info.likeCount + if (liked) 1 else 0) else "赞"

    fun like(to: Boolean) {
        scope.launch { YtLikes.set(info.url, info.id, to)?.let(toast) }
    }

    val first = if (signedIn) {
        ActionSpec(
            label = likeLabel,
            icon = YoinSymbols.ThumbUp,
            checkedIcon = YoinSymbols.ThumbUpFilled,
            checked = liked,
            onLongClick = {
                if (!liked) like(true)
                listSheet = true
            },
            onClick = { like(!liked) },
        )
    } else {
        ActionSpec(
            label = "仅听",
            icon = YoinSymbols.Headphones,
            toggle = false,
            onClick = { Player.changeAudioOnly(true) },
        )
    }
    val disliked = YtLikes.disliked(info.url)
    val dislike = if (!signedIn) null else ActionSpec(
        label = "点踩",
        icon = YoinSymbols.ThumbDown,
        checkedIcon = YoinSymbols.ThumbDownFilled,
        checked = disliked,
        onClick = { scope.launch { YtLikes.setDislike(info.url, info.id, !disliked)?.let(toast) } },
    )
    ConnectedActionGroup(
        listOfNotNull(
            first,
            dislike,
            ActionSpec(
                label = "稍后再看",
                icon = YoinSymbols.WatchLater,
                checked = inLater,
                onClick = {
                    if (signedIn) {
                        val to = !inAccountLater
                        inAccountLater = to
                        scope.launch {
                            val line = runCatching {
                                AccountWrites.run(if (to) "加入稍后再看" else "移出稍后再看") { YtAccount.watchLater(info.id, add = to) }
                            }.getOrElse { e ->
                                inAccountLater = !to
                                e.friendlyMessage()
                            }
                            toast(line)
                        }
                    } else {
                        Library.toggleWatchLater(video)
                    }
                },
            ),
            ActionSpec(
                label = "Mix",
                icon = YoinSymbols.Library,
                checkedIcon = YoinSymbols.LibraryFilled,
                checked = inMix,
                onClick = { listSheet = true },
            ),
            ActionSpec(
                label = "分享",
                icon = YoinSymbols.Share,
                toggle = false,
                onClick = { shareText(context, info.name.orEmpty(), info.url) },
            ),
        ),
        Modifier.padding(horizontal = 16.dp),
    )
    if (listSheet) SaveToListSheet(Library.itemOf(video), onDismiss = { listSheet = false })
}

/**
 * 章节 as PiliPaw's episode row: cards with the chapter's still, its time and title, bleeding
 * to the page edges; the one playing is the primary-container card, a tap seeks there.
 */
@Composable
private fun YtChapters(chapters: List<Chapter>) {
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(chapters) {
        while (true) {
            position = Player.exo.currentPosition
            delay(500)
        }
    }
    val current = chapters.at(position)
    val entries = remember(chapters) {
        chapters.map { c ->
            EpisodeEntry(key = c.startMs, index = Fmt.duration(c.startMs / 1000).ifEmpty { "0:00" }, title = c.title, cover = c.preview)
        }
    }
    EpisodeRow(
        title = "章节",
        entries = entries,
        currentKey = current?.startMs ?: -1L,
        onSelect = { e -> Player.exo.seekTo(e.key) },
        onShowAll = null,
        withCovers = entries.any { it.cover.isNotEmpty() },
    )
}
