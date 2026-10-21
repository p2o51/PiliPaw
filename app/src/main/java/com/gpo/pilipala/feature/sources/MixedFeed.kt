package com.gpo.pilipala.feature.sources

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.source.Sources
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.dynamics.DynItem
import com.gpo.pilipala.feature.dynamics.DynamicsApi
import com.gpo.pilipala.feature.dynamics.author
import com.gpo.pilipala.feature.dynamics.major
import com.gpo.pilipala.feature.home.FeedPager
import com.gpo.pilipala.feature.home.PagedGrid
import com.gpo.pilipala.feature.home.feedItemMotion
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.VideoCardV
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.homeVideoColumns
import com.gpo.pilipala.yt.YtLinks
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.extractor.YtFeeds
import com.gpo.pilipala.yt.feature.mute.Mutes
import com.gpo.pilipala.yt.feature.subscriptions.Subscriptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.gpo.pilipala.yt.core.extractor.VideoItem as YtVideo

/**
 * One card of the mixed 关注 feed. Both sources draw with PiliPaw's own card ([card]); a YouTube
 * one carries its link in [ytUrl] and opens in the YouTube part.
 */
@Immutable
internal data class MixedItem(val source: VideoSource, val card: VideoItem, val at: Long, val ytUrl: String? = null) {
    val key: String get() = ytUrl ?: "bili:${card.bvid}"
}

/**
 * Bilibili's followed uploads and YouTube's subscriptions, newest first, as one feed. Each source
 * pages on its own; a page only shows what is newer than the oldest card either source still has
 * in hand, so a later page never has to slot a card in above ones already shown.
 */
internal class MixedFeedViewModel : ViewModel() {
    private class Lane(val source: VideoSource) {
        val buffer = ArrayList<MixedItem>()
        var cursor: String? = null
        var hasMore = true
        var started = false
    }

    private var lanes = emptyList<Lane>()
    private var biliBaseline = ""

    val feed = FeedPager(viewModelScope, key = { it.key }) { cursor -> load(reset = cursor == null) }

    init {
        feed.refresh()
        // A source ticked or unticked, signing in or out on either side, or a YouTube subscription added: start over.
        viewModelScope.launch {
            combine(
                Sources.enabled,
                Account.user.map { it?.mid },
                Accounts.youtube.map { it is Profile.SignedIn },
                Subscriptions.channels.map { list -> list.map { it.url } },
            ) { a, b, c, d -> listOf(a, b, c, d) }
                .distinctUntilChanged().drop(1).collect { feed.refresh() }
        }
    }

    private suspend fun load(reset: Boolean): Page<MixedItem> {
        if (reset) {
            lanes = buildList {
                if (Sources.isOn(VideoSource.Bilibili) && Account.isLoggedIn) add(Lane(VideoSource.Bilibili))
                if (Sources.isOn(VideoSource.YouTube)) add(Lane(VideoSource.YouTube))
            }
            biliBaseline = ""
        }
        // Every lane that can still page needs something in hand before the watermark means anything.
        coroutineScope {
            lanes.filter { it.hasMore && it.buffer.isEmpty() }.map { lane -> async { fill(lane) } }.awaitAll()
        }
        val open = lanes.filter { it.hasMore }
        // The newest of the lanes' oldest cards: everything at or above it is final.
        val watermark = open.mapNotNull { it.buffer.lastOrNull()?.at }.maxOrNull() ?: Long.MIN_VALUE
        val out = ArrayList<MixedItem>()
        lanes.forEach { lane ->
            val take = lane.buffer.takeWhile { it.at >= watermark }
            out += take
            repeat(take.size) { lane.buffer.removeAt(0) }
        }
        out.sortByDescending { it.at }
        val more = lanes.any { it.hasMore || it.buffer.isNotEmpty() }
        return Page(out, hasMore = more, nextCursor = if (more) "more" else null)
    }

    private suspend fun fill(lane: Lane) {
        val (items, next) = when (lane.source) {
            VideoSource.Bilibili -> bili(lane.cursor)
            VideoSource.YouTube -> youtube(lane.cursor, first = !lane.started)
        }
        lane.started = true
        lane.buffer += items.sortedByDescending { it.at }
        lane.cursor = next
        lane.hasMore = next != null && items.isNotEmpty()
    }

    /** One page of 关注 › 视频. */
    private suspend fun bili(cursor: String?): Pair<List<MixedItem>, String?> {
        val (page, baseline) = DynamicsApi.feed("video", cursor, biliBaseline, null)
        if (biliBaseline.isEmpty()) biliBaseline = baseline
        return page.items.mapNotNull { it.toMixed() } to page.nextCursor.takeIf { page.hasMore }
    }

    private fun DynItem.toMixed(): MixedItem? {
        val a = json.major.obj("archive") ?: return null
        val bvid = a.str("bvid").ifEmpty { return null }
        val author = json.author
        val at = author.long("pub_ts") * 1000
        return MixedItem(
            source = VideoSource.Bilibili,
            card = VideoItem(
                bvid = bvid,
                aid = a.long("aid"),
                title = a.str("title"),
                cover = a.str("cover"),
                duration = Fmt.parseDuration(a.str("duration_text")),
                ownerMid = author.long("mid"),
                ownerName = author.str("name"),
                ownerFace = author.str("face"),
                pubdate = at / 1000,
            ),
            at = at,
        )
    }

    /**
     * Signed in: the account's subscriptions feed, paged. Signed out: every local subscription's
     * latest uploads at once (one page), as the YouTube part's own 订阅 does.
     */
    private suspend fun youtube(cursor: String?, first: Boolean): Pair<List<MixedItem>, String?> {
        if (Accounts.youtube.value is Profile.SignedIn) {
            val page = YtFeeds.videos(YtFeeds.Feed.Subscriptions, cursor)
            return page.items.filterNot { it.isShort || Mutes.hides(it) }.map { it.toMixed() } to page.nextCursor.takeIf { page.hasMore }
        }
        if (!first) return emptyList<MixedItem>() to null
        val channels = Subscriptions.channels.value
        val gate = Semaphore(6)
        val all = coroutineScope {
            channels.map { c ->
                async {
                    gate.withPermit {
                        runCatching { Yt.latest(c.url).map { if (it.channelAvatar.isEmpty()) it.copy(channelAvatar = c.avatar) else it } }
                            .getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }
        return all.filterNot { it.isShort || Mutes.hides(it) }.map { it.toMixed() } to null
    }

    private fun YtVideo.toMixed() = MixedItem(
        source = VideoSource.YouTube,
        card = VideoItem(
            title = title,
            cover = thumbnail,
            duration = durationSec,
            ownerName = channelName,
            ownerFace = channelAvatar,
            views = if (views > 0) views else -1,
            pubdate = uploadedAt / 1000,
            badge = "YouTube",
            // Never opened (the card's tap goes to the YouTube part); a key of its own for the card's shared bounds.
            target = Route.Web(url, title),
        ),
        // Undated uploads sink to the bottom rather than float to the top.
        at = uploadedAt.takeIf { it > 0 } ?: 0L,
        ytUrl = url,
    )
}

/**
 * The 关注 tab when both sources are ticked: one grid, PiliPaw's cards, newest first. A YouTube
 * card hands its link to the YouTube part and brings it to the front.
 */
@Composable
internal fun MixedFollowTab(state: LazyGridState, reselect: Flow<Unit>, headerBehavior: TopAppBarScrollBehavior) {
    val vm: MixedFeedViewModel = viewModel(key = "home_mixed") { MixedFeedViewModel() }
    PagedGrid(
        pager = vm.feed,
        state = state,
        columns = homeVideoColumns(),
        key = { it.key },
        horizontalSpacing = 12.dp,
        emptyLabel = "关注的人还没有新投稿",
        reselect = reselect,
        headerBehavior = headerBehavior,
    ) { _, item ->
        val url = item.ytUrl
        if (url == null) {
            VideoCardV(item.card, feedItemMotion())
        } else {
            VideoCardV(
                item.card,
                feedItemMotion(),
                onLongClick = {},
                onClick = {
                    Sources.show(VideoSource.YouTube)
                    YtLinks.pending.trySend(url)
                },
            )
        }
    }
}
