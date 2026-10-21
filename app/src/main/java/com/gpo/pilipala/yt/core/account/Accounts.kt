package com.gpo.pilipala.yt.core.account

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A source the app reads from, and who it is signed in as there. Today there is one source
 * (YouTube); when PipePaw and PiliPaw become one app, Bilibili is a second [Source] and the
 * account panel lists them (a tick per source decides what Home shows). Kept to what that
 * panel needs: no account manager, one profile per source.
 */
enum class Source(val key: String, val label: String) {
    YouTube("youtube", "YouTube"),
}

/**
 * Who the app is on a [Source]. Signed out is a real profile, not an absence: it works fully
 * (local subscriptions, the signed-out player client) and only suggests signing in.
 */
@Serializable
sealed interface Profile {
    @Serializable
    data object SignedOut : Profile

    /**
     * Signed in: the site's cookies (the session itself, sent with every request to the site)
     * and what the account panel shows. [handle] is "@name" where the site has one.
     */
    @Serializable
    data class SignedIn(
        val cookies: String,
        val name: String = "",
        val handle: String = "",
        val avatar: String = "",
    ) : Profile
}

/**
 * The profiles, persisted in the app's private preferences (cookies never leave the phone
 * except to the site they belong to). [youtube] is what the rest of the app watches.
 */
object Accounts {
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

    private val _youtube = MutableStateFlow<Profile>(Profile.SignedOut)
    val youtube: StateFlow<Profile> = _youtube.asStateFlow()

    /** Cookies to send to YouTube, or null signed out. */
    val youtubeCookies: String? get() = (_youtube.value as? Profile.SignedIn)?.cookies

    fun init(context: Context) {
        prefs = context.getSharedPreferences("paw_accounts", Context.MODE_PRIVATE)
        _youtube.value = prefs.getString(Source.YouTube.key, null)
            ?.let { runCatching { json.decodeFromString(Profile.serializer(), it) }.getOrNull() }
            ?: Profile.SignedOut
    }

    fun setYouTube(profile: Profile) {
        _youtube.value = profile
        prefs.edit().putString(Source.YouTube.key, json.encodeToString(Profile.serializer(), profile)).apply()
    }
}
