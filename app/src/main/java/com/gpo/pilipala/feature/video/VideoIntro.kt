package com.gpo.pilipala.feature.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.str
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.FameCount
import com.gpo.pilipala.ui.components.FameMode
import com.gpo.pilipala.ui.components.Stat
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.pilipala.ui.components.inlineIconSize
import com.gpo.pilipala.ui.components.centered

/**
 * The video is the signed-in user's own (uploader, or one of the staff): under
 * 只隐藏自己的 its 播放 / 弹幕 / 点赞 / 投币 / 收藏 / 分享 counts are hidden. Snapshot-read.
 */
internal val VideoDetail.isFameSelf: Boolean
    get() = FameMode.isSelf(ownerMid) || staff.any { FameMode.isSelf(it.mid) }

/**
 * Title (folds to two lines, unfolds with the spatial spring), stat pairs,
 * honor tags, and the description block that unfolds together with the title.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun VideoHeader(d: VideoDetail, onSeek: ((Long) -> Unit)?, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(d.bvid) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val toast = rememberToast()
    val haptic = LocalHapticFeedback.current
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .animateContentSize(YoinMotion.defaultSpatialSpec())
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        clipboard.setText(AnnotatedString(d.title))
                        toast("已复制标题")
                    },
                    onClick = { expanded = !expanded },
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                d.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            val rotation by animateFloatAsState(if (expanded) 180f else 0f, YoinMotion.defaultSpatialSpec(), label = "titleChevron")
            Icon(
                YoinSymbols.ChevronDown,
                null,
                Modifier.padding(top = 4.dp).size(20.dp).graphicsLayer { rotationZ = rotation },
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val self = d.isFameSelf
            Stat(YoinSymbols.Views, Fmt.count(d.view), revealable = true, self = self)
            Stat(YoinSymbols.Danmaku, Fmt.count(d.danmaku), revealable = true, self = self)
            Text(
                Fmt.date(d.pubdate, "yyyy-MM-dd HH:mm"),
                style = MaterialTheme.typography.labelSmall.centered(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 在看: how many are watching now, as Bilibili counts it ("1000+").
            val watching by produceState("", d.bvid, d.cid) { value = OnlineApi.total(d.bvid, d.cid) }
            if (watching.isNotEmpty()) {
                Text(
                    "$watching 人在看",
                    style = MaterialTheme.typography.labelSmall.centered(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AiSummaryRow(d, onSeek, Modifier.padding(top = 12.dp))
        if (d.honor.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                d.honor.forEach { h ->
                    Row(
                        Modifier
                            .clip(ContinuousRoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val honorStyle = MaterialTheme.typography.labelSmall.centered()
                        Icon(YoinSymbols.Trophy, null, Modifier.size(honorStyle.inlineIconSize()), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(4.dp))
                        Text(h, style = honorStyle, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(YoinMotion.defaultSpatialSpec()) + fadeIn(effects),
            exit = shrinkVertically(YoinMotion.defaultSpatialSpec()) + fadeOut(effects),
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        d.bvid,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        // 48dp touch target around the glyph-sized label (the look stays the same).
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(ContinuousRoundedCornerShape(6.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(d.bvid))
                                toast("已复制")
                            }
                            .padding(horizontal = 2.dp),
                    )
                    if (d.tname.isNotEmpty()) {
                        Text(d.tname, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (d.copyright == 2) {
                        Text("转载", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val hasDesc = d.desc.isNotBlank() && d.desc != "-"
        if (hasDesc) {
            Spacer(Modifier.height(12.dp))
            val rich = rememberRichText(d.descNodes, d.desc, MaterialTheme.colorScheme.primary, onSeek)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(YoinContainerShapes.Card)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                ExpandableText(
                    text = rich,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    collapsedLines = 3,
                    expandedState = expanded,
                    onToggle = { expanded = !expanded },
                )
            }
        }
    }
}

/**
 * UP card: avatar, name, fans, follow toggle. Under 名声模式 the fans count is a line
 * that stays a line: the whole card opens the space.
 */
@Composable
internal fun UpCard(
    mid: Long,
    name: String,
    face: String,
    fans: Long,
    following: Boolean,
    busy: Boolean,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
    mutual: Boolean = false,
) {
    val nav = LocalNavigator.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(YoinContainerShapes.ListRow)
            .clickable { nav.push(Route.Member(mid, face, name)) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(face, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val effects = YoinMotion.defaultEffectsSpec<Float>()
            val size = YoinMotion.defaultSpatialSpec<IntSize>()
            val self = FameMode.isSelf(mid)
            val fame = FameMode.hides(self)
            AnimatedContent(
                targetState = fans,
                transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) using SizeTransform { _, _ -> size } },
                // The fading content shows its own count; under 名声模式 a moved count is the same line, so it stays put.
                contentKey = { f -> if (f < 0) -1L else if (fame) 0L else f },
                label = "fans",
            ) { f ->
                if (f >= 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FameCount(
                            Fmt.count(f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            revealable = false,
                            self = self,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text("粉丝", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        FollowToggle(following, busy, onFollow, mutual = mutual)
    }
}

/**
 * 关注 / 已关注, or 互相关注 when the UP follows back ([mutual]). [followLabel] /
 * [followingLabel] rename it for other sources (YouTube's 订阅 / 已订阅).
 */
@Composable
internal fun FollowToggle(
    following: Boolean,
    busy: Boolean,
    onFollow: () -> Unit,
    mutual: Boolean = false,
    followLabel: String = "关注",
    followingLabel: String = "已关注",
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (following) colors.surfaceContainerHighest else colors.primary,
        YoinMotion.defaultEffectsSpec(),
        label = "followContainer",
    )
    val content by animateColorAsState(
        if (following) colors.onSurfaceVariant else colors.onPrimary,
        YoinMotion.defaultEffectsSpec(),
        label = "followContent",
    )
    ToggleButton(
        checked = following,
        onCheckedChange = {
            if (!busy) {
                haptic.performHapticFeedback(if (following) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onFollow()
            }
        },
        enabled = enabled,
        shapes = ToggleButtonDefaults.shapes(),
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = container,
            contentColor = content,
            checkedContainerColor = container,
            checkedContentColor = content,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        val effects = YoinMotion.defaultEffectsSpec<Float>()
        val label = when {
            following && mutual -> "互相关注"
            following -> followingLabel
            else -> followLabel
        }
        AnimatedContent(
            label,
            transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) },
            label = "follow",
        ) { text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (text != followLabel) YoinSymbols.PersonCheck else YoinSymbols.PersonAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 联合投稿: horizontally scrolling staff avatars with their roles. */
@Composable
internal fun StaffRow(staff: List<Staff>, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(staff, key = { it.mid }) { s ->
            Column(
                Modifier
                    .width(64.dp)
                    .clip(YoinContainerShapes.ListRow)
                    .clickable { nav.push(Route.Member(s.mid, s.face, s.name)) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(s.face, 44.dp)
                Spacer(Modifier.height(4.dp))
                Text(s.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Tags as tonal chips; tapping searches the tag. */
@Composable
internal fun TagChips(tags: List<String>, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tags, key = { it }) { t ->
            Text(
                t,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(ContinuousRoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { nav.push(Route.SearchResult(t)) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Section title used between intro blocks. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 在看 for a video (/x/player/online/total); empty when unknown. */
internal object OnlineApi {
    suspend fun total(bvid: String, cid: Long): String {
        if (bvid.isBlank() || cid <= 0) return ""
        return try {
            com.gpo.pilipala.core.net.BiliClient.getData("/x/player/online/total", mapOf("bvid" to bvid, "cid" to cid))
                .asObj.str("total").takeIf { it.isNotBlank() && it != "0" && it != "1" }.orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            ""
        }
    }
}
