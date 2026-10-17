package com.gpo.pilipala.feature.dynamics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.feature.reply.ReplyList
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.PiliTopBar
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal sealed interface DetailState {
    data object Loading : DetailState
    data class Error(val message: String) : DetailState
    data class Ready(val item: JsonObject) : DetailState
}

internal class DynamicDetailViewModel(private val id: String) : ViewModel() {
    var state by mutableStateOf<DetailState>(DetailState.Loading)
        private set

    init {
        load()
    }

    fun load() {
        state = DetailState.Loading
        viewModelScope.launch {
            state = runCatching { DynamicsApi.detail(id) }
                .fold({ DetailState.Ready(it) }, { DetailState.Error(it.friendlyMessage()) })
        }
    }
}

/** Dynamic detail: the full card with its comment thread (two panes on Expanded). */
@Composable
fun DynamicDetailScreen(route: Route.DynamicDetail) {
    val vm: DynamicDetailViewModel = viewModel(key = "dyn_detail_${route.id}") { DynamicDetailViewModel(route.id) }
    val context = LocalContext.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()
    Scaffold(
        topBar = {
            PiliTopBar(
                "动态详情",
                actions = {
                    val ready = vm.state as? DetailState.Ready
                    if (ready != null) {
                        IconButton(
                            onClick = { shareText(context, dynamicShareUrl(ready.item)) },
                            shapes = IconButtonDefaults.shapes(),
                        ) { Icon(YoinSymbols.Share, contentDescription = "分享") }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        AnimatedContent(
            targetState = vm.state,
            transitionSpec = { fadeIn(fadeInSpec).togetherWith(fadeOut(fadeOutSpec)) },
            contentKey = { it::class },
            label = "dynDetail",
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) { s ->
            when (s) {
                DetailState.Loading -> LoadingState()
                is DetailState.Error -> ErrorState(s.message, onRetry = vm::load)
                is DetailState.Ready -> DetailBody(s.item, navBottom)
            }
        }
    }
}

@Composable
private fun DetailBody(item: JsonObject, navBottom: androidx.compose.ui.unit.Dp) {
    val (oid, type) = item.commentSubject()
    val expanded = widthClass() == WidthClass.Expanded
    val bottomPad = PaddingValues(bottom = navBottom + 24.dp)
    if (oid <= 0) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = navBottom + 24.dp)) {
            item(key = "card") { DynamicCard(item, Modifier.readingWidth(720.dp).padding(horizontal = 16.dp), detail = true) }
        }
        return
    }
    if (expanded) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 24.dp, end = 8.dp, top = 8.dp, bottom = navBottom + 24.dp),
            ) {
                item(key = "card") { DynamicCard(item, Modifier.readingWidth(720.dp), detail = true) }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ReplyList(
                    oid = oid,
                    type = type,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = bottomPad,
                )
            }
        }
    } else {
        ReplyList(
            oid = oid,
            type = type,
            modifier = Modifier.fillMaxSize(),
            contentPadding = bottomPad,
            header = {
                item(key = "dyn_card") {
                    DynamicCard(
                        item,
                        Modifier.readingWidth(720.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                        detail = true,
                    )
                }
            },
        )
    }
}
