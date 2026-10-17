package com.gpo.pilipala.feature.article

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.launch

internal class OpusViewModel(private val id: String) : ViewModel() {
    var state by mutableStateOf<ArticleState>(ArticleState.Loading)
        private set

    init {
        load()
    }

    fun load() {
        state = ArticleState.Loading
        viewModelScope.launch {
            state = runCatching { opusDoc(ArticleApi.opus(id)).toDocument(fallbackType = 11) }
                .fold({ ArticleState.Ready(it) }, { ArticleState.Error(it.friendlyMessage()) })
        }
    }
}

/** 图文 (opus) page: paragraphs rendered natively, comments below (Flutter pages/opus). */
@Composable
fun OpusScreen(route: Route.Opus) {
    val vm: OpusViewModel = viewModel(key = "opus_${route.id}") { OpusViewModel(route.id) }
    ArticleFrame(
        fallbackTitle = route.title,
        webUrl = "https://www.bilibili.com/opus/${route.id}",
        state = vm.state,
        onRetry = vm::load,
    )
}
