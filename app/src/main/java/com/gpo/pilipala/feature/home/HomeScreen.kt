package com.gpo.pilipala.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.account.UserInfo
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

private val HomeTabs = listOf("直播", "推荐", "热门", "番剧")

@Composable
fun HomeScreen() {
    val nav = LocalNavigator.current
    val headerVm: HomeHeaderViewModel = viewModel(key = "home_header") { HomeHeaderViewModel() }
    val user by Account.user.collectAsState()
    val initialPage = remember { AppSettings.homeDefaultTab.value.coerceIn(0, HomeTabs.lastIndex) }
    val pagerState = rememberPagerState(initialPage = initialPage) { HomeTabs.size }
    val scope = rememberCoroutineScope()
    val behavior = rememberHeaderScrollBehavior()
    val pageSpec = YoinMotion.defaultSpatialSpec<Float>()

    // One scroll state per tab, hoisted so it survives the pager disposing pages.
    val liveState = rememberLazyGridState()
    val rcmdState = rememberLazyGridState()
    val hotState = rememberLazyGridState()
    val bangumiState = rememberLazyGridState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .nestedScroll(behavior.nestedScrollConnection),
    ) {
        CollapsingHeader(behavior) {
            HomeHeader(
                user = user,
                hint = headerVm.searchHint,
                onAvatar = {
                    val u = user
                    if (u != null) nav.push(Route.Member(u.mid, u.face, u.name)) else nav.push(Route.Login)
                },
                onSearch = { nav.push(Route.Search(headerVm.searchHint)) },
                onMessages = { nav.push(if (user != null) Route.Messages else Route.Login) },
            )
        }
        SegmentTabs(
            labels = HomeTabs,
            selected = pagerState.targetPage,
            onSelect = { i -> scope.launch { pagerState.animateScrollToPage(i, animationSpec = pageSpec) } },
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            key = { it },
        ) { page ->
            when (page) {
                0 -> LiveTab(liveState)
                1 -> RcmdTab(rcmdState)
                2 -> HotTab(hotState)
                else -> BangumiTab(bangumiState)
            }
        }
    }
}

@Composable
private fun HomeHeader(
    user: UserInfo?,
    hint: String,
    onAvatar: () -> Unit,
    onSearch: () -> Unit,
    onMessages: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderAvatar(user, onAvatar)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SearchPill(hint, onSearch, Modifier.widthIn(max = 640.dp).fillMaxWidth())
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onMessages,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(PiliSymbols.Mail, contentDescription = "消息")
        }
    }
}

@Composable
private fun HeaderAvatar(user: UserInfo?, onClick: () -> Unit) {
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val enterSpec = YoinMotion.defaultEffectsSpec<Float>()
    val exitSpec = YoinMotion.fastEffectsSpec<Float>()
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(48.dp),
    ) {
        AnimatedContent(
            targetState = user?.face,
            transitionSpec = { fadeIn(enterSpec).togetherWith(fadeOut(exitSpec)) },
            label = "homeAvatar",
        ) { face ->
            if (face != null) {
                NetImage(face, Modifier.size(40.dp), contentDescription = "我的", shape = cookie, thumbWidth = 80.dp)
            } else {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, cookie),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        YoinSymbols.Person,
                        contentDescription = "登录",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPill(hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val enterSpec = YoinMotion.defaultEffectsSpec<Float>()
    val exitSpec = YoinMotion.fastEffectsSpec<Float>()
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(YoinSymbols.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            AnimatedContent(
                targetState = hint.ifEmpty { "搜索" },
                transitionSpec = { fadeIn(enterSpec).togetherWith(fadeOut(exitSpec)) },
                label = "searchHint",
                modifier = Modifier.weight(1f),
            ) { text ->
                Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
