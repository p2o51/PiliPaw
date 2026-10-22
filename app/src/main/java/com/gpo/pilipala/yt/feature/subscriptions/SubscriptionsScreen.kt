package com.gpo.pilipala.yt.feature.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.core.account.AccountWrites
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.core.extractor.YtAccount
import com.gpo.pilipala.yt.core.extractor.YtFeeds
import com.gpo.pilipala.yt.ui.components.ChannelRow
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.yt.ui.components.LocalBottomBarInset
import com.gpo.pilipala.yt.ui.components.friendlyMessage
import com.gpo.pilipala.yt.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

/** A channel URL's id (`…/channel/UC…`), the key the account and the local list share. */
private fun channelId(url: String): String = url.trimEnd('/').substringAfterLast('/')

/**
 * 订阅. Signed out: the local subscriptions, newest first. Signed in: the account's channels,
 * then 本机, the local subscriptions the account does not have, with 导入 (a write to the
 * account: a dry run in debug builds).
 */
@Composable
fun SubscriptionsScreen() {
    val local by Subscriptions.channels.collectAsState()
    val profile by Accounts.youtube.collectAsState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val signedIn = profile as? Profile.SignedIn

    var account by remember(signedIn?.cookies) { mutableStateOf<List<ChannelItem>?>(null) }
    var error by remember(signedIn?.cookies) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(signedIn?.cookies, attempt) {
        if (signedIn == null) return@LaunchedEffect
        error = null
        runCatching { YtFeeds.channels() }
            .onSuccess { account = it }
            .onFailure { error = it.friendlyMessage() }
    }

    if (signedIn != null) {
        if (account == null) {
            if (error != null) {
                ErrorState(error.orEmpty(), Modifier.padding(top = statusTop), onRetry = { attempt++ })
            } else {
                LoadingState()
            }
            return
        }
    } else if (local.isEmpty()) {
        EmptyState("暂无订阅", Modifier.padding(top = statusTop), icon = YoinSymbols.Library)
        return
    }

    val mine = account.orEmpty()
    val known = remember(mine) { mine.map { channelId(it.url) }.toSet() }
    val onlyLocal = if (signedIn != null) local.filter { channelId(it.url) !in known } else emptyList()
    val state = rememberLazyListState()
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().readingWidth().seamViewport(state, underStatusBar = true),
        contentPadding = PaddingValues(top = statusTop, bottom = LocalBottomBarInset.current + 16.dp),
    ) {
        item(key = "title") {
            Text("订阅", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        }
        if (signedIn != null) {
            items(mine, key = { "a:" + it.url }) { ChannelRow(it) }
            if (onlyLocal.isNotEmpty()) {
                item(key = "local") { LocalHeader(onlyLocal) }
                items(onlyLocal, key = { "l:" + it.url }) { ChannelRow(it) }
            }
        } else {
            items(local, key = { it.url }) { ChannelRow(it) }
        }
    }
}

/** 本机 above the local-only channels, with 导入 (subscribes the account to them). */
@Composable
private fun LocalHeader(channels: List<ChannelItem>) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("本机", style = MaterialTheme.typography.titleMedium)
            Text(
                "${channels.size} 个频道",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = {
            scope.launch {
                val message = runCatching {
                    AccountWrites.run("导入 ${channels.size} 个订阅") { YtAccount.subscribe(channels.map { channelId(it.url) }) }
                }.getOrElse { it.friendlyMessage() }
                toast(message)
            }
        }) { Text("导入") }
    }
}
