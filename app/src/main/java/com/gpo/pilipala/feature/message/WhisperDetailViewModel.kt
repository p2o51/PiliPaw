package com.gpo.pilipala.feature.message

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

@Immutable
internal data class WhisperMsg(
    val key: String,
    val seqno: Long,
    val senderUid: Long,
    val type: Int,
    val content: JsonObject?,
    /** Unix seconds. */
    val timestamp: Long,
    val status: Int,
)

internal class WhisperDetailViewModel(val talkerId: Long) : ViewModel() {
    /** Newest first (the list is reverse-laid-out). */
    val messages = mutableStateListOf<WhisperMsg>()
    var emotes by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set

    private var minSeqno = 0L
    private var maxSeqno = 0L
    private var pollJob: Job? = null
    private val devId: String = genDevId()
    private var started = false

    /** Talker name/avatar when the route didn't carry them (e.g. opened from a profile). */
    var peer by mutableStateOf<MsgUser?>(null)
        private set

    fun ensureLoaded(needPeer: Boolean = false) {
        if (started) return
        started = true
        load()
        if (needPeer) {
            viewModelScope.launch { peer = runCatching { fetchUserCards(listOf(talkerId))[talkerId] }.getOrNull() }
        }
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            runCatching { fetch() }
                .onSuccess { page ->
                    messages.clear()
                    messages.addAll(page.list)
                    hasMore = page.hasMore
                    minSeqno = page.min
                    maxSeqno = page.max
                    ack()
                    startPolling()
                }
                .onFailure { error = it.friendlyMessage() }
            loading = false
        }
    }

    fun loadOlder() {
        if (loading || loadingMore || !hasMore || minSeqno <= 0) return
        viewModelScope.launch {
            loadingMore = true
            runCatching { fetch(endSeqno = minSeqno) }
                .onSuccess { page ->
                    val known = messages.mapTo(HashSet()) { it.key }
                    messages.addAll(page.list.filter { it.key !in known })
                    hasMore = page.hasMore && page.list.isNotEmpty()
                    if (page.min > 0) minSeqno = page.min
                }
            loadingMore = false
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(6_000)
                if (maxSeqno <= 0) continue
                runCatching { fetch(beginSeqno = maxSeqno) }.onSuccess { page ->
                    val known = messages.mapTo(HashSet()) { it.key }
                    val fresh = page.list.filter { it.key !in known && it.seqno > 0 }
                    if (fresh.isNotEmpty()) {
                        messages.addAll(0, fresh)
                        if (page.max > maxSeqno) maxSeqno = page.max
                        ack()
                    }
                }
            }
        }
    }

    private class MsgPage(val list: List<WhisperMsg>, val hasMore: Boolean, val min: Long, val max: Long)

    private suspend fun fetch(endSeqno: Long? = null, beginSeqno: Long? = null): MsgPage {
        val params = mutableMapOf<String, Any?>(
            "talker_id" to talkerId,
            "session_type" to 1,
            "size" to 20,
            "sender_device_id" to 1,
            "build" to 0,
            "mobi_app" to "web",
        )
        if (endSeqno != null) params["end_seqno"] = endSeqno
        if (beginSeqno != null) params["begin_seqno"] = beginSeqno
        val data = BiliClient.getData("${BiliHosts.VC}/svr_sync/v1/svr_sync/fetch_session_msgs", params, wbi = true).asObj
        val e = data.arr("e_infos").objects().associate { it.str("text") to it.str("url") }
        if (e.isNotEmpty()) emotes = emotes + e
        val list = data.arr("messages").objects()
            .map { m ->
                WhisperMsg(
                    key = m.str("msg_key").ifEmpty { "s${m.long("msg_seqno")}" },
                    seqno = m.long("msg_seqno"),
                    senderUid = m.long("sender_uid"),
                    type = m.int("msg_type"),
                    content = parseMsgContent(m.str("content")),
                    timestamp = m.long("timestamp"),
                    status = m.int("msg_status"),
                )
            }
            .sortedByDescending { it.seqno }
        return MsgPage(list, data.int("has_more") == 1, data.long("min_seqno"), data.long("max_seqno"))
    }

    private fun ack() {
        val seq = maxSeqno.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            runCatching {
                BiliClient.postForm(
                    "${BiliHosts.VC}/session_svr/v1/session_svr/update_ack",
                    mapOf("talker_id" to talkerId, "session_type" to 1, "ack_seqno" to seq, "build" to 0, "mobi_app" to "web"),
                )
            }
        }
    }

    /** Returns an error message, or null on success. */
    suspend fun send(text: String): String? {
        if (text.isBlank() || sending) return null
        sending = true
        return try {
            val contentJson = buildJsonObject { put("content", text) }.toString()
            val now = System.currentTimeMillis() / 1000
            val res = BiliClient.postForm(
                "${BiliHosts.VC}/web_im/v1/web_im/send_msg",
                mapOf(
                    "msg[sender_uid]" to BiliClient.mid,
                    "msg[receiver_id]" to talkerId,
                    "msg[receiver_type]" to 1,
                    "msg[msg_type]" to 1,
                    "msg[msg_status]" to 0,
                    "msg[content]" to contentJson,
                    "msg[timestamp]" to now,
                    "msg[new_face_version]" to 1,
                    "msg[dev_id]" to devId,
                    "from_firework" to 0,
                    "build" to 0,
                    "mobi_app" to "web",
                ),
            )
            if (res.int("code") != 0) {
                res.str("message").ifEmpty { res.str("msg") }.ifEmpty { "发送失败" }
            } else {
                val d = res["data"].asObj
                val key = d.str("msg_key").ifEmpty { "local${System.nanoTime()}" }
                val sent = parseMsgContent(d.str("msg_content")) ?: JsonObject(mapOf("content" to JsonPrimitive(text)))
                if (messages.none { it.key == key }) {
                    messages.add(
                        0,
                        WhisperMsg(key, 0, BiliClient.mid, 1, sent, now, 0),
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

    suspend fun removeSession(): String? = try {
        val res = BiliClient.postForm(
            "${BiliHosts.VC}/session_svr/v1/session_svr/remove_session",
            mapOf("talker_id" to talkerId, "session_type" to 1, "build" to 0, "mobi_app" to "web"),
        )
        if (res.int("code") != 0) res.str("message").ifEmpty { "操作失败" } else null
    } catch (e: Exception) {
        e.friendlyMessage()
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private fun genDevId(): String {
        val hex = "0123456789ABCDEF"
        return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".map { c ->
            when (c) {
                'x' -> hex[Random.nextInt(16)]
                'y' -> hex[(Random.nextInt(16) and 3) or 8]
                else -> c
            }
        }.joinToString("")
    }
}
