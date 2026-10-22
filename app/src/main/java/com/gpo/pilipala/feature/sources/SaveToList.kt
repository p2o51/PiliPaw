package com.gpo.pilipala.feature.sources

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.sheetBottomPadding
import com.gpo.pilipala.yt.core.account.AccountWrites
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.extractor.YtAccount
import com.gpo.pilipala.yt.feature.library.LibItem
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.library.LocalPlaylist
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

/**
 * 加入到列表 for a YouTube video: the signed-in account's playlists, then the Mix lists, each
 * with a round tick that saves at once. A Mix that follows a playlist ticked above gets the video
 * through its sync, so it is not added twice.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SaveToListSheet(item: LibItem, onDismiss: () -> Unit) {
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val profile by Accounts.youtube.collectAsState()
    val signedIn = profile is Profile.SignedIn
    val videoId = item.key.removePrefix("yt:")
    var remote by remember { mutableStateOf<List<YtAccount.Playlist>?>(if (signedIn) null else emptyList()) }
    val inRemote = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        remote = runCatching { YtAccount.playlistOptions(videoId) }.getOrDefault(emptyList())
        remote.orEmpty().forEach { inRemote[it.id] = it.contains }
    }
    val mixes by Library.playlists.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp).padding(bottom = sheetBottomPadding(8.dp)),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("加入到列表", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                if (signedIn) {
                    item(key = "h_yt") { SectionLabel("YouTube") }
                    val r = remote
                    if (r == null) {
                        item(key = "loading") { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                    } else {
                        items(r, key = { "yt:${it.id}" }) { pl ->
                            val on = inRemote[pl.id] == true
                            ListRow(
                                title = pl.title,
                                cover = null,
                                ticked = on,
                                onTick = {
                                    inRemote[pl.id] = !on
                                    haptics.performHapticFeedback(if (!on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                    scope.launch {
                                        runCatching {
                                            AccountWrites.run(if (!on) "加入「${pl.title}」" else "移出「${pl.title}」") {
                                                YtAccount.setInPlaylist(pl.id, videoId, add = !on)
                                            }
                                        }.onSuccess(toast).onFailure {
                                            inRemote[pl.id] = on
                                            toast("没能保存")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                item(key = "h_mix") { SectionLabel("Mix") }
                items(mixes, key = { "mix:${it.id}" }) { mix ->
                    val on = mix.items.any { it.key == item.key }
                    ListRow(
                        title = mix.name,
                        cover = mix.items.firstOrNull()?.thumbnail?.let(MixItems::secure),
                        ticked = on,
                        onTick = {
                            haptics.performHapticFeedback(if (!on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            // Following a playlist the video just went into: the sync brings it.
                            val viaLink = !on && mix.links.any { l -> l.kind == "yt_playlist" && inRemote.any { (id, inIt) -> inIt && l.ref.contains(id) } }
                            if (viaLink) MixSync.sync(mix.id) else Library.togglePlaylistItem(mix.id, item)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The Mix lists inside Bilibili's 收藏到 sheet: ticks are kept until 完成, then [applyMixTicks]
 * adds or removes, skipping a Mix that follows a 收藏夹 the video is going into anyway.
 */
@Composable
fun MixTickRows(item: LibItem, ticks: MutableMap<String, Boolean>) {
    val mixes by Library.playlists.collectAsState()
    if (mixes.isEmpty()) return
    Column {
        SectionLabel("Mix")
        mixes.forEach { mix ->
            val now = ticks[mix.id] ?: mix.items.any { it.key == item.key }
            ListRow(
                title = mix.name,
                cover = mix.items.firstOrNull()?.thumbnail?.let(MixItems::secure),
                ticked = now,
                onTick = { ticks[mix.id] = !now },
            )
        }
    }
}

/** After 收藏到 › 完成: Mix ticks that changed; [addedFolders] are the 收藏夹 ids the video just went into. */
fun applyMixTicks(item: LibItem, ticks: Map<String, Boolean>, addedFolders: Collection<Long>) {
    val mixes: List<LocalPlaylist> = Library.playlists.value
    ticks.forEach { (id, want) ->
        val mix = mixes.firstOrNull { it.id == id } ?: return@forEach
        val has = mix.items.any { it.key == item.key }
        if (want == has) return@forEach
        val viaLink = want && mix.links.any { l -> l.kind == "bili_fav" && l.ref.toLongOrNull() in addedFolders }
        when {
            viaLink -> MixSync.sync(mix.id)
            else -> Library.togglePlaylistItem(mix.id, item)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(title: String, cover: String?, ticked: Boolean, onTick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onTick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.width(64.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                com.gpo.pilipala.ui.components.NetImage(cover, Modifier.matchParentSize(), thumbWidth = 128.dp)
            } else {
                Icon(YoinSymbols.Playlist, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TickMark(ticked, MaterialTheme.colorScheme.primary, onTick)
    }
}
