package com.gpo.pilipala.feature.videomenu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import com.gpo.pilipala.ui.theme.YoinMotion
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.feature.message.UP_ASSISTANT_ID
import com.gpo.pilipala.feature.message.fetchUserCards
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.components.SheetFinisher
import com.gpo.pilipala.ui.components.friendlyMessage
import com.gpo.pilipala.ui.components.rememberToast
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random

@Immutable
internal class ShareContact(val mid: Long, val name: String, val face: String)

internal object ShareContactsApi {
    /** The people you last talked to in 私信 (not system accounts), most recent first. */
    suspend fun recent(limit: Int = 8): List<ShareContact> {
        if (!BiliClient.isLoggedIn) return emptyList()
        val data = BiliClient.getData(
            "${BiliHosts.VC}/session_svr/v1/session_svr/get_sessions",
            mapOf("session_type" to 1, "group_fold" to 1, "unfollow_fold" to 0, "sort_rule" to 2, "build" to 0, "mobi_app" to "web"),
            wbi = true,
        ).asObj
        val ids = data.arr("session_list").objects()
            .filter { it.int("session_type", 1) == 1 }
            .map { it.long("talker_id") }
            .filter { it in 1 until UP_ASSISTANT_ID }
            .take(limit)
        val cards = fetchUserCards(ids)
        return ids.mapNotNull { id -> cards[id]?.let { ShareContact(id, it.name, it.face) } }
    }

    /** Sends [text] to [mid] as a 私信. */
    suspend fun send(mid: Long, text: String) {
        val res = BiliClient.postForm(
            "${BiliHosts.VC}/web_im/v1/web_im/send_msg",
            mapOf(
                "msg[sender_uid]" to BiliClient.mid,
                "msg[receiver_id]" to mid,
                "msg[receiver_type]" to 1,
                "msg[msg_type]" to 1,
                "msg[msg_status]" to 0,
                "msg[content]" to buildJsonObject { put("content", JsonPrimitive(text)) }.toString(),
                "msg[timestamp]" to System.currentTimeMillis() / 1000,
                "msg[new_face_version]" to 1,
                "msg[dev_id]" to devId(),
                "from_firework" to 0,
                "build" to 0,
                "mobi_app" to "web",
            ),
        )
        if (res.int("code") != 0) throw IllegalStateException(res.str("message").ifEmpty { "发送失败" })
    }

    private fun devId(): String {
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

/**
 * 分享's first line: the people you last 私信'd, a face and a name each. A tap picks one (a ring
 * round the face, like the official app); a second tap lets go. While someone is picked, a
 * 说点什么 field and 发送 open under the faces, so nothing goes out without that confirm. 发送 sends
 * the note (if any) with the video's title and link, ticks the face, and the sheet goes.
 * Nothing shows signed out or with no chats.
 */
@Composable
internal fun ShareContactsRow(target: ShareTarget, finisher: SheetFinisher, modifier: Modifier = Modifier) {
    val contacts by produceState<List<ShareContact>>(emptyList()) {
        value = try {
            ShareContactsApi.recent()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }
    if (contacts.isEmpty()) return
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val haptics = LocalHapticFeedback.current
    var picked by remember { mutableStateOf<ShareContact?>(null) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<Long?>(null) }
    val idle = !sending && !finisher.finishing && sent == null
    // The faces and their confirm are one group: tight between them.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            contacts.forEach { c ->
                val selected = picked?.mid == c.mid
                val ring by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    YoinMotion.defaultEffectsSpec(),
                    label = "contactRing",
                )
                val press = remember { MutableInteractionSource() }
                val pressed by press.collectIsPressedAsState()
                val faceScale by animateFloatAsState(if (pressed) 0.92f else 1f, YoinMotion.fastSpatialSpec(), label = "contactPress")
                // No ripple: the ring and a small press on the face are the feedback, so letting
                // go doesn't flash a block over the face and name.
                Column(
                    Modifier
                        .width(64.dp)
                        .clickable(enabled = idle, interactionSource = press, indication = null) {
                            haptics.performHapticFeedback(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            picked = if (selected) null else c
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(56.dp).graphicsLayer { scaleX = faceScale; scaleY = faceScale }.border(2.dp, ring, CircleShape).padding(4.dp)) {
                        NetImage(c.face, Modifier.fillMaxSize(), shape = CircleShape, thumbWidth = 96.dp)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = sent == c.mid,
                            enter = fadeIn() + scaleIn(initialScale = 0.6f),
                            exit = fadeOut() + scaleOut(targetScale = 0.6f),
                        ) {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(YoinSymbols.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    Text(
                        c.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = picked != null,
            enter = fadeIn(YoinMotion.defaultEffectsSpec()) + expandVertically(YoinMotion.defaultSpatialSpec()),
            exit = fadeOut(YoinMotion.fastEffectsSpec()) + shrinkVertically(YoinMotion.defaultSpatialSpec()),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = note,
                    onValueChange = { note = it },
                    enabled = idle,
                    singleLine = true,
                    placeholder = { Text("说点什么（可不填）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                )
                Button(
                    onClick = {
                        val c = picked ?: return@Button
                        sending = true
                        scope.launch {
                            try {
                                val link = "${target.title} ${target.url}"
                                ShareContactsApi.send(c.mid, note.trim().let { if (it.isEmpty()) link else "$it\n$link" })
                                sent = c.mid
                                finisher.close { toast("已发给 ${c.name}") }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                toast(e.friendlyMessage())
                            }
                            sending = false
                        }
                    },
                    enabled = idle,
                    modifier = Modifier.height(52.dp),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(if (sending) "发送中" else "发送", style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}
