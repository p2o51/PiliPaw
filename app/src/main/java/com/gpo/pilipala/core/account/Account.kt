package com.gpo.pilipala.core.account

import android.content.Context
import android.content.SharedPreferences
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.bool
import com.gpo.pilipala.core.net.double
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class UserInfo(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
    val level: Int = 0,
    val coins: Double = 0.0,
    val vipStatus: Int = 0,
    val vipLabel: String = "",
    val following: Int = 0,
    val follower: Int = 0,
    val dynamicCount: Int = 0,
)

/** Signed-in user. `null` = guest. Refreshed from /x/web-interface/nav. */
object Account {
    private lateinit var prefs: SharedPreferences
    private val _user = MutableStateFlow<UserInfo?>(null)
    val user: StateFlow<UserInfo?> = _user.asStateFlow()
    val isLoggedIn: Boolean get() = _user.value != null && BiliClient.isLoggedIn

    fun init(context: Context) {
        prefs = context.getSharedPreferences("pili_account", Context.MODE_PRIVATE)
        _user.value = prefs.getString("user", null)?.let {
            runCatching { BiliJson.decodeFromString(UserInfo.serializer(), it) }.getOrNull()
        }?.takeIf { BiliClient.isLoggedIn }
    }

    suspend fun refresh(): UserInfo? {
        if (!BiliClient.isLoggedIn) {
            set(null)
            return null
        }
        val nav = BiliClient.get("/x/web-interface/nav").obj("data")
        if (!nav.bool("isLogin")) {
            set(null)
            return null
        }
        val stat = runCatching { BiliClient.getData("/x/web-interface/nav/stat").asObj }.getOrNull()
        val info = UserInfo(
            mid = nav.long("mid"),
            name = nav.str("uname"),
            face = nav.str("face"),
            level = nav.obj("level_info").int("current_level"),
            coins = nav.double("money"),
            vipStatus = nav.int("vipStatus"),
            vipLabel = nav.obj("vip_label").str("text"),
            following = stat.int("following"),
            follower = stat.int("follower"),
            dynamicCount = stat.int("dynamic_count"),
        )
        set(info)
        return info
    }

    fun set(info: UserInfo?) {
        _user.value = info
        prefs.edit().apply {
            if (info == null) remove("user") else putString("user", BiliJson.encodeToString(UserInfo.serializer(), info))
        }.apply()
    }

    fun logout() {
        BiliClient.cookies.clear()
        set(null)
    }
}
