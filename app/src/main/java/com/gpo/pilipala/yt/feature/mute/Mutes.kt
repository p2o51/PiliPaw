package com.gpo.pilipala.yt.feature.mute

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.core.extractor.ListItem
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.yt.feature.player.miniPlayerInset
import com.gpo.pilipala.yt.navigation.LocalNavigator
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.yt.ui.components.readingWidth
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A muted channel: what its row in 屏蔽 shows. */
@Serializable
data class MutedChannel(val url: String, val name: String, val avatar: String = "")

@Serializable
private data class MuteRules(val keywords: List<String> = emptyList(), val channels: List<MutedChannel> = emptyList())

/**
 * 屏蔽: channels (from a video's long-press menu) and keywords (matched in titles, any case)
 * whose videos never show in Home, feeds, search or related videos. A channel's own page still
 * shows it: opening it is asking for it.
 */
object Mutes {
    private val stored = AppSettings.register("mute_rules", "")
    private val json = Json { ignoreUnknownKeys = true }

    private fun rules(): MuteRules =
        stored.value.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(MuteRules.serializer(), it) }.getOrNull() } ?: MuteRules()

    private fun save(r: MuteRules) = AppSettings.set(stored, json.encodeToString(MuteRules.serializer(), r))

    /** Changes whenever a rule does (for lists that refilter). */
    val version get() = stored.state

    private fun id(url: String) = url.trimEnd('/').substringAfterLast('/')

    fun hides(v: VideoItem): Boolean {
        val r = rules()
        if (v.channelUrl.isNotEmpty() && r.channels.any { id(it.url) == id(v.channelUrl) }) return true
        return r.keywords.any { k -> v.title.contains(k, ignoreCase = true) }
    }

    fun hides(c: ChannelItem): Boolean = rules().channels.any { id(it.url) == id(c.url) }

    fun hides(item: ListItem): Boolean = when (item) {
        is ListItem.Video -> hides(item.video)
        is ListItem.Channel -> hides(item.channel)
    }

    fun muteChannel(url: String, name: String, avatar: String = "") {
        val r = rules()
        if (r.channels.any { id(it.url) == id(url) }) return
        save(r.copy(channels = r.channels + MutedChannel(url, name, avatar)))
    }

    fun unmuteChannel(url: String) {
        val r = rules()
        save(r.copy(channels = r.channels.filterNot { id(it.url) == id(url) }))
    }

    fun addKeyword(k: String) {
        val t = k.trim()
        if (t.isEmpty()) return
        val r = rules()
        if (r.keywords.any { it.equals(t, ignoreCase = true) }) return
        save(r.copy(keywords = r.keywords + t))
    }

    fun removeKeyword(k: String) {
        val r = rules()
        save(r.copy(keywords = r.keywords - k))
    }

    fun keywords(): List<String> = rules().keywords
    fun channels(): List<MutedChannel> = rules().channels
}

/** 屏蔽: add a keyword; every keyword and channel with a remove button (PiliPaw's library frame). */
@Composable
fun MuteScreen() {
    Mutes.version.collectAsState().value // refresh on change
    val keywords = Mutes.keywords()
    val channels = Mutes.channels()
    var field by remember { mutableStateOf("") }
    com.gpo.pilipala.yt.feature.library.YtLibraryScaffold(title = "屏蔽") { padding ->
        LazyColumn(
            Modifier.fillMaxSize().readingWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "kw-title") { Label("关键词") }
            item(key = "kw-field") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.gpo.pilipala.yt.feature.library.FilledNameField(field, { field = it }, Modifier.weight(1f), placeholder = "关键词")
                    FilledTonalButton(enabled = field.isNotBlank(), onClick = {
                        Mutes.addKeyword(field)
                        field = ""
                    }) { Text("添加") }
                }
            }
            items(keywords, key = { "k:$it" }) { k ->
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { Mutes.removeKeyword(k) }, shapes = androidx.compose.material3.IconButtonDefaults.shapes()) { Icon(YoinSymbols.Close, "移除") }
                }
            }
            item(key = "ch-title") { Label("频道") }
            if (channels.isEmpty()) {
                item(key = "ch-empty") {
                    Text("暂无", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                }
            }
            items(channels, key = { "c:" + it.url }) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(c.avatar.ifEmpty { null }, 36.dp)
                    Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { Mutes.unmuteChannel(c.url) }, shapes = androidx.compose.material3.IconButtonDefaults.shapes()) { Icon(YoinSymbols.Close, "移除") }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}
