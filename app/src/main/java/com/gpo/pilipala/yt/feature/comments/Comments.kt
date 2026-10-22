package com.gpo.pilipala.yt.feature.comments

import android.text.Html
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.centered
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.yt.ui.components.Page
import com.gpo.pilipala.yt.ui.components.Pager
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.comments.CommentsInfoItem

/** One comment as the list draws it: plain, extractor-free. */
class Comment(
    val id: String,
    val author: String,
    val authorUrl: String,
    val avatar: String,
    val text: String,
    val likes: Long,
    val when_: String,
    val pinned: Boolean,
    val hearted: Boolean,
    val verified: Boolean,
    val replyCount: Int,
    val repliesCursor: String?,
)

private fun CommentsInfoItem.toComment(ago: (Long) -> String): Comment = Comment(
    id = commentId.orEmpty().ifEmpty { url.orEmpty() + name },
    author = uploaderName.orEmpty(),
    authorUrl = uploaderUrl.orEmpty(),
    avatar = uploaderAvatarUrl.orEmpty(),
    // The extractor hands HTML (links, bold): read it as text.
    text = Html.fromHtml(commentText?.content.orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString().trim(),
    likes = likeCount.toLong(),
    when_ = ago(uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L),
    pinned = isPinned,
    hearted = isHeartedByUploader,
    verified = isUploaderVerified,
    replyCount = replyCount.coerceAtLeast(0),
    repliesCursor = Yt.repliesCursor(this),
)

private suspend fun loadPage(url: String, cursor: String?, newest: Boolean): Page<Comment> {
    val page = Yt.comments(url, cursor, newest)
    return Page(page.items.map { it.toComment(Fmt::ago) }, page.hasMore, page.nextCursor)
}

/** The comments of one video and their sort; the list's own scope so it outlives the sheet. */
@Stable
class CommentsState(private val scope: CoroutineScope, private val videoUrl: String) {
    var newest by mutableStateOf(false)
        private set
    val pager = Pager<Comment>(scope, dedupKey = { it.id }) { cursor -> loadPage(videoUrl, cursor, newest) }

    /** Replies already loaded per comment id. */
    private val replies = HashMap<String, Pager<Comment>>()

    fun start() {
        if (pager.initialLoading && !pager.isRefreshing) pager.refresh()
    }

    fun sort(byNewest: Boolean) {
        if (byNewest == newest) return
        newest = byNewest
        pager.refresh()
    }

    fun repliesOf(c: Comment): Pager<Comment> = replies.getOrPut(c.id) {
        Pager(scope, dedupKey = { it.id }) { cursor ->
            val page = Yt.replies(videoUrl, cursor ?: c.repliesCursor!!)
            Page(page.items.map { it.toComment(Fmt::ago) }, page.hasMore, page.nextCursor)
        }
    }
}

@Composable
fun rememberCommentsState(videoUrl: String): CommentsState {
    val scope = rememberCoroutineScope()
    return remember(videoUrl) { CommentsState(scope, videoUrl) }
}

/**
 * The comments as a list: 热门 / 最新, then the rows, paged. [onSeek] gets the seconds of a
 * "3:21" in a comment. [header] goes above (the sheet's title, or nothing in the tablet column).
 * Rows, states and footer match PiliPaw's reply list (ReplyList): the shared state blocks,
 * 没有更多了 at the end, everything held to the reading width.
 */
@Composable
fun CommentsList(
    state: CommentsState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    header: LazyListScope.() -> Unit = {},
) {
    LaunchedEffect(state) { state.start() }
    val pager = state.pager
    val list = rememberLazyListState()
    val reachedEnd by remember {
        androidx.compose.runtime.derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    // Only once the first page is in: on an empty list "the end" is visible at once, and an early
    // loadMore would take the pager's job from the first refresh.
    LaunchedEffect(reachedEnd, pager.initialLoading) {
        if (reachedEnd && !pager.initialLoading && pager.items.isNotEmpty() && pager.error == null) pager.loadMore()
    }
    LazyColumn(state = list, modifier = modifier, contentPadding = contentPadding) {
        header()
        item(key = "sort") { SortBar(state.newest, state::sort, Modifier.readingWidth()) }
        when {
            pager.initialLoading || (pager.isRefreshing && pager.items.isEmpty()) -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().heightIn(min = StateMinHeight).animateItem(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
            pager.error != null && pager.items.isEmpty() -> item(key = "error") {
                ErrorState(pager.error.orEmpty(), Modifier.heightIn(min = StateMinHeight).animateItem(), onRetry = pager::refresh)
            }
            pager.items.isEmpty() -> item(key = "empty") {
                EmptyState("暂无评论", Modifier.heightIn(min = StateMinHeight).animateItem(), icon = PiliSymbols.Comment)
            }
            else -> items(pager.items, key = { it.id }) { c ->
                CommentRow(c, state, onSeek, Modifier.readingWidth().animateItem())
            }
        }
        if (pager.items.isNotEmpty()) item(key = "footer") { ListFooter(pager, Modifier.readingWidth().animateItem()) }
    }
}

/** A state block's height, so the list does not jump while it loads (ReplyList's ListState). */
private val StateMinHeight = 260.dp

/** Under the last row: loading, a failed page with 重试, or 没有更多了 (ReplyList's PagerFooter). */
@Composable
private fun ListFooter(pager: Pager<Comment>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
        when {
            pager.isLoadingMore -> LoadingFooter()
            pager.error != null -> Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(pager.error.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = pager::loadMore) { Text("重试") }
            }
            !pager.hasMore -> Text(
                "没有更多了",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 热门 / 最新 as a connected pair (PiliPaw's sort bar). */
@Composable
private fun SortBar(newest: Boolean, onSort: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        listOf(false to "热门", true to "最新").forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = newest == value,
                onCheckedChange = {
                    if (newest != value) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSort(value)
                    }
                },
                shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Lines a comment shows before 展开. */
private const val ClampLines = 6

/** The replies' block (ReplyItem's sub-reply preview). */
private val RepliesShape = ContinuousRoundedCornerShape(14.dp)

/**
 * One comment, laid out as PiliPaw's reply row: the avatar (with the verified dot) on the left;
 * the name with the 置顶 tag, the time on its own quiet line, the text (clamped, 展开), then a row
 * with UP主觉得很赞 and the likes at the end; then the replies block.
 */
@Composable
private fun CommentRow(
    c: Comment,
    state: CommentsState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 36.dp,
    reply: Boolean = false,
) {
    val nav = LocalNavigator.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = if (reply) 12.dp else 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Avatar(c.avatar.ifEmpty { null }, avatarSize) {
                if (c.authorUrl.isNotEmpty()) nav.push(Route.Channel(c.authorUrl, c.author, c.avatar))
            }
            if (c.verified) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(scheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
            }
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    c.author,
                    style = MaterialTheme.typography.labelLarge.centered(),
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.pinned) Tag("置顶", container = scheme.tertiaryContainer, content = scheme.onTertiaryContainer)
            }
            if (c.when_.isNotEmpty()) {
                Text(
                    c.when_,
                    style = MaterialTheme.typography.labelSmall.centered(),
                    color = scheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ClampedText(c, onSeek, Modifier.padding(top = 6.dp))
            ActionRow(c)
            if (!reply && c.replyCount > 0 && c.repliesCursor != null) {
                Replies(c, state, onSeek, Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

/** UP主觉得很赞 and the likes at the end (ReplyItem's action row; YouTube likes are read-only). */
@Composable
private fun ActionRow(c: Comment) {
    if (!c.hearted && c.likes <= 0) return
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (c.hearted) {
            Text("UP主觉得很赞", style = MaterialTheme.typography.labelSmall.centered(), color = scheme.primary)
        }
        Spacer(Modifier.weight(1f))
        if (c.likes > 0) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(PiliSymbols.ThumbUp, null, Modifier.size(16.dp), tint = scheme.outline)
                FameCount(Fmt.count(c.likes), style = MaterialTheme.typography.labelMedium.centered(), color = scheme.outline)
            }
        }
    }
}

/**
 * N 条回复 in a tonal block (PiliPaw's sub-reply preview); open, the replies follow inside the
 * same block, paged with 更多回复.
 */
@Composable
private fun Replies(c: Comment, state: CommentsState, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable(c.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pager = remember(c.id) { state.repliesOf(c) }
    val scheme = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 1f else 0f, YoinMotion.defaultSpatialSpec(), label = "repliesChevron")
    Column(
        modifier
            .fillMaxWidth()
            .clip(RepliesShape)
            .background(scheme.surfaceContainer)
            .animateContentSize(YoinMotion.defaultSpatialSpec()),
    ) {
        if (open) {
            pager.items.forEach { r -> CommentRow(r, state, onSeek, avatarSize = 28.dp, reply = true) }
            when {
                pager.isRefreshing || pager.isLoadingMore -> LoadingFooter()
                pager.hasMore && pager.items.isNotEmpty() -> BlockLink("更多回复", null) { pager.loadMore() }
            }
        }
        BlockLink(if (open) "收起" else "${c.replyCount} 条回复", turn) {
            open = !open
            if (open && pager.items.isEmpty()) scope.launch { pager.refresh() }
        }
    }
}

/** A label in the replies block: primary text and a chevron that turns with [turn] (null: none). */
@Composable
private fun BlockLink(label: String, turn: Float?, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium.centered(), fontWeight = FontWeight.Medium, color = primary)
        if (turn != null) {
            Icon(
                YoinSymbols.ChevronDown,
                null,
                Modifier.size(16.dp).graphicsLayer { rotationZ = 180f * turn },
                tint = primary,
            )
        }
    }
}

/** The text clamped to [ClampLines] with 展开 / 收起 (a pill with a turning chevron); "1:23" timestamps seek. */
@Composable
private fun ClampedText(c: Comment, onSeek: (Long) -> Unit, modifier: Modifier) {
    var expanded by rememberSaveable(c.id) { mutableStateOf(false) }
    var overflows by remember(c.id) { mutableStateOf(false) }
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(c.text, linkColor) { withTimestamps(c.text, linkColor, onSeek) }
    val turn by animateFloatAsState(if (expanded) 1f else 0f, YoinMotion.defaultSpatialSpec(), label = "clampChevron")
    Column(modifier.animateContentSize(YoinMotion.defaultSpatialSpec())) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else ClampLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Row(
                Modifier
                    .padding(top = 2.dp)
                    .offset(x = (-8).dp)
                    .clip(CircleShape)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelLarge.centered(), color = linkColor)
                Icon(YoinSymbols.ChevronDown, null, Modifier.size(16.dp).graphicsLayer { rotationZ = 180f * turn }, tint = linkColor)
            }
        }
    }
}

private val TimestampPattern = Regex("""(?<![\d:])(\d{1,2}:)?\d{1,2}:\d{2}(?![\d:])""")

/** [text] with every "h:mm:ss" / "m:ss" made a link that seeks there. */
private fun withTimestamps(text: String, color: Color, onSeek: (Long) -> Unit): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in TimestampPattern.findAll(text)) {
        append(text.substring(last, m.range.first))
        val parts = m.value.split(':').map { it.toLong() }
        val seconds = parts.fold(0L) { acc, v -> acc * 60 + v }
        pushLink(LinkAnnotation.Clickable(m.value, TextLinkStyles(SpanStyle(color = color))) { onSeek(seconds) })
        append(m.value)
        pop()
        last = m.range.last + 1
    }
    append(text.substring(last))
}
