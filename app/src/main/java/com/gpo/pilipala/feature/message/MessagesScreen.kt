package com.gpo.pilipala.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.PiliLargeTopBar
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.PiliSymbols
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Immutable
internal data class Unread(val reply: Int = 0, val at: Int = 0, val like: Int = 0, val system: Int = 0)

@Immutable
internal data class Session(
    val talkerId: Long,
    val name: String,
    val face: String,
    val preview: String,
    /** Unix seconds of the last message. */
    val time: Long,
    val unread: Int,
    /** Paging cursor (microseconds). */
    val sessionTs: Long,
    val pinned: Boolean,
)

internal class MessagesViewModel : ViewModel() {
    var unread by mutableStateOf(Unread())
        private set
    var whisperUnread by mutableStateOf(0)
        private set

    val sessions = Pager<Session>(viewModelScope, dedupKey = { it.talkerId }) { cursor -> loadSessions(cursor) }

    private var started = false

    fun ensureLoaded() {
        if (started) return
        started = true
        refresh()
    }

    fun refresh() {
        loadUnread()
        sessions.refresh()
    }

    fun clear(kind: String) {
        unread = when (kind) {
            "reply" -> unread.copy(reply = 0)
            "at" -> unread.copy(at = 0)
            "like" -> unread.copy(like = 0)
            "system" -> unread.copy(system = 0)
            else -> unread
        }
    }

    fun markSessionRead(talkerId: Long) {
        sessions.update { list -> list.map { if (it.talkerId == talkerId) it.copy(unread = 0) else it } }
    }

    private fun loadUnread() {
        viewModelScope.launch {
            val feed = async {
                runCatching { BiliClient.getData("/x/msgfeed/unread", mapOf("build" to 0, "mobi_app" to "web")).asObj }.getOrNull()
                    ?: runCatching { BiliClient.getData("${BiliHosts.VC}/x/im/web/msgfeed/unread").asObj }.getOrNull()
            }
            val whisper = async {
                runCatching {
                    BiliClient.getData(
                        "${BiliHosts.VC}/session_svr/v1/session_svr/single_unread",
                        mapOf("unread_type" to 0, "show_unfollow_list" to 1, "show_dustbin" to 1, "build" to 0, "mobi_app" to "web"),
                    ).asObj
                }.getOrNull()
            }
            feed.await()?.let { d ->
                unread = Unread(
                    reply = d.int("reply"),
                    at = d.int("at"),
                    like = d.int("like"),
                    system = d.int("sys_msg"),
                )
            }
            whisper.await()?.let { d ->
                whisperUnread = d.int("follow_unread") + d.int("unfollow_unread")
            }
        }
    }

    private suspend fun loadSessions(cursor: String?): Page<Session> {
        val params = mutableMapOf<String, Any?>(
            "session_type" to 1,
            "group_fold" to 1,
            "unfollow_fold" to 0,
            "sort_rule" to 2,
            "build" to 0,
            "mobi_app" to "web",
        )
        if (cursor != null) params["end_ts"] = cursor
        val data = BiliClient.getData("${BiliHosts.VC}/session_svr/v1/session_svr/get_sessions", params, wbi = true).asObj
        val raw = data.arr("session_list").objects()
        val users = fetchUserCards(raw.map { it.long("talker_id") }.filter { it > 0 })
        val list = raw.map { s ->
            val id = s.long("talker_id")
            val last = s.obj("last_msg")
            val user = users[id]
            Session(
                talkerId = id,
                name = user?.name?.ifEmpty { null } ?: s.str("group_name").ifEmpty { id.toString() },
                face = user?.face?.ifEmpty { null } ?: s.str("group_cover"),
                preview = msgPreview(last.int("msg_type"), parseMsgContent(last.str("content")), last.int("msg_status")),
                time = last.long("timestamp"),
                unread = s.int("unread_count"),
                sessionTs = s.long("session_ts"),
                pinned = s.long("top_ts") > 0,
            )
        }
        val hasMore = data.int("has_more") == 1 && list.isNotEmpty()
        return Page(list, hasMore, list.lastOrNull()?.sessionTs?.toString())
    }
}

private data class NoticeEntry(
    val kind: String,
    val label: String,
    val icon: ImageVector,
    val container: @Composable () -> Color,
    val content: @Composable () -> Color,
)

private val Notices = listOf(
    NoticeEntry("reply", "回复我的", PiliSymbols.Comment, { MaterialTheme.colorScheme.primaryContainer }, { MaterialTheme.colorScheme.onPrimaryContainer }),
    NoticeEntry("at", "@我的", PiliSymbols.At, { MaterialTheme.colorScheme.secondaryContainer }, { MaterialTheme.colorScheme.onSecondaryContainer }),
    NoticeEntry("like", "收到的赞", PiliSymbols.ThumbUp, { MaterialTheme.colorScheme.tertiaryContainer }, { MaterialTheme.colorScheme.onTertiaryContainer }),
    NoticeEntry("system", "系统通知", PiliSymbols.Bell, { MaterialTheme.colorScheme.surfaceContainerHighest }, { MaterialTheme.colorScheme.onSurface }),
)

@Composable
fun MessagesScreen() {
    val nav = LocalNavigator.current
    val user by Account.user.collectAsState()
    val loggedIn = user != null || BiliClient.isLoggedIn
    val vm = viewModel { MessagesViewModel() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val pager = vm.sessions
    LoadMoreEffect(listState) { pager.loadMore() }
    LaunchedEffect(loggedIn) { if (loggedIn) vm.ensureLoaded() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { PiliLargeTopBar("消息", scrollBehavior) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (!loggedIn) {
            LoginPrompt(Modifier.padding(padding))
            return@Scaffold
        }
        RefreshBox(
            isRefreshing = pager.isRefreshing && !pager.initialLoading,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().readingWidth(),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                ),
            ) {
                item(key = "notices") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Notices.forEach { n ->
                            val count = when (n.kind) {
                                "reply" -> vm.unread.reply
                                "at" -> vm.unread.at
                                "like" -> vm.unread.like
                                else -> vm.unread.system
                            }
                            NoticeTile(n, count, Modifier.weight(1f)) {
                                vm.clear(n.kind)
                                nav.push(Route.MessageList(n.kind))
                            }
                        }
                    }
                }
                item(key = "whisper-header") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "私信",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (vm.whisperUnread > 0) {
                            Spacer(Modifier.width(8.dp))
                            Badge { Text(badgeText(vm.whisperUnread)) }
                        }
                    }
                }
                when {
                    pager.initialLoading -> item(key = "loading") { LoadingFooter(Modifier.padding(top = 48.dp)) }
                    pager.items.isEmpty() && pager.error != null -> item(key = "error") {
                        ErrorState(pager.error!!, Modifier.height(320.dp), onRetry = pager::refresh)
                    }
                    pager.items.isEmpty() -> item(key = "empty") {
                        EmptyState("暂无私信", Modifier.height(280.dp), icon = PiliSymbols.Mail)
                    }
                }
                items(pager.items, key = { it.talkerId }) { s ->
                    SessionRow(s, Modifier.animateItem()) {
                        vm.markSessionRead(s.talkerId)
                        nav.push(Route.WhisperDetail(s.talkerId, s.name, s.face))
                    }
                }
                if (pager.isLoadingMore) item(key = "more") { LoadingFooter() }
            }
        }
    }
}

private fun badgeText(n: Int) = if (n > 99) "99+" else n.toString()

@Composable
private fun NoticeTile(entry: NoticeEntry, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val content = entry.content()
    Column(
        modifier
            .clip(YoinContainerShapes.Card)
            .background(entry.container())
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BadgedBox(badge = { if (count > 0) Badge { Text(badgeText(count)) } }) {
            Icon(entry.icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
        }
        Text(
            entry.label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionRow(s: Session, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(YoinContainerShapes.ListRow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgedBox(badge = { if (s.unread > 0) Badge { Text(badgeText(s.unread)) } }) {
            Avatar(s.face, 48.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (s.unread > 0) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    Fmt.relativeTime(s.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (s.pinned) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}
