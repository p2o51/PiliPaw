package com.gpo.pilipala.feature.article

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class ReadViewModel(private val cvid: Long) : ViewModel() {
    var state by mutableStateOf<ArticleState>(ArticleState.Loading)
        private set

    /** True once every native path failed — the screen then hands over to the WebView. */
    var fallbackToWeb by mutableStateOf(false)
        private set

    init {
        load()
    }

    fun load() {
        state = ArticleState.Loading
        viewModelScope.launch {
            // 1. Modern articles are opus documents (cv → /opus/{id} redirect).
            val viaOpus = runCatching {
                val opusId = ArticleApi.resolveOpusId(cvid) ?: error("no opus")
                opusDoc(ArticleApi.opus(opusId)).toDocument(fallbackOid = cvid, fallbackType = 12)
            }.getOrNull()
            if (viaOpus != null && viaOpus.body.let { it is ArticleBody.Paragraphs && it.json.isNotEmpty() }) {
                state = ArticleState.Ready(viaOpus)
                return@launch
            }
            // 2. Legacy /x/article/view (opus paragraphs or HTML content).
            val viaView = runCatching { viewDocument(ArticleApi.articleView(cvid)) }
            viaView.onSuccess {
                state = ArticleState.Ready(it)
                return@launch
            }
            // 3. Give up natively.
            state = ArticleState.Error(viaView.exceptionOrNull()?.message ?: "解析失败")
            fallbackToWeb = true
        }
    }

    private fun viewDocument(v: JsonObject): ArticleDocument {
        val author = v.obj("author")
        val stats = v.obj("stats")
        val paragraphs = v.obj("opus").obj("content").arr("paragraphs")
        val html = v.str("content")
        val body = when {
            paragraphs.isNotEmpty() -> ArticleBody.Paragraphs(paragraphs)
            html.isNotBlank() -> ArticleBody.Html(html)
            else -> error("empty")
        }
        val banner = v.str("banner_url").ifEmpty {
            (v.arr("image_urls").firstOrNull() as? JsonPrimitive)?.content.orEmpty()
        }
        return ArticleDocument(
            header = ArticleHeaderData(
                title = v.str("title"),
                banner = banner,
                authorMid = author.long("mid"),
                authorName = author.str("name"),
                authorFace = author.str("face"),
                time = Fmt.date(v.long("publish_time"), "yyyy-MM-dd HH:mm"),
                stats = listOf(
                    YoinSymbols.Views to stats.long("view"),
                    YoinSymbols.ThumbUp to stats.long("like"),
                    YoinSymbols.Comment to stats.long("reply"),
                ),
                collection = v.obj("list").str("name"),
            ),
            body = body,
            commentOid = cvid,
            commentType = 12,
        )
    }
}

/** 专栏 cv page (Flutter pages/read). Falls back to the WebView when parsing fails. */
@Composable
fun ReadScreen(route: Route.Read) {
    val vm: ReadViewModel = viewModel(key = "read_${route.cvid}") { ReadViewModel(route.cvid) }
    val nav = LocalNavigator.current
    val webUrl = "https://www.bilibili.com/read/cv${route.cvid}"
    LaunchedEffect(vm.fallbackToWeb) {
        if (vm.fallbackToWeb) nav.replace(Route.Web(webUrl, route.title))
    }
    ArticleFrame(
        fallbackTitle = route.title,
        webUrl = webUrl,
        state = vm.state,
        onRetry = vm::load,
    )
}
