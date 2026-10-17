package com.gpo.pilipala.feature.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.CoverBadge
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.PiliSymbols
import java.util.Locale

// ── 番剧 / 影视 ─────────────────────────────────────────────────────────────

/** Cover-left filled card: badge, title, meta, score, watch button, episode strip. */
@Composable
internal fun MediaCard(hit: MediaHit, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val scheme = MaterialTheme.colorScheme
    val open = { epId: Long -> nav.push(Route.Bangumi(seasonId = hit.seasonId, epId = epId, cover = hit.cover)) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.Card)
            .background(scheme.surfaceContainerLow)
            .clickable { open(0) },
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(100.dp).aspectRatio(3f / 4f)) {
                NetImage(hit.cover, Modifier.matchParentSize(), shape = SearchCoverShape, thumbWidth = 140.dp)
                val badge = hit.badge.ifEmpty { hit.typeLabel }
                if (badge.isNotEmpty()) CoverBadge(badge, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(Modifier.weight(1f).height(133.dp)) {
                Text(
                    hit.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val meta = listOfNotNull(
                    hit.typeLabel.takeIf { it.isNotEmpty() && it != hit.badge.ifEmpty { hit.typeLabel } },
                    hit.year.takeIf { it.isNotEmpty() },
                    hit.areas.takeIf { it.isNotEmpty() },
                )
                if (meta.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        meta.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (hit.styles.isNotEmpty()) {
                    Text(
                        hit.styles,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (hit.indexShow.isNotEmpty()) {
                    Text(
                        hit.indexShow,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hit.score > 0) {
                        Text(
                            String.format(Locale.US, "%.1f", hit.score),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = scheme.primary,
                        )
                        if (hit.scoreUsers > 0) {
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(
                                    Fmt.count(hit.scoreUsers),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                                Text("人评分", style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = { open(0) },
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.heightIn(min = 36.dp),
                    ) {
                        Icon(PiliSymbols.Video, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(6.dp))
                        Text(hit.buttonText.ifEmpty { "观看" }, maxLines = 1)
                    }
                }
            }
        }
        if (hit.eps.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(hit.eps, key = { it.id }) { ep ->
                    SearchChip(ep.label, maxWidth = 180.dp) { open(ep.id) }
                }
            }
        }
    }
}

// ── 直播 ────────────────────────────────────────────────────────────────────

@Composable
internal fun LiveCard(hit: LiveHit, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(YoinContainerShapes.Card)
            .clickable { nav.push(Route.LiveRoom(hit.roomId, hit.cover, hit.title)) },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            NetImage(hit.cover, Modifier.matchParentSize(), shape = SearchCoverShape, thumbWidth = 360.dp)
            if (hit.living) {
                CoverBadge("直播中", Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(SearchCoverShape)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Stat(PiliSymbols.Live, Fmt.count(hit.online), tint = Color.White)
                Spacer(Modifier.weight(1f))
                if (hit.area.isNotEmpty()) {
                    Text(
                        hit.area,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)) {
            Text(
                hit.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(hit.uface, 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    hit.uname,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── 用户 ────────────────────────────────────────────────────────────────────

@Composable
internal fun UserRow(
    hit: UserHit,
    following: Boolean,
    showFollow: Boolean,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nav = LocalNavigator.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .clickable { nav.push(Route.Member(hit.mid, hit.face, hit.name)) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(hit.face, 52.dp)
            if (hit.verifyType >= 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(scheme.surface)
                        .padding(1.dp),
                ) {
                    Icon(
                        SearchGlyphs.Verified,
                        contentDescription = "认证",
                        tint = if (hit.verifyType == 0) scheme.tertiary else scheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.level > 0) {
                    Spacer(Modifier.width(6.dp))
                    LevelTag(hit.level)
                }
                if (hit.isLive) {
                    Spacer(Modifier.width(6.dp))
                    Tag("直播中", container = scheme.primaryContainer, content = scheme.onPrimaryContainer)
                }
            }
            if (hit.verifyDesc.isNotEmpty()) {
                Text(
                    hit.verifyDesc,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CountPair(hit.fans, "粉丝")
                CountPair(hit.videos, "视频")
            }
            if (hit.sign.isNotEmpty()) {
                Text(
                    hit.sign,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showFollow) {
            Spacer(Modifier.width(12.dp))
            FollowButton(following, onToggleFollow)
        }
    }
}

@Composable
private fun CountPair(n: Long, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            Fmt.count(n),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun LevelTag(level: Int) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when {
        level >= 6 -> scheme.primaryContainer to scheme.onPrimaryContainer
        level >= 4 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Tag("LV$level", container = container, content = content)
}

@Composable
private fun FollowButton(following: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ToggleButton(
        checked = following,
        onCheckedChange = { onToggle() },
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            checkedContainerColor = scheme.surfaceContainerHighest,
            checkedContentColor = scheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = Modifier.heightIn(min = 36.dp),
    ) {
        AnimatedContent(
            targetState = following,
            transitionSpec = fadeThrough(),
            label = "follow",
        ) { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (f) PiliSymbols.PersonCheck else PiliSymbols.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (f) "已关注" else "关注", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

// ── 专栏 ────────────────────────────────────────────────────────────────────

@Composable
internal fun ArticleRow(hit: ArticleHit, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .clickable { nav.push(Route.Read(hit.id, hit.title)) }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hit.cover.isNotEmpty()) {
            NetImage(
                hit.cover,
                Modifier.width(136.dp).aspectRatio(16f / 10f),
                shape = SearchCoverShape,
                thumbWidth = 160.dp,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 85.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                hit.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hit.author.isNotEmpty()) {
                        Text(
                            hit.author,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (hit.category.isNotEmpty()) {
                        Text(hit.category, style = MaterialTheme.typography.labelMedium, color = scheme.outline, maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stat(PiliSymbols.Views, Fmt.count(hit.views))
                    Stat(PiliSymbols.ThumbUp, Fmt.count(hit.likes))
                    Stat(PiliSymbols.Comment, Fmt.count(hit.replies))
                    if (hit.pubTime > 0) {
                        Text(
                            Fmt.relativeTime(hit.pubTime),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.outline,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
