package com.gpo.pilipala.yt.core.account

import com.gpo.pilipala.yt.core.extractor.YtAccount
import com.gpo.pilipala.yt.core.extractor.YtFeeds
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The signed-in account's subscribed channel ids (UC…), loaded once per session so every
 * 订阅 button can show the account's state. Changes go through [AccountWrites]: a debug build
 * leaves both the account and this set as they are.
 */
object AccountSubscriptions {
    private val scope = MainScope()
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    fun start() {
        scope.launch {
            Accounts.youtube.collect { profile ->
                _ids.value = emptySet()
                if (profile is Profile.SignedIn) {
                    runCatching { YtFeeds.channels() }.onSuccess { list -> _ids.value = list.map { channelId(it.url) }.toSet() }
                }
            }
        }
    }

    /** Subscribe or unsubscribe [url]'s channel; returns the line to show. */
    suspend fun toggle(url: String, name: String): String {
        val id = channelId(url)
        val on = id in _ids.value
        val what = if (on) "取消订阅 $name" else "订阅 $name"
        return AccountWrites.run(what) {
            if (on) YtAccount.unsubscribe(listOf(id)) else YtAccount.subscribe(listOf(id))
            _ids.value = if (on) _ids.value - id else _ids.value + id
        }
    }

    fun channelId(url: String): String = url.trimEnd('/').substringAfterLast('/')
}
