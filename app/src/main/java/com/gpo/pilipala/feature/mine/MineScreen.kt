package com.gpo.pilipala.feature.mine

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.account.UserInfo
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.library.FavFolder
import com.gpo.pilipala.feature.library.FolderCard
import com.gpo.pilipala.feature.library.toFavFolder
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.LocalBottomBarInset
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.WidthClass
import com.gpo.pilipala.ui.components.seam.seamGraphic
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.components.widthClass
import com.gpo.pilipala.ui.theme.LocalPiliDark
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import java.util.Locale
import com.gpo.pilipala.ui.components.inlineIconSize
import com.gpo.pilipala.ui.components.centered

internal class MineViewModel : ViewModel() {
    var folders by mutableStateOf<List<FavFolder>>(emptyList())
        private set
    var folderCount by mutableIntStateOf(0)
        private set
    var foldersLoading by mutableStateOf(false)
        private set

    fun loadFolders() {
        val mid = BiliClient.mid
        if (!BiliClient.isLoggedIn || mid <= 0) {
            folders = emptyList()
            folderCount = 0
            return
        }
        if (foldersLoading) return
        foldersLoading = folders.isEmpty()
        viewModelScope.launch {
            runCatching {
                BiliClient.getData(
                    "/x/v3/fav/folder/created/list",
                    mapOf("up_mid" to mid, "pn" to 1, "ps" to 20),
                ).asObj
            }.onSuccess { d ->
                folders = d.arr("list").objects().map { it.toFavFolder() }
                folderCount = d.int("count")
            }
            foldersLoading = false
        }
    }
}

private data class MineTile(val label: String, val icon: ImageVector, val route: androidx.navigation3.runtime.NavKey)

/** YouTube's own 我的 (account, subscriptions, history, Mix, its settings), once YouTube is ticked. */
private val YouTubeTile = MineTile("YouTube", YoinSymbols.PlayFilled, com.gpo.pilipala.yt.navigation.Route.Settings)

private val MineTiles = listOf(
    MineTile("历史记录", PiliSymbols.History, Route.History),
    MineTile("我的收藏", YoinSymbols.Star, Route.Fav()),
    MineTile("稍后再看", PiliSymbols.WatchLater, Route.Later),
    MineTile("离线缓存", YoinSymbols.Download, Route.Downloads),
    MineTile("我的订阅", YoinSymbols.LibraryAdded, Route.Subscription(0)),
    MineTile("消息", PiliSymbols.Mail, Route.Messages),
    MineTile("设置", YoinSymbols.Settings, Route.Settings),
)

@Composable
fun MineScreen(asPage: Boolean = false) {
    val user by Account.user.collectAsState()
    val vm = viewModel { MineViewModel() }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { runCatching { Account.refresh() } }
        vm.loadFolders()
    }
    val width = widthClass()
    val bottom = LocalBottomBarInset.current +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp

    // The page scrolls up under the status bar and sinks under its tide line there
    // (the clock never sits on bare content); graphics dot in the floating bar's field.
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .seamViewport(scroll, underStatusBar = true)
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(bottom = bottom),
    ) {
        MineTopRow(asPage)
        Text(
            "我的",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 24.dp, top = 8.dp, bottom = 24.dp)
                .seamText(MaterialTheme.typography.displaySmall.fontSize)
                .semantics { heading() },
        )
        if (width == WidthClass.Expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ProfileSection(user)
                }
                Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    TileGrid(columns = 4)
                    FolderSection(vm, user)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ProfileSection(user)
                TileGrid(columns = if (width == WidthClass.Medium) 7 else 3)
                FolderSection(vm, user)
            }
        }
    }
}

@Composable
private fun MineTopRow(asPage: Boolean) {
    val nav = LocalNavigator.current
    val dark = LocalPiliDark.current
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).seamText(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pushed from the avatar menu (no longer a tab): back on the left.
        if (asPage) {
            IconButton(onClick = { nav.pop() }, shapes = IconButtonDefaults.shapes()) {
                Icon(YoinSymbols.Back, contentDescription = "返回")
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
        IconButton(
            onClick = {
                haptics.performHapticFeedback(if (dark) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                AppSettings.set(AppSettings.themeMode, if (dark) 1 else 2)
            },
            shapes = IconButtonDefaults.shapes(),
        ) {
            val enterFade = YoinMotion.defaultEffectsSpec<Float>()
            val exitFade = YoinMotion.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = dark,
                transitionSpec = { fadeIn(enterFade) togetherWith fadeOut(exitFade) },
                label = "themeIcon",
            ) { d ->
                Icon(if (d) YoinSymbols.LightMode else PiliSymbols.DarkMode, contentDescription = if (d) "浅色" else "深色")
            }
        }
        IconButton(onClick = { nav.push(Route.Settings) }, shapes = IconButtonDefaults.shapes()) {
            Icon(YoinSymbols.Settings, contentDescription = "设置")
        }
    }
}

// ── Profile ────────────────────────────────────────────────────────────────

@Composable
private fun ProfileSection(user: UserInfo?) {
    val enterFade = YoinMotion.defaultEffectsSpec<Float>()
    val exitFade = YoinMotion.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = user?.takeIf { BiliClient.isLoggedIn },
        contentKey = { it != null },
        transitionSpec = { fadeIn(enterFade) togetherWith fadeOut(exitFade) },
        label = "profile",
    ) { u ->
        if (u == null) GuestProfile() else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            UserProfile(u)
            StatsRow(u)
        }
    }
}

@Composable
private fun GuestProfile() {
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .seamGraphic()
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(YoinSymbols.Person, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(
            "未登录",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).seamText(),
        )
        Button(
            onClick = { nav.push(Route.Login) },
            contentPadding = ButtonDefaults.MediumContentPadding,
            modifier = Modifier.height(ButtonDefaults.MediumContainerHeight).seamText(),
        ) { Text("登录", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun UserProfile(u: UserInfo) {
    val nav = LocalNavigator.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(YoinContainerShapes.Card)
            .clickable { nav.push(Route.Member(u.mid, u.face, u.name)) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Avatar(u.face, 72.dp)
        Column(Modifier.weight(1f).seamText(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                u.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (u.vipStatus == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(
                    "LV${u.level}",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (u.vipStatus == 1 && u.vipLabel.isNotEmpty()) {
                    Tag(
                        u.vipLabel,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val coinStyle = MaterialTheme.typography.labelLarge.centered()
                    Icon(PiliSymbols.Coin, contentDescription = "硬币", Modifier.size(coinStyle.inlineIconSize()), tint = MaterialTheme.colorScheme.outline)
                    Text(
                        formatCoins(u.coins),
                        style = coinStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Icon(YoinSymbols.ChevronRight, null, Modifier.seamText(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatCoins(c: Double): String =
    if (c % 1.0 == 0.0) c.toLong().toString() else String.format(Locale.US, "%.1f", c)

@Composable
private fun StatsRow(u: UserInfo) {
    val nav = LocalNavigator.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).seamGraphic(),
        shape = YoinContainerShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(6.dp)) {
            StatCell(Fmt.count(u.dynamicCount), "动态", Modifier.weight(1f)) { nav.push(Route.MemberDynamics(u.mid, u.name)) }
            StatCell(Fmt.count(u.following), "关注", Modifier.weight(1f)) { nav.push(Route.Follow(u.mid, u.name)) }
            StatCell(Fmt.count(u.follower), "粉丝", Modifier.weight(1f), fame = true) { nav.push(Route.Fans(u.mid, u.name)) }
        }
    }
}

/**
 * Number over label; [fame] (粉丝) draws a line under 名声模式. The cell opens a list,
 * so the line stays a line and the whole cell keeps its tap.
 */
@Composable
private fun StatCell(value: String, label: String, modifier: Modifier, fame: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier
            .clip(YoinContainerShapes.ListRow)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .seamText(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val enterFade = YoinMotion.defaultEffectsSpec<Float>()
        val exitFade = YoinMotion.fastEffectsSpec<Float>()
        // The signed-in user's own numbers: 只隐藏自己的 hides them too.
        val hidden = fame && FameMode.hides(self = true)
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(enterFade) togetherWith fadeOut(exitFade) },
            // A hidden count that moves is the same line: nothing to fade.
            contentKey = { v -> if (hidden) "" else v },
            label = "stat",
        ) { v ->
            FameCount(v, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, revealable = false, hide = fame, self = true)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Tiles ──────────────────────────────────────────────────────────────────

@Composable
private fun TileGrid(columns: Int) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val sources by com.gpo.pilipala.core.source.Sources.enabled.collectAsState()
        val tiles = if (com.gpo.pilipala.core.source.VideoSource.YouTube in sources) MineTiles + YouTubeTile else MineTiles
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t -> Tile(t, Modifier.weight(1f)) }
                // A lone last tile spans the row (7 tiles over 3 columns) instead of sitting by two gaps.
                if (row.size > 1) repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(tile: MineTile, modifier: Modifier) {
    val nav = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = YoinMotion.fastSpatialSpec(),
        label = "tileScale",
    )
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            val r = tile.route
            nav.pushKey(
                when (r) {
                    is Route.Subscription -> Route.Subscription(BiliClient.mid)
                    is Route.Fav -> Route.Fav(BiliClient.mid)
                    else -> r
                },
            )
        },
        // A tinted tile is a graphic for the seams; its icon and label lift out whole.
        modifier = modifier
            .height(96.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .seamGraphic(),
        shape = YoinContainerShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = interaction,
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp).seamText(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(tile.icon, null, tint = MaterialTheme.colorScheme.onSurface)
            Text(tile.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

// ── Folders ────────────────────────────────────────────────────────────────

@Composable
private fun FolderSection(vm: MineViewModel, user: UserInfo?) {
    val nav = LocalNavigator.current
    val visible = user != null && BiliClient.isLoggedIn && (vm.folders.isNotEmpty() || vm.foldersLoading)
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(YoinMotion.defaultEffectsSpec()),
        exit = shrinkVertically(YoinMotion.fastSpatialSpec()) + fadeOut(YoinMotion.fastEffectsSpec()),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).seamText(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("收藏夹", style = MaterialTheme.typography.titleMedium)
                if (vm.folderCount > 0) {
                    Text(
                        vm.folderCount.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { nav.push(Route.Fav(BiliClient.mid)) }) { Text("全部") }
            }
            if (vm.folders.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(40.dp))
                }
            } else {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vm.folders, key = { it.id }) { f ->
                        val route = Route.FavDetail(f.id, f.title, f.cover)
                        FolderCard(
                            f,
                            modifier = Modifier.width(168.dp).animateItem(),
                            route = route,
                            onClick = { nav.push(route) },
                        )
                    }
                }
            }
        }
    }
}
