package com.gpo.pilipala.feature.live

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.path
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal val LiveDefaultQn = AppSettings.register("live_default_qn", 10000)

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

    private suspend fun fetchInfo(): LiveRoomInfo {
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
        val item = MediaItem.fromUri(url)
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
        return ExoPlayer.Builder(appContext).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    this@LiveRoomViewModel.isPlaying = playing
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

    private var userPaused = false

    fun togglePlay() {
        if (player.isPlaying) {
            userPaused = true
            player.pause()
        } else {
            userPaused = false
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                loadStream()
            } else {
                // Live: jump back to the edge after a pause.
                player.seekToDefaultPosition()
                player.play()
            }
        }
    }

    fun pauseForBackground() {
        if (playerBuilt) player.pause()
    }

    fun resumeFromBackground() {
        if (playerBuilt && !userPaused && info?.liveStatus != 0 && streamError == null) {
            player.seekToDefaultPosition()
            player.play()
        }
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
            val text = o.str("text")
            val name = o.str("nickname").ifEmpty { o.path("user.base.name").asString() }
            val key = "h${o.long("uid")}-${o.str("timeline")}-${text.hashCode()}"
            if (text.isEmpty() || !seenHistory.add(key)) return@mapNotNull null
            LiveChat(
                key = key,
                uid = o.long("uid"),
                name = name,
                text = text,
                face = o.path("user.base.face").asString(),
                emoticon = o.obj("emoticon").str("url"),
            )
        }
        if (fresh.isNotEmpty()) appendChats(fresh)
    }

    private fun appendChats(list: List<LiveChat>) {
        messages.addAll(list)
        if (messages.size > 400) messages.removeRange(0, messages.size - 300)
        _incoming.tryEmit(list.filter { it.kind == LiveChatKind.Chat && it.emoticon.isEmpty() })
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
        if (playerBuilt) player.release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "LiveRoomVM"
    }
}
