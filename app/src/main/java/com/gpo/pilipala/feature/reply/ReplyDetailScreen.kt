package com.gpo.pilipala.feature.reply

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.LoadMoreEffect
import com.gpo.pilipala.ui.components.PiliTopBar
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion

/** Reply thread ("楼中楼"): the root comment, then its paged replies, with a reply composer. */
@Composable
fun ReplyDetailScreen(route: Route.ReplyDetail) {
    val vm: ReplyThreadViewModel = viewModel(key = "reply_thread_${route.oid}_${route.type}_${route.rpid}") {
        ReplyThreadViewModel(route.oid, route.type, route.rpid)
    }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val ui = rememberReplyUiState()
    val listState = rememberLazyListState()
    val pager = vm.pager
    val me by Account.user.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LoadMoreEffect(listState) { pager.loadMore() }

    val actions = rememberReplyActions(
        vm = vm,
        ui = ui,
        onReply = { r ->
            if (r.rpid == route.rpid || r.root == 0L) {
                ComposeTarget(root = route.rpid, parent = r.rpid, replyTo = r.member.name)
            } else {
                ComposeTarget(root = route.rpid, parent = r.rpid, replyTo = r.member.name, prefixMention = true)
            }
        },
        onOpenThread = null,
        onTimestampClick = null,
    )
    val replyToRoot = {
        val root = vm.root
        if (requireLogin(nav, toast)) {
            ui.composer = ComposeTarget(root = route.rpid, parent = route.rpid, replyTo = root?.member?.name.orEmpty())
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { PiliTopBar("评论详情", scrollBehavior = scrollBehavior) },
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .navigationBarsPadding(),
            ) {
                ComposePill(
                    face = me?.face,
                    label = vm.root?.member?.name?.let { "回复 @$it" } ?: "回复",
                    modifier = Modifier.readingWidth(),
                    onClick = replyToRoot,
                )
            }
        },
    ) { padding ->
        RefreshBox(
            isRefreshing = pager.isRefreshing && vm.root != null,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
            indicatorPadding = PaddingValues(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            ) {
                val root = vm.root
                if (root == null) {
                    item(key = "thread:state") {
                        ListState(pager = pager, hasTop = false, onRetry = vm::refresh, modifier = Modifier.animateItem().readingWidth())
                    }
                } else {
                    item(key = "thread:root", contentType = "root") {
                        ReplyRow(
                            reply = root,
                            actions = actions,
                            modifier = Modifier.animateItem().readingWidth(),
                            avatarSize = 40.dp,
                            showPreview = false,
                            clampLines = false,
                        ) { actions.onReply(root) }
                    }
                    item(key = "thread:header", contentType = "header") {
                        ThreadHeader(vm.count, Modifier.readingWidth())
                    }
                    if (pager.items.isEmpty()) {
                        item(key = "thread:empty") {
                            ListState(pager = pager, hasTop = false, onRetry = vm::refresh, modifier = Modifier.animateItem().readingWidth())
                        }
                    } else {
                        items(pager.items, key = { "thread:${it.rpid}" }, contentType = { "reply" }) { r ->
                            ReplyRow(
                                reply = r,
                                actions = actions,
                                modifier = Modifier.animateItem().readingWidth(),
                                avatarSize = 32.dp,
                                showPreview = false,
                                clampLines = false,
                            ) { actions.onReply(r) }
                        }
                        item(key = "thread:footer") { PagerFooter(pager, Modifier.readingWidth()) }
                    }
                }
            }
        }
    }
    ReplyOverlays(ui, vm) { deleted -> if (deleted.rpid == route.rpid) nav.pop() }
}

@Composable
private fun ThreadHeader(count: Long, modifier: Modifier = Modifier) {
    val motion = replyMotion()
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, ContinuousRoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("相关回复", style = MaterialTheme.typography.titleSmall)
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    motion.crossfade()
                },
                label = "threadCount",
            ) { c ->
                Text(
                    Fmt.count(c),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
