package com.gpo.pilipala.feature.dynamics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.core.util.IdUtils
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Navigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.CoverBadge
import com.gpo.pilipala.ui.components.DurationChip
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.FameSelf
import com.gpo.pilipala.ui.components.LocalFameSelf
import com.gpo.pilipala.ui.components.fameHides
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.seam.seamGraphic
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.SheetInsets
import com.gpo.pilipala.ui.components.inlineIconSize
import com.gpo.pilipala.ui.components.centered

private val CoverShape = ContinuousRoundedCornerShape(12.dp)
private val BlockShape = ContinuousRoundedCornerShape(12.dp)
private val LinkShape = RoundedCornerShape(6.dp)

/**
 * One dynamic, rendered as a filled surface card (Flutter DynamicPanel):
 * author row, rich text, pictures / major content, the forwarded original in
 * a raised block, and the connected action group. [detail] shows the full
 * text, makes it selectable and drops the comment shortcut.
 */
@Composable
internal fun DynamicCard(
    item: DynItem,
    modifier: Modifier = Modifier,
    detail: Boolean = false,
) {
    val nav = LocalNavigator.current
    val viewer = rememberImageViewerState()
    val route = item.route
    val handler = remember(nav, viewer) { CardUriHandler(nav, viewer) }

    CompositionLocalProvider(
        LocalUriHandler provides handler,
        // 名声模式: the user's own dynamic (转发 / 点赞, a video it posts) counts as their own.
        LocalFameSelf provides FameMode.isSelf(item.json.author.long("mid")),
    ) {
        Column(
            modifier
                .fillMaxWidth()
                // A tinted card is one print for the seams (it dots in the floating bar's
                // field); every text block in it carries seamText and lifts out whole.
                .seamGraphic()
                .clip(YoinContainerShapes.Card)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                // In detail mode the card still opens what it links to (article, video, live…).
                .then(if (route != null && !(detail && route is Route.DynamicDetail)) Modifier.clickable { nav.push(route) } else Modifier)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AuthorRow(item)
            Column(
                Modifier.padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DynamicBody(
                    item = item,
                    nested = false,
                    detail = detail,
                    blockColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    viewer = viewer,
                )
                if (item.kind == "DYNAMIC_TYPE_FORWARD") {
                    item.orig?.let { ForwardBlock(it, viewer) }
                }
                ActionRow(item, detail)
            }
        }
    }
    ImageViewerHost(viewer)
}

private class CardUriHandler(private val nav: Navigator, private val viewer: ImageViewerState) : UriHandler {
    override fun openUri(uri: String) {
        if (uri.startsWith(PICS_SCHEME)) {
            viewer.open(uri.removePrefix(PICS_SCHEME).split('|').filter { it.isNotEmpty() })
        } else {
            nav.openUrl(uri)
        }
    }
}

// ── Author ──────────────────────────────────────────────────────────────────

@Composable
private fun AuthorRow(item: DynItem) {
    val nav = LocalNavigator.current
    val a = item.json.author
    val mid = a.long("mid")
    val face = a.str("face")
    val name = a.str("name")
    val isPgc = a.str("type") == "AUTHOR_TYPE_PGC"
    val vip = a.obj("vip").int("status") > 0
    val isTop = a.bool("is_top") || item.json.obj("modules").obj("module_tag").str("text") == "置顶"
    var showMore by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            face,
            40.dp,
            onClick = if (isPgc || mid <= 0) null else { { nav.push(Route.Member(mid, face, name)) } },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).seamText(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = if (vip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val time = item.timeText
                if (time.isNotEmpty()) {
                    Text(time, style = MaterialTheme.typography.labelSmall.centered(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val action = a.str("pub_action")
                if (action.isNotEmpty()) {
                    Text(
                        action,
                        style = MaterialTheme.typography.labelSmall.centered(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (isTop) {
            Tag("置顶", Modifier.padding(horizontal = 4.dp))
        }
        IconButton(onClick = { showMore = true }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.seamText()) {
            Icon(YoinSymbols.MoreVertical, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showMore) MoreSheet(item.json) { showMore = false }
}

// ── Body ────────────────────────────────────────────────────────────────────

@Composable
private fun DynamicBody(
    item: DynItem,
    nested: Boolean,
    detail: Boolean,
    blockColor: Color,
    viewer: ImageViewerState,
) {
    val dyn = item.json.dynModule
    val topic = dyn.obj("topic")
    if (topic != null && topic.str("name").isNotEmpty()) {
        val nav = LocalNavigator.current
        Text(
            "#" + topic.str("name"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.seamText().overflowClickable(
                horizontal = 8.dp,
                vertical = 14.dp,
                shape = LinkShape,
                enabled = topic.str("jump_url").isNotEmpty(),
            ) { nav.openUrl(normalizeUrl(topic.str("jump_url"))) },
        )
    }

    val rich = rememberRichContent(item.json)
    val textStyle = if (nested) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    if (detail && !nested) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            RichTextView(rich, Modifier.seamText(textStyle.fontSize), style = textStyle)
        }
    } else {
        RichTextView(
            rich,
            Modifier.seamText(textStyle.fontSize),
            style = textStyle,
            collapsedLines = if (nested) 4 else 6,
            expandable = !nested,
        )
    }

    val pics = item.pictures
    if (pics.isNotEmpty()) {
        PicGrid(pics, onOpen = { viewer.open(pics.map { it.url }, it) })
    }

    MajorContent(item.json, blockColor)
    AdditionalContent(item.json, blockColor)
}

@Composable
private fun MajorContent(item: JsonObject, blockColor: Color) {
    val nav = LocalNavigator.current
    val major = item.major ?: return
    when (major.str("type")) {
        "MAJOR_TYPE_ARCHIVE" -> major.obj("archive")?.let { a ->
            val aid = a.long("aid")
            val bvid = a.str("bvid").ifEmpty { if (aid > 0) IdUtils.av2bv(aid) else "" }
            VideoBlock(
                cover = a.str("cover"),
                title = a.str("title"),
                duration = a.str("duration_text"),
                play = a.obj("stat").str("play"),
                danmaku = a.obj("stat").str("danmaku"),
                badge = a.obj("badge").str("text").takeIf { it != "投稿视频" },
                onClick = if (bvid.isEmpty()) null else {
                    { nav.push(Route.Video(bvid, aid = aid, cover = a.str("cover"), title = a.str("title"))) }
                },
            )
        }
        "MAJOR_TYPE_UGC_SEASON" -> major.obj("ugc_season")?.let { s ->
            val aid = s.long("aid")
            VideoBlock(
                cover = s.str("cover"),
                title = s.str("title"),
                duration = s.str("duration_text"),
                play = s.obj("stat").str("play"),
                danmaku = s.obj("stat").str("danmaku"),
                badge = "合集",
                onClick = if (aid <= 0) null else {
                    { nav.push(Route.Video(IdUtils.av2bv(aid), aid = aid, cover = s.str("cover"), title = s.str("title"))) }
                },
            )
        }
        "MAJOR_TYPE_PGC" -> major.obj("pgc")?.let { p ->
            VideoBlock(
                cover = p.str("cover"),
                title = p.str("title"),
                duration = "",
                play = p.obj("stat").str("play"),
                danmaku = p.obj("stat").str("danmaku"),
                badge = p.obj("badge").str("text").ifEmpty { null },
                onClick = { nav.push(Route.Bangumi(seasonId = p.long("season_id"), epId = p.long("epid"), cover = p.str("cover"))) },
            )
        }
        "MAJOR_TYPE_LIVE_RCMD" -> parseLiveRcmd(major)?.let { l ->
            LiveBlock(
                cover = l.cover,
                title = l.title,
                area = l.area,
                watched = l.watched,
                isLive = l.isLive,
                onClick = { nav.push(Route.LiveRoom(l.roomId, l.cover, l.title)) },
            )
        }
        "MAJOR_TYPE_LIVE" -> major.obj("live")?.let { l ->
            RowBlock(
                cover = l.str("cover"),
                coverRatio = 16f / 10f,
                title = l.str("title"),
                subtitle = l.str("desc_first"),
                badge = l.obj("badge").str("text").ifEmpty { null },
                color = blockColor,
                onClick = { nav.push(Route.LiveRoom(l.long("id"), l.str("cover"), l.str("title"))) },
            )
        }
        "MAJOR_TYPE_ARTICLE" -> major.obj("article")?.let { ar ->
            RowBlock(
                cover = ar.arr("covers").firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                coverRatio = 16f / 10f,
                title = ar.str("title"),
                subtitle = ar.str("desc").ifEmpty { ar.str("label") },
                badge = "专栏",
                color = blockColor,
                onClick = { articleRoute(major)?.let(nav::push) },
            )
        }
        "MAJOR_TYPE_COMMON" -> major.obj("common")?.let { c ->
            RowBlock(
                cover = c.str("cover"),
                title = c.str("title"),
                subtitle = c.str("desc"),
                badge = c.obj("badge").str("text").ifEmpty { null },
                color = blockColor,
                onClick = c.str("jump_url").takeIf { it.isNotEmpty() }?.let { u -> { nav.openUrl(normalizeUrl(u), c.str("title")) } },
            )
        }
        "MAJOR_TYPE_MUSIC" -> major.obj("music")?.let { m ->
            RowBlock(
                cover = m.str("cover"),
                title = m.str("title"),
                subtitle = m.str("label"),
                badge = "音频",
                color = blockColor,
                onClick = m.str("jump_url").takeIf { it.isNotEmpty() }?.let { u -> { nav.openUrl(normalizeUrl(u), m.str("title")) } },
            )
        }
        "MAJOR_TYPE_COURSES" -> major.obj("courses")?.let { c ->
            RowBlock(
                cover = c.str("cover"),
                coverRatio = 16f / 10f,
                title = c.str("title"),
                subtitle = c.str("sub_title").ifEmpty { c.str("desc") },
                badge = c.obj("badge").str("text").ifEmpty { "课堂" },
                color = blockColor,
                onClick = c.str("jump_url").takeIf { it.isNotEmpty() }?.let { u -> { nav.openUrl(normalizeUrl(u), c.str("title")) } },
            )
        }
        "MAJOR_TYPE_MEDIALIST", "MAJOR_TYPE_SUBSCRIPTION_NEW", "MAJOR_TYPE_UPOWER_COMMON" -> {
            val key = major.str("type").removePrefix("MAJOR_TYPE_").lowercase()
            val o = major.obj(key)
            if (o != null && o.str("title").isNotEmpty()) {
                RowBlock(
                    cover = o.str("cover"),
                    title = o.str("title"),
                    subtitle = o.str("sub_title").ifEmpty { o.str("desc") },
                    badge = null,
                    color = blockColor,
                    onClick = o.str("jump_url").takeIf { it.isNotEmpty() }?.let { u -> { nav.openUrl(normalizeUrl(u)) } },
                )
            }
        }
        "MAJOR_TYPE_NONE" -> QuietNotice(major.obj("none").str("tips").ifEmpty { "动态已失效" })
        "MAJOR_TYPE_BLOCKED" -> QuietNotice(major.obj("blocked").str("hint_message").ifEmpty { "内容不可见" })
        else -> Unit
    }
}

@Composable
private fun AdditionalContent(item: JsonObject, blockColor: Color) {
    val nav = LocalNavigator.current
    val add = item.dynModule.obj("additional")
    // A vote card for additional.vote, or for a vote that only shows as an inline node.
    if (add == null || add.str("type") == "ADDITIONAL_TYPE_VOTE") {
        VoteForItem(item, blockColor)
        return
    }
    when (add.str("type")) {
        "ADDITIONAL_TYPE_UGC" -> add.obj("ugc")?.let { u ->
            val bvid = IdUtils.findBvid(u.str("jump_url"))
            RowBlock(
                cover = u.str("cover"),
                coverRatio = 16f / 10f,
                title = u.str("title"),
                subtitle = u.str("desc_second"),
                badge = u.str("duration").ifEmpty { null },
                color = blockColor,
                onClick = { if (bvid != null) nav.push(Route.Video(bvid, cover = u.str("cover"), title = u.str("title"))) else nav.openUrl(normalizeUrl(u.str("jump_url"))) },
            )
        }
        "ADDITIONAL_TYPE_RESERVE" -> add.obj("reserve")?.let { r ->
            if (r.int("state") == -1 || r.str("title").isEmpty()) return@let
            RowBlock(
                cover = null,
                title = r.str("title"),
                subtitle = listOf(r.obj("desc1").str("text"), r.obj("desc2").str("text")).filter { it.isNotEmpty() }.joinToString("  "),
                badge = r.obj("button").obj("uncheck").str("text").ifEmpty { null },
                color = blockColor,
                onClick = r.str("jump_url").takeIf { it.isNotEmpty() }?.let { u -> { nav.openUrl(normalizeUrl(u)) } },
            )
        }
        "ADDITIONAL_TYPE_GOODS" -> add.obj("goods")?.let { g ->
            val first = g.arr("items").objects().firstOrNull() ?: return@let
            RowBlock(
                cover = first.str("cover"),
                title = first.str("name"),
                subtitle = first.str("price").ifEmpty { g.str("head_text") },
                badge = "商品",
                color = blockColor,
                onClick = first.str("jump_url").ifEmpty { g.str("jump_url") }.takeIf { it.isNotEmpty() }?.let { u -> { nav.openUrl(normalizeUrl(u)) } },
            )
        }
        "ADDITIONAL_TYPE_COMMON" -> add.obj("common")?.let { c ->
            RowBlock(
                cover = c.str("cover"),
                title = c.str("title"),
                subtitle = c.str("desc1").ifEmpty { c.str("desc2") },
                badge = c.str("head_text").ifEmpty { null },
                color = blockColor,
                onClick = c.str("jump_url").takeIf { it.isNotEmpty() }?.let { u -> { nav.openUrl(normalizeUrl(u)) } },
            )
        }
        "ADDITIONAL_TYPE_UPOWER_LOTTERY" -> add.obj("upower_lottery")?.let { l ->
            RowBlock(
                cover = null,
                icon = PiliSymbols.Gift,
                title = l.str("title"),
                subtitle = l.obj("desc").str("text"),
                badge = null,
                color = blockColor,
                onClick = l.str("jump_url").takeIf { it.isNotEmpty() }?.let { u -> { nav.openUrl(normalizeUrl(u)) } },
            )
        }
        else -> Unit
    }
}

// ── Forwarded original ─────────────────────────────────────────────────────

@Composable
private fun ForwardBlock(orig: DynItem, viewer: ImageViewerState) {
    val nav = LocalNavigator.current
    val route = orig.route
    val a = orig.json.author
    val high = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        Modifier
            .fillMaxWidth()
            .clip(BlockShape)
            .background(high)
            .then(if (route != null) Modifier.clickable { nav.push(route) } else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (orig.kind == "DYNAMIC_TYPE_NONE") {
            QuietNotice(orig.json.major.obj("none").str("tips").ifEmpty { "源动态已被删除" })
            return@Column
        }
        Row(Modifier.seamText(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val name = a.str("name")
            val mid = a.long("mid")
            val isPgc = a.str("type") == "AUTHOR_TYPE_PGC"
            Text(
                if (isPgc) name else "@$name",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Name and time share a baseline (two sizes).
                modifier = Modifier
                    .alignByBaseline()
                    .weight(1f, fill = false)
                    .overflowClickable(
                        horizontal = 8.dp,
                        vertical = 14.dp,
                        shape = LinkShape,
                        enabled = !isPgc && mid > 0,
                    ) { nav.push(Route.Member(mid, a.str("face"), name)) },
            )
            val time = orig.timeText
            if (time.isNotEmpty()) {
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
            }
        }
        // The original belongs to its own author, not to whoever forwarded it.
        FameSelf(FameMode.isSelf(a.long("mid"))) {
            DynamicBody(
                item = orig,
                nested = true,
                detail = false,
                blockColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                viewer = viewer,
            )
        }
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────

@Composable
private fun VideoBlock(
    cover: String,
    title: String,
    duration: String,
    play: String,
    danmaku: String,
    badge: String?,
    onClick: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CoverShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            NetImage(cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = 480.dp)
            CoverScrim(Modifier.align(Alignment.BottomCenter))
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (play.isNotEmpty() && play != "-") Stat(PiliSymbols.Views, Fmt.count(play), tint = Color.White)
                if (danmaku.isNotEmpty() && danmaku != "-") Stat(PiliSymbols.Danmaku, Fmt.count(danmaku), tint = Color.White)
                Spacer(Modifier.weight(1f))
                if (duration.isNotEmpty()) DurationChip(duration)
            }
            if (!badge.isNullOrEmpty()) CoverBadge(badge, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        if (title.isNotEmpty()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp).seamText(),
            )
        }
    }
}

@Composable
private fun LiveBlock(
    cover: String,
    title: String,
    area: String,
    watched: String,
    isLive: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(CoverShape).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            NetImage(cover, Modifier.matchParentSize(), shape = CoverShape, thumbWidth = 480.dp)
            Row(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (watched.isNotEmpty()) {
                    Text(
                        watched,
                        modifier = Modifier
                            .seamText()
                            .clip(ContinuousRoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall.centered(),
                        color = Color.White,
                    )
                }
                CoverBadge(if (isLive) "直播中" else "直播结束")
            }
            if (area.isNotEmpty()) {
                CoverScrim(Modifier.align(Alignment.BottomCenter))
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val areaStyle = MaterialTheme.typography.labelSmall.centered()
                    Icon(PiliSymbols.Live, null, Modifier.size(areaStyle.inlineIconSize()).seamText(), tint = Color.White)
                    Text(area, style = areaStyle, color = Color.White, modifier = Modifier.seamText())
                }
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.seamText())
    }
}

/**
 * Bottom scrim behind white labels on a cover: tall enough (56dp) that the
 * label row sits on ~0.5 black even over white covers.
 */
@Composable
private fun CoverScrim(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CoverShape)
            .background(CoverScrimBrush),
    )
}

private val CoverScrimBrush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.4f to Color.Black.copy(alpha = 0.35f),
    1f to Color.Black.copy(alpha = 0.7f),
)

@Composable
private fun RowBlock(
    cover: String?,
    title: String,
    subtitle: String?,
    badge: String?,
    color: Color,
    onClick: (() -> Unit)?,
    coverRatio: Float = 1f,
    icon: ImageVector? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(BlockShape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!cover.isNullOrEmpty()) {
            NetImage(
                cover,
                Modifier.height(56.dp).aspectRatio(coverRatio),
                shape = ContinuousRoundedCornerShape(8.dp),
                thumbWidth = 160.dp,
            )
        } else if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(ContinuousRoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Column(Modifier.weight(1f).seamText(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!badge.isNullOrEmpty()) Tag(badge)
    }
}

@Composable
private fun QuietNotice(text: String) {
    Row(Modifier.seamText(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(YoinSymbols.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Actions ─────────────────────────────────────────────────────────────────

@Composable
private fun ActionRow(item: DynItem, detail: Boolean) {
    val nav = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val stat = item.json.statModule ?: return
    val id = item.id
    if (id.isEmpty()) return
    val fallback = remember(item) { LikeState(stat.obj("like").bool("status"), stat.obj("like").long("count")) }
    val like = LikeCache.get(id, fallback)
    var showForward by remember { mutableStateOf(false) }

    val quiet = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val pad = PaddingValues(horizontal = 8.dp)
    val popIn = YoinMotion.fastSpatialSpec<Float>()
    val fadeInSpec = YoinMotion.defaultEffectsSpec<Float>()
    val fadeOutSpec = YoinMotion.fastEffectsSpec<Float>()

    Row(
        // The quiet buttons are labels: they lift out of the card's print whole.
        Modifier.fillMaxWidth().heightIn(min = 40.dp).seamText(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        Button(
            onClick = {
                if (!BiliClient.isLoggedIn) nav.push(Route.Login) else showForward = true
            },
            shapes = ButtonDefaults.shapes(
                ButtonGroupDefaults.connectedLeadingButtonShape,
                ButtonGroupDefaults.connectedLeadingButtonPressShape,
            ),
            colors = quiet,
            contentPadding = pad,
            modifier = Modifier.weight(1f),
        ) {
            ActionLabel(PiliSymbols.Forward, stat.obj("forward").long("count"), "转发")
        }
        if (!detail) {
            Button(
                onClick = { nav.push(Route.DynamicDetail(id)) },
                shapes = ButtonDefaults.shapes(
                    RoundedCornerShape(8.dp),
                    ButtonGroupDefaults.connectedMiddleButtonPressShape,
                ),
                colors = quiet,
                contentPadding = pad,
                modifier = Modifier.weight(1f),
            ) {
                ActionLabel(PiliSymbols.Comment, stat.obj("comment").long("count"), "评论", fame = false)
            }
        }
        ToggleButton(
            checked = like.liked,
            onCheckedChange = { target ->
                if (!BiliClient.isLoggedIn) {
                    nav.push(Route.Login)
                    return@ToggleButton
                }
                haptics.performHapticFeedback(if (target) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                val before = like
                LikeCache.set(id, LikeState(target, (before.count + if (target) 1 else -1).coerceAtLeast(0)))
                scope.launch {
                    runCatching { DynamicsApi.like(id, target) }.onFailure {
                        LikeCache.set(id, before)
                        toast(it.friendlyMessage())
                    }
                }
            },
            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            colors = ToggleButtonDefaults.toggleButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            contentPadding = pad,
            modifier = Modifier.weight(1f),
        ) {
            AnimatedContent(
                targetState = like.liked,
                transitionSpec = {
                    (scaleIn(popIn, initialScale = 0.6f) + fadeIn(fadeInSpec)).togetherWith(fadeOut(fadeOutSpec))
                },
                label = "likeIcon",
            ) { liked ->
                Icon(if (liked) PiliSymbols.ThumbUpFilled else PiliSymbols.ThumbUp, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            AnimatedContent(
                targetState = countText(like.count, "点赞", hidden = fameHides()),
                transitionSpec = { fadeIn(fadeInSpec).togetherWith(fadeOut(fadeOutSpec)) },
                label = "likeCount",
            ) { text ->
                Text(text, style = MaterialTheme.typography.labelLarge.centered(), maxLines = 1)
            }
        }
    }
    if (showForward) ForwardSheet(item) { showForward = false }
}

@Composable
private fun ActionLabel(icon: ImageVector, count: Long, label: String, fame: Boolean = true) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(countText(count, label, hidden = fame && fameHides()), style = MaterialTheme.typography.labelLarge.centered(), maxLines = 1)
}

/**
 * A button's count, or its action word at zero. Under 名声模式 a popularity count
 * (转发, 点赞; [hidden] from [fameHides]) gives way to the word as well: a button shows
 * nothing that looks like a line to tap open, since a tap there is the button's own action.
 */
private fun countText(count: Long, label: String, hidden: Boolean): String =
    if (count > 0 && !hidden) Fmt.count(count) else label

// ── Sheets ──────────────────────────────────────────────────────────────────

@Composable
private fun MoreSheet(item: JsonObject, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val nav = LocalNavigator.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val url = dynamicShareUrl(item)
    val archive = item.major.obj("archive").takeIf { item.dynType == "DYNAMIC_TYPE_AV" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SheetGroup {
                if (archive != null) {
                    SheetRow(PiliSymbols.WatchLater, "稍后再看") {
                        scope.launch {
                            runCatching { DynamicsApi.watchLater(archive.long("aid"), archive.str("bvid")) }
                                .onSuccess { toast("已添加到稍后再看") }
                                .onFailure { toast(it.friendlyMessage()) }
                            onDismiss()
                        }
                    }
                }
                SheetRow(YoinSymbols.Share, "分享") {
                    shareText(context, url)
                    onDismiss()
                }
                SheetRow(YoinSymbols.Copy, "复制链接") {
                    copyText(context, url)
                    toast("已复制")
                    onDismiss()
                }
                SheetRow(YoinSymbols.Launch, "网页打开") {
                    onDismiss()
                    nav.push(Route.Web(url, "动态"))
                }
            }
        }
    }
}

@Composable
internal fun SheetGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(YoinContainerShapes.Panel).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        content = content,
    )
}

@Composable
internal fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Repost composer (Flutter ActionPanel.forwardHandler). */
@Composable
private fun ForwardSheet(item: DynItem, onDismiss: () -> Unit) {
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val preview = rememberRichContent(item.json)
    val a = item.json.author
    val cover = remember(item) {
        item.pictures.firstOrNull()?.url
            ?: item.json.major.obj("archive")?.str("cover")
            ?: item.orig?.pictures?.firstOrNull()?.url
            ?: item.orig?.json?.major.obj("archive")?.str("cover")
            ?: a.str("face")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = SheetInsets,
        properties = rememberSheetProperties(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("转发动态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(
                    enabled = !sending,
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        sending = true
                        scope.launch {
                            runCatching { DynamicsApi.forward(item.id, text.trim()) }
                                .onSuccess {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    toast("已转发")
                                    onDismiss()
                                }
                                .onFailure { toast(it.friendlyMessage()) }
                            sending = false
                        }
                    },
                ) { Text("转发") }
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                placeholder = { Text("附言") },
                shape = YoinContainerShapes.Card,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Row(
                Modifier.fillMaxWidth().clip(BlockShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetImage(cover, Modifier.size(48.dp), shape = ContinuousRoundedCornerShape(8.dp), thumbWidth = 96.dp)
                Column(Modifier.weight(1f).widthIn(min = 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("@" + a.str("name"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (!preview.isEmpty) {
                        Text(
                            preview.text.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
