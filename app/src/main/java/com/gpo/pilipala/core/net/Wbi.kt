package com.gpo.pilipala.core.net

import java.net.URLEncoder
import java.security.MessageDigest

/** WBI signing (w_rid / wts). https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/misc/sign/wbi.md */
object Wbi {
    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    )
    private val chrFilter = Regex("[!'()*]")

    fun mixinKey(orig: String): String = buildString {
        for (i in mixinKeyEncTab) if (i < orig.length) append(orig[i])
    }.take(32)

    fun sign(params: Map<String, String>, imgKey: String, subKey: String): Map<String, String> {
        val mixin = mixinKey(imgKey + subKey)
        val wts = (System.currentTimeMillis() / 1000).toString()
        val all = params + ("wts" to wts)
        val query = all.keys.sorted().joinToString("&") { k ->
            "${enc(k)}=${enc(all[k]!!.replace(chrFilter, ""))}"
        }
        val wRid = md5(query + mixin)
        return all + ("w_rid" to wRid)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** APP sign (appkey + sign) for app.bilibili.com / passport TV endpoints. */
object AppSign {
    const val ANDROID_HD_KEY = "dfca71928277209b"
    const val ANDROID_HD_SEC = "b5475a8825547a4fc26c7d518eaaa02e"
    const val TV_KEY = "4409e2ce8ffd12b8"
    const val TV_SEC = "59b43e04ad6965f34319062b478f83dd"

    fun sign(params: Map<String, String>, appKey: String = TV_KEY, appSec: String = TV_SEC): Map<String, String> {
        val all = params + ("appkey" to appKey)
        val query = all.keys.sorted().joinToString("&") { k ->
            "$k=${URLEncoder.encode(all[k]!!, "UTF-8")}"
        }
        return all + ("sign" to Wbi.md5(query + appSec))
    }
}
