package com.gpo.pilipala.yt.feature.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.core.extractor.ListItem
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.feature.player.miniPlayerInset
import com.gpo.pilipala.feature.settings.Choice
import com.gpo.pilipala.feature.settings.ConnectedToggleRow
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.yt.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.yt.ui.components.PawTopBar
import com.gpo.pilipala.yt.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.delay

/**
 * 新建 / 编辑 Feed (PiliPaw's editor, lighter): name, 混合 / 最新, the sources (a remove button
 * each), and one field that adds what is typed as a keyword or one of the channels it finds.
 * 完成 saves; an existing feed has 删除.
 */
@Composable
fun FeedEditorScreen(route: Route.FeedEditor) {
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val existing = route.id?.let { FeedStore.get(it) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var mix by remember { mutableStateOf(existing?.mix ?: FeedMix.Mixed) }
    val sources = remember { mutableStateListOf<FeedSource>().apply { addAll(existing?.sources.orEmpty()) } }
    var query by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<ChannelItem>>(emptyList()) }
    LaunchedEffect(query) {
        channels = emptyList()
        val q = query.trim()
        if (q.length < 2) return@LaunchedEffect
        delay(400)
        channels = runCatching {
            Yt.search(q, null, Yt.SearchFilter(type = Yt.SearchFilter.Type.Channels)).items
                .filterIsInstance<ListItem.Channel>().map { it.channel }.take(5)
        }.getOrDefault(emptyList())
    }
    fun add(s: FeedSource) {
        if (sources.size >= MaxSources) {
            toast("最多 $MaxSources 个来源")
            return
        }
        if (sources.none { it.key == s.key }) sources += s
        query = ""
    }
    Column(Modifier.fillMaxSize()) {
        PawTopBar(title = if (existing == null) "新建 Feed" else "编辑 Feed", onBack = { nav.pop() }, actions = {
            if (existing != null) {
                IconButton(onClick = {
                    FeedStore.delete(existing.id)
                    nav.pop()
                }) { Icon(YoinSymbols.Delete, "删除 Feed") }
            }
            TextButton(enabled = sources.isNotEmpty(), onClick = {
                FeedStore.save(existing?.id, name, sources.toList(), mix)
                nav.pop()
            }) { Text("完成") }
        })
        LazyColumn(
            Modifier.fillMaxSize().readingWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = miniPlayerInset() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    name,
                    { name = it.take(MaxFeedName) },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名称") },
                    placeholder = { Text(sources.firstOrNull()?.label ?: "Feed") },
                )
            }
            item(key = "mix") {
                ConnectedToggleRow(FeedMix.entries.map { Choice(it, it.label) }, mix, { mix = it }, Modifier.padding(vertical = 8.dp))
            }
            item(key = "sources-title") { Label("来源 ${sources.size}/$MaxSources") }
            items(sources, key = { it.key }) { s -> SourceRow(s) { sources.remove(s) } }
            item(key = "add") {
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    placeholder = { Text("关键词或频道") },
                    leadingIcon = { Icon(YoinSymbols.Search, null) },
                )
            }
            if (query.isNotBlank()) {
                item(key = "add-keyword") {
                    AddRow(null, "关键词「${query.trim()}」") { add(FeedSource.Keyword(query.trim())) }
                }
                items(channels, key = { "c:" + it.url }) { c ->
                    AddRow(c.avatar, c.name) { add(FeedSource.Channel(c.url, c.name, c.avatar)) }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(s: FeedSource, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SourceMark(s)
        Column(Modifier.weight(1f)) {
            Text(s.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (s is FeedSource.Keyword) "关键词" else "频道",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) { Icon(YoinSymbols.Close, "移除") }
    }
}

/** A channel's avatar, or a search glyph on a tonal disc for a keyword. */
@Composable
fun SourceMark(s: FeedSource) {
    if (s is FeedSource.Channel) {
        Avatar(s.avatar.ifEmpty { null }, 36.dp)
    } else {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(YoinSymbols.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun AddRow(avatar: String?, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (avatar != null) Avatar(avatar.ifEmpty { null }, 36.dp) else SourceMark(FeedSource.Keyword(label))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(YoinSymbols.Add, "添加", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}
