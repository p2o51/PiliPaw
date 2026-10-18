package com.gpo.pilipala.feature.dynamics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asInt
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.Avatar
import com.gpo.pilipala.ui.components.PiliTopBar
import com.gpo.pilipala.ui.components.Tag
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.pilipala.ui.components.seam.seamText
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.pilipala.ui.theme.YoinMotion
import com.gpo.pilipala.ui.theme.YoinMotionRole
import com.gpo.pilipala.ui.theme.YoinMotionSpeed
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

// ── Model ───────────────────────────────────────────────────────────────────

/**
 * What a card already knows about its vote before the details load: the feed's
 * `module_dynamic.additional.vote` or an opus `link_card.card.vote`
 * (vote_id, desc, join_num, end_time, status, choice_cnt).
 */
@Immutable
internal data class VoteSummary(
    val voteId: Long,
    val title: String,
    val joinCount: Long,
    val endTime: Long,
    val status: Int,
    val choiceCount: Int,
)

/** [fallbackId]: the link card's `oid` when the vote object omits its id. */
internal fun voteSummaryOf(vote: JsonObject?, fallbackId: Long = 0L): VoteSummary? {
    val id = vote.long("vote_id").takeIf { it > 0 } ?: fallbackId
    if (id <= 0) return null
    return VoteSummary(
        voteId = id,
        title = vote.str("desc").ifEmpty { vote.str("title") },
        joinCount = vote.long("join_num"),
        endTime = vote.long("end_time"),
        status = vote.int("status"),
        choiceCount = vote.int("choice_cnt", 1).coerceAtLeast(1),
    )
}

@Immutable
internal data class VoteOption(val idx: Int, val desc: String, val count: Long, val image: String)

/** A vote's details (`/x/vote/vote_info`, or the legacy vc `vote_info`). */
@Immutable
internal data class VoteInfo(
    val id: Long,
    val title: String,
    val desc: String,
    /** How many options one voter may pick (1 = single choice). */
    val choiceCount: Int,
    val joinCount: Long,
    /** Unix seconds; 0 or far in the future (up to Long.MAX) for open-ended votes. */
    val endTime: Long,
    /** 1 open; 2 and above closed (4 = 已结束). */
    val status: Int,
    val options: List<VoteOption>,
    /** The viewer's own choices (option idx); empty when not voted or logged out. */
    val myVotes: List<Int>,
    val publisherMid: Long,
    val publisherName: String,
    val publisherFace: String,
) {
    val voted: Boolean get() = myVotes.isNotEmpty()
    val isImage: Boolean get() = options.any { it.image.isNotEmpty() }
    private val total: Long = options.sumOf { it.count }

    fun ended(nowSec: Long): Boolean = status >= 2 || endTime in 1 until nowSec

    /** Share of all picks, so a single-choice vote sums to 100%. */
    fun share(o: VoteOption): Float = if (total <= 0L) 0f else o.count.toFloat() / total

    /** The result when the vote went through but no fresh info came back. */
    fun withOwnVote(votes: List<Int>): VoteInfo = copy(
        myVotes = votes,
        joinCount = joinCount + 1,
        options = options.map { if (it.idx in votes) it.copy(count = it.count + 1) else it },
    )
}

/**
 * Both shapes: new `{vote_info: {opt_idx, opt_desc, img_url, cnt, join_num, end_time,
 * vote_publisher…}, my_votes}` (cnt omitted at zero) and legacy `{info: {idx, desc,
 * cnt, starttime, endtime, uid…}}`. Null when there is no vote (an unknown id comes
 * back as `vote_info: {status: 4}`).
 */
internal fun parseVoteInfo(data: JsonObject?): VoteInfo? {
    val v = data.obj("vote_info") ?: data.obj("info") ?: return null
    val id = v.long("vote_id")
    if (id <= 0) return null
    val options = v.arr("options").objects().mapIndexed { i, o ->
        VoteOption(
            idx = o.int("opt_idx", o.int("idx", i + 1)),
            desc = o.str("opt_desc").ifEmpty { o.str("desc") },
            count = o.long("cnt"),
            image = normalizeUrl(o.str("img_url")),
        )
    }
    val mine = data.arr("my_votes").ifEmpty { v.arr("my_votes") }.map { it.asInt() }.filter { it > 0 }
    return VoteInfo(
        id = id,
        title = v.str("title"),
        desc = v.str("desc"),
        choiceCount = v.int("choice_cnt", 1).coerceAtLeast(1),
        joinCount = v.long("join_num", v.long("cnt")),
        endTime = v.long("end_time", v.long("endtime")),
        status = v.int("status"),
        options = options,
        myVotes = mine,
        publisherMid = v.long("vote_publisher", v.long("uid")),
        publisherName = v.str("name"),
        publisherFace = v.str("face"),
    )
}

internal fun voteWebUrl(voteId: Long, dynamicId: String = ""): String =
    "https://t.bilibili.com/vote/h5/index/#/result?vote_id=$voteId" +
        (if (dynamicId.isNotEmpty()) "&dynamic_id=$dynamicId" else "") + "&isWeb=1"

// ── State ───────────────────────────────────────────────────────────────────

internal sealed interface VoteLoad {
    data object Loading : VoteLoad
    data class Failed(val message: String) : VoteLoad
    data class Ready(val info: VoteInfo) : VoteLoad
}

/** Results after the viewer's own vote, read by every page so list, detail and the vote page agree. */
private object VoteResults {
    val map = mutableStateMapOf<Long, VoteInfo>()
}

/**
 * Vote details for one page (nav entry). A card asks the first time it composes —
 * lazy lists compose only what is on screen — and the answer is cached per vote_id,
 * so scrolling back never refetches.
 */
internal class VoteViewModel : ViewModel() {
    private val loads = mutableStateMapOf<Long, VoteLoad>()
    private val sending = mutableStateMapOf<Long, Boolean>()

    fun state(id: Long): VoteLoad? = VoteResults.map[id]?.let { VoteLoad.Ready(it) } ?: loads[id]

    fun isSending(id: Long): Boolean = sending[id] == true

    fun ensure(id: Long) {
        if (loads[id] == null) fetch(id)
    }

    fun retry(id: Long) = fetch(id)

    private fun fetch(id: Long) {
        loads[id] = VoteLoad.Loading
        viewModelScope.launch {
            loads[id] = try {
                VoteLoad.Ready(DynamicsApi.voteInfo(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VoteLoad.Failed(e.friendlyMessage())
            }
        }
    }

    /**
     * Casts [votes] — only from the 投票 button's tap. Runs in the view model's scope
     * so a card scrolling away mid-request still lands the result.
     */
    fun vote(info: VoteInfo, votes: List<Int>, dynamicId: String, onDone: (Throwable?) -> Unit) {
        if (votes.isEmpty() || sending[info.id] == true) return
        sending[info.id] = true
        viewModelScope.launch {
            val error = try {
                val fresh = DynamicsApi.doVote(info.id, votes, dynamicId)
                    ?: runCatching { DynamicsApi.voteInfo(info.id) }.getOrNull()
                val result = when {
                    fresh == null -> info.withOwnVote(votes)
                    fresh.myVotes.isEmpty() -> fresh.copy(myVotes = votes)
                    else -> fresh
                }
                VoteResults.map[result.id] = result
                loads[result.id] = VoteLoad.Ready(result)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            } finally {
                sending.remove(info.id)
            }
            onDone(error)
        }
    }
}

// ── Card ────────────────────────────────────────────────────────────────────

private val BlockShape = ContinuousRoundedCornerShape(12.dp)
private val OptionShape = ContinuousRoundedCornerShape(10.dp)
private val TileShape = ContinuousRoundedCornerShape(12.dp)
private val ThumbShape = ContinuousRoundedCornerShape(8.dp)
private val HeaderShape = ContinuousRoundedCornerShape(8.dp)

/** The vote block of a dynamic (`additional.vote`) or an opus link card (`card.vote`). */
@Composable
internal fun VoteCard(
    vote: JsonObject,
    modifier: Modifier = Modifier,
    dynamicId: String = "",
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val summary = remember(vote) { voteSummaryOf(vote) } ?: return
    VoteCard(summary, modifier, dynamicId, color)
}

/**
 * A vote inside a card: title, the options (rows, or a grid of thumbnails for an
 * image vote), and once results show (voted, ended, or 结果 toggled) a bar per
 * option that springs to its share. Tapping the header opens the vote page.
 */
@Composable
internal fun VoteCard(
    summary: VoteSummary,
    modifier: Modifier = Modifier,
    dynamicId: String = "",
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val nav = LocalNavigator.current
    VoteBlock(
        voteId = summary.voteId,
        summary = summary,
        dynamicId = dynamicId,
        color = color,
        modifier = modifier,
        onOpen = { nav.push(Route.Vote(summary.voteId, dynamicId, summary.title)) },
    )
}

@Composable
private fun VoteBlock(
    voteId: Long,
    summary: VoteSummary?,
    dynamicId: String,
    color: Color,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    fallbackTitle: String = "",
    page: Boolean = false,
) {
    val vm: VoteViewModel = viewModel { VoteViewModel() }
    val nav = LocalNavigator.current
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    val viewer = rememberImageViewerState()
    LaunchedEffect(voteId) { vm.ensure(voteId) }

    val cs = MaterialTheme.colorScheme
    val load = vm.state(voteId)
    val info = (load as? VoteLoad.Ready)?.info
    val now = remember(load) { System.currentTimeMillis() / 1000 }
    var picked by remember(voteId) { mutableStateOf(emptyList<Int>()) }
    var peek by remember(voteId) { mutableStateOf(false) }

    val title = info?.title.orEmpty().ifEmpty { summary?.title.orEmpty() }.ifEmpty { fallbackTitle }
    val choiceCount = info?.choiceCount ?: summary?.choiceCount ?: 1
    val ended = info?.ended(now) ?: summary?.let { it.status >= 2 || it.endTime in 1 until now } ?: false
    val voted = info?.voted == true
    val canVote = info != null && !voted && !ended
    val showResults = info != null && (voted || ended || peek)
    val chosen = if (voted) info?.myVotes.orEmpty() else picked
    // Rows sit one step off the block, whichever container the block itself is.
    val rowBase = if (color == cs.surfaceContainerHighest) cs.surfaceContainerLow else cs.surfaceContainerHighest

    fun toggle(idx: Int) {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        picked = when {
            idx in picked -> picked - idx
            choiceCount <= 1 -> listOf(idx)
            picked.size >= choiceCount -> picked.drop(1) + idx
            else -> picked + idx
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(BlockShape)
            .background(color)
            .animateContentSize(YoinMotion.defaultSpatialSpec())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (page && info != null && info.publisherName.isNotEmpty()) {
            Publisher(info)
        }
        VoteHeader(
            title = title,
            desc = info?.desc.orEmpty(),
            multi = choiceCount > 1,
            large = page,
            onOpen = onOpen,
        )
        AnimatedContent(
            targetState = load,
            contentKey = { it?.let { l -> l::class } },
            transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard, YoinMotionSpeed.Fast) },
            label = "voteBody",
        ) { l ->
            when (l) {
                is VoteLoad.Ready -> {
                    val data = l.info
                    if (data.isImage) {
                        ImageOptions(
                            info = data,
                            chosen = chosen,
                            showResults = showResults,
                            enabled = canVote,
                            base = rowBase,
                            track = color,
                            onToggle = ::toggle,
                            onView = { i -> viewer.open(data.options.map { it.image }.filter { it.isNotEmpty() }, i) },
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            data.options.forEach { o ->
                                key(o.idx) {
                                    OptionRow(
                                        option = o,
                                        share = data.share(o),
                                        selected = o.idx in chosen,
                                        showResults = showResults,
                                        enabled = canVote,
                                        multi = choiceCount > 1,
                                        base = rowBase,
                                        onClick = { toggle(o.idx) },
                                    )
                                }
                            }
                        }
                    }
                }
                is VoteLoad.Failed -> Row(
                    Modifier.fillMaxWidth().seamText(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(YoinSymbols.Info, null, Modifier.size(16.dp), tint = cs.outline)
                    Text(
                        l.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.retry(voteId) }, shapes = ButtonDefaults.shapes()) { Text("重试") }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(2) {
                        Box(Modifier.fillMaxWidth().height(44.dp).clip(OptionShape).background(rowBase))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = canVote,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.defaultSpatialSpec()),
        ) {
            VoteActions(
                picked = picked.size,
                choiceCount = choiceCount,
                peek = peek,
                sending = vm.isSending(voteId),
                onPeek = { peek = it },
                onVote = {
                    val current = info ?: return@VoteActions
                    if (!BiliClient.isLoggedIn) {
                        nav.push(Route.Login)
                        return@VoteActions
                    }
                    vm.vote(current, picked, dynamicId) { error ->
                        if (error == null) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        } else {
                            toast(error.friendlyMessage())
                        }
                    }
                },
            )
        }
        VoteFooter(
            joinCount = info?.joinCount ?: summary?.joinCount ?: 0L,
            endTime = info?.endTime ?: summary?.endTime ?: 0L,
            ended = ended,
            now = now,
        )
    }
    ImageViewerHost(viewer)
}

@Composable
private fun Publisher(info: VoteInfo) {
    val nav = LocalNavigator.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(
            info.publisherFace,
            32.dp,
            onClick = if (info.publisherMid > 0) {
                { nav.push(Route.Member(info.publisherMid, info.publisherFace, info.publisherName)) }
            } else {
                null
            },
        )
        Text(
            info.publisherName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.seamText(),
        )
    }
}

@Composable
private fun VoteHeader(title: String, desc: String, multi: Boolean, large: Boolean, onOpen: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clip(HeaderShape).clickable(onClick = onOpen) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(ContinuousRoundedCornerShape(10.dp)).background(cs.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PollGlyph, null, Modifier.size(20.dp), tint = cs.onSecondaryContainer)
        }
        Column(Modifier.weight(1f).seamText(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title.ifEmpty { "投票" },
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (large) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (desc.isNotEmpty() && desc != title) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = if (large) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (multi) Tag("多选")
        if (onOpen != null) {
            Icon(YoinSymbols.ChevronRight, null, Modifier.size(18.dp).seamText(), tint = cs.onSurfaceVariant)
        }
    }
}

/**
 * One text option. Its fill is a single bar: the whole row while picked, the
 * option's share once results show — growing in (and re-proportioning after a
 * vote) on the spatial spring.
 */
@Composable
private fun OptionRow(
    option: VoteOption,
    share: Float,
    selected: Boolean,
    showResults: Boolean,
    enabled: Boolean,
    multi: Boolean,
    base: Color,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val target = when {
        showResults -> share
        selected -> 1f
        else -> 0f
    }
    val fill = remember { Animatable(0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(target) { fill.animateTo(target, spatial) }
    val fillColor by animateColorAsState(
        if (selected) cs.primaryContainer else cs.secondaryContainer,
        YoinMotion.defaultEffectsSpec(),
        label = "voteFill",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(OptionShape)
            .background(base)
            .drawBehind { drawBar(fill.value, fillColor) }
            .then(
                if (enabled) {
                    Modifier.clickable(role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.4f) + fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.4f) + fadeOut(YoinMotion.fastEffectsSpec()),
        ) {
            Icon(YoinSymbols.Check, null, Modifier.padding(end = 8.dp).size(18.dp).seamText(), tint = cs.primary)
        }
        Text(
            option.desc,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            modifier = Modifier.weight(1f).seamText(),
        )
        AnimatedVisibility(
            visible = showResults,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()),
            exit = fadeOut(YoinMotion.fastEffectsSpec()),
        ) {
            Percent(share, Modifier.padding(start = 8.dp))
        }
    }
}

/** Fill from the start edge, [fraction] of the width (a spring may overshoot; clamp). */
private fun DrawScope.drawBar(fraction: Float, color: Color) {
    val f = fraction.coerceIn(0f, 1f)
    if (f <= 0f) return
    val w = size.width * f
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
    drawRect(color, topLeft = Offset(left, 0f), size = Size(w, size.height))
}

/** Bold number, quiet sign. */
@Composable
private fun Percent(share: Float, modifier: Modifier = Modifier) {
    Row(modifier.seamText()) {
        Text(
            "${(share * 100).roundToInt()}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            "%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** Image vote: a grid of rounded thumbnails, the caption and (with results) a thin share bar under each. */
@Composable
private fun ImageOptions(
    info: VoteInfo,
    chosen: List<Int>,
    showResults: Boolean,
    enabled: Boolean,
    base: Color,
    track: Color,
    onToggle: (Int) -> Unit,
    onView: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val cols = ((maxWidth + gap) / (112.dp + gap)).toInt().coerceIn(2, 5)
        val withImage = info.options.filter { it.image.isNotEmpty() }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            info.options.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { o ->
                        key(o.idx) {
                            ImageTile(
                                option = o,
                                share = info.share(o),
                                selected = o.idx in chosen,
                                showResults = showResults,
                                enabled = enabled,
                                base = base,
                                track = track,
                                onClick = { onToggle(o.idx) },
                                onView = { withImage.indexOf(o).takeIf { it >= 0 }?.let(onView) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ImageTile(
    option: VoteOption,
    share: Float,
    selected: Boolean,
    showResults: Boolean,
    enabled: Boolean,
    base: Color,
    track: Color,
    onClick: () -> Unit,
    onView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        if (selected) cs.primaryContainer else base,
        YoinMotion.defaultEffectsSpec(),
        label = "voteTile",
    )
    val fill = remember { Animatable(0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(showResults, share) { fill.animateTo(if (showResults) share else 0f, spatial) }
    val barColor = if (selected) cs.primary else cs.secondary
    Column(
        modifier
            .clip(TileShape)
            .drawBehind { drawRect(bg) }
            .combinedClickable(
                role = if (enabled) Role.Checkbox else Role.Image,
                onLongClick = onView,
                onClick = if (enabled) onClick else onView,
            )
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            com.gpo.pilipala.ui.components.NetImage(
                option.image,
                Modifier.matchParentSize(),
                contentDescription = option.desc,
                shape = ThumbShape,
                thumbWidth = 240.dp,
            )
            // Inside a Box in a Column: the scope-free overload, not ColumnScope's.
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                enter = scaleIn(YoinMotion.fastSpatialSpec(), initialScale = 0.4f) + fadeIn(YoinMotion.defaultEffectsSpec()),
                exit = scaleOut(YoinMotion.fastSpatialSpec(), targetScale = 0.4f) + fadeOut(YoinMotion.fastEffectsSpec()),
            ) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(YoinSymbols.Check, null, Modifier.size(16.dp), tint = cs.onPrimary)
                }
            }
        }
        Text(
            option.desc,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) cs.onPrimaryContainer else cs.onSurface,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp).seamText(),
        )
        AnimatedVisibility(
            visible = showResults,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.defaultSpatialSpec()),
        ) {
            Row(
                Modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(track)
                        .drawBehind { drawBar(fill.value, barColor) },
                )
                Percent(share)
            }
        }
    }
}

@Composable
private fun VoteActions(
    picked: Int,
    choiceCount: Int,
    peek: Boolean,
    sending: Boolean,
    onPeek: (Boolean) -> Unit,
    onVote: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().seamText(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (choiceCount > 1) {
            AnimatedContent(
                targetState = picked,
                transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard, YoinMotionSpeed.Fast) },
                label = "votePicked",
            ) { n ->
                Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.onSurface)
            }
            Text("/$choiceCount", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            ToggleButton(
                checked = peek,
                onCheckedChange = onPeek,
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = cs.surfaceContainerHighest,
                    contentColor = cs.onSurfaceVariant,
                    checkedContainerColor = cs.secondaryContainer,
                    checkedContentColor = cs.onSecondaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text("结果", style = MaterialTheme.typography.labelLarge)
            }
            val trailing = ButtonGroupDefaults.connectedTrailingButtonShapes()
            Button(
                onClick = onVote,
                enabled = picked > 0 && !sending,
                shapes = ButtonDefaults.shapes(trailing.shape, trailing.pressedShape),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                AnimatedContent(
                    targetState = sending,
                    transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard, YoinMotionSpeed.Fast) },
                    contentAlignment = Alignment.Center,
                    label = "voteSending",
                ) { busy ->
                    if (busy) {
                        LoadingIndicator(Modifier.size(20.dp), color = cs.onSurfaceVariant)
                    } else {
                        Text("投票", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** Participants (bold number, quiet 人参与), then the time left or 已结束. */
@Composable
private fun VoteFooter(joinCount: Long, endTime: Long, ended: Boolean, now: Long) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().seamText()) {
        Text(
            Fmt.count(joinCount),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
            modifier = Modifier.alignByBaseline(),
        )
        Spacer(Modifier.width(2.dp))
        Text("人参与", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.weight(1f))
        val left = if (ended) null else timeLeft(endTime, now)
        when {
            ended -> Text("已结束", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.alignByBaseline())
            left != null -> {
                Text(
                    left.first,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(2.dp))
                Text(left.second, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

/** (number, unit) until [endTime]; null when open-ended (no end, or more than a year away). */
private fun timeLeft(endTime: Long, now: Long): Pair<String, String>? {
    if (endTime <= 0) return null
    val left = endTime - now
    if (left <= 0 || left > 365L * 86_400) return null
    return when {
        left >= 86_400 -> "${left / 86_400}" to "天后截止"
        left >= 3_600 -> "${left / 3_600}" to "小时后截止"
        else -> "${(left / 60).coerceAtLeast(1)}" to "分钟后截止"
    }
}

/** Three option bars of different lengths, in the symbols' 24 / 1.5 stroke idiom. */
private val PollGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "PollGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        listOf("M5.25 7 L18.75 7", "M5.25 12 L12.75 12", "M5.25 17 L15.75 17").forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}

// ── Page ────────────────────────────────────────────────────────────────────

/** Native vote page: t.bilibili.com/vote links and the header of a vote card land here. */
@Composable
fun VoteScreen(route: Route.Vote) {
    val nav = LocalNavigator.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Scaffold(
        topBar = {
            PiliTopBar(
                "投票",
                actions = {
                    IconButton(
                        onClick = { nav.push(Route.Web(voteWebUrl(route.voteId, route.dynamicId), "投票")) },
                        shapes = IconButtonDefaults.shapes(),
                    ) { Icon(YoinSymbols.Launch, contentDescription = "网页打开") }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = navBottom + 24.dp),
        ) {
            item(key = "vote") {
                VoteBlock(
                    voteId = route.voteId,
                    summary = null,
                    dynamicId = route.dynamicId,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.readingWidth(720.dp).padding(horizontal = 16.dp),
                    fallbackTitle = route.title,
                    page = true,
                )
            }
        }
    }
}
