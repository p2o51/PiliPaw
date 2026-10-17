package com.gpo.pilipala.feature.live

import android.util.Log
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asInt
import com.gpo.pilipala.core.net.asLong
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.path
import com.gpo.pilipala.core.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Inflater

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
    private var closed = false
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
                    while (isActive) {
                        webSocket.send(packet(2, "[object Object]".toByteArray()))
                        delay(30_000)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
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
        parsePackets(data, chats)
        if (chats.isNotEmpty()) onChat(chats)
    }

    private fun parsePackets(data: ByteArray, out: MutableList<LiveChat>) {
        var offset = 0
        val buf = ByteBuffer.wrap(data)
        while (offset + 16 <= data.size) {
            val packLen = buf.getInt(offset)
            val headLen = buf.getShort(offset + 4).toInt()
            val ver = buf.getShort(offset + 6).toInt()
            val op = buf.getInt(offset + 8)
            if (packLen < headLen || offset + packLen > data.size) break
            val body = data.copyOfRange(offset + headLen, offset + packLen)
            when (op) {
                8 -> onState(true)
                5 -> when (ver) {
                    2 -> parsePackets(inflate(body), out)
                    0, 1 -> parseCommand(String(body, Charsets.UTF_8), out)
                    else -> Unit // brotli (3) is not available
                }
            }
            offset += packLen
        }
    }

    private fun inflate(body: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(body)
        val out = ByteArrayOutputStream(body.size * 4)
        val chunk = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(chunk, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun parseCommand(text: String, out: MutableList<LiveChat>) {
        val obj = runCatching { BiliJson.parseToJsonElement(text) }.getOrNull().asObj ?: return
        val cmd = obj.str("cmd")
        when {
            cmd.startsWith("DANMU_MSG") -> parseDanmu(obj, seq.incrementAndGet())?.let(out::add)
            cmd == "SUPER_CHAT_MESSAGE" -> {
                val d = obj.obj("data") ?: return
                val user = d.obj("user_info")
                out += LiveChat(
                    key = "sc${d.long("id")}",
                    uid = d.long("uid"),
                    name = user.str("uname"),
                    text = d.str("message"),
                    face = user.str("face"),
                    kind = LiveChatKind.SuperChat,
                    price = d.str("price"),
                )
            }
            cmd == "INTERACT_WORD" -> {
                val d = obj.obj("data") ?: return
                val name = d.str("uname")
                if (name.isNotEmpty()) onTip(if (d.int("msg_type") == 2) "$name 关注了主播" else "$name 进入直播间")
            }
            cmd == "WATCHED_CHANGE" -> obj.obj("data").str("text_large").takeIf { it.isNotEmpty() }?.let(onWatched)
        }
    }

    companion object {
        private const val TAG = "LiveDanmakuSocket"

        /** `info` array of a DANMU_MSG. */
        fun parseDanmu(obj: JsonObject, n: Long): LiveChat? {
            val info = obj["info"].asArr ?: return null
            val meta = info.getOrNull(0).asArr
            val text = info.getOrNull(1).asString()
            val user = info.getOrNull(2).asArr
            val uid = user?.getOrNull(0).asLong()
            val face = meta?.getOrNull(15).path("user.base.face").asString()
            val name = user?.getOrNull(1).asString().ifEmpty { meta?.getOrNull(15).path("user.base.name").asString() }
            val emoticon = if (meta?.getOrNull(12).asInt() == 1) meta?.getOrNull(13).asObj.str("url") else ""
            return LiveChat(
                key = "dm$n-${meta?.getOrNull(4).asLong()}",
                uid = uid,
                name = name,
                text = text,
                face = face,
                color = meta?.getOrNull(3).asInt(0xFFFFFF).takeIf { it != 0 } ?: 0xFFFFFF,
                emoticon = emoticon,
            )
        }
    }
}
