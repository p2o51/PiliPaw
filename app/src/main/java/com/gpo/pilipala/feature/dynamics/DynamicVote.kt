package com.gpo.pilipala.feature.dynamics

import com.gpo.pilipala.core.util.ScopeErrors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asInt
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.media.LocalImageViewer
import com.gpo.pilipala.feature.media.ViewerImage
import com.gpo.pilipala.feature.media.imageViewerOrigin
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.floor
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

/** A vote that only shows as an inline `RICH_TEXT_NODE_TYPE_VOTE` node (desc or opus summary). */
internal fun inlineVoteSummary(item: JsonObject): VoteSummary? {
    val node = richSource(item).second?.objects()?.firstOrNull { it.str("type") == "RICH_TEXT_NODE_TYPE_VOTE" } ?: return null
    val id = node.long("rid").takeIf { it > 0 } ?: return null
    return VoteSummary(id, title = node.str("text"), joinCount = 0L, endTime = 0L, status = 0, choiceCount = 1)
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
    val hasVotes: Boolean get() = joinCount > 0 || options.any { it.count > 0 }

    /**
     * Single choice: all picks (one per voter). Multi choice: the participants
     * (join_num), so an option reads as how many voters picked it.
     */
    private val denominator: Long = options.sumOf { it.count }.let { picks ->
        if (choiceCount > 1 && joinCount > 0) joinCount else picks
    }
    private val percents: Map<Int, Int> = wholePercents(options, single = choiceCount <= 1, denominator)

    fun ended(nowSec: Long): Boolean = status >= 2 || endTime in 1 until nowSec

    /** Bar length, capped at 1 (a multi-choice count can run ahead of join_num). */
    fun share(o: VoteOption): Float = if (denominator <= 0L) 0f else (o.count.toFloat() / denominator).coerceIn(0f, 1f)

    /** Whole percentage; a single-choice vote's add up to exactly 100. */
    fun percent(o: VoteOption): Int = percents[o.idx] ?: 0

    /** The result when the vote went through but no fresh info came back. */
    fun withOwnVote(votes: List<Int>): VoteInfo = copy(
        myVotes = votes,
        joinCount = joinCount + 1,
        options = options.map { if (it.idx in votes) it.copy(count = it.count + 1) else it },
    )
}

/**
 * Whole percentages by option idx. Multi choice: each rounded on its own (they
 * overlap, so no total to keep). Single choice: largest remainder — floor every
 * share, then hand the points still missing from 100 to the biggest remainders.
 */
private fun wholePercents(options: List<VoteOption>, single: Boolean, denominator: Long): Map<Int, Int> {
    if (denominator <= 0L) return emptyMap()
    if (!single) return options.associate { it.idx to (it.count * 100.0 / denominator).roundToInt().coerceIn(0, 100) }
    val exact = options.map { it.count * 100.0 / denominator }
    val whole = IntArray(options.size) { floor(exact[it]).toInt() }
    var missing = 100 - whole.sum()
    for (i in exact.indices.sortedByDescending { exact[it] - whole[it] }) {
        if (missing <= 0) break
        if (exact[i] > whole[i]) {
            whole[i]++
            missing--
        }
    }
    return options.indices.associate { options[it].idx to whole[it] }
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

/** One vote as one account sees it (`my_votes` differs per account; 0 = guest). */
internal data class VoteKey(val mid: Long, val voteId: Long)

/** The account requests go out as: the cookie's mid, 0 for a guest. */
private fun voterMid(): Long = if (BiliClient.isLoggedIn) BiliClient.mid else 0L

/**
 * Vote state every page shares, per account:
 * - [results]: the latest info after the viewer's own vote, so list, detail and the vote page agree;
 * - [pending]: votes in flight, so one vote is never sent twice from two screens;
 * - [generation]: bumped by a feed refresh, so cards on screen fetch their counts again.
 */
internal object VoteStore {
    val results = mutableStateMapOf<VoteKey, VoteInfo>()
    val pending = mutableStateMapOf<VoteKey, Boolean>()
    var generation by mutableIntStateOf(0)
        private set

    /** Sends outlive the page that started them, so leaving it still lands the result and frees [pending]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + ScopeErrors)

    private var account = Long.MIN_VALUE

    fun isPending(key: VoteKey): Boolean = pending[key] == true

    /** A feed pull-to-refresh: cards fetch again as they show (quietly, keeping what they show). */
    fun invalidate() {
        generation++
    }

    /** Signed in, out or switched: forget what belonged to other accounts. */
    fun onAccount(mid: Long) {
        if (mid == account) return
        account = mid
        results.keys.filter { it.mid != mid }.forEach { results.remove(it) }
    }

    /**
     * Fresh info from a fetch. Once the viewer has voted here, the server's counts
     * replace the stored ones; its `my_votes` too, unless it lags behind the vote.
     */
    fun offer(key: VoteKey, fresh: VoteInfo) {
        val stored = results[key] ?: return
        results[key] = if (fresh.voted) fresh else fresh.copy(myVotes = stored.myVotes)
    }
}

/**
 * Vote details for one page (nav entry). A card asks the first time it composes —
 * lazy lists compose only what is on screen — and the answer is cached per
 * (account, vote), so scrolling back never refetches; an account change or a feed
 * refresh does. Picks and the 结果 peek live here too, so they survive a card
 * scrolling out and back.
 */
internal class VoteViewModel : ViewModel() {
    private val loads = mutableStateMapOf<VoteKey, VoteLoad>()

    /** The [VoteStore.generation] each load was asked in. */
    private val askedIn = HashMap<VoteKey, Int>()
    private val picks = mutableStateMapOf<Long, List<Int>>()
    private val peeks = mutableStateMapOf<Long, Boolean>()

    init {
        viewModelScope.launch {
            Account.user.collect {
                val mid = voterMid()
                VoteStore.onAccount(mid)
                loads.keys.filter { it.mid != mid }.forEach {
                    loads.remove(it)
                    askedIn.remove(it)
                }
            }
        }
    }

    fun state(key: VoteKey): VoteLoad? = VoteStore.results[key]?.let { VoteLoad.Ready(it) } ?: loads[key]

    fun picks(voteId: Long): List<Int> = picks[voteId].orEmpty()

    fun setPicks(voteId: Long, value: List<Int>) {
        picks[voteId] = value
    }

    fun peek(voteId: Long): Boolean = peeks[voteId] == true

    fun setPeek(voteId: Long, on: Boolean) {
        peeks[voteId] = on
    }

    /** First sight of a vote, or a feed refresh since it was fetched. */
    fun ensure(key: VoteKey, generation: Int) {
        val current = loads[key]
        when {
            current == null -> fetch(key, generation, quiet = false)
            current is VoteLoad.Loading -> Unit
            (askedIn[key] ?: generation) < generation -> fetch(key, generation, quiet = current is VoteLoad.Ready)
        }
    }

    fun retry(key: VoteKey) = fetch(key, VoteStore.generation, quiet = false)

    /** [quiet]: a refresh behind counts already shown — no skeleton, and a failure keeps them. */
    private fun fetch(key: VoteKey, generation: Int, quiet: Boolean) {
        askedIn[key] = generation
        if (!quiet) loads[key] = VoteLoad.Loading
        viewModelScope.launch {
            val result = try {
                VoteLoad.Ready(DynamicsApi.voteInfo(key.voteId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VoteLoad.Failed(e.friendlyMessage())
            }
            // Asked as another account: that answer's my_votes is not this viewer's.
            if (voterMid() != key.mid) return@launch
            if (result is VoteLoad.Ready) VoteStore.offer(key, result.info)
            if (result is VoteLoad.Ready || loads[key] !is VoteLoad.Ready) loads[key] = result
        }
    }

    /**
     * Casts [votes] — only from the 投票 button's tap. At most one send per
     * (account, vote) at a time across every page ([VoteStore.pending]).
     */
    fun vote(key: VoteKey, info: VoteInfo, votes: List<Int>, dynamicId: String, onDone: (Throwable?) -> Unit) {
        if (votes.isEmpty() || key.mid <= 0 || VoteStore.isPending(key)) return
        VoteStore.pending[key] = true
        VoteStore.scope.launch {
            val error = try {
                val fresh = DynamicsApi.doVote(info.id, votes, dynamicId)
                    ?: runCatching { DynamicsApi.voteInfo(info.id) }.getOrNull()
                val result = when {
                    fresh == null -> info.withOwnVote(votes)
                    fresh.myVotes.isEmpty() -> fresh.copy(myVotes = votes)
                    else -> fresh
                }
                VoteStore.results[key] = result
                loads[key] = VoteLoad.Ready(result)
                picks.remove(key.voteId)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            } finally {
                VoteStore.pending.remove(key)
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

/** A feed card shows this many text options (or image tiles); the rest wait on the vote page. */
private const val CardTextOptions = 4
private const val CardImageOptions = 6

/** Option rows and tiles: a full touch target. */
private val OptionMinHeight = 48.dp

/** The vote of a dynamic: its `additional.vote`, else a vote that only shows as an inline node. */
@Composable
internal fun VoteForItem(item: JsonObject, color: Color) {
    val summary = remember(item) {
        voteSummaryOf(item.dynModule.obj("additional").obj("vote")) ?: inlineVoteSummary(item)
    } ?: return
    VoteCard(summary, dynamicId = item.dynId, color = color)
}

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
 * A vote inside a card: title, the first options (rows, or a grid of thumbnails
 * for an image vote) with a 全部 N 项 row when there are more, and once results
 * show (voted, ended, or 结果 toggled) a bar per option that springs to its share.
 * Tapping the header or 全部 N 项 opens the vote page.
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
private fun revealIn(): EnterTransition =
    fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec())

@Composable
private fun revealOut(): ExitTransition =
    fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.defaultSpatialSpec())

/** The last non-null [value], so content leaving through an exit transition keeps what it showed. */
@Composable
private fun <T : Any> latest(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
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
    // Read the account so signing in (coming back from 登录), out or switching recomposes;
    // the cache key follows the cookie's mid, which a change of key refetches.
    val user by Account.user.collectAsState()
    val mid = if (user != null || BiliClient.isLoggedIn) voterMid() else 0L
    val key = VoteKey(mid, voteId)
    val generation = VoteStore.generation
    LaunchedEffect(key, generation) { vm.ensure(key, generation) }

    val cs = MaterialTheme.colorScheme
    val load = vm.state(key)
    val info = (load as? VoteLoad.Ready)?.info
    val now = remember(load) { System.currentTimeMillis() / 1000 }
    val picked = vm.picks(voteId)
    val peek = vm.peek(voteId)
    val sending = VoteStore.isPending(key)
    var rejects by remember(voteId) { mutableIntStateOf(0) }

    val title = info?.title.orEmpty().ifEmpty { summary?.title.orEmpty() }.ifEmpty { fallbackTitle }
    val choiceCount = info?.choiceCount ?: summary?.choiceCount ?: 1
    val ended = info?.ended(now) ?: summary?.let { it.status >= 2 || it.endTime in 1 until now } ?: false
    val voted = info?.voted == true
    val canVote = info != null && !voted && !ended
    val showResults = info != null && (voted || ended || peek)
    val chosen = if (voted) info?.myVotes.orEmpty() else picked
    // Rows sit one step off the block, whichever container the block itself is.
    val rowBase = if (color == cs.surfaceContainerHighest) cs.surfaceContainerLow else cs.surfaceContainerHighest

    // Past the limit a tap is refused (never trading away an earlier pick): a reject
    // haptic and the n/N counter pulses.
    fun toggle(idx: Int) {
        val current = vm.picks(voteId)
        val next = when {
            idx in current -> current - idx
            choiceCount <= 1 -> listOf(idx)
            current.size >= choiceCount -> null
            else -> current + idx
        }
        if (next == null) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            rejects++
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            vm.setPicks(voteId, next)
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
        val publisher = latest(info?.takeIf { page && it.publisherName.isNotEmpty() })
        AnimatedVisibility(
            visible = page && info?.publisherName?.isNotEmpty() == true,
            enter = revealIn(),
            exit = revealOut(),
        ) {
            publisher?.let { Publisher(it) }
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
                    val limit = when {
                        page -> Int.MAX_VALUE
                        data.isImage -> CardImageOptions
                        else -> CardTextOptions
                    }
                    val shown = data.options.take(limit)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (data.isImage) {
                            ImageOptions(
                                info = data,
                                options = shown,
                                compact = !page,
                                chosen = chosen,
                                showResults = showResults,
                                enabled = canVote && !sending,
                                base = rowBase,
                                track = color,
                                onToggle = ::toggle,
                            )
                        } else {
                            shown.forEach { o ->
                                key(o.idx) {
                                    OptionRow(
                                        option = o,
                                        share = data.share(o),
                                        percent = data.percent(o),
                                        selected = o.idx in chosen,
                                        showResults = showResults,
                                        enabled = canVote && !sending,
                                        multi = choiceCount > 1,
                                        base = rowBase,
                                        onClick = { toggle(o.idx) },
                                    )
                                }
                            }
                        }
                        if (shown.size < data.options.size && onOpen != null) {
                            AllOptionsRow(count = data.options.size, base = rowBase, onClick = onOpen)
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
                    TextButton(onClick = { vm.retry(key) }, shapes = ButtonDefaults.shapes()) { Text("重试") }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(2) {
                        Box(Modifier.fillMaxWidth().height(OptionMinHeight).clip(OptionShape).background(rowBase))
                    }
                }
            }
        }
        AnimatedVisibility(visible = canVote, enter = revealIn(), exit = revealOut()) {
            VoteActions(
                picked = picked.size,
                choiceCount = choiceCount,
                rejects = rejects,
                peek = peek,
                showPeek = info?.hasVotes == true,
                sending = sending,
                loggedIn = mid > 0,
                base = rowBase,
                onPeek = { vm.setPeek(voteId, it) },
                onVote = {
                    val current = info ?: return@VoteActions
                    if (mid <= 0) {
                        nav.push(Route.Login)
                        return@VoteActions
                    }
                    vm.vote(key, current, vm.picks(voteId), dynamicId) { error ->
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
            // A card that only knew the vote id shows 投票 until the title loads.
            AnimatedContent(
                targetState = title.ifEmpty { "投票" },
                transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard, YoinMotionSpeed.Fast) },
                label = "voteTitle",
            ) { t ->
                Text(
                    t,
                    style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (large) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val shownDesc = desc.takeIf { it.isNotEmpty() && it != title }
            val lastDesc = latest(shownDesc)
            AnimatedVisibility(visible = shownDesc != null, enter = revealIn(), exit = revealOut()) {
                Text(
                    lastDesc.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = if (large) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(
            visible = multi,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandHorizontally(YoinMotion.defaultSpatialSpec()),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkHorizontally(YoinMotion.defaultSpatialSpec()),
        ) {
            Tag("多选")
        }
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
    percent: Int,
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
            .heightIn(min = OptionMinHeight)
            .clip(OptionShape)
            .background(base)
            .drawBehind { drawBar(fill.value, fillColor) }
            // selectable / toggleable: TalkBack reads the picked state, not just the role.
            .then(
                if (multi) {
                    Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
                } else {
                    Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
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
            Percent(percent, Modifier.padding(start = 8.dp))
        }
    }
}

/** 全部 N 项: the options a feed card leaves out, on the vote page. */
@Composable
private fun AllOptionsRow(count: Int, base: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = OptionMinHeight)
            .clip(OptionShape)
            .background(base)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .seamText(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("全部", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(4.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
            modifier = Modifier.alignByBaseline(),
        )
        Spacer(Modifier.width(4.dp))
        Text("项", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.weight(1f))
        Icon(YoinSymbols.ChevronRight, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
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
private fun Percent(value: Int, modifier: Modifier = Modifier) {
    Row(modifier.seamText()) {
        Text(
            "$value",
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

/**
 * Image vote: a grid of rounded thumbnails, the caption and (with results) a thin
 * share bar under each. [compact] (a feed card): small tiles, one row of six or
 * rows of three; the vote page: larger tiles, two to five across.
 */
@Composable
private fun ImageOptions(
    info: VoteInfo,
    options: List<VoteOption>,
    compact: Boolean,
    chosen: List<Int>,
    showResults: Boolean,
    enabled: Boolean,
    base: Color,
    track: Color,
    onToggle: (Int) -> Unit,
) {
    val viewer = LocalImageViewer.current
    // Every picture of the vote pages in the viewer (a card shows only the first few); each tile is its origin.
    val withImage = remember(info) { info.options.filter { it.image.isNotEmpty() } }
    val images = remember(withImage) { withImage.map { ViewerImage(it.image) } }
    val keys = remember(info.id, withImage) { withImage.map { "vote:${info.id}:${it.idx}" } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val cols = if (compact) {
            if (((maxWidth + gap) / (80.dp + gap)).toInt() >= 6) 6 else 3
        } else {
            ((maxWidth + gap) / (112.dp + gap)).toInt().coerceIn(2, 5)
        }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            options.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { o ->
                        key(o.idx) {
                            ImageTile(
                                option = o,
                                share = info.share(o),
                                percent = info.percent(o),
                                selected = o.idx in chosen,
                                showResults = showResults,
                                enabled = enabled,
                                base = base,
                                track = track,
                                onClick = { onToggle(o.idx) },
                                originKey = "vote:${info.id}:${o.idx}",
                                onView = { withImage.indexOf(o).takeIf { it >= 0 }?.let { viewer.open(images, it, keys) } },
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
    percent: Int,
    selected: Boolean,
    showResults: Boolean,
    enabled: Boolean,
    base: Color,
    track: Color,
    onClick: () -> Unit,
    originKey: String,
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
            .semantics { this.selected = selected }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            com.gpo.pilipala.ui.components.NetImage(
                option.image,
                Modifier.matchParentSize().imageViewerOrigin(originKey, ThumbShape),
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
            enter = revealIn(),
            exit = revealOut(),
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
                Percent(percent)
            }
        }
    }
}

/**
 * The action row: the n/N counter (multi choice) and 结果 on the left, 投票 alone
 * on the right — a filled button, kept apart from the harmless toggle by the
 * flexible gap (never under 16dp), since a vote cannot be taken back.
 */
@Composable
private fun VoteActions(
    picked: Int,
    choiceCount: Int,
    rejects: Int,
    peek: Boolean,
    showPeek: Boolean,
    sending: Boolean,
    loggedIn: Boolean,
    base: Color,
    onPeek: (Boolean) -> Unit,
    onVote: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().seamText(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (choiceCount > 1) PickCounter(picked, choiceCount, rejects)
        AnimatedVisibility(
            visible = showPeek,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandHorizontally(YoinMotion.defaultSpatialSpec(), expandFrom = Alignment.Start),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkHorizontally(YoinMotion.defaultSpatialSpec(), shrinkTowards = Alignment.Start),
        ) {
            ToggleButton(
                checked = peek,
                onCheckedChange = onPeek,
                shapes = ToggleButtonDefaults.shapes(),
                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = base,
                    contentColor = cs.onSurfaceVariant,
                    checkedContainerColor = cs.secondaryContainer,
                    checkedContentColor = cs.onSecondaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text("结果", style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onVote,
            // A guest's tap goes to 登录 before anything is picked.
            enabled = !sending && (picked > 0 || !loggedIn),
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = 24.dp),
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

/** n/N picked; each refused tap ([rejects] ticking up) pulses it on the spatial spring, flashing error. */
@Composable
private fun PickCounter(picked: Int, choiceCount: Int, rejects: Int) {
    val cs = MaterialTheme.colorScheme
    val scale = remember { Animatable(1f) }
    var flashing by remember { mutableStateOf(false) }
    val up = YoinMotion.fastSpatialSpec<Float>()
    val settle = YoinMotion.defaultSpatialSpec<Float>()
    LaunchedEffect(rejects) {
        if (rejects == 0) return@LaunchedEffect
        flashing = true
        scale.animateTo(1.3f, up)
        flashing = false
        scale.animateTo(1f, settle)
    }
    val numberColor by animateColorAsState(
        if (flashing) cs.error else cs.onSurface,
        YoinMotion.fastEffectsSpec(),
        label = "votePickedColor",
    )
    Row(
        Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    ) {
        AnimatedContent(
            targetState = picked,
            transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard, YoinMotionSpeed.Fast) },
            label = "votePicked",
        ) { n ->
            Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = numberColor)
        }
        Text("/$choiceCount", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

/** Participants (bold number, quiet 人参与), then the time left or 已结束. */
@Composable
private fun VoteFooter(joinCount: Long, endTime: Long, ended: Boolean, now: Long) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().seamText()) {
        AnimatedContent(
            targetState = Fmt.count(joinCount),
            transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard, YoinMotionSpeed.Fast) },
            modifier = Modifier.alignByBaseline(),
            label = "voteJoined",
        ) { count ->
            Text(count, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.onSurface)
        }
        Spacer(Modifier.width(2.dp))
        Text("人参与", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.weight(1f))
        // (number, unit); an empty number is 已结束; null shows nothing (open-ended).
        val tail = if (ended) "" to "已结束" else timeLeft(endTime, now)
        AnimatedContent(
            targetState = tail,
            transitionSpec = { YoinMotion.fadeIn(YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(YoinMotionRole.Standard, YoinMotionSpeed.Fast) },
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.alignByBaseline(),
            label = "voteTail",
        ) { t ->
            Row {
                if (t != null && t.first.isNotEmpty()) {
                    Text(
                        t.first,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                if (t != null) {
                    Text(t.second, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                }
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
