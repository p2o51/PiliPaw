package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.gpo.pilipala.feature.sources.PlayerHost
import com.gpo.pilipala.feature.sources.PlayerSlot
import com.gpo.pilipala.yt.core.extractor.VideoItem
import com.gpo.pilipala.yt.core.extractor.toVideo
import com.gpo.pilipala.yt.core.extractor.Yt
import com.gpo.pilipala.yt.core.settings.AppSettings
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.ui.components.friendlyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo
import androidx.media3.common.Player as Media3Player

/**
 * The one player of the app. It is not tied to a page: the overlay ([PlayerOverlayHost]) shows
 * whatever [session] holds, as a full player or a mini bar, over any page. One ExoPlayer for
 * the process; a new video replaces the old one (a queue comes later).
 */
object Player {
    private lateinit var app: Context
    private val scope = MainScope()
    private var loadJob: Job? = null
    private var qualityJob: Job? = null

    /** What the overlay shows. Null: no player on screen. */
    class Session(
        val url: String,
        /** The card that was tapped: title, thumbnail and channel draw at once, before [info] lands. */
        val preview: VideoItem?,
        val info: StreamInfo? = null,
        val error: String? = null,
    ) {
        val title: String get() = info?.name ?: preview?.title.orEmpty()
        val thumbnail: String get() = info?.thumbnailUrl ?: preview?.thumbnail.orEmpty()
        val channelName: String get() = info?.uploaderName ?: preview?.channelName.orEmpty()
    }

    var session by mutableStateOf<Session?>(null)
        private set

    /**
     * The overlay's target: full player (true) or mini bar (false). Opening puts YouTube in
     * front (a docked Bilibili video goes); collapsing docks it in the mini bar (PlayerHost.kt).
     */
    private var expandedState by mutableStateOf(false)
    var expanded: Boolean
        get() = expandedState
        set(value) {
            if (value == expandedState) return
            expandedState = value
            if (session == null) return
            if (value) PlayerHost.opened(PlayerSlot.YouTube)
            else if (!PlayerHost.dock(PlayerSlot.YouTube)) close()
        }

    /** The overlay is open or still on its way down; the mini bar shows the player only once it is not. */
    var overlayUp by mutableStateOf(false)
        internal set

    /** The picture fills the window ([FullscreenPlayer]); only while expanded. */
    var fullscreen by mutableStateOf(false)

    /** The video's display aspect (width / height, pixel ratio applied); 16:9 until the first frame says otherwise. */
    var videoAspect by mutableFloatStateOf(16f / 9f)
        private set

    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    /** The chosen speed (倍速); a hold boosts above it for as long as the finger stays. */
    var speed by mutableFloatStateOf(1f)
        private set
    var boosting by mutableStateOf(false)
        private set

    /** Heights the video offers (descending), the one playing, the subtitle tracks and the one on (-1: off). */
    var heights by mutableStateOf<List<Int>>(emptyList())
        private set
    var currentHeight by mutableIntStateOf(0)
        private set
    var subtitleTracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
        private set
    var subtitleIndex by mutableIntStateOf(-1)
        private set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set
    private var subtitleJob: Job? = null

    /** Re-resolves the video at [height] (0 = highest) and picks up where it was. */
    fun changeQuality(height: Int) {
        val s = session ?: return
        val info = s.info ?: return
        val position = exo.currentPosition
        val resume = exo.playWhenReady
        qualityJob?.cancel()
        qualityJob = scope.launch {
            try {
                val resolved = withContext(Dispatchers.Default) { StreamResolver.resolve(info, upstream, height) }
                if (session?.url != s.url) return@launch
                exo.setMediaSource(resolved.source, position)
                exo.prepare()
                exo.playWhenReady = resume
                currentHeight = resolved.video?.height ?: 0
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    /**
     * 仅听声音: the video track is switched off (nothing of it is loaded: listening costs only
     * the audio's data) and the overlay shows the listen page. While on with subtitles off, a
     * track is borrowed for the lyrics and handed back when the picture returns.
     */
    var audioOnly by mutableStateOf(false)
        private set
    private var borrowedSubtitles = false

    /**
     * The video track has drawn a frame since 仅听声音 last changed (显示画面 waits for it, so the
     * cover lands on the picture).
     */
    var pictureReady by mutableStateOf(false)
        private set

    fun changeAudioOnly(on: Boolean) {
        if (on == audioOnly) return
        audioOnly = on
        pictureReady = false
        if (!on) com.gpo.pilipala.feature.listen.ListenLog.videoAsked("YouTube, track on")
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on)
            .build()
        if (on && subtitleIndex < 0 && subtitleTracks.isNotEmpty()) {
            borrowedSubtitles = true
            chooseSubtitle(lyricTrack())
        } else if (!on && borrowedSubtitles) {
            borrowedSubtitles = false
            chooseSubtitle(-1)
        }
    }

    /**
     * The lyrics' track: the words as sung or said, so the video's own language. YouTube's auto
     * captions are always in it: a person-made track in that language first, else the auto one;
     * without auto captions, the app's language, else the first track.
     */
    private fun lyricTrack(): Int {
        val tracks = subtitleTracks
        fun lang(t: SubtitleTrack) = t.stream.languageTag.substringBefore('-').lowercase()
        val auto = tracks.indexOfFirst { it.stream.isAutoGenerated }
        if (auto >= 0) {
            val spoken = lang(tracks[auto])
            val human = tracks.indexOfFirst { !it.stream.isAutoGenerated && lang(it) == spoken }
            return if (human >= 0) human else auto
        }
        val mine = java.util.Locale.getDefault().language
        return tracks.indexOfFirst { lang(it) == mine }.takeIf { it >= 0 } ?: 0
    }

    /** 单曲循环 (the listen page's play-mode key). */
    var loop by mutableStateOf(false)
        private set

    fun changeLoop(on: Boolean) {
        loop = on
        exo.repeatMode = if (on) Media3Player.REPEAT_MODE_ONE else Media3Player.REPEAT_MODE_OFF
    }

    /** 下一个: the first related video (YouTube's own up next), in the same mode. */
    fun playNext() {
        val info = session?.info ?: return
        val next = info.relatedItems.orEmpty().filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>().firstOrNull() ?: return
        play(next.url, next.toVideo())
    }

    val hasNext: Boolean get() = session?.info?.relatedItems.orEmpty().any { it is org.schabi.newpipe.extractor.stream.StreamInfoItem }

    /** Turns subtitle track [index] on (-1: off); the cues load in the background. */
    fun chooseSubtitle(index: Int) {
        subtitleJob?.cancel()
        subtitleIndex = index
        cues = emptyList()
        val track = subtitleTracks.getOrNull(index) ?: return
        subtitleJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { Subtitles.load(track) }.getOrDefault(emptyList()) }
            if (subtitleIndex == index) cues = loaded
        }
    }

    /** The video's chapters and SponsorBlock segments (empty until [session] has its info). */
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set
    var skips by mutableStateOf<List<SkipSegment>>(emptyList())
        private set

    /** The segment just skipped (the bar closes it up; 撤销 goes back into it). */
    var lastSkip by mutableStateOf<SkipSegment?>(null)
        private set
    private val skipped = HashSet<String>()
    private var skipJob: Job? = null

    /**
     * SponsorBlock's auto skip: four times a second, a playhead inside a segment whose category
     * is set to skip (and not skipped before in this video, so 撤销 and seeking back stay put)
     * jumps to its end.
     */
    private fun watchSkips() {
        skipJob?.cancel()
        if (skips.isEmpty()) return
        skipJob = scope.launch {
            while (true) {
                delay(250)
                if (!exo.isPlaying) continue
                val at = exo.currentPosition
                val s = skips.firstOrNull { at >= it.startMs && at < it.endMs - 500 && it.category.skip.value && it.id !in skipped } ?: continue
                skipped += s.id
                exo.seekTo(s.endMs)
                lastSkip = s
            }
        }
    }

    /** 撤销: back to the start of the skipped segment, which then plays. */
    fun undoSkip() {
        val s = lastSkip ?: return
        exo.seekTo(s.startMs)
        lastSkip = null
    }

    private fun resetTracks(info: StreamInfo?) {
        borrowedSubtitles = false
        chapters = Segments.chapters(info)
        skips = Segments.skips(info)
        skipped.clear()
        lastSkip = null
        watchSkips()
        subtitleJob?.cancel()
        subtitleIndex = -1
        cues = emptyList()
        subtitleTracks = Subtitles.tracks(info?.subtitles.orEmpty())
        heights = info?.videoOnlyStreams.orEmpty().filter { it.itagItem != null }.map { it.height }.filter { it > 0 }.distinct().sortedDescending()
        currentHeight = 0
    }

    private var speedChosen = false

    fun changeSpeed(value: Float) {
        speedChosen = true
        speed = value
        if (!boosting) exo.setPlaybackSpeed(value)
    }

    fun beginBoost(rate: Float) {
        boosting = true
        exo.setPlaybackSpeed(rate)
    }

    fun endBoost() {
        if (!boosting) return
        boosting = false
        exo.setPlaybackSpeed(speed)
    }

    val exo: ExoPlayer by lazy {
        ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                addListener(object : Media3Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        this@Player.isPlaying = playing
                        if (playing) PlayerHost.started(PlayerSlot.YouTube)
                        // Paused (or ended): the resume point is exact.
                        if (!playing) savePosition()
                    }
                    override fun onRenderedFirstFrame() {
                        pictureReady = true
                        com.gpo.pilipala.feature.listen.ListenLog.firstFrame()
                    }
                    override fun onVideoSizeChanged(size: VideoSize) {
                        if (size.width > 0 && size.height > 0) videoAspect = size.width * size.pixelWidthHeightRatio / size.height
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        isBuffering = state == Media3Player.STATE_BUFFERING
                        if (state == Media3Player.STATE_READY) durationMs = duration.coerceAtLeast(0)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val s = session ?: return
                        if (error.errorCode in RecoverableErrors && recover(s)) return
                        session = Session(s.url, s.preview, s.info, error = "播放出错 ${error.errorCodeName}")
                    }
                })
            }
    }

    private val upstream by lazy { YoutubeDataSource.upstream(Yt.downloader.client) }

    /**
     * HTTP and network failures (a 403 on an expired or refused URL above all) re-extract the
     * stream and pick up where it was, as PipePipe's PlayerErrorHandler does; twice per video,
     * then the error shows with 重试.
     */
    private val RecoverableErrors = setOf(
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
    )
    private const val MaxRecoveries = 2
    private var recoveries = 0

    private fun recover(s: Session): Boolean {
        if (recoveries >= MaxRecoveries) return false
        recoveries++
        val position = exo.currentPosition
        val resume = exo.playWhenReady
        qualityJob?.cancel()
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val info = Yt.stream(s.url)
                if (session?.url != s.url) return@launch
                session = Session(s.url, s.preview, info)
                val resolved = withContext(Dispatchers.Default) {
                    StreamResolver.resolve(info, upstream, currentHeight.takeIf { it > 0 } ?: AppSettings.preferredHeight.value)
                }
                exo.setMediaSource(resolved.source, position)
                exo.prepare()
                exo.playWhenReady = resume
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (session?.url == s.url) session = Session(s.url, s.preview, session?.info, error = e.friendlyMessage())
            }
        }
        return true
    }

    fun init(context: Context) {
        app = context.applicationContext
        scope.launch { AppSettings.backgroundPlay.state.collect { syncBackgroundHost() } }
        // The history's resume point, kept fresh while playing.
        scope.launch {
            while (true) {
                delay(5_000)
                if (isPlaying) savePosition()
            }
        }
    }

    /**
     * The media session (notification, lock screen, Bluetooth keys). Made with the player and
     * kept for the process; [PlaybackService] borrows it while background play wants it.
     */
    private val mediaSession: MediaSession? by lazy {
        runCatching {
            MediaSession.Builder(app, exo)
                .setId("pipepaw-player")
                .setMediaButtonPreferences(PlaybackService.buttons)
                .setBitmapLoader(CoilBitmapLoader(app))
                .setSessionActivity(PlaybackService.sessionActivity(app))
                .build()
        }.onFailure { android.util.Log.w("PipePaw", "media session", it) }.getOrNull()
    }
    private var hosted = false

    /** Hands the session to [PlaybackService] while a video is open and 后台播放 is on. */
    private fun syncBackgroundHost() {
        if (session == null && !hosted) return
        val s = mediaSession ?: return
        val want = session != null && AppSettings.backgroundPlay.value
        if (want == hosted) return
        hosted = want
        if (want) PlaybackService.attach(app, s) else PlaybackService.detach(s)
    }

    /** Opens [url] in the player, expanded. Tapping the video that is already playing just expands it. */
    fun play(url: String, preview: VideoItem? = null) {
        // 继续播放: a video opened on the open player (a related one, a link) replaces this one in
        // place, and this one goes on the back menu; any other open starts a new watch.
        val trail = com.gpo.pilipala.feature.sources.WatchTrail
        val current = session
        if (expandedState && current != null && current.url != url && com.gpo.pilipala.feature.sources.KeepPlaying.on) {
            trail.push(
                com.gpo.pilipala.feature.sources.TrailEntry(
                    key = current.url,
                    source = com.gpo.pilipala.core.source.VideoSource.YouTube,
                    title = current.title,
                    cover = current.thumbnail,
                    positionMs = exo.currentPosition.coerceAtLeast(0),
                    durationMs = durationMs,
                    target = YtTrailTarget(current.url, current.preview),
                ),
            )
            trail.forget(url)
        } else if (!expandedState) {
            trail.clear()
        }
        expandedState = true
        // Opened from a card: the player grows from its own place, not from where the bar was.
        com.gpo.pilipala.feature.sources.KeepPlaying.openFrom = null
        // One player for the user: a docked Bilibili video (or YouTube's own bar) gives way.
        PlayerHost.opened(PlayerSlot.YouTube)
        if (session?.url == url && session?.error == null) return
        savePosition()
        qualityJob?.cancel()
        loadJob?.cancel()
        exo.stop()
        exo.clearMediaItems()
        durationMs = 0
        recoveries = 0
        resetTracks(null)
        session = Session(url, preview)
        syncBackgroundHost()
        loadJob = scope.launch {
            try {
                val info = Yt.stream(url)
                if (session?.url != url) return@launch
                session = Session(url, preview, info)
                resetTracks(info)
                // Still listening: the next video's lyrics too.
                if (audioOnly && subtitleTracks.isNotEmpty()) {
                    borrowedSubtitles = true
                    chooseSubtitle(lyricTrack())
                }
                Library.watched(videoOf(info, preview))
                val resolved = withContext(Dispatchers.Default) {
                    StreamResolver.resolve(info, upstream, AppSettings.preferredHeight.value)
                }
                currentHeight = resolved.video?.height ?: 0
                // Pick up where it was left (history), unless it was watched to the end.
                exo.setMediaSource(resolved.source, Library.resumeAt(url))
                // The speed picked for the last video carries on (the setting's default before that).
                if (!speedChosen) speed = AppSettings.defaultSpeed.value
                exo.setPlaybackSpeed(speed)
                exo.prepare()
                exo.playWhenReady = AppSettings.autoPlay.value
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (session?.url == url) session = Session(url, preview, session?.info, error = e.friendlyMessage())
            }
        }
    }

    /** The history's row for a video: what the info says, the card's thumbnail as a fallback. */
    private fun videoOf(info: StreamInfo, preview: VideoItem?): VideoItem = VideoItem(
        url = info.url,
        title = info.name.orEmpty(),
        thumbnail = info.thumbnailUrl ?: preview?.thumbnail.orEmpty(),
        channelName = info.uploaderName.orEmpty(),
        channelUrl = info.uploaderUrl.orEmpty(),
        channelAvatar = info.uploaderAvatarUrl.orEmpty(),
        durationSec = info.duration,
        views = info.viewCount,
        uploaded = "",
        uploadedAt = 0,
        isLive = false,
        isShort = false,
        channelVerified = false,
    )

    /** Writes where the current video is to the history (every few seconds, and on leaving it). */
    private fun savePosition() {
        val s = session ?: return
        if (s.info == null || exo.duration <= 0) return
        Library.savePosition(s.url, exo.currentPosition)
    }

    fun retry() {
        val s = session ?: return
        session = null
        play(s.url, s.preview)
    }

    fun toggle() {
        if (exo.isPlaying) exo.pause() else {
            if (exo.playbackState == Media3Player.STATE_ENDED) exo.seekTo(0)
            exo.play()
        }
    }

    /** Mini bar's close: playback stops and the overlay goes away. */
    fun close() {
        com.gpo.pilipala.feature.sources.WatchTrail.clear()
        savePosition()
        qualityJob?.cancel()
        loadJob?.cancel()
        exo.stop()
        exo.clearMediaItems()
        expandedState = false
        fullscreen = false
        changeAudioOnly(false)
        resetTracks(null)
        durationMs = 0
        session = null
        PlayerHost.released(PlayerSlot.YouTube)
        syncBackgroundHost()
    }
}

/** What reopens a YouTube video from the back menu (WatchTrail): its url and card. */
data class YtTrailTarget(val url: String, val preview: VideoItem?)
