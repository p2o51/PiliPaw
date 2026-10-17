package com.gpo.pilipala.feature.player

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
private val playerIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
internal class PiliPlayerController(private val context: Context) : BiliPlayerState(), RememberObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

    /** width / height of the picture. */
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set
    var speed by mutableFloatStateOf(AppSettings.defaultSpeed.value)
        private set
    var boosting by mutableStateOf(false)
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

    /** 仅听声音: only the DASH audio stream is loaded (video track off for progressive streams). */
    var audioOnly by mutableStateOf(false)
        private set

    private val dataSourceFactory by lazy {
        OkHttpDataSource.Factory(BiliClient.http).setDefaultRequestProperties(BiliClient.mediaHeaders())
    }
    private val progressiveFactory by lazy { ProgressiveMediaSource.Factory(dataSourceFactory) }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            mirror?.isPlaying = playing
            onPlayingChanged(playing)
            if (playing && !hostWanted) {
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
        if (cur != null && cur.cid != t.cid) {
            ResumeMemory.put(cur.cid, positionMs)
            heartbeat(if (ended) -1 else exactPositionMs() / 1000, force = true)
        }
        val keepPosition = if (force && !fresh && cur?.cid == t.cid) positionMs else -1L
        heartbeatJob?.cancel()
        phase = PlayerPhase.Loading
        target = t
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
                val pi = PlayUrlApi.fetch(t, wish)
                withContext(Dispatchers.Default) { CodecSupport.warm(pi) }
                info = pi
                currentQn = StreamPicker.videoQn(pi, wish)
                if (!pi.isDash) currentQn = pi.quality
                currentCodec = Qualities.codecIdForSetting(AppSettings.preferredCodec.value)
                currentAudio = StreamPicker.audio(pi, AppSettings.defaultAudioQuality.value)?.id ?: 0
                val duration = pi.timelengthMs
                val remembered = ResumeMemory.get(t.cid)
                // Queue items play like tracks: from the top unless the page asks for a position.
                val queued = fresh || queue?.items?.any { it.bvid == t.bvid && t.bvid.isNotEmpty() } == true
                val start = when {
                    keepPosition >= 0 -> keepPosition
                    t.startPositionMs > 0 -> t.startPositionMs
                    queued -> 0L
                    remembered > 0 -> remembered
                    pi.lastPlayTimeMs > 0 && (duration <= 0 || pi.lastPlayTimeMs < duration - 5_000) -> pi.lastPlayTimeMs
                    else -> 0L
                }
                if (duration > 0) durationMs = duration
                prepare(start, playWhenReady = force || fresh || AppSettings.autoPlay.value)
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
                runCatching { SubtitleApi.tracks(t) }.onSuccess { subtitles = it }
            }
        }
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

    fun beginBoost() {
        if (boosting) return
        boosting = true
        exo?.setPlaybackSpeed(3f)
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
        refreshSessionControls()
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
        applyVideoTrack()
        // The picture comes back (or goes) with the next rendered frame; the cover covers the gap.
        firstFrameRendered = false
        if (exo != null && info?.isDash == true && phase !is PlayerPhase.Error) reprepare()
    }

    private fun applyVideoTrack() {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, backgrounded || audioOnly)
            .build()
    }

    private fun sessionButtons(): List<CommandButton> =
        queue?.let { PlaybackService.queueButtons(it.mode.value) } ?: PlaybackService.mediaButtons

    /** Previous / next appear or go, and the mode button follows the queue's mode. */
    private fun refreshSessionControls() {
        sessionPlayer?.notifyCommandsChanged()
        session?.setMediaButtonPreferences(sessionButtons())
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
        val track = subtitles.getOrNull(index) ?: return
        subtitleJob = scope.launch {
            runCatching { SubtitleApi.lines(track) }.onSuccess { subtitleLines = it }
        }
    }

    /** Background playback: drop the video track while the window is not visible. */
    fun setBackground(bg: Boolean) {
        if (bg == backgrounded) return
        backgrounded = bg
        applyVideoTrack()
    }

    override fun release() {
        target?.let { t ->
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
        release()
        scope.cancel()
    }

    override fun onAbandoned() {
        release()
        scope.cancel()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun ensurePlayer(): ExoPlayer {
        exo?.let { return it }
        val p = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(listener)
        exo = p
        player = p
        applyRepeat()
        applyVideoTrack()
        val sp = SessionPlayer(p)
        session = runCatching {
            MediaSession.Builder(context, sp)
                .setId("pili-player-${sessionIds.incrementAndGet()}")
                .setCallback(PiliSessionCallback(buttons = ::sessionButtons, onPlayMode = { queue?.cycleMode() }))
                .setMediaButtonPreferences(sessionButtons())
                .setBitmapLoader(CoilBitmapLoader(context))
                .apply { target?.let { setSessionActivity(PlaybackService.sessionActivity(context, it)) } }
                .build()
        }.getOrNull()
        sessionPlayer = sp.takeIf { session != null }
        syncBackgroundHost()
        return p
    }

    /** Hands the session to [PlaybackService] (notification + foreground) while background play wants it. */
    private fun syncBackgroundHost() {
        val s = session ?: return
        val want = hostWanted && AppSettings.backgroundPlay.value
        if (want == hosted) return
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
     * Previous / next exist only while a queue is attached and step through it; a single
     * video hides them so the system shows ±10s.
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

        override fun hasNextMediaItem(): Boolean = queue != null || super.hasNextMediaItem()

        override fun hasPreviousMediaItem(): Boolean = queue != null || super.hasPreviousMediaItem()

        override fun seekToNext() = if (queue != null) skipQueue(forward = true) else super.seekToNext()

        override fun seekToNextMediaItem() = if (queue != null) skipQueue(forward = true) else super.seekToNextMediaItem()

        override fun seekToPrevious() = if (queue != null) skipQueue(forward = false) else super.seekToPrevious()

        override fun seekToPreviousMediaItem() {
            val q = queue
            if (q != null) playQueueIndex(q.previousIndex()) else super.seekToPreviousMediaItem()
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

    private fun sessionCommands(base: Player.Commands): Player.Commands {
        val skip = intArrayOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )
        val b = base.buildUpon()
        if (queue != null) b.addAll(*skip) else b.removeAll(*skip)
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
        p.setPlaybackSpeed(if (boosting) 3f else speed)
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
        if (pi.isDash) {
            val v = StreamPicker.video(pi, currentQn, currentCodec) ?: return null
            currentQn = v.id
            currentCodec = v.codecId
            if (v.width > 0 && v.height > 0 && !firstFrameRendered) videoAspect = v.width.toFloat() / v.height
            val vUrl = v.urls[urlAttempt % v.urls.size]
            val videoSource = progressiveFactory.createMediaSource(metadataItem(vUrl))
            val a = StreamPicker.playableAudios(pi).firstOrNull { it.id == currentAudio }
                ?: StreamPicker.audio(pi, AppSettings.defaultAudioQuality.value)
                ?: return videoSource
            currentAudio = a.id
            val aUrl = a.urls[urlAttempt % a.urls.size]
            // 仅听声音: the audio stream alone (nothing to download or decode for the picture).
            if (audioOnly) return progressiveFactory.createMediaSource(metadataItem(aUrl))
            val audioSource = progressiveFactory.createMediaSource(MediaItem.fromUri(aUrl))
            return MergingMediaSource(false, true, videoSource, audioSource)
        }
        if (pi.durl.isEmpty()) return null
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
