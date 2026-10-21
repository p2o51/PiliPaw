package com.gpo.pilipala.yt.feature.subscriptions

import android.content.Context
import android.content.SharedPreferences
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Local subscriptions: no account, nothing leaves the phone. The 订阅 feed is built from
 * these channels' RSS feeds. Newest subscription first.
 */
object Subscriptions {
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ChannelItem.serializer())

    private val _channels = MutableStateFlow<List<ChannelItem>>(emptyList())
    val channels: StateFlow<List<ChannelItem>> = _channels.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("paw_subscriptions", Context.MODE_PRIVATE)
        _channels.value = prefs.getString(Key, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()
    }

    /** Cloud sync: channels from another device join the list (none is removed). */
    fun mergeIn(others: List<ChannelItem>) {
        val now = _channels.value
        val add = others.filter { o -> now.none { it.url == o.url } }
        if (add.isNotEmpty()) save(now + add)
    }

    fun isSubscribed(url: String): Boolean = _channels.value.any { it.url == url }

    fun toggle(channel: ChannelItem) {
        val now = _channels.value
        save(if (now.any { it.url == channel.url }) now.filterNot { it.url == channel.url } else listOf(channel) + now)
    }

    private fun save(list: List<ChannelItem>) {
        _channels.value = list
        prefs.edit().putString(Key, json.encodeToString(serializer, list)).apply()
    }

    private const val Key = "channels"
}
