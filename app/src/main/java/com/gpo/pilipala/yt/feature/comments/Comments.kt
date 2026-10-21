package com.gpo.pilipala.yt.feature.comments

import android.text.Html
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.util.Fmt
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.yt.ui.components.FameCount
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
    LaunchedEffect(reachedEnd, pager.initialLoading) { if (reachedEnd && !pager.initialLoading && pager.items.isNotEmpty()) pager.loadMore() }
    LazyColumn(state = list, modifier = modifier, contentPadding = contentPadding) {
        header()
        item(key = "sort") { SortBar(state.newest, state::sort) }
        when {
            pager.initialLoading || (pager.isRefreshing && pager.items.isEmpty()) -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
            pager.error != null && pager.items.isEmpty() -> item(key = "error") {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(pager.error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = pager::refresh) { Text("重试") }
                }
            }
            pager.items.isEmpty() -> item(key = "empty") {
                Text(
                    "还没有评论",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            else -> items(pager.items, key = { it.id }) { c -> CommentRow(c, state, onSeek) }
        }
        if (pager.isLoadingMore) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
        }
    }
}

/** 热门 / 最新 as a connected pair (PiliPaw's sort bar). */
@Composable
private fun SortBar(newest: Boolean, onSort: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
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

/**
 * One comment (PiliPaw's row, lighter): the avatar on the left; the name with 置顶 / ♥ (the
 * uploader's heart) tags, the time, the text (clamped, 展开), likes; then 展开 N 条回复, which
 * opens the replies right under it.
 */
@Composable
private fun CommentRow(c: Comment, state: CommentsState, onSeek: (Long) -> Unit, reply: Boolean = false) {
    val nav = LocalNavigator.current
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (reply) 64.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(c.avatar.ifEmpty { null }, if (reply) 28.dp else 36.dp) {
            if (c.authorUrl.isNotEmpty()) nav.push(Route.Channel(c.authorUrl, c.author, c.avatar))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    c.author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.pinned) Tag("置顶", scheme.tertiaryContainer, scheme.onTertiaryContainer)
                if (c.hearted) Tag("♥ UP", scheme.primaryContainer, scheme.onPrimaryContainer)
                if (c.when_.isNotEmpty()) Text(c.when_, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
            }
            ClampedText(c, onSeek, Modifier.padding(top = 4.dp))
            if (c.likes > 0) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PiliSymbols.ThumbUp, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    FameCount(Fmt.count(c.likes), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            if (!reply && c.replyCount > 0 && c.repliesCursor != null) Replies(c, state, onSeek)
        }
    }
}

/** 展开 N 条回复 → the replies under the comment, paged with 更多回复. */
@Composable
private fun Replies(c: Comment, state: CommentsState, onSeek: (Long) -> Unit) {
    var open by rememberSaveable(c.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pager = remember(c.id) { state.repliesOf(c) }
    Column(Modifier.animateContentSize()) {
        TextButton(
            onClick = {
                open = !open
                if (open && pager.items.isEmpty()) scope.launch { pager.refresh() }
            },
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Icon(if (open) YoinSymbols.ChevronUp else YoinSymbols.ChevronDown, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (open) "收起回复" else "${c.replyCount} 条回复", style = MaterialTheme.typography.labelLarge)
        }
        if (open) {
            pager.items.forEach { r -> Box(Modifier.padding(start = 0.dp)) { CommentRow(r, state, onSeek, reply = true) } }
            when {
                pager.isRefreshing || pager.isLoadingMore -> LoadingIndicator(Modifier.size(28.dp))
                pager.hasMore && pager.items.isNotEmpty() -> TextButton(onClick = { pager.loadMore() }) { Text("更多回复") }
            }
        }
    }
}

@Composable
private fun Tag(label: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** The text clamped to [ClampLines] with 展开 / 收起; "1:23" timestamps seek. */
@Composable
private fun ClampedText(c: Comment, onSeek: (Long) -> Unit, modifier: Modifier) {
    var expanded by rememberSaveable(c.id) { mutableStateOf(false) }
    var overflows by remember(c.id) { mutableStateOf(false) }
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(c.text, linkColor) { withTimestamps(c.text, linkColor, onSeek) }
    Column(modifier.animateContentSize()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else ClampLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelLarge,
                color = linkColor,
                modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
            )
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
