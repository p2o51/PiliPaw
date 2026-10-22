package com.gpo.pilipala.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.navigation.transition.pageSharedSource
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.EmptyState
import com.gpo.pilipala.ui.components.ErrorState
import com.gpo.pilipala.ui.components.LoadingFooter
import com.gpo.pilipala.ui.components.LoadingState
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Pager
import com.gpo.pilipala.ui.components.PiliLargeTopBar
import com.gpo.pilipala.ui.components.RefreshBox
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.components.seam.seamViewport
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets

// Shared building blocks for the library pages (收藏 / 稍后再看 / 历史 / 订阅 / 关注 / 粉丝 / 黑名单).

internal typealias JsonObject = kotlinx.serialization.json.JsonObject

internal val LibCoverShape = ContinuousRoundedCornerShape(12.dp)

/** Min cell width for row-card grids: 1 column on phones, 2 on tablet portrait, 3 on landscape. */
internal val RowGridMinCell = 380.dp

/** Large collapsing bar + Scaffold wired for nested scroll. */
@Composable
internal fun LibraryScaffold(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    showBack: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { PiliLargeTopBar(title = title, subtitle = subtitle, scrollBehavior = scrollBehavior, actions = actions, showBack = showBack) },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

private enum class BodyState { NeedLogin, Loading, Error, Empty, Content }

/**
 * Loading / error / empty / content for a [Pager], cross-faded with the effects
 * spring, under pull-to-refresh. [content] receives the list content padding.
 */
@Composable
internal fun <T> PagerBody(
    pager: Pager<T>,
    padding: PaddingValues,
    emptyLabel: String = "空空如也",
    emptyIcon: ImageVector = YoinSymbols.Sparkle,
    requireLogin: Boolean = true,
    /** The content shows whatever the pager holds (it has other things to show than the pager's items). */
    alwaysContent: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val user by Account.user.collectAsState()
    val loggedIn = user != null || BiliClient.isLoggedIn
    LaunchedEffect(loggedIn) {
        if (requireLogin && loggedIn && pager.error != null && pager.items.isEmpty()) pager.refresh()
    }
    val state = when {
        alwaysContent -> BodyState.Content
        requireLogin && !loggedIn -> BodyState.NeedLogin
        pager.items.isNotEmpty() -> BodyState.Content
        pager.initialLoading || pager.isRefreshing -> BodyState.Loading
        pager.error != null -> BodyState.Error
        else -> BodyState.Empty
    }
    val listPadding = PaddingValues(
        top = padding.calculateTopPadding() + ListTopGap,
        bottom = padding.calculateBottomPadding() + 24.dp,
    )
    val enterFade = YoinMotion.defaultEffectsSpec<Float>()
    val exitFade = YoinMotion.fastEffectsSpec<Float>()
    RefreshBox(
        isRefreshing = pager.isRefreshing && pager.items.isNotEmpty(),
        onRefresh = { pager.refresh() },
        modifier = Modifier.fillMaxSize(),
        indicatorPadding = PaddingValues(top = padding.calculateTopPadding()),
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(enterFade) togetherWith fadeOut(exitFade) },
            label = "pagerBody",
        ) { s ->
            when (s) {
                BodyState.NeedLogin -> Box(Modifier.fillMaxSize().padding(padding)) { LoginNeeded() }
                BodyState.Loading -> LoadingState(Modifier.padding(padding))
                BodyState.Error -> ScrollableFill(padding) { h ->
                    ErrorState(pager.error.orEmpty(), Modifier.height(h), onRetry = { pager.refresh() })
                }
                BodyState.Empty -> ScrollableFill(padding) { h ->
                    EmptyState(emptyLabel, Modifier.height(h), icon = emptyIcon)
                }
                BodyState.Content -> content(listPadding)
            }
        }
    }
}

/** The gap [PagerBody] adds above a list, under whatever chrome its padding clears. */
private val ListTopGap = 4.dp

/**
 * A library grid as a seam viewport (ui/components/seam): the seam is the
 * chrome above it — the large top bar, a tab row, the search field — at
 * [listPadding]'s top less [PagerBody]'s gap, so rows sink under the tide
 * line there instead of being cut at the bar.
 */
@Composable
internal fun Modifier.librarySeam(state: LazyGridState, listPadding: PaddingValues): Modifier =
    seamViewport(state, topChrome = (listPadding.calculateTopPadding() - ListTopGap).coerceAtLeast(0.dp))

/** Full-height, vertically scrollable slot so pull-to-refresh also works on empty / error states. */
@Composable
private fun ScrollableFill(padding: PaddingValues, content: @Composable (Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val h = maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content(h) }
    }
}

/** Footer for paged grids: loading indicator, or retry after a failed page. */
internal fun <T> LazyGridScope.pagerFooter(pager: Pager<T>) {
    item(key = "__footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
        when {
            pager.isLoadingMore -> LoadingFooter()
            pager.error != null && pager.hasMore -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = { pager.loadMore() }) { Text("重试") }
            }
            else -> Spacer(Modifier.height(8.dp))
        }
    }
}

/** Section label inside a grid (spans the whole line). */
internal fun LazyGridScope.sectionHeader(key: String, title: String, trailing: (@Composable () -> Unit)? = null) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).seamText(),
            )
            trailing?.invoke()
        }
    }
}

@Composable
internal fun LoginNeeded(modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(YoinSymbols.Person, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        Text("未登录", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { nav.push(Route.Login) }) { Text("登录") }
    }
}

// ── Dialogs & sheets ────────────────────────────────────────────────────────

@Composable
internal fun ConfirmDialog(
    title: String,
    text: String? = null,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Immutable
internal data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** One-item action sheet: a header (cover + title), then actions as segmented color blocks. */
@Composable
internal fun ItemActionSheet(
    title: String,
    cover: String? = null,
    avatar: Boolean = false,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!cover.isNullOrEmpty()) {
                    if (avatar) {
                        Avatar(cover, 44.dp)
                    } else {
                        NetImage(cover, Modifier.width(80.dp).aspectRatio(16f / 10f), shape = ContinuousRoundedCornerShape(8.dp), thumbWidth = 80.dp)
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                actions.forEachIndexed { i, a ->
                    val top = if (i == 0) 20.dp else 4.dp
                    val bottom = if (i == actions.lastIndex) 20.dp else 4.dp
                    val shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
                    val tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable {
                                scope.launch { state.hide() }.invokeOnCompletion {
                                    onDismiss()
                                    a.onClick()
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(a.icon, null, tint = tint)
                        Text(a.label, style = MaterialTheme.typography.bodyLarge, color = tint)
                    }
                }
            }
        }
    }
}

// ── User rows (关注 / 粉丝 / 黑名单) ─────────────────────────────────────────

@Immutable
internal data class LibUser(
    val mid: Long,
    val name: String,
    val face: String,
    val sign: String,
    /** 0 none, 1 悄悄关注, 2 关注, 6 互相关注, 128 拉黑. */
    val attribute: Int = 0,
    val officialDesc: String = "",
    val vip: Boolean = false,
    val mtime: Long = 0,
)

internal fun JsonObject.toLibUser() = LibUser(
    mid = long("mid"),
    name = str("uname"),
    face = str("face"),
    sign = str("sign"),
    attribute = int("attribute"),
    officialDesc = obj("official_verify").str("desc"),
    vip = obj("vip").int("vipStatus") == 1,
    mtime = long("mtime"),
)

/** Avatar, name over sign, optional trailing control. Tap opens the space. */
@Composable
internal fun UserRow(
    user: LibUser,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val nav = LocalNavigator.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .combinedClickable(
                onLongClick = onLongClick,
                onClick = { nav.push(Route.Member(user.mid, user.face, user.name)) },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(user.face, 48.dp)
        Column(Modifier.weight(1f).seamText(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    user.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (user.vip) FontWeight.SemiBold else null,
                    color = if (user.vip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) Tag(badge)
            }
            val second = user.officialDesc.ifEmpty { user.sign }
            if (second.isNotBlank()) {
                Text(
                    second,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** POST /x/relation/modify — act: 1 follow, 2 unfollow, 5 block, 6 unblock. */
internal suspend fun relationModify(fid: Long, act: Int, reSrc: Int = 11) {
    BiliClient.postData("/x/relation/modify", mapOf("fid" to fid, "act" to act, "re_src" to reSrc))
}

// ── Fav folders ─────────────────────────────────────────────────────────────

@Immutable
internal data class FavFolder(
    val id: Long,
    val fid: Long = 0,
    val mid: Long = 0,
    val attr: Int = 0,
    val title: String,
    val cover: String,
    val intro: String = "",
    val mediaCount: Int = 0,
    val upperName: String = "",
    val upperMid: Long = 0,
    /** Collected list: 11 = 收藏夹, 21 = 合集. */
    val type: Int = 11,
) {
    val isPrivate: Boolean get() = attr and 1 == 1
    val isDefault: Boolean get() = attr and 2 == 0
}

internal fun JsonObject.toFavFolder() = FavFolder(
    id = long("id"),
    fid = long("fid"),
    mid = long("mid"),
    attr = int("attr"),
    title = str("title"),
    cover = str("cover"),
    intro = str("intro"),
    mediaCount = int("media_count"),
    upperName = obj("upper").str("name"),
    upperMid = obj("upper").long("mid"),
    type = int("type", 11),
)

/** A media entry of /x/v3/fav/resource/list or /x/space/fav/season/list. */
internal fun JsonObject.favMediaToVideo(): VideoItem {
    val link = str("link")
    val ogvSeason = obj("ogv").long("season_id")
    val target: Route? = when {
        ogvSeason > 0 -> Route.Bangumi(seasonId = ogvSeason, cover = str("cover"))
        link.contains("/bangumi/") -> com.gpo.pilipala.navigation.LinkRouter.route(link, str("title"))
        else -> null
    }
    val cnt = obj("cnt_info")
    return VideoItem(
        bvid = str("bvid").ifEmpty { str("bv_id") },
        aid = long("id"),
        cid = obj("ugc").long("first_cid"),
        title = str("title"),
        cover = str("cover"),
        duration = long("duration"),
        ownerMid = obj("upper").long("mid"),
        ownerName = obj("upper").str("name"),
        ownerFace = obj("upper").str("face"),
        views = if (cnt != null) cnt.long("play") else -1,
        danmaku = if (cnt != null) cnt.long("danmaku") else -1,
        pubdate = long("pubtime"),
        badge = obj("ogv").str("type_name"),
        target = target,
    )
}

/** Folder card: cover 16:10, title, item count; lock for private folders. */
@Composable
internal fun FolderCard(
    folder: FavFolder,
    modifier: Modifier = Modifier,
    /** The page it opens, for the card-grown transition. */
    route: Route? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .then(if (route != null) Modifier.pageSharedSource(route) else Modifier)
            .clip(YoinContainerShapes.Card)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            NetImage(folder.cover, Modifier.matchParentSize(), shape = LibCoverShape, thumbWidth = 240.dp)
            if (folder.cover.isEmpty()) {
                Icon(
                    YoinSymbols.Folder,
                    null,
                    Modifier.align(Alignment.Center).size(32.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Text(
            folder.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp).seamText(),
        )
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 2.dp).seamText(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${folder.mediaCount}个内容",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (folder.isPrivate) {
                Icon(
                    com.gpo.yoin.symbols.PiliSymbols.Lock,
                    contentDescription = "私密",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Filled tonal surface for a group (no strokes). */
@Composable
internal fun BlockSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, shape = YoinContainerShapes.Card, color = MaterialTheme.colorScheme.surfaceContainer, content = content)
}

// ── Multi-select (历史 / 稍后再看) ───────────────────────────────────────────

/** Select mode's top-bar glyph: a rounded square holding a check. 24 viewport, 1.5 stroke, round caps. */
internal val SelectGlyph: ImageVector by lazy {
    ImageVector.Builder("Select", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M7.5 4h9a3.5 3.5 0 0 1 3.5 3.5v9a3.5 3.5 0 0 1 -3.5 3.5h-9a3.5 3.5 0 0 1 -3.5 -3.5v-9a3.5 3.5 0 0 1 3.5 -3.5z" +
                    "M8.5 12.3l2.4 2.4l4.6 -5.2",
            ).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}

/** A selected row sits on a tonal block; unselected rows have none. */
@Composable
internal fun Modifier.selectionBlock(selected: Boolean): Modifier {
    val block = MaterialTheme.colorScheme.secondaryContainer
    val color by animateColorAsState(
        if (selected) block else block.copy(alpha = 0f),
        YoinMotion.defaultEffectsSpec(),
        label = "selectionBlock",
    )
    // Background only, no clip: the card's shared-element source must not be cut.
    return this.background(color, YoinContainerShapes.ListRow)
}

/** Filled check block laid over a selected cover (placed and sized by [modifier]). */
@Composable
internal fun SelectionCover(selected: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = selected,
        modifier = modifier,
        enter = fadeIn(YoinMotion.defaultEffectsSpec()),
        exit = fadeOut(YoinMotion.fastEffectsSpec()),
    ) {
        Box(
            Modifier.fillMaxSize().clip(LibCoverShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .animateEnterExit(
                        enter = scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.4f),
                        exit = scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.4f),
                    )
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(YoinSymbols.Check, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** The cover slot of a 152dp row card (VideoCardH / HistoryCard), where [SelectionCover] goes. */
internal val RowCoverSlot: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(152.dp).aspectRatio(16f / 10f)

/** Room the floating selection toolbar takes at the bottom of a list. */
internal val SelectionToolbarRoom = 96.dp

/** Floating toolbar of select mode: 全选 toggle, then the destructive action with the count. */
@Composable
internal fun BoxScope.SelectionToolbar(
    visible: Boolean,
    count: Int,
    allSelected: Boolean,
    deleteLabel: String,
    onSelectAll: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(YoinMotion.defaultSpatialSpec()) { it } + fadeIn(YoinMotion.defaultEffectsSpec()),
        exit = slideOutVertically(YoinMotion.fastSpatialSpec()) { it } + fadeOut(YoinMotion.fastEffectsSpec()),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
        ) {
            ToggleButton(
                checked = allSelected,
                onCheckedChange = {
                    haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onSelectAll(it)
                },
                shapes = ToggleButtonDefaults.shapes(),
            ) {
                Icon(YoinSymbols.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("全选")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onDelete,
                enabled = count > 0,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(YoinSymbols.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(deleteLabel)
                Spacer(Modifier.width(6.dp))
                val slide = YoinMotion.fastSpatialSpec<IntOffset>()
                val fade = YoinMotion.fastEffectsSpec<Float>()
                AnimatedContent(
                    targetState = count,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(slide) { if (up) it else -it } + fadeIn(fade)) togetherWith
                            (slideOutVertically(slide) { if (up) -it else it } + fadeOut(fade))
                    },
                    label = "selectionCount",
                ) { n -> Text("$n", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Top-bar toggle of select mode: the select glyph morphs into a close. */
@Composable
internal fun <K> SelectModeButton(selection: Selection<K>) {
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptics.performHapticFeedback(if (selection.active) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            if (selection.active) selection.exit() else selection.enter()
        },
        shapes = IconButtonDefaults.shapes(),
    ) {
        val scale = YoinMotion.fastSpatialSpec<Float>()
        val enterFade = YoinMotion.defaultEffectsSpec<Float>()
        val exitFade = YoinMotion.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = selection.active,
            transitionSpec = {
                (scaleIn(scale, initialScale = 0.6f) + fadeIn(enterFade)) togetherWith
                    (scaleOut(scale, targetScale = 0.6f) + fadeOut(exitFade))
            },
            label = "selectMode",
        ) { on -> Icon(if (on) YoinSymbols.Close else SelectGlyph, contentDescription = if (on) "退出多选" else "多选") }
    }
}

/** Select mode state for a list keyed by [K]: the selected keys, kept to what is still on screen. */
@Stable
internal class Selection<K> {
    var active by mutableStateOf(false)
        private set
    var keys by mutableStateOf<Set<K>>(emptySet())
        private set

    fun enter() {
        active = true
    }

    fun exit() {
        active = false
        keys = emptySet()
    }

    fun toggle(key: K) {
        keys = if (key in keys) keys - key else keys + key
    }

    fun setAll(all: Collection<K>, on: Boolean) {
        keys = if (on) keys + all else keys - all.toSet()
    }

    /** Drops keys that left the list (deleted, refreshed away). */
    fun retain(present: Collection<K>) {
        val set = present.toSet()
        if (keys.any { it !in set }) keys = keys.filterTo(HashSet()) { it in set }
    }
}

