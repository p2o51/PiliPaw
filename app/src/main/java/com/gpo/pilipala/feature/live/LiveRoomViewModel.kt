package com.gpo.pilipala.feature.live

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.path
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal val LiveDefaultQn = AppSettings.register("live_default_qn", 10000)

/** 直播默认画质 choices, best first (pilipala's LiveQuality list). */
internal val LiveQnChoices: List<Pair<Int, String>> = listOf(30000, 20000, 10000, 400, 250, 150, 80).map { it to LiveQnNames.getValue(it) }

internal class LiveRoomViewModel(
    val roomId: Long,
    private val appContext: Context,
) : ViewModel() {

    // ── Room ──────────────────────────────────────────────────────────────
    var info by mutableStateOf<LiveRoomInfo?>(null)
        private set
    var infoError by mutableStateOf<String?>(null)
        private set
    var loadingInfo by mutableStateOf(true)
        private set

    // ── Stream ────────────────────────────────────────────────────────────
    var qualities by mutableStateOf<List<LiveQn>>(emptyList())
        private set
    var currentQn by mutableStateOf(0)
        private set
    var streamError by mutableStateOf<String?>(null)
        private set
    var loadingStream by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var isPortraitStream by mutableStateOf(false)
        private set

    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0

    val player: ExoPlayer by lazy { buildPlayer() }
    private var playerBuilt = false
    private var userPaused = false

    // Background: the session is handed to LivePlaybackService while hosted.
    private var session: MediaSession? = null
    private var hosted = false
    /** The live page is the one on screen (not covered by another page). */
    private var visible = true

    // ── Chat ──────────────────────────────────────────────────────────────
    val messages = mutableStateListOf<LiveChat>()
    private val _incoming = MutableSharedFlow<List<LiveChat>>(extraBufferCapacity = 64)
    /** New danmaku for the video overlay. */
    val incoming: SharedFlow<List<LiveChat>> = _incoming
    var tip by mutableStateOf("")
        private set
    var tipVisible by mutableStateOf(false)
        private set
    var danmakuOn by mutableStateOf(AppSettings.danmakuEnabled.value)
    /** The room's `[code]` emotes (logged in only), for messages that don't carry their own. */
    var roomEmotes by mutableStateOf<Map<String, LiveEmote>>(emptyMap())
        private set
    private var socket: LiveDanmakuSocket? = null
    private var pollJob: Job? = null
    private var tipJob: Job? = null
    private val seenHistory = HashSet<String>()

    // ── Relation ──────────────────────────────────────────────────────────
    var following by mutableStateOf(false)
        private set
    var followBusy by mutableStateOf(false)
        private set

    var sending by mutableStateOf(false)
        private set

    init {
        loadInfo()
        startChat()
        loadRoomEmotes()
        viewModelScope.launch { AppSettings.backgroundPlay.state.collect { syncBackgroundHost() } }
    }

    private fun loadRoomEmotes() {
        if (!BiliClient.isLoggedIn) return
        viewModelScope.launch {
            runCatching {
                BiliClient.getData(
                    "${BiliHosts.LIVE}/xlive/web-ucenter/v2/emoticon/GetEmoticons",
                    mapOf("platform" to "pc", "room_id" to roomId),
                ).asObj
            }.onSuccess { roomEmotes = LiveMessages.roomEmotes(it) }
                .onFailure { Log.w(TAG, "emoticons", it) }
        }
    }

    fun loadInfo() {
        viewModelScope.launch {
            loadingInfo = true
            infoError = null
            runCatching { fetchInfo() }
                .onSuccess { room ->
                    info = room
                    if (room.liveStatus != 0) loadStream(LiveDefaultQn.value) else streamError = null
                    if (BiliClient.isLoggedIn && room.uid > 0) {
                        loadRelation(room.uid)
                        roomEntry()
                    }
                }
                .onFailure { infoError = it.friendlyMessage() }
            loadingInfo = false
        }
    }

    private suspend fun fetchInfo(): LiveRoomInfo = coroutineScope {
        val extra = async { runCatching { fetchExtra() }.getOrNull() }
        val base = fetchBase()
        val e = extra.await() ?: return@coroutineScope base
        base.copy(
            description = e.description.ifEmpty { base.description },
            tags = e.tags,
            areaName = base.areaName.ifEmpty { e.areaName },
            parentAreaName = base.parentAreaName.ifEmpty { e.parentAreaName },
        )
    }

    /** Room/get_info: the only public endpoint that still has tags. */
    private suspend fun fetchExtra(): LiveRoomInfo {
        val d = BiliClient.getData("${BiliHosts.LIVE}/room/v1/Room/get_info", mapOf("room_id" to roomId)).asObj
        return LiveRoomInfo(
            description = Fmt.stripTags(d.str("description")).trim(),
            tags = d.str("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            areaName = d.str("area_name"),
            parentAreaName = d.str("parent_area_name"),
        )
    }

    private suspend fun fetchBase(): LiveRoomInfo {
        val h5 = runCatching {
            BiliClient.getData(
                "${BiliHosts.LIVE}/xlive/web-room/v1/index/getH5InfoByRoom",
                mapOf("room_id" to roomId),
            ).asObj
        }.getOrNull()
        if (h5 != null) {
            val room = h5.obj("room_info")
            val anchor = h5.obj("anchor_info")
            return LiveRoomInfo(
                roomId = room.long("room_id", roomId),
                uid = room.long("uid"),
                title = room.str("title"),
                cover = room.str("cover"),
                background = room.str("app_background").ifEmpty { room.str("background") },
                liveStatus = room.int("live_status"),
                liveStartTime = room.long("live_start_time"),
                online = room.long("online"),
                watchedText = h5.obj("watched_show").str("text_large"),
                areaName = room.str("area_name"),
                parentAreaName = room.str("parent_area_name"),
                anchorName = anchor.obj("base_info").str("uname"),
                anchorFace = anchor.obj("base_info").str("face"),
                attention = anchor.obj("relation_info").long("attention"),
                description = Fmt.stripTags(room.str("description")).trim(),
                news = h5.obj("news_info").str("content").trim(),
            )
        }
        val d = BiliClient.getData(
            "${BiliHosts.LIVE}/xlive/web-room/v1/index/getInfoByRoom",
            mapOf("room_id" to roomId),
            wbi = true,
        ).asObj
        val room = d.obj("room_info")
        val anchor = d.obj("anchor_info")
        return LiveRoomInfo(
            roomId = room.long("room_id", roomId),
            uid = room.long("uid"),
            title = room.str("title"),
            cover = room.str("cover"),
            background = room.str("background"),
            liveStatus = room.int("live_status"),
            liveStartTime = room.long("live_start_time"),
            online = room.long("online"),
            watchedText = d.obj("watched_show").str("text_large"),
            areaName = room.str("area_name"),
            parentAreaName = room.str("parent_area_name"),
            anchorName = anchor.obj("base_info").str("uname"),
            anchorFace = anchor.obj("base_info").str("face"),
            attention = anchor.obj("relation_info").long("attention"),
            description = Fmt.stripTags(room.str("description")).trim(),
            news = d.obj("news_info").str("content").trim(),
        )
    }

    // ── Stream ────────────────────────────────────────────────────────────

    fun loadStream(qn: Int = currentQn.takeIf { it > 0 } ?: LiveDefaultQn.value) {
        viewModelScope.launch {
            loadingStream = true
            streamError = null
            runCatching {
                BiliClient.getData(
                    "${BiliHosts.LIVE}/xlive/web-room/v2/index/getRoomPlayInfo",
                    mapOf(
                        "room_id" to roomId,
                        "protocol" to "0,1",
                        "format" to "0,1,2",
                        "codec" to "0,1",
                        "qn" to qn,
                        "platform" to "web",
                        "ptype" to 8,
                        "dolby" to 5,
                        "panorama" to 1,
                    ),
                ).asObj
            }.onSuccess { data ->
                isPortraitStream = data.bool("is_portrait")
                val playurl = data.path("playurl_info.playurl").asObj
                if (playurl == null) {
                    streamError = "未开播"
                    return@onSuccess
                }
                val names = playurl.arr("g_qn_desc").objects().associate { it.int("qn") to it.str("desc") }
                val streams = playurl.arr("stream").objects()
                candidates = pickUrls(streams)
                val codec = firstCodec(streams)
                currentQn = codec.int("current_qn")
                qualities = codec.arr("accept_qn").map { it.toString().toIntOrNull() ?: 0 }
                    .filter { it > 0 }
                    .map { LiveQn(it, names[it] ?: LiveQnNames[it] ?: it.toString()) }
                candidateIndex = 0
                if (candidates.isEmpty()) streamError = "没有可用的播放地址" else play(candidates.first())
            }.onFailure { streamError = it.friendlyMessage() }
            loadingStream = false
        }
    }

    fun changeQn(qn: Int) {
        if (qn == currentQn) return
        AppSettings.set(LiveDefaultQn, qn)
        loadStream(qn)
    }

    private fun firstCodec(streams: List<JsonObject>): JsonObject? =
        streams.firstOrNull()?.arr("format")?.objects()?.firstOrNull()?.arr("codec")?.objects()?.firstOrNull()

    /** HLS fmp4 → HLS ts → FLV, AVC before HEVC; every host of each as a fallback. */
    private fun pickUrls(streams: List<JsonObject>): List<String> {
        data class Cand(val score: Int, val urls: List<String>)
        val list = mutableListOf<Cand>()
        streams.forEach { s ->
            val proto = s.str("protocol_name")
            s.arr("format").objects().forEach { f ->
                val format = f.str("format_name")
                f.arr("codec").objects().forEach { c ->
                    val codecName = c.str("codec_name")
                    val base = c.str("base_url")
                    val urls = c.arr("url_info").objects().map { it.str("host") + base + it.str("extra") }
                    val score = when {
                        proto == "http_hls" && format == "fmp4" -> 0
                        proto == "http_hls" && format == "ts" -> 10
                        proto == "http_stream" -> 20
                        else -> 30
                    } + if (codecName == "avc") 0 else 5
                    if (urls.isNotEmpty()) list += Cand(score, urls)
                }
            }
        }
        return list.sortedBy { it.score }.flatMap { it.urls }
    }

    private fun play(url: String) {
        userPaused = false
        val dsf = OkHttpDataSource.Factory(BiliClient.http)
            .setDefaultRequestProperties(BiliClient.mediaHeaders())
        val room = info
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(room?.title?.ifEmpty { null })
                    .setArtist(room?.anchorName?.ifEmpty { null })
                    .apply { room?.cover?.ifEmpty { null }?.let { setArtworkUri(Uri.parse(Images.normalize(it))) } }
                    .build(),
            )
            .build()
        val source = if (url.contains(".m3u8")) {
            HlsMediaSource.Factory(dsf).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dsf).createMediaSource(item)
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    private fun buildPlayer(): ExoPlayer {
        playerBuilt = true
        return ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    this@LiveRoomViewModel.isPlaying = playing
                    if (playing) syncBackgroundHost()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    isBuffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) streamError = null
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "player error", error)
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        seekToDefaultPosition()
                        prepare()
                        return
                    }
                    candidateIndex++
                    if (candidateIndex < candidates.size) {
                        play(candidates[candidateIndex])
                    } else {
                        streamError = "播放失败"
                    }
                }
            })
        }
    }

    fun togglePlay() {
        if (player.isPlaying) userPause() else userPlay()
    }

    private fun userPause() {
        userPaused = true
        player.pause()
    }

    private fun userPlay() {
        userPaused = false
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            loadStream()
        } else {
            // Live: jump back to the edge after a pause.
            player.seekToDefaultPosition()
            player.play()
        }
    }

    /** The page is covered by another one: stop, and let go of the notification. */
    fun pauseForBackground() {
        if (!playerBuilt) return
        player.pause()
        visible = false
        syncBackgroundHost()
    }

    /** The app went to the background: keep the audio going when background play is on. */
    fun onAppStopped() {
        if (!playerBuilt) return
        if (!(AppSettings.backgroundPlay.value && hosted && player.isPlaying)) player.pause()
    }

    fun resumeFromBackground() {
        visible = true
        if (playerBuilt && !userPaused && info?.liveStatus != 0 && streamError == null) {
            if (!player.isPlaying) {
                player.seekToDefaultPosition()
                player.play()
            }
        }
        syncBackgroundHost()
    }

    // ── Background (MediaSession + LivePlaybackService) ───────────────────

    private fun ensureSession(): MediaSession? {
        session?.let { return it }
        val sp = LiveSessionPlayer(player, onPlay = ::userPlay, onPause = ::userPause)
        session = runCatching {
            MediaSession.Builder(appContext, sp)
                .setId("pili-live-$roomId-${System.nanoTime()}")
                .setCallback(LiveSessionCallback)
                .setBitmapLoader(LiveBitmapLoader(appContext))
                .setSessionActivity(LivePlaybackService.sessionActivity(appContext))
                .build()
        }.onFailure { Log.w(TAG, "session", it) }.getOrNull()
        return session
    }

    /** Hands the session to the service while playing on a visible page with background play on. */
    private fun syncBackgroundHost() {
        if (!playerBuilt) return
        val want = AppSettings.backgroundPlay.value && visible && (player.isPlaying || hosted)
        if (want == hosted) return
        val s = ensureSession() ?: return
        hosted = want
        if (want) LivePlaybackService.attach(appContext, s) else LivePlaybackService.detach(s)
    }

    fun refresh() {
        loadInfo()
    }

    // ── Chat ──────────────────────────────────────────────────────────────

    private fun startChat() {
        viewModelScope.launch {
            loadHistory()
            val conf = runCatching {
                BiliClient.getData(
                    "${BiliHosts.LIVE}/xlive/web-room/v1/index/getDanmuInfo",
                    mapOf("id" to roomId, "type" to 0),
                    wbi = true,
                ).asObj
            }.getOrNull()
            val host = conf?.arr("host_list")?.objects()?.firstOrNull()?.str("host").orEmpty()
            val token = conf?.str("token").orEmpty()
            if (token.isEmpty()) {
                startPolling()
                return@launch
            }
            socket = LiveDanmakuSocket(
                scope = viewModelScope,
                roomId = roomId,
                onChat = { list -> viewModelScope.launch { appendChats(list) } },
                onTip = { t -> viewModelScope.launch { showTip(t) } },
                onWatched = { w -> viewModelScope.launch { info = info?.copy(watchedText = w) } },
                onState = { connected ->
                    viewModelScope.launch {
                        if (connected) pollJob?.cancel() else startPolling()
                    }
                },
            ).also { it.connect(host.ifEmpty { "broadcastlv.chat.bilibili.com" }, token) }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(4_000)
                loadHistory()
            }
        }
    }

    private suspend fun loadHistory() {
        val data = runCatching {
            BiliClient.getData(
                "${BiliHosts.LIVE}/xlive/web-room/v1/dM/gethistory",
                mapOf("roomid" to roomId, "room_id" to roomId),
            ).asObj
        }.getOrNull() ?: return
        val fresh = data.arr("room").objects().mapNotNull { o ->
            val key = "h${o.long("uid")}-${o.str("timeline")}-${o.str("text").hashCode()}"
            if (!seenHistory.add(key)) return@mapNotNull null
            LiveMessages.history(o, key)
        }
        if (fresh.isNotEmpty()) appendChats(fresh)
    }

    private fun appendChats(list: List<LiveChat>) {
        val keys = messages.mapTo(HashSet()) { it.key }
        val fresh = ArrayList<LiveChat>(list.size)
        for (c in list) {
            if (!keys.add(c.key)) continue
            if (c.kind == LiveChatKind.Gift && mergeGift(c, fresh)) continue
            fresh += c
        }
        messages.addAll(fresh)
        if (messages.size > 400) messages.removeRange(0, messages.size - 300)
        _incoming.tryEmit(fresh.filter { it.kind == LiveChatKind.Chat && it.sticker == null })
    }

    /** Folds a gift into a recent row of the same combo (same sender + gift), bumping its count. */
    private fun mergeGift(g: LiveChat, pending: MutableList<LiveChat>): Boolean {
        fun same(o: LiveChat) = o.kind == LiveChatKind.Gift && o.comboKey == g.comboKey && o.giftName == g.giftName
        val p = pending.indexOfLast(::same)
        if (p >= 0) {
            pending[p] = pending[p].copy(giftNum = pending[p].giftNum + g.giftNum)
            return true
        }
        val from = (messages.size - 20).coerceAtLeast(0)
        for (i in messages.lastIndex downTo from) {
            if (same(messages[i])) {
                messages[i] = messages[i].copy(giftNum = messages[i].giftNum + g.giftNum)
                return true
            }
        }
        return false
    }

    private fun showTip(t: String) {
        tip = t
        tipVisible = true
        tipJob?.cancel()
        tipJob = viewModelScope.launch {
            delay(3_000)
            tipVisible = false
        }
    }

    suspend fun send(text: String): String? {
        if (text.isBlank()) return null
        sending = true
        return try {
            val res = BiliClient.postForm(
                "${BiliHosts.LIVE}/msg/send",
                mapOf(
                    "bubble" to 0,
                    "msg" to text,
                    "color" to 16777215,
                    "mode" to 1,
                    "room_type" to 0,
                    "jumpfrom" to 71001,
                    "reply_mid" to 0,
                    "reply_attr" to 0,
                    "replay_dmid" to "",
                    "statistics" to """{"appId":100,"platform":5}""",
                    "fontsize" to 25,
                    "rnd" to System.currentTimeMillis() / 1000,
                    "roomid" to roomId,
                ),
            )
            val code = res.int("code")
            if (code != 0) {
                res.str("message").ifEmpty { res.str("msg") }.ifEmpty { "发送失败" }
            } else {
                if (socket == null || pollJob?.isActive == true) {
                    val me = Account.user.value
                    appendChats(
                        listOf(
                            LiveChat(
                                key = "me${System.nanoTime()}",
                                uid = BiliClient.mid,
                                name = me?.name ?: "我",
                                text = text,
                                face = me?.face.orEmpty(),
                            ),
                        ),
                    )
                }
                null
            }
        } catch (e: Exception) {
            e.friendlyMessage()
        } finally {
            sending = false
        }
    }

    // ── Relation ──────────────────────────────────────────────────────────

    private fun loadRelation(uid: Long) {
        viewModelScope.launch {
            runCatching { BiliClient.getData("/x/relation", mapOf("fid" to uid)).asObj }
                .onSuccess { following = it.int("attribute") in setOf(1, 2, 6) }
        }
    }

    suspend fun toggleFollow(): String? {
        val uid = info?.uid ?: return null
        followBusy = true
        val target = !following
        return try {
            BiliClient.postData(
                "/x/relation/modify",
                mapOf("fid" to uid, "act" to if (target) 1 else 2, "re_src" to 11),
            )
            following = target
            null
        } catch (e: Exception) {
            e.friendlyMessage()
        } finally {
            followBusy = false
        }
    }

    private fun roomEntry() {
        viewModelScope.launch {
            runCatching {
                BiliClient.postForm(
                    "${BiliHosts.LIVE}/xlive/web-room/v1/index/roomEntryAction",
                    mapOf("room_id" to roomId, "platform" to "pc", "visit_id" to ""),
                )
            }
        }
    }

    override fun onCleared() {
        socket?.close()
        pollJob?.cancel()
        session?.let {
            if (hosted) LivePlaybackService.detach(it)
            it.release()
        }
        hosted = false
        session = null
        if (playerBuilt) player.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "LiveRoomVM"
    }
}
