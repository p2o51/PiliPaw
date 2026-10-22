package com.gpo.pilipala.feature.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gpo.pilipala.core.source.Sources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.source.VideoSource
import com.gpo.pilipala.ui.components.VideoItem
import com.gpo.pilipala.yt.core.account.Accounts
import com.gpo.pilipala.yt.core.account.Profile
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.extractor.YtFeeds
import com.gpo.pilipala.yt.feature.mute.Mutes
import com.gpo.pilipala.yt.feature.subscriptions.Subscriptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.gpo.pilipala.yt.core.extractor.VideoItem as YtVideo

/**
 * With both sources ticked, Home's 推荐 and 关注 hold both; a filter chip narrows them to one
 * source (Spotify's chip row: pick one, it shows ×, the other steps back). Null: both.
 */
open class SourceFilter {
    private val _only = MutableStateFlow<VideoSource?>(null)
    val only = _only.asStateFlow()

    fun set(source: VideoSource?) {
        _only.value = source
    }

    /** Both sources ticked: the mixed lists and their chips exist at all. */
    val mixing: Boolean get() = Sources.enabled.value.size > 1

    fun wants(source: VideoSource): Boolean = Sources.isOn(source) && (!mixing || _only.value == null || _only.value == source)
}

/** Home's 推荐 and 关注. */
object MixFilter : SourceFilter()

/** Search results (their 视频 tab) and new Feeds: both sources, or one. */
object SearchScope : SourceFilter()

/**
 * YouTube's side of the mixed 推荐: the account's home feed signed in; signed out, the local
 * subscriptions' latest uploads, shuffled, as the nearest thing to a recommendation.
 */
internal class YouTubeRecommendations {
    private var cursor: String? = null
    private var done = false
    private var pool = ArrayDeque<YtVideo>()

    fun reset() {
        cursor = null
        done = false
        pool.clear()
    }

    suspend fun next(count: Int): List<VideoItem> {
        if (Accounts.youtube.value is Profile.SignedIn) {
            if (done) return emptyList()
            val page = YtFeeds.videos(YtFeeds.Feed.Home, cursor)
            cursor = page.nextCursor
            done = page.nextCursor == null
            return page.items.filterNot { it.isShort || Mutes.hides(it) }.map { it.toCard() }
        }
        if (pool.isEmpty() && !done) {
            done = true
            val gate = Semaphore(6)
            val all = coroutineScope {
                Subscriptions.channels.value.map { c ->
                    async { gate.withPermit { runCatching { Yt.latest(c.url) }.getOrDefault(emptyList()) } }
                }.awaitAll().flatten()
            }
            pool.addAll(all.filterNot { it.isShort || Mutes.hides(it) }.shuffled())
        }
        return List(minOf(count, pool.size)) { pool.removeFirst().toCard() }
    }
}

/** For callers outside this package's file scope: `with(Cards) { video.card() }`. */
object Cards {
    fun YtVideo.card(): VideoItem = toCard()
}

/** A YouTube video as one of PiliPaw's cards (it plays in the YouTube player when tapped). */
internal fun YtVideo.toCard(): VideoItem = VideoItem(
    title = title,
    cover = thumbnail,
    duration = durationSec,
    ownerName = channelName,
    ownerFace = channelAvatar,
    views = if (views > 0) views else -1,
    pubdate = uploadedAt / 1000,
    badge = "YouTube",
    youtubeUrl = url,
)

/**
 * The chip row under Home's tabs (both sources ticked, on 推荐 or 关注): one chip per account.
 * Tapping one narrows the feed to it; it fills, grows an ×, and the other chip folds away.
 * The × (or tapping it again) brings both back.
 */
@androidx.compose.runtime.Composable
fun SourceChips(filter: SourceFilter = MixFilter, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val only by filter.only.collectAsStateCompat()
    androidx.compose.foundation.layout.Row(
        modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        VideoSource.entries.forEach { source ->
            val picked = only == source
            androidx.compose.animation.AnimatedVisibility(
                visible = only == null || picked,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally(),
            ) {
                val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                val scheme = androidx.compose.material3.MaterialTheme.colorScheme
                val bg by androidx.compose.animation.animateColorAsState(
                    if (picked) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                    com.gpo.pilipala.ui.theme.YoinMotion.defaultEffectsSpec(),
                    label = "chip",
                )
                androidx.compose.foundation.layout.Row(
                    androidx.compose.ui.Modifier
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(bg)
                        .clickable {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                            filter.set(if (picked) null else source)
                        }
                        .padding(start = 6.dp, end = if (picked) 10.dp else 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    SourceDotForMenu(source, 24.dp)
                    androidx.compose.material3.Text(
                        source.label,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = if (picked) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    )
                    if (picked) {
                        androidx.compose.material3.Icon(
                            com.gpo.yoin.symbols.YoinSymbols.Close,
                            contentDescription = "两边都看",
                            modifier = androidx.compose.ui.Modifier.size(18.dp),
                            tint = scheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat() = collectAsState()
