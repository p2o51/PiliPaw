package com.gpo.pilipala.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.navigation.LocalNavigator
import com.gpo.pilipala.navigation.Route
import com.gpo.pilipala.ui.components.NetImage
import com.gpo.pilipala.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Immutable
internal data class MsgUser(val mid: Long, val name: String, val face: String)

internal const val UP_ASSISTANT_ID = 844424930131966L

/** Names/avatars for whisper talkers (vc account cards, polymer cards as fallback). */
internal suspend fun fetchUserCards(uids: List<Long>): Map<Long, MsgUser> {
    if (uids.isEmpty()) return emptyMap()
    val joined = uids.distinct().joinToString(",")
    val out = HashMap<Long, MsgUser>()
    fun collect(el: JsonElement?) {
        when {
            el.asArr != null -> el.asArr!!.objects().forEach { add(it, out) }
            el.asObj != null -> {
                val o = el.asObj!!
                if (o.containsKey("mid") || o.containsKey("face")) add(o, out)
                else o.values.forEach { v -> v.asObj?.let { add(it, out) } ?: v.asArr?.objects()?.forEach { add(it, out) } }
            }
        }
    }
    runCatching {
        collect(BiliClient.getData("${BiliHosts.VC}/account/v1/user/cards", mapOf("uids" to joined, "build" to 0, "mobi_app" to "web")))
    }
    val missing = uids.filter { it !in out }
    if (missing.isNotEmpty()) {
        runCatching { collect(BiliClient.getData("/x/polymer/pc-electron/v1/user/cards", mapOf("uids" to missing.joinToString(",")))) }
    }
    out[UP_ASSISTANT_ID] = MsgUser(
        UP_ASSISTANT_ID,
        "UP主小助手",
        "https://message.biliimg.com/bfs/im/489a63efadfb202366c2f88853d2217b5ddc7a13.png",
    )
    return out
}

private fun add(o: JsonObject, out: MutableMap<Long, MsgUser>) {
    val mid = o.long("mid").takeIf { it > 0 } ?: o.long("uid")
    if (mid <= 0) return
    out[mid] = MsgUser(mid, o.str("name").ifEmpty { o.str("uname") }, o.str("face"))
}

/** `content` of whisper messages is itself a JSON string. */
internal fun parseMsgContent(raw: String): JsonObject? {
    if (raw.isBlank()) return null
    val el = runCatching { BiliJson.parseToJsonElement(raw) }.getOrNull()
    return when (el) {
        is JsonObject -> el
        is JsonPrimitive -> JsonObject(mapOf("content" to el))
        else -> null
    }
}

/** One-line preview for a session's last message. */
internal fun msgPreview(type: Int, content: JsonObject?, status: Int): String = when {
    status == 1 -> "已撤回一条消息"
    type == 2 -> "[图片]"
    type == 5 -> "已撤回一条消息"
    type == 6 -> "[表情]"
    content == null -> ""
    else -> content.str("text")
        .ifEmpty { content.str("content") }
        .ifEmpty { content.str("title") }
        .ifEmpty { content.str("reply_content") }
        .ifEmpty { content.str("main_title") }
        .let { if (it.startsWith("[{")) "[通知]" else it }
        .ifEmpty { "[消息]" }
}

/** Strips bilibili rich-text link markup `#{label}{"url"}` and returns (text, firstUrl). */
internal fun stripLinkMarkup(s: String): Pair<String, String?> {
    val re = Regex("""#\{([^}]*)\}\{"([^"]*)"\}""")
    val first = re.find(s)?.groupValues?.getOrNull(2)
    return re.replace(s) { it.groupValues[1] } to first
}

/** Guest state: playful shape + a single 登录 action. */
@Composable
internal fun LoginPrompt(modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(96.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(YoinSymbols.Mail, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("未登录", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { nav.push(Route.Login) }) { Text("登录") }
    }
}

/** Quiet quoted-source block (the thing that was replied to / liked). */
@Composable
internal fun SourceBlock(
    text: String,
    image: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    if (text.isEmpty() && image.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (image.isNotEmpty()) {
            NetImage(image, Modifier.size(48.dp), shape = ContinuousRoundedCornerShape(8.dp), thumbWidth = 96.dp)
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = if (image.isEmpty()) 4.dp else 0.dp),
        )
    }
}

@Composable
internal fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
