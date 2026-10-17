package com.gpo.pilipala.core.net

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAt: Long = Long.MAX_VALUE,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val hostOnly: Boolean = false,
)

/**
 * Persistent cookie jar (SharedPreferences, JSON). Keeps every cookie bilibili
 * sets — SESSDATA / bili_jct / DedeUserID / buvid3 — across launches.
 */
class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pili_cookies", Context.MODE_PRIVATE)
    private val lock = Any()
    private val cookies: MutableList<Cookie> = load()

    private fun load(): MutableList<Cookie> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return runCatching {
            BiliJson.decodeFromString(ListSerializer(StoredCookie.serializer()), raw).map { s ->
                Cookie.Builder().name(s.name).value(s.value).path(s.path).expiresAt(s.expiresAt).apply {
                    if (s.hostOnly) hostOnlyDomain(s.domain) else domain(s.domain)
                    if (s.secure) secure()
                    if (s.httpOnly) httpOnly()
                }.build()
            }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun persist() {
        val list = cookies.filter { it.persistent || it.expiresAt == Long.MAX_VALUE || true }.map {
            StoredCookie(it.name, it.value, it.domain, it.path, it.expiresAt, it.secure, it.httpOnly, it.hostOnly)
        }
        prefs.edit().putString(KEY, BiliJson.encodeToString(ListSerializer(StoredCookie.serializer()), list)).apply()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            cookies.forEach { c ->
                this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                this.cookies += c
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        cookies.filter { it.matches(url) }
    }

    /** Add/replace a cookie for `.bilibili.com` (used for buvid3, login results). */
    fun put(name: String, value: String, domain: String = "bilibili.com") {
        val c = Cookie.Builder().name(name).value(value).domain(domain).path("/")
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 3600 * 1000).build()
        saveFromResponse("https://www.$domain".toHttpUrl(), listOf(c))
    }

    fun get(name: String): String? = synchronized(lock) {
        cookies.lastOrNull { it.name == name && it.domain.endsWith("bilibili.com") }?.value
    }

    /** `name=value; ...` for a URL — for WebView sync and media requests. */
    fun cookieHeader(url: String = "https://www.bilibili.com"): String =
        loadForRequest(url.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }

    fun all(): List<Cookie> = synchronized(lock) { cookies.toList() }

    fun clear() = synchronized(lock) {
        cookies.clear()
        persist()
    }

    private companion object {
        const val KEY = "cookies"
    }
}
