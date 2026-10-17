package com.gpo.pilipala.feature.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "HomeFeed"

internal fun VideoItem.feedKey(): Any = when {
    bvid.isNotEmpty() -> bvid
    target != null -> target
    else -> "$title|$cover"
}

/** Header: default search word. */
internal class HomeHeaderViewModel : ViewModel() {
    var searchHint by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            BiliClient.ensureBuvid()
            searchHint = runCatching { HomeApi.searchDefault() }.getOrDefault("")
        }
    }
}

/** 推荐: web feed by default, the app feed when the setting asks for it. */
internal class RcmdViewModel : ViewModel() {
    private var freshIdx = 0
    private var appIdx = 0L

    /** App-feed cards keep what "不感兴趣" needs; web cards are only hidden locally. */
    private val appRefs = mutableMapOf<Any, AppFeedRef>()

    val feed = FeedPager(viewModelScope, key = { it.feedKey() }) { cursor -> load(cursor == null) }

    init {
        feed.refresh()
        viewModelScope.launch { AppSettings.rcmdSource.state.drop(1).collect { feed.refresh() } }
        viewModelScope.launch {
            combine(RcmdMinDuration.state, RcmdMinLikeRatio.state, RcmdExemptFollowed.state) { a, b, c -> Triple(a, b, c) }
                .drop(1)
                .collect { feed.refresh() }
        }
        viewModelScope.launch {
            Account.user.map { it?.mid }.distinctUntilChanged().drop(1).collect {
                Blacklist.ensureLoaded()
                feed.refresh()
            }
        }
        viewModelScope.launch {
            Blacklist.ensureLoaded()
            Blacklist.mids.collect { blocked -> feed.removeWhere { it.ownerMid > 0 && it.ownerMid in blocked } }
        }
    }

    fun appRef(item: VideoItem): AppFeedRef? = appRefs[item.feedKey()]

    fun remove(item: VideoItem) {
        val key = item.feedKey()
        feed.removeWhere { it.feedKey() == key }
    }

    private suspend fun load(first: Boolean): Page<VideoItem> {
        BiliClient.ensureBuvid()
        if (first) {
            freshIdx = 0
            appIdx = 0
            appRefs.clear()
        }
        val wantApp = AppSettings.rcmdSource.value == "app"
        // Signed in: the app feed is only personal with an access_key, so get
        // one first; without it the cookie-personalised web feed is better.
        val accessKey = if (wantApp) AppAuth.ensure() else ""
        val useApp = wantApp && (accessKey.isNotEmpty() || !BiliClient.isLoggedIn)
        val list = if (useApp) {
            try {
                val page = HomeApi.rcmdApp(appIdx, pull = first, accessKey = accessKey)
                appIdx = page.nextIdx
                page.items.zip(page.refs).forEach { (item, ref) -> appRefs[item.feedKey()] = ref }
                Log.i(TAG, "rcmd app page: ${page.items.size} items, key=${accessKey.isNotEmpty()}")
                page.items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // -101 / -663: the token no longer works; drop it so the next refresh asks again.
                if (e is BiliException && (e.code == -101 || e.code == -663 || e.code == -2)) AppAuth.clear()
                Log.w(TAG, "rcmd app failed, web fallback", e)
                HomeApi.rcmdWeb(freshIdx)
            }
        } else {
            HomeApi.rcmdWeb(freshIdx)
        }
        freshIdx++
        return Page(list, hasMore = true, nextCursor = freshIdx.toString())
    }
}

/** 热门 */
internal class HotViewModel : ViewModel() {
    val feed = FeedPager(viewModelScope, key = { it.feedKey() }) { cursor ->
        BiliClient.ensureBuvid()
        val pn = cursor?.toIntOrNull() ?: 1
        val (list, more) = HomeApi.hot(pn)
        Page(list, hasMore = more, nextCursor = (pn + 1).toString())
    }

    init {
        feed.refresh()
        viewModelScope.launch {
            Blacklist.ensureLoaded()
            Blacklist.mids.collect { blocked -> feed.removeWhere { it.ownerMid > 0 && it.ownerMid in blocked } }
        }
    }

    fun remove(item: VideoItem) {
        val key = item.feedKey()
        feed.removeWhere { it.feedKey() == key }
    }
}

/** 直播: followed rooms that are live (signed in) + the recommend list. */
internal class LiveViewModel : ViewModel() {
    var following by mutableStateOf<List<LiveFollowItem>>(emptyList())
        private set

    val feed = FeedPager(viewModelScope, key = { it.roomId }) { cursor ->
        BiliClient.ensureBuvid()
        val page = cursor?.toIntOrNull() ?: 1
        if (page == 1) loadFollowing()
        val (list, more) = HomeApi.liveRecommend(page)
        Page(list, hasMore = more, nextCursor = (page + 1).toString())
    }

    init {
        feed.refresh()
        viewModelScope.launch {
            Account.user.map { it?.mid }.distinctUntilChanged().drop(1).collect { feed.refresh() }
        }
    }

    private fun loadFollowing() {
        if (!BiliClient.isLoggedIn) {
            following = emptyList()
            return
        }
        viewModelScope.launch {
            following = runCatching { HomeApi.liveFollowing() }.getOrDefault(following)
        }
    }
}

/** 番剧: my followed bangumi (signed in) + the index list. */
internal class BangumiTabViewModel : ViewModel() {
    /** null = not loaded / guest. */
    var follows by mutableStateOf<List<BangumiItem>?>(null)
        private set
    var followsLoading by mutableStateOf(false)
        private set

    val feed = FeedPager(viewModelScope, key = { it.seasonId }) { cursor ->
        val page = cursor?.toIntOrNull() ?: 1
        if (page == 1) loadFollows()
        val (list, more) = HomeApi.bangumiIndex(page)
        Page(list, hasMore = more, nextCursor = (page + 1).toString())
    }

    init {
        feed.refresh()
        viewModelScope.launch {
            Account.user.map { it?.mid }.distinctUntilChanged().drop(1).collect { feed.refresh() }
        }
    }

    private fun loadFollows() {
        val mid = Account.user.value?.mid?.takeIf { it > 0 } ?: BiliClient.mid.takeIf { BiliClient.isLoggedIn && it > 0 }
        if (mid == null) {
            follows = null
            return
        }
        viewModelScope.launch {
            followsLoading = true
            follows = runCatching { HomeApi.bangumiFollow(mid) }.getOrElse { follows ?: emptyList() }
            followsLoading = false
        }
    }
}
