package com.gpo.pilipala.feature.live

import android.util.Base64
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.asArr
import com.gpo.pilipala.core.net.asBool
import com.gpo.pilipala.core.net.asInt
import com.gpo.pilipala.core.net.asLong
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.asString
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.path
import com.gpo.pilipala.core.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Turns live websocket commands and history rows into [LiveChat]s. */
internal object LiveMessages {

    private val emoteCode = Regex("""\[[^\[\]]{1,16}]""")

    /** `info` array of a DANMU_MSG. */
    fun danmu(obj: JsonObject, n: Long): LiveChat? {
        val info = obj["info"].asArr ?: return null
        val meta = info.getOrNull(0).asArr
        val text = info.getOrNull(1).asString()
        val user = info.getOrNull(2).asArr
        val ext = meta?.getOrNull(15).asObj
        val uid = user?.getOrNull(0).asLong()
        val name = ext.path("user.base.name").asString().ifEmpty { user?.getOrNull(1).asString() }
        val sticker = if (meta?.getOrNull(12).asInt() == 1) emote(objOrString(meta?.getOrNull(13))) else null
        val extra = objOrString(ext?.get("extra"))
        val medal = medalObj(ext.path("user.medal").asObj) ?: medalArr(info.getOrNull(3).asArr)
        return LiveChat(
            key = "dm$n-${meta?.getOrNull(4).asLong()}",
            uid = uid,
            name = name,
            text = text,
            face = ext.path("user.base.face").asString(),
            color = meta?.getOrNull(3).asInt(0xFFFFFF).takeIf { it != 0 } ?: 0xFFFFFF,
            mode = meta?.getOrNull(1).asInt(1),
            sticker = sticker,
            emots = emots(extra?.obj("emots")),
            medal = medal,
        )
    }

    /** gethistory `room[]` row. */
    fun history(o: JsonObject, key: String): LiveChat? {
        val text = o.str("text")
        if (text.isEmpty()) return null
        val sticker = emote(o.obj("emoticon"))
        return LiveChat(
            key = key,
            uid = o.long("uid"),
            name = o.path("user.base.name").asString().ifEmpty { o.str("nickname") },
            text = text,
            face = o.path("user.base.face").asString(),
            sticker = sticker,
            emots = emots(o.obj("emots")),
            medal = medalObj(o.path("user.medal").asObj) ?: medalArr(o["medal"].asArr),
        )
    }

    fun superChat(d: JsonObject): LiveChat {
        val user = d.obj("user_info")
        val m = d.obj("medal_info")
        return LiveChat(
            key = "sc${d.long("id")}",
            uid = d.long("uid"),
            name = user.str("uname"),
            text = d.str("message"),
            face = user.str("face"),
            kind = LiveChatKind.SuperChat,
            price = d.str("price"),
            medal = m?.str("medal_name")?.takeIf { it.isNotEmpty() }?.let {
                LiveMedal(it, m.int("medal_level"), m.int("guard_level"), m.int("is_lighted", 1) == 1)
            },
            guardLevel = user.int("guard_level"),
        )
    }

    /** JSON SEND_GIFT (logged-in sockets still get these). */
    fun gift(d: JsonObject, n: Long): LiveChat {
        val m = d.obj("medal_info")
        val uid = d.long("uid")
        val giftId = d.long("giftId")
        return LiveChat(
            key = "gift$n",
            uid = uid,
            name = d.path("sender_uinfo.base.name").asString().ifEmpty { d.str("uname") },
            text = "",
            face = d.path("sender_uinfo.base.face").asString().ifEmpty { d.str("face") },
            kind = LiveChatKind.Gift,
            giftName = d.str("giftName"),
            giftNum = d.int("num", 1),
            giftIcon = d.path("gift_info.webp").asString().ifEmpty { d.path("gift_info.img_basic").asString() },
            action = d.str("action").ifEmpty { "投喂" },
            comboKey = d.str("batch_combo_id").ifEmpty { "$uid-$giftId" },
            price = coinLabel(d.str("coin_type"), d.long("price") * d.int("num", 1)),
            medal = m?.str("medal_name")?.takeIf { it.isNotEmpty() }?.let {
                LiveMedal(it, m.int("medal_level"), m.int("guard_level"), m.int("is_lighted", 1) == 1)
            },
        )
    }

    /**
     * SEND_GIFT_V2 carries the gift as base64 protobuf in `data.pb`. Field
     * numbers (seen on the wire): 1 uid, 2 uname, 3 face, 10 gift {1 id,
     * 2 name, 3 num, 5 price, 8 coin_type, 12 combo id, 18 action, 35.1 icon},
     * 15 sender {3 medal {1 name, 2 level}}.
     */
    fun giftPb(b64: String, n: Long): LiveChat? {
        val root = Pb.parse(Base64.decode(b64, Base64.DEFAULT)) ?: return null
        val g = root.msg(10) ?: return null
        val name = g.str(2)
        if (name.isEmpty()) return null
        val uid = root.long(1)
        val num = g.long(3).toInt().coerceAtLeast(1)
        val medal = root.msg(15)?.msg(3)
        return LiveChat(
            key = "gift$n",
            uid = uid,
            name = root.str(2),
            text = "",
            face = root.str(3),
            kind = LiveChatKind.Gift,
            giftName = name,
            giftNum = num,
            giftIcon = g.msg(35)?.str(2)?.ifEmpty { null } ?: g.msg(35)?.str(1).orEmpty(),
            action = g.str(18).ifEmpty { "投喂" },
            comboKey = g.str(12).ifEmpty { "$uid-${g.long(1)}" },
            price = coinLabel(g.str(8), g.long(5) * num),
            medal = medal?.str(1)?.takeIf { it.isNotEmpty() }?.let { LiveMedal(it, medal.long(2).toInt()) },
        )
    }

    /** GUARD_BUY / USER_TOAST_MSG; both describe the same purchase, keyed so the second is dropped. */
    fun guard(d: JsonObject): LiveChat? {
        val level = d.int("guard_level")
        if (level !in 1..3) return null
        val uid = d.long("uid")
        val num = d.int("num", 1).coerceAtLeast(1)
        val unit = d.str("unit").ifEmpty { "月" }.removePrefix("个")
        return LiveChat(
            key = "guard$uid-${d.long("start_time")}-$level",
            uid = uid,
            name = d.str("username"),
            text = "",
            kind = LiveChatKind.Guard,
            guardLevel = level,
            giftName = d.str("role_name").ifEmpty { d.str("gift_name") }.ifEmpty { guardName(level) },
            giftNum = num,
            action = "开通",
            price = unit,
        )
    }

    /** INTERACT_WORD(_V2): (uname, msg_type) — 1 entered, 2 followed. */
    fun interact(d: JsonObject): Pair<String, Int>? {
        val pb = d.str("pb")
        if (pb.isNotEmpty()) {
            val m = Pb.parse(Base64.decode(pb, Base64.DEFAULT)) ?: return null
            return m.str(2).takeIf { it.isNotEmpty() }?.let { it to m.long(5).toInt() }
        }
        return d.str("uname").takeIf { it.isNotEmpty() }?.let { it to d.int("msg_type") }
    }

    /** The room's emoticon packs (GetEmoticons, needs login): `[code]` → image. */
    fun roomEmotes(data: JsonObject?): Map<String, LiveEmote> {
        val out = HashMap<String, LiveEmote>()
        data?.get("data").asArr?.forEach { pkg ->
            pkg.asObj?.get("emoticons").asArr?.forEach { e ->
                val o = e.asObj ?: return@forEach
                val code = o.str("emoji")
                val url = o.str("url")
                if (code.startsWith("[") && code.endsWith("]") && url.isNotEmpty()) {
                    out[code] = LiveEmote(url, o.int("width"), o.int("height"))
                }
            }
        }
        return out
    }

    /** Text split into runs of plain text and known emote codes. */
    fun segments(text: String, emots: Map<String, LiveEmote>, room: Map<String, LiveEmote>): List<Pair<String, LiveEmote?>> {
        if (emots.isEmpty() && room.isEmpty()) return listOf(text to null)
        val out = ArrayList<Pair<String, LiveEmote?>>()
        var last = 0
        for (m in emoteCode.findAll(text)) {
            val e = emots[m.value] ?: room[m.value] ?: continue
            if (m.range.first > last) out += text.substring(last, m.range.first) to null
            out += m.value to e
            last = m.range.last + 1
        }
        if (last < text.length) out += text.substring(last) to null
        return out
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun coinLabel(coinType: String, coins: Long): String =
        if (coinType == "gold" && coins >= 1000) "¥${coins / 1000}" else ""

    /** Some fields arrive as a JSON object, others as a JSON-encoded string of one. */
    private fun objOrString(e: JsonElement?): JsonObject? = when (e) {
        is JsonObject -> e
        is JsonPrimitive -> e.asString().takeIf { it.startsWith("{") }
            ?.let { runCatching { BiliJson.parseToJsonElement(it) }.getOrNull().asObj }
        else -> null
    }

    private fun emote(o: JsonObject?): LiveEmote? {
        val url = o.str("url")
        if (url.isEmpty()) return null
        return LiveEmote(url, o.int("width"), o.int("height"))
    }

    private fun emots(o: JsonObject?): Map<String, LiveEmote> {
        if (o == null || o.isEmpty()) return emptyMap()
        return o.mapNotNull { (k, v) -> emote(v.asObj)?.let { k to it } }.toMap()
    }

    private fun medalObj(o: JsonObject?): LiveMedal? {
        val name = o.str("name")
        if (name.isEmpty() || o.int("level") <= 0) return null
        return LiveMedal(name, o.int("level"), o.int("guard_level"), o?.get("is_light")?.asBool(true) ?: true)
    }

    /** `[level, name, anchor, roomid, color, special, ?, border, start, end, guard, lit, anchorUid]`. */
    private fun medalArr(a: JsonArray?): LiveMedal? {
        if (a == null || a.size < 2) return null
        val name = a[1].asString()
        if (name.isEmpty()) return null
        return LiveMedal(name, a[0].asInt(), a.getOrNull(10).asInt(), a.getOrNull(11)?.asInt(1) != 0)
    }
}

/** Just enough protobuf: varints and length-delimited fields, first value wins. */
internal class Pb private constructor(private val fields: Map<Int, Any>) {
    fun long(f: Int): Long = fields[f] as? Long ?: 0L
    fun str(f: Int): String = (fields[f] as? ByteArray)?.toString(Charsets.UTF_8).orEmpty()
    fun msg(f: Int): Pb? = (fields[f] as? ByteArray)?.let { parse(it) }

    companion object {
        fun parse(b: ByteArray): Pb? = runCatching {
            val out = HashMap<Int, Any>()
            var i = 0
            fun varint(): Long {
                var r = 0L
                var shift = 0
                while (true) {
                    val c = b[i++].toInt() and 0xFF
                    r = r or ((c and 0x7F).toLong() shl shift)
                    if (c < 0x80) return r
                    shift += 7
                }
            }
            while (i < b.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> varint().let { out.putIfAbsent(field, it) }
                    1 -> i += 8
                    2 -> {
                        val len = varint().toInt()
                        require(len >= 0 && i + len <= b.size)
                        out.putIfAbsent(field, b.copyOfRange(i, i + len))
                        i += len
                    }
                    5 -> i += 4
                    else -> error("wire type")
                }
            }
            Pb(out)
        }.getOrNull()
    }
}
