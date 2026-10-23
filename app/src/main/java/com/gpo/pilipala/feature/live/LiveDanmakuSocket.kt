package com.gpo.pilipala.feature.live

import android.util.Log
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Bilibili live danmaku websocket (`wss://<host>/sub`). Packets are a 16-byte
 * big-endian header + body; we ask for protover 2 (zlib) since brotli isn't
 * available. Ops: 2 heartbeat, 3 heartbeat reply (popularity), 5 command,
 * 7 auth, 8 auth reply.
 */
internal class LiveDanmakuSocket(
    private val scope: CoroutineScope,
    private val roomId: Long,
    private val onChat: (List<LiveChat>) -> Unit,
    private val onTip: (String) -> Unit,
    private val onWatched: (String) -> Unit,
    private val onState: (connected: Boolean) -> Unit,
) {
    private var ws: WebSocket? = null
    private var heartbeat: Job? = null
    @Volatile private var closed = false
    private val seq = AtomicLong(0)

    fun connect(host: String, token: String) {
        closed = false
        val req = Request.Builder()
            .url("wss://$host/sub")
            .header("Origin", "https://live.bilibili.com")
            .header("User-Agent", BiliClient.UA_PC)
            .build()
        ws = BiliClient.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) {
                    webSocket.cancel()
                    return
                }
                val auth = buildJsonObject {
                    put("uid", BiliClient.mid)
                    put("roomid", roomId)
                    put("protover", 2)
                    put("buvid", BiliClient.buvid3)
                    put("platform", "web")
                    put("type", 2)
                    put("key", token)
                }.toString()
                webSocket.send(packet(7, auth.toByteArray()))
                heartbeat?.cancel()
                heartbeat = scope.launch {
                    while (isActive && !closed) {
                        webSocket.send(packet(2, "[object Object]".toByteArray()))
                        delay(30_000)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (closed) return
                runCatching { handle(bytes.toByteArray()) }.onFailure { Log.w(TAG, "decode", it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure", t)
                heartbeat?.cancel()
                if (!closed) onState(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeat?.cancel()
                if (!closed) onState(false)
            }
        })
    }

    fun close() {
        closed = true
        heartbeat?.cancel()
        ws?.close(1000, null)
        ws = null
    }

    private fun packet(op: Int, body: ByteArray): ByteString {
        val buf = ByteBuffer.allocate(16 + body.size)
        buf.putInt(16 + body.size)
        buf.putShort(16)
        buf.putShort(1)
        buf.putInt(op)
        buf.putInt(1)
        buf.put(body)
        return buf.array().toByteString()
    }

    private fun handle(data: ByteArray) {
        val chats = mutableListOf<LiveChat>()
        LivePackets.decode(data) { op, body ->
            when (op) {
                8 -> onState(true)
                5 -> parseCommand(body.toString(Charsets.UTF_8), chats)
            }
        }
        if (chats.isNotEmpty()) onChat(chats)
    }

    private fun parseCommand(text: String, out: MutableList<LiveChat>) {
        val obj = runCatching { BiliJson.parseToJsonElement(text) }.getOrNull().asObj ?: return
        val cmd = obj.str("cmd").substringBefore(':')
        val n = seq.incrementAndGet()
        when (cmd) {
            "DANMU_MSG" -> LiveMessages.danmu(obj, n)?.let(out::add)
            "SUPER_CHAT_MESSAGE" -> obj.obj("data")?.let { out += LiveMessages.superChat(it) }
            "SEND_GIFT" -> obj.obj("data")?.let { out += LiveMessages.gift(it, n) }
            "SEND_GIFT_V2" -> obj.obj("data")?.str("pb")?.takeIf { it.isNotEmpty() }
                ?.let { LiveMessages.giftPb(it, n) }?.let(out::add)
            "GUARD_BUY", "USER_TOAST_MSG" -> obj.obj("data")?.let(LiveMessages::guard)?.let(out::add)
            "INTERACT_WORD", "INTERACT_WORD_V2" -> obj.obj("data")?.let(LiveMessages::interact)?.let { (name, type) ->
                if (type == 1 || type == 2) onTip(if (type == 2) "$name 关注了主播" else "$name 进入直播间")
            }
            "WATCHED_CHANGE" -> obj.obj("data").str("text_large").takeIf { it.isNotEmpty() }?.let(onWatched)
        }
    }

    companion object {
        private const val TAG = "LiveDanmakuSocket"
    }
}
