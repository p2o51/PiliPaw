package com.gpo.pilipala.feature.player.cast

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.Immutable
import com.gpo.pilipala.core.net.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL

/** A DLNA renderer on the local network: its name and AVTransport control URL. */
@Immutable
data class DlnaDevice(val id: String, val name: String, val model: String, val controlUrl: String)

/**
 * The small part of UPnP / DLNA that 投屏 needs: find renderers with SSDP, then drive their
 * AVTransport (set the URL, play, pause, seek, stop, read the position). No library: the
 * protocol is a few SOAP calls.
 */
object Dlna {
    private const val SsdpHost = "239.255.255.250"
    private const val SsdpPort = 1900
    private const val AvTransport = "urn:schemas-upnp-org:service:AVTransport:1"

    /**
     * Searches for [timeoutMs] and calls [onFound] for each renderer as its description comes
     * in (so the list fills while the search runs).
     */
    suspend fun discover(context: Context, timeoutMs: Int = 4_000, onFound: suspend (DlnaDevice) -> Unit) = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("pilipaw-dlna")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val seen = HashSet<String>()
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = 600
                val msg = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SsdpHost:$SsdpPort\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: $AvTransport\r\n\r\n"
                    ).toByteArray()
                val group = InetAddress.getByName(SsdpHost)
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() >= nextSend) {
                        runCatching { socket.send(DatagramPacket(msg, msg.size, group, SsdpPort)) }
                        nextSend = System.currentTimeMillis() + 1_200
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    val location = header(text, "LOCATION") ?: continue
                    if (!seen.add(location)) continue
                    describe(location)?.let { onFound(it) }
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun header(text: String, name: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    private fun describe(location: String): DlnaDevice? = runCatching {
        val xml = http(Request.Builder().url(location).build())
        val model = unescape(tag(xml, "modelName").orEmpty())
        val name = cleanName(unescape(tag(xml, "friendlyName").orEmpty())).ifEmpty { model.ifEmpty { "未知设备" } }
        val udn = tag(xml, "UDN") ?: location
        // The AVTransport service block, then its controlURL.
        val services = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
        val av = services.firstOrNull { it.contains("AVTransport") } ?: return null
        val control = tag(av, "controlURL") ?: return null
        val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        DlnaDevice(udn, name, model, URL(URL(base), control).toString())
    }.getOrNull()

    /**
     * Some renderers pack the address and a serial into the name ("192.168.0.84 - Sonos Era 300 -
     * RINCON_…"); keep the part a person would call it.
     */
    private fun cleanName(raw: String): String =
        raw.split(" - ").map { it.trim() }
            .filterNot { it.isEmpty() || it.matches(Regex("[0-9.:]+")) || it.matches(Regex("[A-Z0-9_:-]{12,}")) }
            .joinToString(" · ")

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name[^>]*>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    private fun http(req: Request): String = BiliClient.http.newCall(req).execute().use { r ->
        val body = r.body.string()
        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
        body
    }

    private suspend fun soap(d: DlnaDevice, action: String, args: String): String = withContext(Dispatchers.IO) {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:$action xmlns:u="$AvTransport"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""
        http(
            Request.Builder()
                .url(d.controlUrl)
                .header("SOAPACTION", "\"$AvTransport#$action\"")
                .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build(),
        )
    }

    suspend fun cast(d: DlnaDevice, url: String, title: String) {
        val meta = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1"><dc:title>${escape(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class>""" +
            """<res protocolInfo="http-get:*:video/mp4:*">${escape(url)}</res></item></DIDL-Lite>"""
        runCatching { soap(d, "Stop", "") }
        soap(d, "SetAVTransportURI", "<CurrentURI>${escape(url)}</CurrentURI><CurrentURIMetaData>${escape(meta)}</CurrentURIMetaData>")
        play(d)
    }

    suspend fun play(d: DlnaDevice) {
        soap(d, "Play", "<Speed>1</Speed>")
    }

    suspend fun pause(d: DlnaDevice) {
        soap(d, "Pause", "")
    }

    suspend fun stop(d: DlnaDevice) {
        soap(d, "Stop", "")
    }

    suspend fun seek(d: DlnaDevice, ms: Long) {
        soap(d, "Seek", "<Unit>REL_TIME</Unit><Target>${clock(ms)}</Target>")
    }

    /** (position, duration) in ms; null when the renderer does not answer. */
    suspend fun position(d: DlnaDevice): Pair<Long, Long>? = runCatching {
        val xml = soap(d, "GetPositionInfo", "")
        val pos = parseClock(tag(xml, "RelTime"))
        val dur = parseClock(tag(xml, "TrackDuration"))
        pos to dur
    }.getOrNull()

    /** PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING; null when unknown. */
    suspend fun state(d: DlnaDevice): String? = runCatching {
        tag(soap(d, "GetTransportInfo", ""), "CurrentTransportState")
    }.getOrNull()

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    private fun parseClock(s: String?): Long {
        val parts = s?.substringBefore('.')?.split(':')?.mapNotNull { it.toLongOrNull() } ?: return 0
        if (parts.size != 3) return 0
        return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
    }
}
