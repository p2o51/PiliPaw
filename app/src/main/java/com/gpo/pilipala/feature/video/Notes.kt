package com.gpo.pilipala.feature.video

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asLong
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.web.createBiliWebView
import com.gpo.pilipala.feature.web.syncCookiesToWebView
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException

@Immutable
internal class PublicNote(
    val cvid: Long,
    val title: String,
    val summary: String,
    val author: String,
    val face: String,
    val likes: Long,
    val time: String,
)

@Immutable
internal class VideoNotes(val mine: List<Long>, val public: List<PublicNote>, val publicTotal: Int)

internal object NotesApi {
    /** My notes on [aid] (ids; signed in only) and the first page of public ones. */
    suspend fun load(aid: Long): VideoNotes {
        val mine = if (BiliClient.isLoggedIn) {
            safe {
                BiliClient.getData("/x/note/list/archive", mapOf("oid" to aid, "oid_type" to 0)).asObj
                    .arr("noteIds").map { it.asLong() }.filter { it > 0 }
            }.orEmpty()
        } else {
            emptyList()
        }
        val pub = safe {
            BiliClient.getData("/x/note/publish/list/archive", mapOf("oid" to aid, "oid_type" to 0, "pn" to 1, "ps" to 20)).asObj
        }
        val list = pub.arr("list").objects().map { n ->
            val a = n.obj("author")
            PublicNote(
                cvid = n.long("cvid"),
                title = n.str("title"),
                summary = n.str("summary"),
                author = a.str("name"),
                face = a.str("face"),
                likes = n.long("likes"),
                time = n.str("pubtime"),
            )
        }.filter { it.cvid > 0 }
        return VideoNotes(mine, list, pub.obj("page").int("total").coerceAtLeast(list.size))
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    fun editorUrl(aid: Long, noteId: Long?): String =
        "https://www.bilibili.com/h5/note-app?oid=$aid&pagefrom=ugcvideo&is_stein_gate=0" + (noteId?.let { "&note_id=$it" } ?: "")

    fun readerUrl(cvid: Long): String = "https://www.bilibili.com/h5/note-app/view?cvid=$cvid&pagefrom=ugcvideo"
}

/**
 * 笔记, the video page's third tab: your own note on top (in the 关注 button's colours, it is
 * yours), then the public notes. Reading and writing open in a sheet under the player, so the
 * video plays on; 写笔记 floats at the bottom end.
 */
@Composable
internal fun NotesPane(aid: Long, notes: VideoNotes?, onChanged: () -> Unit, pad: PaddingValues, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<String?>(null) }
    val dir = LocalLayoutDirection.current
    Box(modifier.fillMaxSize()) {
        val n = notes
        if (n == null) {
            LoadingState()
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = pad.calculateTopPadding() + 12.dp,
                    bottom = pad.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (n.mine.isNotEmpty()) {
                    item("mine") {
                        MyNoteCard(count = n.mine.size) { open = NotesApi.editorUrl(aid, n.mine.first()) }
                    }
                    item("gap") { Spacer(Modifier.heightIn(min = 16.dp)) }
                }
                item("label") {
                    Text(
                        if (n.publicTotal > 0) "公开笔记 · ${n.publicTotal} 篇" else "公开笔记",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                if (n.public.isEmpty()) {
                    item("empty") {
                        Text(
                            "还没有人公开笔记。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                        )
                    }
                }
                items(n.public, key = { it.cvid }) { note ->
                    PublicNoteCard(note) { open = NotesApi.readerUrl(note.cvid) }
                }
            }
        }
        if (BiliClient.isLoggedIn) {
            ExtendedFloatingActionButton(
                onClick = { open = NotesApi.editorUrl(aid, notes?.mine?.firstOrNull()) },
                icon = { Icon(YoinSymbols.EditNote, null) },
                text = { Text("写笔记") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp + pad.calculateEndPadding(dir), bottom = 16.dp + pad.calculateBottomPadding()),
            )
        }
    }
    open?.let { url ->
        NoteWebSheet(url, onDismiss = {
            open = null
            onChanged()
        })
    }
}

@Composable
private fun MyNoteCard(count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = YoinContainerShapes.Card,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(YoinSymbols.EditNote, null, Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("我的笔记", style = MaterialTheme.typography.titleSmall)
                Text(if (count > 1) "$count 篇 · 点开接着写" else "点开接着写", style = MaterialTheme.typography.bodySmall)
            }
            Icon(YoinSymbols.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PublicNoteCard(n: PublicNote, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = YoinContainerShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = n.face,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    n.author,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (n.title.isNotBlank()) {
                Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (n.summary.isNotBlank()) {
                Text(
                    n.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = listOfNotNull(n.likes.takeIf { it > 0 }?.let { "${Fmt.count(it)} 赞" }, n.time.takeIf { it.isNotBlank() })
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Bilibili's own note page (read or write) in a tall sheet; the player stays above it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun NoteWebSheet(url: String, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        // The page scrolls itself; dragging the sheet would fight it.
        sheetGesturesEnabled = false,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("笔记", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(YoinSymbols.Close, contentDescription = "关闭") }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).clip(YoinContainerShapes.Card),
                factory = { ctx ->
                    syncCookiesToWebView()
                    createBiliWebView(ctx, desktop = false, dark = dark).apply {
                        webViewClient = android.webkit.WebViewClient()
                        loadUrl(url)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
