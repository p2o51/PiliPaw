package com.gpo.pilipala.feature.article

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.feature.dynamics.ImageViewerHost
import com.gpo.pilipala.feature.dynamics.rememberImageViewerState
import com.gpo.pilipala.feature.dynamics.shareText
import com.gpo.pilipala.feature.reply.ReplyList
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.BackButton
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Request

// ── Network ─────────────────────────────────────────────────────────────────

internal object ArticleApi {
    private const val OPUS_FEATURES = "onlyfansVote,onlyfansAssetsV2,decorationCard,htmlNewStyle,ugcDelete,editable,opusPrivateVisible"

    // Responses are parsed on Dispatchers.Default (an opus body can be hundreds of KB), never on main.
    suspend fun opus(id: String): JsonObject = withContext(Dispatchers.Default) {
        BiliClient.getData(
            "/x/polymer/web-dynamic/v1/opus/detail",
            mapOf("id" to id, "timezone_offset" to "-480", "features" to OPUS_FEATURES),
            wbi = true,
        ).asObj.obj("item") ?: throw BiliException(-1, "内容不存在")
    }

    /** Modern articles live at /opus/{id}; the mobile read URL redirects there. */
    suspend fun resolveOpusId(cvid: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://www.bilibili.com/read/mobile?id=$cvid")
                .header("User-Agent", BiliClient.UA_PC)
                .get()
                .build()
            BiliClient.http.newCall(req).execute().use { resp ->
                Regex("""/opus/(\d+)""").find(resp.request.url.toString())?.groupValues?.get(1)
            }
        }.getOrNull()
    }

    suspend fun articleView(cvid: Long): JsonObject = withContext(Dispatchers.Default) {
        BiliClient.getData(
            "/x/article/view",
            mapOf("id" to cvid, "gaia_source" to "main_web", "web_location" to "333.976"),
            wbi = true,
        ).asObj ?: throw BiliException(-1, "内容不存在")
    }
}

// ── State ───────────────────────────────────────────────────────────────────

internal sealed interface ArticleBody {
    /** [dynamicId]: the opus id when the paragraphs come from an opus (vote cards cast with it). */
    class Paragraphs(val json: JsonArray, val dynamicId: String = "") : ArticleBody
    class Html(val html: String) : ArticleBody
}

internal class ArticleDocument(
    val header: ArticleHeaderData,
    val body: ArticleBody,
    val commentOid: Long,
    val commentType: Int,
)

internal sealed interface ArticleState {
    data object Loading : ArticleState
    data class Error(val message: String) : ArticleState
    data class Ready(val doc: ArticleDocument) : ArticleState
}

internal fun OpusDoc.toDocument(fallbackOid: Long = 0, fallbackType: Int = 12) = ArticleDocument(
    header = header,
    body = ArticleBody.Paragraphs(paragraphs, dynamicId = id),
    commentOid = commentOid.takeIf { it > 0 } ?: fallbackOid,
    commentType = if (commentOid > 0) commentType else fallbackType,
)

// ── Page frame ──────────────────────────────────────────────────────────────

/**
 * Shared frame for opus and cv pages: top bar whose title fades in once the
 * article title scrolls away, reading-width body, comments below (or in a
 * second pane on Expanded).
 */
@Composable
internal fun ArticleFrame(
    fallbackTitle: String,
    webUrl: String,
    state: ArticleState,
    onRetry: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val threshold = with(LocalDensity.current) { 160.dp.toPx() }
    val title = (state as? ArticleState.Ready)?.doc?.header?.title?.ifEmpty { null } ?: fallbackTitle
    // derivedStateOf: only the threshold crossing invalidates, never each scroll delta.
    // Read inside ArticleTopBar so the frame and body never recompose on scroll.
    val showTitle = remember(scrollBehavior, threshold) {
        derivedStateOf { scrollBehavior.state.contentOffset < -threshold }
    }
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { ArticleTopBar(title, { showTitle.value }, webUrl, scrollBehavior) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(fadeInSpec).togetherWith(fadeOut(fadeOutSpec)) },
            contentKey = { it::class },
            label = "article",
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) { s ->
            when (s) {
                ArticleState.Loading -> LoadingState()
                is ArticleState.Error -> ErrorState(s.message, onRetry = onRetry)
                is ArticleState.Ready -> ArticleBodyView(s.doc)
            }
        }
    }
}

@Composable
private fun ArticleTopBar(title: String, showTitle: () -> Boolean, webUrl: String, scrollBehavior: TopAppBarScrollBehavior) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val enter = YoinMotion.defaultEffectsSpec<Float>()
    val slide = YoinMotion.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val exit = YoinMotion.fastEffectsSpec<Float>()
    TopAppBar(
        title = {
            AnimatedVisibility(
                visible = showTitle(),
                enter = fadeIn(enter) + slideInVertically(slide) { it / 2 },
                exit = fadeOut(exit),
            ) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
            }
        },
        navigationIcon = { BackButton() },
        actions = {
            IconButton(onClick = { shareText(context, webUrl, title) }, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.Share, contentDescription = "分享")
            }
            IconButton(onClick = { nav.push(Route.Web(webUrl, title)) }, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.Launch, contentDescription = "网页打开")
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

private class NavUriHandler(private val nav: Navigator) : UriHandler {
    override fun openUri(uri: String) = nav.openUrl(uri)
}

@Composable
private fun ArticleBodyView(doc: ArticleDocument) {
    val nav = LocalNavigator.current
    val viewer = rememberImageViewerState()
    val primary = MaterialTheme.colorScheme.primary
    val bodySize = MaterialTheme.typography.bodyLarge.fontSize
    val parsed by produceState<ParsedArticle?>(null, doc, primary) {
        value = withContext(Dispatchers.Default) {
            val style = ParseStyle(primary, bodySize)
            runCatching {
                when (val b = doc.body) {
                    is ArticleBody.Paragraphs -> parseOpusParagraphs(b.json, style, b.dynamicId)
                    is ArticleBody.Html -> parseHtml(b.html, style)
                }
            }.getOrElse { ParsedArticle(emptyList(), emptyList()) }
        }
    }
    val handler = remember(nav) { NavUriHandler(nav) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val expanded = widthClass() == WidthClass.Expanded
    val h = doc.header
    val itemMod = Modifier.readingWidth(760.dp).padding(horizontal = 20.dp)

    val content: LazyListScope.() -> Unit = {
        if (h.banner.isNotEmpty()) {
            item(key = "banner") { ArticleBanner(h.banner, itemMod.padding(top = 8.dp, bottom = 8.dp)) }
        }
        item(key = "title") { ArticleTitle(h.title, itemMod.padding(top = 8.dp, bottom = 12.dp)) }
        item(key = "meta") { ArticleMeta(h, itemMod.padding(bottom = 20.dp)) }
        val p = parsed
        if (p == null) {
            item(key = "parsing") { LoadingFooter() }
        } else {
            itemsIndexed(p.blocks, key = { i, _ -> "b$i" }) { _, block ->
                ArticleBlockView(block, onImage = { viewer.open(p.images, it) }, modifier = itemMod.padding(vertical = 6.dp))
            }
        }
        item(key = "end") { Spacer(Modifier.height(32.dp)) }
    }

    CompositionLocalProvider(LocalUriHandler provides handler) {
        when {
            doc.commentOid <= 0 -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = navBottom + 24.dp),
                content = content,
            )
            expanded -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(
                    Modifier.weight(1.25f).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = navBottom + 24.dp),
                    content = content,
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    ReplyList(
                        oid = doc.commentOid,
                        type = doc.commentType,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = navBottom + 24.dp),
                    )
                }
            }
            else -> ReplyList(
                oid = doc.commentOid,
                type = doc.commentType,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = navBottom + 24.dp),
                header = content,
            )
        }
    }
    ImageViewerHost(viewer)
}
