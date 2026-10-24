package com.gpo.pilipala.feature.player

import com.gpo.pilipala.core.util.ScopeErrors
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

internal sealed interface PlayerPhase {
    data object Idle : PlayerPhase
    data object Loading : PlayerPhase
    data object Ready : PlayerPhase
    data class Error(val message: String) : PlayerPhase
}

/** Process-wide scope for fire-and-forget calls that must outlive a player (final heartbeat). */
private val playerIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeErrors)

/** Last positions per cid so coming back to a page resumes where it left off. */
private object ResumeMemory {
    private val map = object : LinkedHashMap<Long, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?) = size > 64
    }

    fun put(cid: Long, pos: Long) = synchronized(map) { map[cid] = pos }
    fun get(cid: Long): Long = synchronized(map) { map[cid] ?: 0L }
}

private val sessionIds = AtomicInteger(0)

/**
 * The real [BiliPlayerState]: owns an ExoPlayer + MediaSession, resolves the
 * playurl, picks streams, reports heartbeats and loads danmaku/subtitles.
 * Remembered by [rememberBiliPlayerState]; released when forgotten.
 */
@Stable
internal class PiliPlayerController(
    private val context: Context,
    preview: Boolean = false,
) : BiliPlayerState(), RememberObserver {
    /**
     * A 单列 card's preview (FeedPreview.kt): muted, no audio focus, no session or
     * notification, nothing written to history or resume memory. [adopt] turns it into a
     * page's player, mid-play, when the card is opened.
     */
    var preview by mutableStateOf(preview)
        private set

    /** A preview's sound: off by default (YouTube's feed). Sounding, it holds audio focus like playback. */
    var previewMuted by mutableStateOf(true)
        private set

    fun setPreviewSound(muted: Boolean) {
        previewMuted = muted
        if (!preview) return
        exo?.let { p ->
            p.setAudioAttributes(mediaAttributes, !muted)
            p.volume = if (muted) 0f else 1f
        }
    }

    /** Came from a card's preview: the page draws it through a TextureView, flight included. */
    var handedOver by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + ScopeErrors)
    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null
    private var sessionPlayer: SessionPlayer? = null

    /** Background host: wanted once playback starts, dropped when it ends or the page goes away. */
    private var hostWanted = false
    private var hosted = false
    private var endJob: Job? = null

    /** Mirrors progress into a caller-supplied plain [BiliPlayerState] (fallback path). */
    var mirror: BiliPlayerState? = null
    var onCompleted: () -> Unit = {}

    var player by mutableStateOf<Player?>(null)
        private set
    var target by mutableStateOf<PlayTarget?>(null)
        private set
    var phase by mutableStateOf<PlayerPhase>(PlayerPhase.Idle)
        private set
    var info by mutableStateOf<PlayInfo?>(null)
        private set
    var currentQn by mutableIntStateOf(0)
        private set
    var currentAudio by mutableIntStateOf(0)
        private set
    var currentCodec by mutableIntStateOf(7)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var firstFrameRendered by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set
    var playWhenReady by mutableStateOf(false)
        private set

    /** The detail layout kept with this player while it is docked; a new player starts undecided. */
    internal var detailImmersive: Boolean? = null

    /** width / height of the picture. */
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set
    var speed by mutableFloatStateOf(AppSettings.defaultSpeed.value)
        private set
    var boosting by mutableStateOf(false)
        private set

    /** What a boost plays at (hold to speed up steps it); a re-prepare mid-boost keeps it. */
    var boostRate by mutableFloatStateOf(3f)
        private set
    var loop by mutableStateOf(false)
        private set
    var sleepMinutes by mutableIntStateOf(0)
        private set
    var sleepDeadline by mutableLongStateOf(0L)
        private set

    var danmaku by mutableStateOf<List<DanmakuItem>>(emptyList())
        private set

    /** Bumped on every seek / source change so danmaku lanes are cleared. */
    var seekGeneration by mutableIntStateOf(0)
        private set

    var subtitles by mutableStateOf<List<SubtitleTrack>>(emptyList())
        private set
    var subtitleIndex by mutableIntStateOf(-1)
        private set
    var subtitleLines by mutableStateOf<List<SubtitleLine>>(emptyList())
        private set

    /** The track list for the current video has arrived (or failed): an empty list is final. */
    var subtitlesReady by mutableStateOf(false)
        private set

    /** The chosen track's cues are on their way. */
    var subtitleLoading by mutableStateOf(false)
        private set

    /** A track picked only so 仅听声音 has lyrics; dropped again when the picture returns. */
    internal var lyricsBorrowedSubtitle = false

    /** Chapters (view points) of the current video; empty when it has none. */
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set

    /** Seek preview sprites; null until loaded or when the video has none. */
    var shots by mutableStateOf<VideoShots?>(null)
        private set

    /** 高能进度条: the audience heat over the video; null until loaded or when it has none. */
    var heat by mutableStateOf<HeatCurve?>(null)
        private set

    /** 跳过片段 of the current video: 番剧 片头 / 片尾 and 空降助手 (SkipSegments.kt). */
    var skips by mutableStateOf<List<BiliSkip>>(emptyList())
        private set

    /** The segment just skipped (the bar closes it up; 撤销 goes back into it). */
    var lastSkip by mutableStateOf<BiliSkip?>(null)
        private set

    /** A 提示我 segment the playhead has entered: the player offers 跳过. */
    var askSkip by mutableStateOf<BiliSkip?>(null)
        private set

    private val skipsHandled = HashSet<String>()
    private var skipWatch: Job? = null

    /** Fullscreen that came from rotating the phone (so rotating back exits it). */
    var fullscreenByRotation = false

    /** Fullscreen requested only so the PiP window shows the picture alone. */
    var pipForcedFullscreen by mutableStateOf(false)

    private var loadJob: Job? = null
    private var sideJobs = mutableListOf<Job>()
    private var heartbeatJob: Job? = null
    private var sleepJob: Job? = null
    private var subtitleJob: Job? = null
    private var urlAttempt = 0
    private var lastHeartbeatAt = 0L
    private var backgrounded = false

    /** Play queue driving what plays next (收藏夹 / 稍后再看 as a playlist); null = a single video. */
    var queue: PlayQueue? = null
        private set
    private var queueJob: Job? = null
    private var advanceJob: Job? = null
    private var skipJob: Job? = null
    private var queueFailures = 0

    /**
     * 仅听声音: the video track is switched off (its stream stops loading; the audio plays on
     * without a rebuild). A page opened to listen loads the DASH audio stream alone.
     */
    var audioOnly by mutableStateOf(false)
        private set

    /** The prepared source carries no picture (built while listening): 显示画面 has to rebuild it. */
    private var sourceAudioOnly = false

    /** What the page offers beyond a single video: 分P / 合集 neighbours, its target, the like toggle. */
    private var hooks: PlayerPageHooks? = null
    private var hooksJob: Job? = null
    private var pageHasNext = false
    private var pageHasPrevious = false

    /** null: the like button is not offered (signed out, nothing loaded). */
    private var pageLiked: Boolean? = null

    /** A next / previous item exists (queue, or the page's 分P / 合集): overlay and media controls follow. */
    var hasNext by mutableStateOf(false)
        private set
    var hasPrevious by mutableStateOf(false)
        private set

    /** The cached files playing instead of the network streams (null: online). */
    private var localMedia: com.gpo.pilipala.feature.download.LocalMedia? = null

    private val dataSourceFactory by lazy {
        OkHttpDataSource.Factory(BiliClient.http).setDefaultRequestProperties(BiliClient.mediaHeaders())
    }
    // Overseas line routing (feature/player/cdn): every media open goes through CdnRouter;
    // 多线程加载 (ParallelRangeDataSource) fetches DASH segment files as parallel ranges.
    private val progressiveFactory by lazy {
        ProgressiveMediaSource.Factory(
            com.gpo.pilipala.feature.player.cdn.CdnDataSource.Factory(
                com.gpo.pilipala.feature.player.cdn.ParallelRangeDataSource.Factory(dataSourceFactory),
            ),
        )
            .setLoadErrorHandlingPolicy(com.gpo.pilipala.feature.player.cdn.CdnLoadErrorPolicy())
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            mirror?.isPlaying = playing
            onPlayingChanged(playing)
            // A page's player starting: the mini bar's other video steps aside (PlayerHost.kt).
            if (playing && !preview) com.gpo.pilipala.feature.sources.PlayerHost.started(com.gpo.pilipala.feature.sources.PlayerSlot.Bili(this@PiliPlayerController))
            // Every start, not only the first: the notification may have gone to another player.
            if (playing) {
                hostWanted = true
                syncBackgroundHost()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            isBuffering = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> {
                    if (phase !is PlayerPhase.Ready) phase = PlayerPhase.Ready
                    ended = false
                    queueFailures = 0
                    tick()
                }
                Player.STATE_ENDED -> {
                    ended = true
                    tick()
                    heartbeat(-1)
                    val q = queue
                    // A Mix playing through (feature/sources/MixPlayback): it takes over what comes next.
                    if (q == null && target?.bvid?.let { completionHook?.invoke(this@PiliPlayerController, it) } == true) return
                    if (q != null) {
                        // The queue decides what comes next (also in the background, where the page cannot).
                        endJob?.cancel()
                        scope.launch(Dispatchers.Main) { if (queue === q && ended) playQueueIndex(q.nextIndex()) }
                        return
                    }
                    onCompleted()
                    // Nothing queued after this one (auto-play-next resets `ended` via load) → drop the notification.
                    endJob?.cancel()
                    endJob = scope.launch {
                        delay(3_000)
                        if (ended) {
                            hostWanted = false
                            syncBackgroundHost()
                        }
                    }
                }
                else -> Unit
            }
            if (state != Player.STATE_ENDED) endJob?.cancel()
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = exo ?: return
            val pi = info
            val candidates = maxCandidates(pi)
            val pos = p.currentPosition.coerceAtLeast(positionMs)
            val decoderError = error.errorCode in setOf(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            )
            if (decoderError && pi != null && pi.isDash && currentCodec != 7 &&
                pi.videos.any { it.id == currentQn && it.codecId == 7 }
            ) {
                currentCodec = 7
                urlAttempt = 0
                prepare(pos, playWhenReady = true)
            } else if (urlAttempt + 1 < candidates) {
                urlAttempt++
                prepare(pos, playWhenReady = true)
            } else {
                phase = PlayerPhase.Error(errorLabel(error))
                onQueueItemFailed()
            }
        }

        override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) {
            playWhenReady = ready
        }

        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
            com.gpo.pilipala.feature.listen.ListenLog.firstFrame()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                seekGeneration++
            }
        }
    }

    init {
        scope.launch {
            while (isActive) {
                tick()
                delay(if (isPlaying) 250L else 600L)
            }
        }
        scope.launch { AppSettings.backgroundPlay.state.collect { syncBackgroundHost() } }
        scope.launch { snapshotFlow { artist }.collect { sessionPlayer?.notifyMetadataChanged() } }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** [fresh]: a queue step — start from 0 and play, whatever history says. */
    fun load(t: PlayTarget, force: Boolean = false, fresh: Boolean = false) {
        val cur = target
        if (!force && !fresh && cur != null && cur.cid == t.cid && cur.epId == t.epId && cur.bvid == t.bvid &&
            phase !is PlayerPhase.Error && phase !is PlayerPhase.Idle
        ) {
            return
        }
        if (cur != null && cur.cid != t.cid && !preview) {
            ResumeMemory.put(cur.cid, positionMs)
            heartbeat(if (ended) -1 else exactPositionMs() / 1000, force = true)
        }
        val keepPosition = if (force && !fresh && cur?.cid == t.cid) positionMs else -1L
        heartbeatJob?.cancel()
        phase = PlayerPhase.Loading
        target = t
        // In a Mix or not, the media controls' 下一个 follows the video now loading.
        updateNeighbours()
        info = null
        positionMs = keepPosition.coerceAtLeast(0L)
        bufferedMs = 0L
        firstFrameRendered = false
        ended = false
        urlAttempt = 0
        danmaku = emptyList()
        subtitles = emptyList()
        subtitleIndex = -1
        subtitleLines = emptyList()
        subtitlesReady = false
        subtitleLoading = false
        lyricsBorrowedSubtitle = false
        subtitleJob?.cancel()
        chapters = emptyList()
        shots = null
        heat = null
        skips = emptyList()
        lastSkip = null
        askSkip = null
        skipsHandled.clear()
        skipWatch?.cancel()
        seekGeneration++
        exo?.stop()
        exo?.clearMediaItems()
        session?.setSessionActivity(PlaybackService.sessionActivity(context, t))
        sessionPlayer?.notifyMetadataChanged()
        loadJob?.cancel()
        sideJobs.forEach { it.cancel() }
        sideJobs.clear()

        loadJob = scope.launch {
            try {
                val wish = AppSettings.defaultVideoQuality.value
                // 离线缓存 (feature/download): a finished cache of this cid plays from its file.
                val local = com.gpo.pilipala.feature.download.DownloadStore.localMedia(context, t.cid)
                localMedia = local
                val pi = local?.playInfo() ?: PlayUrlApi.fetch(t, wish)
                withContext(Dispatchers.Default) { CodecSupport.warm(pi) }
                info = pi
                if (pi.clips.isNotEmpty()) addSkips(pi.clips)
                currentQn = StreamPicker.videoQn(pi, wish)
                if (!pi.isDash) currentQn = pi.quality
                currentCodec = Qualities.codecIdForSetting(AppSettings.preferredCodec.value)
                currentAudio = StreamPicker.audio(pi, AppSettings.defaultAudioQuality.value)?.id ?: 0
                val duration = pi.timelengthMs
                val remembered = ResumeMemory.get(t.cid)
                // Queue items play like tracks: from the top unless the page asks for a position.
                // A Mix playing through is a list too (feature/sources/MixPlayback).
                val queued = fresh || queue?.items?.any { it.bvid == t.bvid && t.bvid.isNotEmpty() } == true ||
                    (t.bvid.isNotEmpty() && com.gpo.pilipala.feature.sources.MixPlayback.runs("bili:${t.bvid}"))
                val start = when {
                    keepPosition >= 0 -> keepPosition
                    t.startPositionMs > 0 -> t.startPositionMs
                    queued -> 0L
                    remembered > 0 && (duration <= 0 || remembered < duration - 5_000) -> remembered
                    pi.lastPlayTimeMs > 0 && (duration <= 0 || pi.lastPlayTimeMs < duration - 5_000) -> pi.lastPlayTimeMs
                    else -> 0L
                }
                if (duration > 0) durationMs = duration
                prepare(start, playWhenReady = force || fresh || preview || AppSettings.autoPlay.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                phase = PlayerPhase.Error(e.message?.takeIf { it.isNotBlank() } ?: "加载失败")
                onQueueItemFailed()
            }
        }
        sideJobs += scope.launch {
            runCatching { DanmakuApi.fetch(t.cid) }.onSuccess { danmaku = it }
        }
        if (!t.isPgc || t.bvid.isNotBlank() || t.aid > 0) {
            sideJobs += scope.launch {
                try {
                    val (tracks, points) = SubtitleApi.playerInfo(t)
                    subtitles = tracks
                    chapters = points
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
                subtitlesReady = true
            }
        } else {
            subtitlesReady = true
        }
        sideJobs += scope.launch {
            runCatching { VideoShotsApi.get(t) }.onSuccess { shots = it }
        }
        sideJobs += scope.launch {
            runCatching { HeatApi.get(t) }.onSuccess { heat = it }
        }
        if (!t.isPgc && t.bvid.isNotBlank() && !preview) {
            sideJobs += scope.launch {
                val found = BiliSkips.community(t.bvid, t.cid)
                if (found.isNotEmpty()) addSkips(found)
            }
        }
    }

    private fun addSkips(more: List<BiliSkip>) {
        skips = (skips + more).distinctBy { it.id }.sortedBy { it.startMs }
        watchSkips()
    }

    /**
     * 跳过片段: four times a second, a playhead inside a segment whose kind is 自动跳过 jumps to
     * its end; one set to 提示我 raises [askSkip]. Each segment acts once per video, so 撤销 and
     * seeking back into it stay put.
     */
    private fun watchSkips() {
        skipWatch?.cancel()
        if (preview || skips.isEmpty()) return
        skipWatch = scope.launch {
            while (isActive) {
                delay(250)
                val p = exo ?: continue
                val at = p.currentPosition
                askSkip?.let { a -> if (at < a.startMs || at >= a.endMs) askSkip = null }
                if (!p.isPlaying) continue
                val s = skips.firstOrNull {
                    at >= it.startMs && at < it.endMs - 500 && it.id !in skipsHandled && it.kind.mode != SkipMode.Mark
                } ?: continue
                skipsHandled += s.id
                when (s.kind.mode) {
                    SkipMode.Skip -> {
                        seekTo(s.endMs)
                        lastSkip = s
                    }
                    SkipMode.Ask -> askSkip = s
                    SkipMode.Mark -> Unit
                }
            }
        }
    }

    /** 撤销: back to the start of the segment just skipped, which then plays. */
    fun undoSkip() {
        val s = lastSkip ?: return
        lastSkip = null
        seekTo(s.startMs)
    }

    /** 跳过 on a 提示我 segment. */
    fun acceptSkip() {
        val s = askSkip ?: return
        askSkip = null
        seekTo(s.endMs)
        lastSkip = s
    }

    fun retry() {
        val t = target ?: return
        load(t, force = true)
    }

    override fun play() {
        val p = exo ?: return
        if (phase is PlayerPhase.Error) {
            retry()
            return
        }
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    override fun pause() {
        exo?.pause()
    }

    fun togglePlay() {
        if (exo?.playWhenReady == true && !ended) pause() else play()
    }

    override fun seekTo(ms: Long) {
        val p = exo ?: return
        val d = durationMs
        val target = if (d > 0) ms.coerceIn(0L, d) else ms.coerceAtLeast(0L)
        p.seekTo(target)
        positionMs = target
        mirror?.positionMs = target
        ended = false
        seekGeneration++
    }

    /** Exact clock for the danmaku canvas (ExoPlayer interpolates between ticks). */
    fun exactPositionMs(): Long = exo?.currentPosition ?: positionMs

    fun changeSpeed(value: Float) {
        speed = value
        if (!boosting) exo?.setPlaybackSpeed(value)
    }

    /** Plays at [rate] until [endBoost]; called again mid-boost, it only changes the rate. */
    fun beginBoost(rate: Float = 3f) {
        boostRate = rate
        boosting = true
        exo?.setPlaybackSpeed(rate)
    }

    fun endBoost() {
        if (!boosting) return
        boosting = false
        exo?.setPlaybackSpeed(speed)
    }

    fun changeLoop(on: Boolean) {
        loop = on
        applyRepeat()
    }

    /** 洗脑循环 or the queue's 单曲循环 → the player repeats the item itself (no reload, no completion). */
    private fun applyRepeat() {
        exo?.repeatMode = if (loop || queue?.mode?.value == PlayMode.RepeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // ── Play queue ──────────────────────────────────────────────────────────

    /** Attaches the page's queue: completion, notification previous / next and the mode follow it. */
    fun attachQueue(q: PlayQueue?) {
        if (q === queue) return
        queueJob?.cancel()
        advanceJob?.cancel()
        skipJob?.cancel()
        queueFailures = 0
        queue = q
        if (q != null) {
            queueJob = scope.launch {
                launch {
                    q.mode.collect {
                        applyRepeat()
                        refreshSessionControls()
                    }
                }
                launch { q.audioOnly.collect { changeAudioOnly(it) } }
            }
        } else {
            changeAudioOnly(false)
            applyRepeat()
        }
        updateNeighbours()
    }

    /**
     * Binds the page's [PlayerPageHooks] (null unbinds). Read through snapshots on the
     * controller's own scope, so they keep working while the page is in the background:
     * a target the page switches to (auto-play-next, 下一个 from the notification) loads
     * even though the page itself does not recompose then.
     */
    fun bindPage(h: PlayerPageHooks?) {
        if (h === hooks) return
        hooks = h
        hooksJob?.cancel()
        hooksJob = null
        if (h == null) {
            pageHasNext = false
            pageHasPrevious = false
            pageLiked = null
            updateNeighbours()
            return
        }
        hooksJob = scope.launch {
            launch {
                snapshotFlow { Triple(h.hasNext(), h.hasPrevious(), h.liked()) }.collect { (n, p, l) ->
                    pageHasNext = n
                    pageHasPrevious = p
                    pageLiked = l
                    updateNeighbours()
                }
            }
            launch { snapshotFlow { h.target() }.collect { t -> if (t != null) load(t) } }
        }
    }

    /** A Mix playing through this video (feature/sources/MixPlayback): 下一个 is the Mix's next. */
    private val inMix: Boolean
        get() = queue == null && target?.bvid?.takeIf { it.isNotEmpty() }?.let { com.gpo.pilipala.feature.sources.MixPlayback.runs("bili:$it") } == true

    private fun updateNeighbours() {
        val q = queue
        hasNext = q != null || inMix || pageHasNext
        hasPrevious = q != null || pageHasPrevious
        refreshSessionControls()
    }

    /** 下一个: the queue's next item, a Mix's next video, else the page's next 分P / 合集 episode. */
    fun skipNext() {
        if (queue != null) {
            skipQueue(forward = true)
        } else if (inMix) {
            com.gpo.pilipala.feature.sources.MixPlayback.next()
        } else if (pageHasNext) {
            hooks?.next?.invoke()
        }
    }

    /** 上一个. Restarts the current item first when it has played over 3s. */
    fun skipPrevious() {
        if (queue != null) {
            skipQueue(forward = false)
            return
        }
        if (exactPositionMs() > 3_000 && phase is PlayerPhase.Ready) {
            seekTo(0)
            return
        }
        if (pageHasPrevious) hooks?.previous?.invoke()
    }

    /** 仅听声音 on / off (through the queue when there is one, so its panel follows). */
    fun toggleAudioOnly() {
        val q = queue
        if (q != null) q.setAudioOnly(!q.audioOnly.value) else changeAudioOnly(!audioOnly)
    }

    /** 仅听声音 set outright (a page opened to listen); through the queue when there is one. */
    fun listenOnly(on: Boolean) {
        val q = queue
        if (q != null) q.setAudioOnly(on) else changeAudioOnly(on)
    }

    /** Next / previous in the queue. Previous restarts the item first when it has played over 3s. */
    fun skipQueue(forward: Boolean) {
        val q = queue ?: return
        if (!forward && exactPositionMs() > 3_000 && phase is PlayerPhase.Ready) {
            seekTo(0)
            return
        }
        playQueueIndex(if (forward) q.nextIndex() else q.previousIndex())
    }

    /** Plays queue item [i] from the top; the page follows through [PlayQueue.index]. */
    fun playQueueIndex(i: Int) {
        val q = queue ?: return
        val item = q.items.getOrNull(i) ?: return
        advanceJob?.cancel()
        skipJob?.cancel()
        advanceJob = scope.launch {
            var cid = q.cidOf(i)
            if (cid <= 0) {
                phase = PlayerPhase.Loading
                cid = try {
                    resolveFirstCid(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    0L
                }
            }
            artist = item.owner
            if (cid <= 0) {
                q.moveTo(i)
                phase = PlayerPhase.Error("加载失败")
                onQueueItemFailed()
                return@launch
            }
            q.rememberCid(item.bvid, cid)
            load(
                PlayTarget(bvid = item.bvid, aid = item.aid, cid = cid, title = item.title, cover = item.cover),
                fresh = true,
            )
            // After the player switched, so the page's own load of this item is a no-op.
            q.moveTo(i)
        }
    }

    /** A queue item that cannot play (失效, no stream) is skipped — a few in a row at most. */
    private fun onQueueItemFailed() {
        val q = queue ?: return
        if (q.size < 2 || queueFailures >= 3) return
        queueFailures++
        skipJob?.cancel()
        skipJob = scope.launch {
            delay(1_500)
            if (queue === q && phase is PlayerPhase.Error) playQueueIndex(q.nextIndex())
        }
    }

    private fun changeAudioOnly(on: Boolean) {
        if (on == audioOnly) return
        audioOnly = on
        if (!on) com.gpo.pilipala.feature.listen.ListenLog.videoAsked(if (sourceAudioOnly) "B站, rebuild" else "B站, track on")
        applyVideoTrack()
        // The picture comes back (or goes) with the next rendered frame; the cover covers the gap.
        firstFrameRendered = false
        // Switching the video track is enough: its stream stops loading while it is off and
        // picks up at the playhead when it comes back, the audio never stopping. Only a source
        // built without a picture (opened to listen) is rebuilt for it.
        if (!on && sourceAudioOnly && exo != null && phase !is PlayerPhase.Error) reprepare()
        refreshSessionControls()
        syncBackgroundHost()
    }

    private fun applyVideoTrack() {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, backgrounded || audioOnly)
            .build()
    }

    private fun sessionButtons(): List<CommandButton> = PlaybackService.buttons(
        hasPrevious = hasPrevious,
        hasNext = hasNext,
        mode = queue?.mode?.value,
        liked = pageLiked.takeIf { BiliClient.isLoggedIn },
        audioOnly = audioOnly,
    )

    private var lastButtons: List<CommandButton>? = null

    /** Previous / next appear or go; the like, 仅听声音 and play-mode buttons follow their state. */
    private fun refreshSessionControls() {
        sessionPlayer?.notifyCommandsChanged()
        val s = session ?: return
        val buttons = sessionButtons()
        if (buttons == lastButtons) return
        lastButtons = buttons
        s.setMediaButtonPreferences(buttons)
    }

    /** A tap on one of the session's custom buttons (notification, lock screen, headset app). */
    private fun onSessionCommand(action: String): Boolean {
        when (action) {
            PlaybackService.ACTION_PLAY_MODE -> queue?.cycleMode()
            PlaybackService.ACTION_LIKE -> if (BiliClient.isLoggedIn && pageLiked != null) hooks?.toggleLike?.invoke()
            PlaybackService.ACTION_AUDIO_ONLY -> toggleAudioOnly()
            else -> return false
        }
        return true
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepMinutes = minutes
        if (minutes <= 0) {
            sleepDeadline = 0L
            return
        }
        sleepDeadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            sleepMinutes = 0
            sleepDeadline = 0L
        }
    }

    fun selectQuality(qn: Int) {
        val pi = info ?: return
        val t = target ?: return
        if (qn == currentQn) return
        if (pi.isDash) {
            if (pi.videos.none { it.id == qn }) return
            currentQn = qn
            urlAttempt = 0
            reprepare()
        } else {
            val pos = exactPositionMs()
            val wasPlaying = exo?.playWhenReady ?: true
            loadJob?.cancel()
            loadJob = scope.launch {
                runCatching { PlayUrlApi.fetch(t, qn) }.onSuccess { fresh ->
                    info = fresh
                    currentQn = fresh.quality
                    urlAttempt = 0
                    prepare(pos, wasPlaying)
                }.onFailure { if (it !is CancellationException) phase = PlayerPhase.Error(it.message ?: "加载失败") }
            }
        }
    }

    fun selectAudio(id: Int) {
        if (id == currentAudio) return
        currentAudio = id
        urlAttempt = 0
        reprepare()
    }

    fun selectCodec(codecId: Int) {
        if (codecId == currentCodec) return
        currentCodec = codecId
        urlAttempt = 0
        reprepare()
    }

    fun selectSubtitle(index: Int) {
        subtitleJob?.cancel()
        subtitleIndex = index
        subtitleLines = emptyList()
        val track = subtitles.getOrNull(index)
        if (track == null) {
            subtitleLoading = false
            return
        }
        subtitleLoading = true
        subtitleJob = scope.launch {
            try {
                subtitleLines = SubtitleApi.lines(track)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
            subtitleLoading = false
        }
    }

    /** Background playback: drop the video track while the window is not visible. */
    fun setBackground(bg: Boolean) {
        if (bg == backgrounded) return
        backgrounded = bg
        applyVideoTrack()
        syncBackgroundHost()
    }

    /** The preview becomes a page's player: sound, focus, session and history from here on. */
    fun adopt() {
        if (!preview) return
        preview = false
        handedOver = true
        exo?.let { p ->
            p.setAudioAttributes(mediaAttributes, true)
            p.volume = 1f
        }
        if (exo != null && session == null) buildSession(exo!!)
        if (isPlaying) {
            onPlayingChanged(true)
            hostWanted = true
            syncBackgroundHost()
        }
    }

    /** Releases a preview no page took, scope included (it is not remembered by any composition). */
    fun dispose() {
        release()
        scope.cancel()
    }

    /** The CDN routing session this player began; another player's (a preview's, a page's) is left alone. */
    private var cdnSession = -1L

    override fun release() {
        com.gpo.pilipala.feature.sources.PlayerHost.released(com.gpo.pilipala.feature.sources.PlayerSlot.Bili(this))
        if (cdnSession >= 0 && com.gpo.pilipala.feature.player.cdn.CdnRouter.sessionId() == cdnSession) {
            com.gpo.pilipala.feature.player.cdn.CdnRouter.endSession()
        }
        cdnSession = -1L
        if (!preview) target?.let { t ->
            ResumeMemory.put(t.cid, positionMs)
            heartbeat(if (ended) -1 else positionMs / 1000, force = true)
        }
        loadJob?.cancel()
        sideJobs.forEach { it.cancel() }
        sideJobs.clear()
        heartbeatJob?.cancel()
        sleepJob?.cancel()
        subtitleJob?.cancel()
        endJob?.cancel()
        queueJob?.cancel()
        advanceJob?.cancel()
        skipJob?.cancel()
        hooksJob?.cancel()
        hooksJob = null
        hooks = null
        pageHasNext = false
        pageHasPrevious = false
        pageLiked = null
        hasNext = false
        hasPrevious = false
        lastButtons = null
        queue = null
        audioOnly = false
        hostWanted = false
        session?.let { if (hosted) PlaybackService.detach(it) }
        hosted = false
        session?.release()
        session = null
        sessionPlayer = null
        exo?.removeListener(listener)
        exo?.release()
        exo = null
        player = null
        target = null
        phase = PlayerPhase.Idle
        isPlaying = false
        playWhenReady = false
        mirror?.isPlaying = false
        boosting = false
        backgrounded = false
    }

    // ── RememberObserver ────────────────────────────────────────────────────

    override fun onRemembered() = Unit

    override fun onForgotten() {
        // 继续播放: the page goes, the player stays (BiliKeeper), in the mini bar until a page takes it back.
        if (com.gpo.pilipala.feature.sources.BiliKeeper.offer(this)) return
        release()
        scope.cancel()
    }

    companion object {
        /** Called with the bvid when a video plays to its end; true: handled (nothing else follows). */
        var completionHook: ((PiliPlayerController, String) -> Boolean)? = null
    }

    /** A page's player (not a feed preview) with a video it was playing: worth keeping when its page goes. */
    val keepable: Boolean get() = !preview && target != null && !ended && (isPlaying || playWhenReady)

    override fun onAbandoned() {
        release()
        scope.cancel()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun ensurePlayer(): ExoPlayer {
        exo?.let { return it }
        val p = ExoPlayer.Builder(context)
            // A muted preview never takes audio focus: whatever the user is listening to keeps playing.
            .setAudioAttributes(mediaAttributes, !preview || !previewMuted)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(listener)
        if (preview && previewMuted) p.volume = 0f
        exo = p
        player = p
        applyRepeat()
        applyVideoTrack()
        if (!preview) buildSession(p)
        return p
    }

    private val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    private fun buildSession(p: ExoPlayer) {
        val sp = SessionPlayer(p)
        val buttons = sessionButtons()
        lastButtons = buttons
        session = runCatching {
            MediaSession.Builder(context, sp)
                .setId("pili-player-${sessionIds.incrementAndGet()}")
                .setCallback(PiliSessionCallback(buttons = ::sessionButtons, onCommand = ::onSessionCommand))
                .setMediaButtonPreferences(buttons)
                .setBitmapLoader(CoilBitmapLoader(context))
                .apply { target?.let { setSessionActivity(PlaybackService.sessionActivity(context, it)) } }
                .build()
        }.getOrNull()
        sessionPlayer = sp.takeIf { session != null }
        syncBackgroundHost()
    }

    /** Hands the session to [PlaybackService] (notification + foreground) while background play or 仅听声音 wants it. */
    private fun syncBackgroundHost() {
        val s = session ?: return
        // 仅听声音 is listening: it always gets the notification and keeps playing in the
        // background, whatever 后台播放 says. So does a page already in the background
        // (the picture just came back from the notification: let it be until the page returns).
        val want = hostWanted && (AppSettings.backgroundPlay.value || audioOnly || backgrounded)
        // Hosted, but another player (YouTube's, a Mix's next) took the notification meanwhile:
        // attach again, or the controls stay gone while this one plays.
        if (want == hosted && (!want || PlaybackService.isHosting(s))) return
        hosted = want
        if (want) PlaybackService.attach(context, s) else PlaybackService.detach(s)
    }

    private fun sessionMetadata(): MediaMetadata {
        val t = target
        return MediaMetadata.Builder()
            .setTitle(t?.title?.takeIf { it.isNotBlank() })
            .setArtist(artist.takeIf { it.isNotBlank() })
            .apply { t?.cover?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(BiliClient.resolve(it))) } }
            .build()
    }

    /**
     * What the session (notification, lock screen, headsets) sees: our title / UP / cover
     * instead of the stream's metadata, and play() through the controller (retry, replay).
     * Previous / next exist only while there is somewhere to go (the queue, or the page's
     * 分P / 合集) and step there; otherwise they are hidden so the system shows ±10s.
     */
    private inner class SessionPlayer(p: Player) : ForwardingPlayer(p) {
        private val wrapped = HashMap<Player.Listener, Player.Listener>()

        override fun addListener(listener: Player.Listener) {
            val w = MetadataOverrideListener(listener, ::sessionMetadata, ::sessionCommands)
            wrapped[listener] = w
            super.addListener(w)
        }

        override fun removeListener(listener: Player.Listener) {
            wrapped.remove(listener)?.let { super.removeListener(it) }
        }

        override fun getMediaMetadata(): MediaMetadata = sessionMetadata()

        override fun play() = this@PiliPlayerController.play()

        override fun getAvailableCommands(): Player.Commands = sessionCommands(super.getAvailableCommands())

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun hasNextMediaItem(): Boolean = hasNext || super.hasNextMediaItem()

        override fun hasPreviousMediaItem(): Boolean = hasPrevious || super.hasPreviousMediaItem()

        override fun seekToNext() = if (hasNext) skipNext() else super.seekToNext()

        override fun seekToNextMediaItem() = if (hasNext) skipNext() else super.seekToNextMediaItem()

        override fun seekToPrevious() = if (hasPrevious) skipPrevious() else super.seekToPrevious()

        override fun seekToPreviousMediaItem() {
            val q = queue
            when {
                q != null -> playQueueIndex(q.previousIndex())
                pageHasPrevious -> hooks?.previous?.invoke()
                else -> super.seekToPreviousMediaItem()
            }
        }

        fun notifyMetadataChanged() {
            val m = sessionMetadata()
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
            wrapped.values.toList().forEach {
                it.onMediaMetadataChanged(m)
                it.onEvents(this, events)
            }
        }

        fun notifyCommandsChanged() {
            val c = availableCommands
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED).build())
            wrapped.values.toList().forEach {
                it.onAvailableCommandsChanged(c)
                it.onEvents(this, events)
            }
        }
    }

    /** Previous / next only when there is an item to go to; otherwise the system shows ±10s in their slots. */
    private fun sessionCommands(base: Player.Commands): Player.Commands {
        val previous = intArrayOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        val next = intArrayOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val b = base.buildUpon()
        if (hasPrevious) b.addAll(*previous) else b.removeAll(*previous)
        if (hasNext) b.addAll(*next) else b.removeAll(*next)
        return b.build()
    }

    private fun reprepare() {
        val p = exo
        val pos = p?.currentPosition ?: positionMs
        val playing = p?.playWhenReady ?: AppSettings.autoPlay.value
        prepare(pos, playing)
    }

    private fun prepare(startMs: Long, playWhenReady: Boolean) {
        val pi = info ?: return
        val p = ensurePlayer()
        val source = buildSource(pi)
        if (source == null) {
            phase = PlayerPhase.Error("没有可播放的视频流")
            return
        }
        if (phase is PlayerPhase.Error) phase = PlayerPhase.Loading
        p.setMediaSource(source, startMs.coerceAtLeast(0L))
        p.setPlaybackSpeed(if (boosting) boostRate else speed)
        p.playWhenReady = playWhenReady
        p.prepare()
        positionMs = startMs
        seekGeneration++
    }

    private fun maxCandidates(pi: PlayInfo?): Int {
        pi ?: return 1
        return if (pi.isDash) {
            val v = StreamPicker.video(pi, currentQn, currentCodec)?.urls?.size ?: 1
            val a = StreamPicker.playableAudios(pi).firstOrNull { it.id == currentAudio }?.urls?.size ?: 1
            maxOf(v, a, 1)
        } else {
            pi.durl.maxOfOrNull { it.urls.size } ?: 1
        }
    }

    private fun metadataItem(url: String): MediaItem {
        val t = target
        val meta = MediaMetadata.Builder()
            .setTitle(t?.title)
            .apply { t?.cover?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(BiliClient.resolve(it))) } }
            .build()
        return MediaItem.Builder().setUri(url).setMediaMetadata(meta).build()
    }

    private fun buildSource(pi: PlayInfo): MediaSource? {
        sourceAudioOnly = false
        localMedia?.let { return it.source(context, ::metadataItem) }
        if (pi.isDash) {
            val v = StreamPicker.video(pi, currentQn, currentCodec) ?: return null
            currentQn = v.id
            currentCodec = v.codecId
            if (v.width > 0 && v.height > 0 && !firstFrameRendered) videoAspect = v.width.toFloat() / v.height
            val vUrls = com.gpo.pilipala.feature.player.cdn.CdnRouter.orderUrls(v.urls)
            // No address at all (a malformed answer): a load error, not `x % 0`.
            if (vUrls.isEmpty()) return null
            val vUrl = vUrls[urlAttempt % vUrls.size]
            val videoSource = progressiveFactory.createMediaSource(metadataItem(vUrl))
            val a = (StreamPicker.playableAudios(pi).firstOrNull { it.id == currentAudio }
                ?: StreamPicker.audio(pi, AppSettings.defaultAudioQuality.value))?.takeIf { it.urls.isNotEmpty() }
            com.gpo.pilipala.feature.player.cdn.CdnRouter.beginSession(
                videoUrls = if (audioOnly) a?.urls.orEmpty() else v.urls,
                audioUrls = if (audioOnly) emptyList() else a?.urls.orEmpty(),
                requiredBps = if (audioOnly) (a?.bandwidth ?: 0) else v.bandwidth,
                bufferedAheadMs = { (bufferedMs - positionMs).coerceAtLeast(0) },
            )
            cdnSession = com.gpo.pilipala.feature.player.cdn.CdnRouter.sessionId()
            if (a == null) return videoSource
            currentAudio = a.id
            val aUrls = com.gpo.pilipala.feature.player.cdn.CdnRouter.orderUrls(a.urls)
            val aUrl = aUrls[urlAttempt % aUrls.size]
            // 仅听声音: the audio stream alone (nothing to download or decode for the picture).
            if (audioOnly) {
                sourceAudioOnly = true
                return progressiveFactory.createMediaSource(metadataItem(aUrl))
            }
            val audioSource = progressiveFactory.createMediaSource(MediaItem.fromUri(aUrl))
            return MergingMediaSource(false, true, videoSource, audioSource)
        }
        if (pi.durl.isEmpty() || pi.durl.any { it.urls.isEmpty() }) return null
        if (pi.durl.size == 1) {
            val seg = pi.durl.first()
            return progressiveFactory.createMediaSource(metadataItem(seg.urls[urlAttempt % seg.urls.size]))
        }
        val builder = ConcatenatingMediaSource2.Builder()
        pi.durl.forEachIndexed { i, seg ->
            val url = seg.urls[urlAttempt % seg.urls.size]
            val item = if (i == 0) metadataItem(url) else MediaItem.fromUri(url)
            builder.add(progressiveFactory.createMediaSource(item), seg.lengthMs.coerceAtLeast(1L))
        }
        return builder.build()
    }

    private fun tick() {
        val p = exo ?: return
        val pos = p.currentPosition.coerceAtLeast(0L)
        if (pos != positionMs) {
            positionMs = pos
            mirror?.positionMs = pos
        }
        bufferedMs = p.bufferedPosition.coerceAtLeast(0L)
        val d = p.duration
        val dur = if (d != C.TIME_UNSET && d > 0) d else info?.timelengthMs ?: 0L
        if (dur > 0 && dur != durationMs) {
            durationMs = dur
            mirror?.durationMs = dur
        }
    }

    private fun onPlayingChanged(playing: Boolean) {
        heartbeatJob?.cancel()
        if (playing) {
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(15_000)
                    heartbeat(exactPositionMs() / 1000)
                }
            }
        } else if (!ended) {
            heartbeat(exactPositionMs() / 1000)
        }
    }

    private fun heartbeat(playedSeconds: Long, force: Boolean = false) {
        if (preview) return
        val t = target ?: return
        if (!force && phase !is PlayerPhase.Ready) return
        if (!BiliClient.isLoggedIn || AppSettings.pauseHistory.value) return
        val now = SystemClock.elapsedRealtime()
        if (!force && playedSeconds >= 0 && now - lastHeartbeatAt < 3_000) return
        lastHeartbeatAt = now
        val form = buildMap<String, Any?> {
            if (t.bvid.isNotBlank()) put("bvid", t.bvid)
            if (t.aid > 0) put("aid", t.aid)
            put("cid", t.cid)
            put("played_time", playedSeconds)
            if (t.isPgc) {
                if (t.epId > 0) put("epid", t.epId)
                if (t.seasonId > 0) put("sid", t.seasonId)
                put("type", 4)
            } else {
                put("type", 3)
            }
        }
        playerIoScope.launch {
            runCatching { BiliClient.postForm("/x/click-interface/web/heartbeat", form) }
        }
    }

    private fun errorLabel(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接失败"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "视频地址失效"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "解码失败"
        else -> "播放失败"
    }
}

/**
 * Forwards every callback, swapping in our metadata and transport commands. Written out
 * by hand: Kotlin's `by` delegation skips Java default methods, which is all of [Player.Listener].
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class MetadataOverrideListener(
    private val d: Player.Listener,
    private val metadata: () -> MediaMetadata,
    private val commands: (Player.Commands) -> Player.Commands,
) : Player.Listener {
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = d.onMediaMetadataChanged(metadata())
    override fun onEvents(player: Player, events: Player.Events) = d.onEvents(player, events)
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = d.onTimelineChanged(timeline, reason)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = d.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = d.onTracksChanged(tracks)
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = d.onPlaylistMetadataChanged(mediaMetadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = d.onIsLoadingChanged(isLoading)
    override fun onLoadingChanged(isLoading: Boolean) = d.onLoadingChanged(isLoading)
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = d.onAvailableCommandsChanged(commands(availableCommands))
    override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) =
        d.onTrackSelectionParametersChanged(parameters)
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = d.onPlayerStateChanged(playWhenReady, playbackState)
    override fun onPlaybackStateChanged(playbackState: Int) = d.onPlaybackStateChanged(playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = d.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
        d.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = d.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = d.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = d.onShuffleModeEnabledChanged(shuffleModeEnabled)
    override fun onPlayerError(error: PlaybackException) = d.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = d.onPlayerErrorChanged(error)
    override fun onPositionDiscontinuity(reason: Int) = d.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        d.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) =
        d.onPlaybackParametersChanged(playbackParameters)
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = d.onSeekBackIncrementChanged(seekBackIncrementMs)
    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = d.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
        d.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = d.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = d.onAudioAttributesChanged(audioAttributes)
    override fun onVolumeChanged(volume: Float) = d.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = d.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    override fun onDeviceInfoChanged(deviceInfo: androidx.media3.common.DeviceInfo) = d.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = d.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: VideoSize) = d.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = d.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = d.onRenderedFirstFrame()
    override fun onCues(cues: List<androidx.media3.common.text.Cue>) = d.onCues(cues)
    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) = d.onCues(cueGroup)
    override fun onMetadata(metadata: androidx.media3.common.Metadata) = d.onMetadata(metadata)
}
