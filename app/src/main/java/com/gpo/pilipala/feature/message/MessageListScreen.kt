package com.gpo.pilipala.feature.message

import com.gpo.pilipala.ui.components.FameMode
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.LinkRouter
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.feature.reply.ReplyAnchor
import com.gpo.pilipala.feature.reply.ReplyAnchors
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.Page
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.PiliLargeTopBar
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

@Immutable
internal data class MsgEntry(
    val key: String,
    val users: List<MsgUser> = emptyList(),
    val action: String = "",
    val content: String = "",
    /** nickname → mid for @mentions inside [content]. */
    val mentions: Map<String, Long> = emptyMap(),
    val sourceText: String = "",
    val sourceImage: String = "",
    /** Unix seconds. */
    val time: Long = 0,
    val timeText: String = "",
    val counts: Int = 0,
    val uri: String = "",
    val replyRoute: Route? = null,
    /** The comment this entry is about: tapping opens [uri]'s page on its comments with it pinned. */
    val anchor: ReplyAnchor? = null,
    val title: String = "",
    val cursor: Long = 0,
)

private fun titleOf(kind: String) = when (kind) {
    "reply" -> "回复我的"
    "at" -> "@我的"
    "like" -> "收到的赞"
    "system" -> "系统通知"
    else -> "消息"
}

internal class MessageListViewModel(private val kind: String) : ViewModel() {
    val pager = Pager<MsgEntry>(viewModelScope, dedupKey = { it.key }) { cursor ->
        // JSON mapping stays off the main thread.
        withContext(Dispatchers.Default) {
            when (kind) {
                "reply" -> loadFeed("/x/msgfeed/reply", "reply_time", cursor) { parseReply(it) }
                "at" -> loadFeed("/x/msgfeed/at", "at_time", cursor) { parseAt(it) }
                "like" -> loadLike(cursor)
                else -> loadSystem()
            }
        }
    }

    private var started = false

    fun ensureLoaded() {
        if (started) return
        started = true
        pager.refresh()
    }

    private suspend fun loadFeed(
        path: String,
        timeKey: String,
        cursor: String?,
        parse: (JsonObject) -> MsgEntry,
    ): Page<MsgEntry> {
        val params = mutableMapOf<String, Any?>("platform" to "web", "build" to 0, "mobi_app" to "web")
        cursor?.split('|')?.let { (id, time) ->
            params["id"] = id
            params[timeKey] = time
        }
        val data = BiliClient.getData(path, params).asObj
        val c = data.obj("cursor")
        val list = data.arr("items").objects().map(parse)
        return Page(list, !c.bool("is_end") && list.isNotEmpty(), "${c.long("id")}|${c.long("time")}")
    }

    private suspend fun loadLike(cursor: String?): Page<MsgEntry> {
        val params = mutableMapOf<String, Any?>("platform" to "web", "build" to 0, "mobi_app" to "web")
        cursor?.split('|')?.let { (id, time) ->
            params["id"] = id
            params["like_time"] = time
        }
        val data = BiliClient.getData("/x/msgfeed/like", params).asObj
        val total = data.obj("total")
        val c = total.obj("cursor")
        val latest = if (cursor == null) data.obj("latest").arr("items").objects() else emptyList()
        val list = (latest + total.arr("items").objects()).map(::parseLike)
        return Page(list, c != null && !c.bool("is_end") && list.isNotEmpty(), "${c.long("id")}|${c.long("time")}")
    }

    private suspend fun loadSystem(): Page<MsgEntry> = coroutineScope {
        val common = mapOf("csrf" to BiliClient.csrf, "page_size" to 20, "build" to 0, "mobi_app" to "web")
        val unified = async {
            runCatching { BiliClient.getData("${BiliHosts.MESSAGE}/x/sys-msg/query_unified_notify", common).asObj }
        }
        val personal = async {
            runCatching { BiliClient.getData("${BiliHosts.MESSAGE}/x/sys-msg/query_user_notify", common).asObj }
        }
        val u = unified.await()
        val p = personal.await()
        if (u.isFailure && p.isFailure) throw u.exceptionOrNull()!!
        val items = (u.getOrNull().arr("system_notify_list").objects() + p.getOrNull().arr("system_notify_list").objects())
            .map(::parseSystem)
            .sortedByDescending { it.cursor }
        items.firstOrNull()?.cursor?.takeIf { it > 0 }?.let { cur ->
            launch {
                runCatching {
                    BiliClient.get("${BiliHosts.MESSAGE}/x/sys-msg/update_cursor", mapOf("csrf" to BiliClient.csrf, "cursor" to cur))
                }
            }
        }
        Page(items, hasMore = false)
    }

    // ── Parsers ───────────────────────────────────────────────────────────

    private fun user(o: JsonObject?) = MsgUser(o.long("mid"), o.str("nickname"), o.str("avatar"))

    private fun mentions(item: JsonObject?): Map<String, Long> =
        item.arr("at_details").objects().associate { it.str("nickname") to it.long("mid") }

    private fun replyRoute(item: JsonObject?): Route? {
        val oid = item.long("subject_id")
        val type = item.int("business_id")
        val rpid = item.long("root_id").takeIf { it > 0 } ?: item.long("source_id")
        return if (oid > 0 && type > 0 && rpid > 0) Route.ReplyDetail(oid, type, rpid) else null
    }

    /** A reply / @ item: the new comment ([source_id]) inside its thread. */
    private fun commentAnchor(item: JsonObject?): ReplyAnchor? {
        val rpid = item.long("source_id")
        val oid = item.long("subject_id")
        val type = item.int("business_id")
        ReplyAnchors.parse(item.str("native_uri"), rpid, oid, type)?.let { return it }
        val root = item.long("root_id").takeIf { it > 0 } ?: rpid
        return if (oid > 0 && type > 0 && root > 0) ReplyAnchor(oid, type, root, rpid.takeIf { it > 0 } ?: root) else null
    }

    private fun parseReply(o: JsonObject): MsgEntry {
        val item = o.obj("item")
        val type = item.str("type")
        val business = item.str("business")
        return MsgEntry(
            key = "r${o.long("id")}",
            users = listOf(user(o.obj("user"))),
            action = if (type == "reply") "回复了我的评论" else "评论了我的${business.ifEmpty { "内容" }}",
            content = item.str("source_content"),
            mentions = mentions(item),
            sourceText = item.str("target_reply_content").ifEmpty { item.str("root_reply_content") }.ifEmpty { item.str("title") },
            sourceImage = item.str("image"),
            time = o.long("reply_time"),
            uri = item.str("uri").ifEmpty { item.str("native_uri") },
            replyRoute = replyRoute(item),
            anchor = commentAnchor(item),
        )
    }

    private fun parseAt(o: JsonObject): MsgEntry {
        val item = o.obj("item")
        val business = item.str("business")
        return MsgEntry(
            key = "a${o.long("id")}",
            users = listOf(user(o.obj("user"))),
            action = "在${business.ifEmpty { "评论" }}中@了我",
            content = item.str("source_content"),
            mentions = mentions(item),
            sourceText = item.str("title"),
            sourceImage = item.str("image"),
            time = o.long("at_time"),
            uri = item.str("uri").ifEmpty { item.str("native_uri") }.ifEmpty { item.str("native_url") },
            replyRoute = replyRoute(item),
            anchor = commentAnchor(item).takeIf { item.str("type") == "reply" },
        )
    }

    private fun parseLike(o: JsonObject): MsgEntry {
        val item = o.obj("item")
        return MsgEntry(
            key = "l${o.long("id")}",
            users = o.arr("users").objects().map(::user),
            action = "赞了我的${item.str("business").ifEmpty { "内容" }}",
            sourceText = item.str("title").ifEmpty { item.str("desc") },
            sourceImage = item.str("image"),
            time = o.long("like_time"),
            counts = o.int("counts"),
            uri = item.str("uri").ifEmpty { item.str("native_uri") },
            // A liked comment: the item is the user's own comment (item_id).
            anchor = if (item.str("type") == "reply") ReplyAnchors.parse(item.str("native_uri"), item.long("item_id")) else null,
        )
    }

    private fun parseSystem(o: JsonObject): MsgEntry {
        val raw = o.str("content")
        val contentObj = parseMsgContent(raw)
        val text = contentObj?.str("web")?.ifEmpty { null } ?: contentObj?.str("content")?.ifEmpty { null } ?: raw
        val (clean, link) = stripLinkMarkup(text)
        return MsgEntry(
            key = "s${o.long("id")}-${o.long("cursor")}",
            title = o.str("title"),
            content = clean,
            timeText = o.str("time_at"),
            uri = link ?: o.str("card_link"),
            sourceText = o.str("card_brief"),
            sourceImage = o.str("card_cover"),
            cursor = o.long("cursor"),
        )
    }
}

@Composable
fun MessageListScreen(route: Route.MessageList) {
    val user by Account.user.collectAsState()
    val loggedIn = user != null || BiliClient.isLoggedIn
    val vm = viewModel(key = "msglist-${route.kind}") { MessageListViewModel(route.kind) }
    val pager = vm.pager
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    LoadMoreEffect(listState) { pager.loadMore() }
    LaunchedEffect(loggedIn) { if (loggedIn) vm.ensureLoaded() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { PiliLargeTopBar(titleOf(route.kind), scrollBehavior) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
        when {
            !loggedIn -> LoginPrompt(body)
            pager.initialLoading -> LoadingState(body)
            pager.items.isEmpty() && pager.error != null -> ErrorState(pager.error!!, body, onRetry = pager::refresh)
            else -> RefreshBox(isRefreshing = pager.isRefreshing, onRefresh = pager::refresh, modifier = body) {
                if (pager.items.isEmpty()) {
                    EmptyState(
                        "暂无消息",
                        icon = when (route.kind) {
                            "reply" -> YoinSymbols.Comment
                            "at" -> YoinSymbols.At
                            "like" -> YoinSymbols.ThumbUp
                            else -> YoinSymbols.Bell
                        },
                    )
                }
                LazyColumn(
                    state = listState,
                    // Under the large top bar: rows sink under the tide line instead of being cut.
                    modifier = Modifier.fillMaxSize().readingWidth().seamViewport(listState),
                    contentPadding = PaddingValues(
                        start = if (route.kind == "system") 16.dp else 0.dp,
                        end = if (route.kind == "system") 16.dp else 0.dp,
                        top = 4.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (route.kind == "system") 10.dp else 2.dp),
                ) {
                    items(pager.items, key = { it.key }) { e ->
                        when (route.kind) {
                            "system" -> SystemCard(e, Modifier.animateItem())
                            "like" -> LikeRow(e, Modifier.animateItem())
                            else -> FeedRow(e, Modifier.animateItem())
                        }
                    }
                    if (pager.isLoadingMore) item(key = "more") { LoadingFooter() }
                }
            }
        }
    }
}

@Composable
private fun FeedRow(e: MsgEntry, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val actor = e.users.firstOrNull()
    val primary = MaterialTheme.colorScheme.primary
    val text = remember(e.content, e.mentions, primary) {
        buildAnnotatedString {
            if (e.mentions.isEmpty()) {
                append(e.content)
            } else {
                val re = Regex(e.mentions.keys.filter { it.isNotEmpty() }.joinToString("|") { "@" + Regex.escape(it) }.ifEmpty { "(?!)" })
                var last = 0
                re.findAll(e.content).forEach { m ->
                    append(e.content.substring(last, m.range.first))
                    withStyle(SpanStyle(color = primary)) { append(m.value) }
                    last = m.range.last + 1
                }
                append(e.content.substring(last))
            }
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(YoinContainerShapes.ListRow)
            .clickable { openEntry(nav, e) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Avatar(actor?.face, 42.dp) { actor?.let { nav.push(Route.Member(it.mid, it.face, it.name)) } }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.seamText(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    actor?.name.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    e.action,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (e.content.isNotEmpty()) {
                VSpace(6)
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.seamText())
            }
            VSpace(8)
            SourceBlock(e.sourceText, e.sourceImage, onClick = if (e.uri.isNotEmpty()) ({ nav.openUrl(e.uri) }) else null)
            VSpace(6)
            Text(
                Fmt.relativeTime(e.time),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.seamText(),
            )
        }
    }
}

/**
 * A comment entry opens the page it was left on, at its comments, with the comment pinned
 * on top (官方 does the same); without a native page for it, the thread page.
 */
private fun openEntry(nav: Navigator, e: MsgEntry) {
    val anchor = e.anchor
    if (anchor != null && e.uri.isNotEmpty()) {
        val route = LinkRouter.route(e.uri)
        if (route !is Route.Web) {
            ReplyAnchors.put(anchor)
            nav.push(route)
            return
        }
    }
    when {
        e.replyRoute != null -> nav.push(e.replyRoute)
        anchor != null && anchor.oid > 0 && anchor.type > 0 -> nav.push(Route.ReplyDetail(anchor.oid, anchor.type, anchor.root))
        e.uri.isNotEmpty() -> nav.openUrl(e.uri)
    }
}

@Composable
private fun LikeRow(e: MsgEntry, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val shown = e.users.take(3)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(YoinContainerShapes.ListRow)
            .clickable { openEntry(nav, e) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        // Overlapping avatars, first on top. Only the front one opens a profile: the ones
        // behind show 14dp slivers, too thin to hit; tapping them falls through to the row.
        // The slot is always three avatars wide so the text column lines up across rows.
        Box(Modifier.width(42.dp + 14.dp * 2).height(42.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(42.dp + 14.dp * (shown.size - 1).coerceAtLeast(0)).height(42.dp)) {
                shown.forEachIndexed { i, u ->
                    Avatar(
                        u.face,
                        42.dp,
                        Modifier.offset(x = 14.dp * i).zIndex((shown.size - i).toFloat()),
                        onClick = if (i == 0) ({ nav.push(Route.Member(u.mid, u.face, u.name)) }) else null,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val names = e.users.take(2).joinToString("、") { it.name }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(names) }
                    if (e.counts > 2 || e.counts > e.users.size.coerceAtMost(2)) {
                        // How many liked the user's own comment or video: a 名声模式 number.
                        val more = if (FameMode.hides(self = true)) "  等人" else "  等${e.counts}人"
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(more) }
                    }
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.seamText(),
            )
            VSpace(2)
            Text(e.action, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.seamText())
            VSpace(8)
            SourceBlock(e.sourceText, e.sourceImage)
            VSpace(6)
            Text(Fmt.relativeTime(e.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.seamText())
        }
    }
}

@Composable
private fun SystemCard(e: MsgEntry, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (e.uri.isNotEmpty()) Modifier.clickable { nav.openUrl(e.uri) } else Modifier)
            .padding(16.dp),
    ) {
        Text(e.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        VSpace(2)
        Text(e.timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (e.content.isNotEmpty()) {
            VSpace(10)
            Text(e.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (e.sourceText.isNotEmpty() || e.sourceImage.isNotEmpty()) {
            VSpace(10)
            SourceBlock(e.sourceText, e.sourceImage)
        }
    }
}
