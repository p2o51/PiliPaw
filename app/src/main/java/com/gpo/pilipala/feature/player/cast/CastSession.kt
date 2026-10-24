package com.gpo.pilipala.feature.player.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliException
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.core.util.ScopeErrors
import com.gpo.pilipala.feature.player.PlayTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one 投屏 under way: which renderer, what it plays, and where it is (polled once a second).
 * It outlives the page that started it, so the 投屏 page can come back to the remote.
 */
object CastSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + ScopeErrors)
    private var poll: Job? = null

    var device by mutableStateOf<DlnaDevice?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var cid by mutableLongStateOf(0L)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set

    /** A cast being set up (the URL fetched, the renderer told). */
    var connecting by mutableStateOf<DlnaDevice?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    /** Sends [target] from [startMs] to [d]; [onStarted] runs once it plays (pause the phone there). */
    fun start(d: DlnaDevice, target: PlayTarget, startMs: Long, onStarted: () -> Unit) {
        error = null
        connecting = d
        scope.launch {
            try {
                val url = CastUrl.fetch(target)
                Dlna.cast(d, url, target.title)
                device = d
                title = target.title
                cid = target.cid
                playing = true
                positionMs = startMs
                if (startMs > 5_000) {
                    // Renderers take a moment before they accept a seek.
                    delay(1_500)
                    runCatching { Dlna.seek(d, startMs) }
                }
                onStarted()
                watch(d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "投屏失败：${e.message ?: "设备没有回应"}"
            } finally {
                connecting = null
            }
        }
    }

    private fun watch(d: DlnaDevice) {
        poll?.cancel()
        poll = scope.launch {
            while (isActive && device == d) {
                Dlna.position(d)?.let { (p, dur) ->
                    positionMs = p
                    if (dur > 0) durationMs = dur
                }
                Dlna.state(d)?.let { s -> playing = s == "PLAYING" || s == "TRANSITIONING" }
                delay(1_000)
            }
        }
    }

    fun togglePlay() {
        val d = device ?: return
        val was = playing
        playing = !was
        scope.launch { runCatching { if (was) Dlna.pause(d) else Dlna.play(d) } }
    }

    fun seekTo(ms: Long) {
        val d = device ?: return
        positionMs = ms
        scope.launch { runCatching { Dlna.seek(d, ms) } }
    }

    /** 退出投屏: the renderer stops; returns where it was, for the phone to carry on from. */
    fun stop(): Long {
        val d = device ?: return 0
        val at = positionMs
        device = null
        poll?.cancel()
        scope.launch { runCatching { Dlna.stop(d) } }
        return at
    }
}

/** A single-file MP4 of the video that a TV can fetch without Bilibili's Referer. */
internal object CastUrl {
    suspend fun fetch(t: PlayTarget): String {
        val data = if (t.isPgc) {
            BiliClient.getData(
                "/pgc/player/web/playurl",
                buildMap {
                    if (t.epId > 0) put("ep_id", t.epId)
                    put("cid", t.cid)
                    put("qn", 80)
                    put("fnval", 0)
                    put("fnver", 0)
                    put("fourk", 0)
                },
            ).asObj
        } else {
            BiliClient.getData(
                "/x/player/playurl",
                buildMap {
                    if (t.bvid.isNotBlank()) put("bvid", t.bvid) else put("avid", t.aid)
                    put("cid", t.cid)
                    put("qn", 80)
                    put("fnval", 1)
                    put("fnver", 0)
                    put("platform", "html5")
                    put("high_quality", 1)
                },
            ).asObj
        }
        return data.arr("durl").objects().firstOrNull()?.str("url")?.takeIf { it.isNotBlank() }
            ?: throw BiliException(-1, "这个视频拿不到可投的地址")
    }
}
