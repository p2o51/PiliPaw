package com.gpo.pilipala.feature.home

import android.util.Log
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.animation.core.animate
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.gpo.pilipala.core.net.AppSign
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.videomenu.VideoCardMenu
import com.gpo.pilipala.feature.widget.WidgetStore
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.theme.YoinMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "FeedActions"

/** mid the stored [AppAccessKey] belongs to; a key for another account is ignored. */
internal val AppAccessKeyMid = AppSettings.register("access_key_mid", 0L)

// ── access_key (Flutter MemberHttp.cookieToKey) ─────────────────────────────

/**
 * Turns the web session into an app access_key the way pilipala does: ask the
 * TV login for a QR auth_code, confirm it with the signed-in cookies (the H5
 * "scan" step), then poll it once for the token.
 */
internal object AppAuth {
    private const val TV_PASSPORT = "https://passport.snm0516.aisee.tv"
    private val mutex = Mutex()

    /** One automatic attempt per session; [refresh] forces another. */
    @Volatile
    private var triedThisSession = false

    /** The key for the signed-in account, or "" (guest / not obtained / other account). */
    val validKey: String
        get() {
            if (!BiliClient.isLoggedIn) return ""
            val key = AppAccessKey.value
            val mid = AppAccessKeyMid.value
            return if (key.isNotEmpty() && (mid == 0L || mid == BiliClient.mid)) key else ""
        }

    /** Valid key, obtaining one first if this session has not tried yet. */
    suspend fun ensure(): String {
        validKey.takeIf { it.isNotEmpty() }?.let { return it }
        if (!BiliClient.isLoggedIn || triedThisSession) return ""
        return mutex.withLock {
            validKey.takeIf { it.isNotEmpty() } ?: run {
                triedThisSession = true
                runCatching { exchange() }
                    .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "access_key", it) }
                    .getOrDefault("")
            }
        }
    }

    /** Drops the key and fetches a new one; throws on failure. */
    suspend fun refresh(): String = mutex.withLock {
        clear()
        triedThisSession = true
        exchange()
    }

    fun clear() {
        AppSettings.set(AppAccessKey, "")
        AppSettings.set(AppAccessKeyMid, 0L)
    }

    private fun tvParams(extra: Map<String, String> = emptyMap()): Map<String, String> =
        AppSign.sign(extra + mapOf("local_id" to "0", "ts" to (System.currentTimeMillis() / 1000).toString()))

    private suspend fun exchange(): String {
        check(BiliClient.isLoggedIn) { "未登录" }
        // The TV endpoints must not replace the web session cookies.
        val guarded = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")
        val before = guarded.associateWith { BiliClient.cookies.get(it) }
        try {
            val code = BiliClient.unwrap(
                BiliClient.postForm(TV_PASSPORT + "/x/passport-tv-login/qrcode/auth_code", params = tvParams(), csrf = false),
            ).asObj.str("auth_code")
            check(code.isNotEmpty()) { "auth_code 为空" }
            BiliClient.unwrap(
                BiliClient.postForm(
                    BiliHosts.PASSPORT + "/x/passport-tv-login/h5/qrcode/confirm",
                    form = mapOf("auth_code" to code, "build" to 708200, "csrf" to BiliClient.csrf),
                ),
            )
            delay(300)
            val data = BiliClient.unwrap(
                BiliClient.postForm(
                    BiliHosts.PASSPORT + "/x/passport-tv-login/qrcode/poll",
                    params = tvParams(mapOf("auth_code" to code)),
                    csrf = false,
                ),
            ).asObj
            val token = data.str("access_token")
            check(token.isNotEmpty()) { "access_key 为空" }
            AppSettings.set(AppAccessKey, token)
            AppSettings.set(AppAccessKeyMid, data.long("mid").takeIf { it > 0 } ?: BiliClient.mid)
            return token
        } finally {
            before.forEach { (name, value) ->
                if (value != null && BiliClient.cookies.get(name) != value) BiliClient.cookies.put(name, value)
            }
        }
    }
}

// ── Blacklist (/x/relation/blacks) ──────────────────────────────────────────

/** Blocked UP mids, fetched once per session when signed in. */
internal object Blacklist {
    private val _mids = MutableStateFlow<Set<Long>>(emptySet())
    val mids: StateFlow<Set<Long>> = _mids.asStateFlow()
    private val mutex = Mutex()
    private var loadedFor = -1L

    fun blocks(mid: Long): Boolean = mid > 0 && mid in _mids.value

    suspend fun ensureLoaded() {
        val me = if (BiliClient.isLoggedIn) BiliClient.mid else 0L
        if (loadedFor == me) return
        mutex.withLock {
            if (loadedFor == me) return
            if (me == 0L) {
                _mids.value = emptySet()
                loadedFor = 0L
                return
            }
            runCatching {
                val all = mutableSetOf<Long>()
                var pn = 1
                while (pn <= 40) {
                    val data = BiliClient.getData("/x/relation/blacks", mapOf("pn" to pn, "ps" to 50, "re_version" to 0)).asObj
                    val page = data.arr("list").objects().map { it.long("mid") }.filter { it > 0 }
                    all += page
                    val total = data.int("total")
                    if (page.isEmpty() || (total > 0 && all.size >= total)) break
                    pn++
                }
                all
            }.onSuccess {
                _mids.value = it
                loadedFor = me
            }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "blacks", it) }
        }
    }

    fun add(mid: Long) {
        if (mid > 0) _mids.value = _mids.value + mid
    }
}

/** Shared filter for rcmd / hot / rank lists. */
internal fun List<VideoItem>.withoutBlocked(): List<VideoItem> {
    val blocked = Blacklist.mids.value
    return if (blocked.isEmpty()) this else filterNot { it.ownerMid > 0 && it.ownerMid in blocked }
}

// ── Card actions ────────────────────────────────────────────────────────────

/** What the app feed needs to send a dislike for one card. */
internal data class AppFeedRef(val goto: String, val id: String, val reasonId: Int?)

internal object FeedActions {
    suspend fun watchLater(item: VideoItem) {
        val form = if (item.aid > 0) mapOf("aid" to item.aid) else mapOf("bvid" to item.bvid)
        BiliClient.postData("/x/v2/history/toview/add", form)
        WidgetStore.laterChanged()
    }

    suspend fun block(mid: Long) {
        BiliClient.postData("/x/relation/modify", mapOf("fid" to mid, "act" to 5, "re_src" to 11))
        Blacklist.add(mid)
    }

    /** App feed "不感兴趣" (`app.bilibili.com/x/feed/dislike`). */
    suspend fun dislikeApp(ref: AppFeedRef) {
        val key = AppAuth.validKey
        check(key.isNotEmpty()) { "access_key 无效" }
        val params = buildMap {
            put("goto", ref.goto)
            put("id", ref.id)
            put("reason_id", (ref.reasonId ?: 1).toString())
            put("build", "1")
            put("mobi_app", "android")
            put("access_key", key)
            put("ts", (System.currentTimeMillis() / 1000).toString())
        }
        BiliClient.getData(BiliHosts.APP + "/x/feed/dislike", AppSign.sign(params))
        Log.i(TAG, "dislike goto=${ref.goto} id=${ref.id} reason=${params["reason_id"]}")
    }
}

// ── Long-press sheet ────────────────────────────────────────────────────────

/**
 * Menu for one feed card: the shared video card menu (VideoCardMenu.kt) as a feed list
 * has it, with 不感兴趣 and 拉黑 UP. [onRemove] drops the card from its list (the grid
 * animates it out).
 */
@Composable
internal fun FeedCardSheet(
    item: VideoItem,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    dislike: (suspend () -> Unit)? = null,
) {
    VideoCardMenu(item = item, onDismiss = onDismiss, onRemove = onRemove, dislike = dislike)
}

// ── Tab reselect ────────────────────────────────────────────────────────────

/**
 * Reselecting a tab: scroll to the top, or refresh when already there. The
 * header comes back with the list.
 */
@Composable
internal fun ReselectEffect(
    events: Flow<Unit>,
    state: LazyGridState,
    header: TopAppBarScrollBehavior?,
    onRefresh: () -> Unit,
) {
    val refresh by rememberUpdatedState(onRefresh)
    val spring = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(events, state, header) {
        events.collect {
            val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
            if (atTop) {
                refresh()
            } else {
                launch {
                    val bar = header?.state ?: return@launch
                    animate(bar.heightOffset, 0f, animationSpec = spring) { v, _ -> bar.heightOffset = v }
                }
                if (state.firstVisibleItemIndex > 12) state.scrollToItem(12)
                state.animateScrollToItem(0)
            }
        }
    }
}
