package com.gpo.pilipala.feature.login

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.gpo.pilipala.core.account.Account
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.BiliHosts
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.ui.components.friendlyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Web QR login state. */
internal sealed interface QrState {
    data object Loading : QrState
    /** 86101 未扫码 */
    data object Waiting : QrState
    /** 86090 已扫码未确认 */
    data object Scanned : QrState
    /** 86038 二维码已失效 */
    data object Expired : QrState
    data object Success : QrState
    data class Error(val message: String) : QrState
}

/**
 * passport web QR login: generate → url + qrcode_key, poll every 2s.
 * The poll response sets the account cookies on BiliClient's jar (it goes
 * through BiliClient.http); the cross-domain url in the response carries them
 * again in its query, which we copy over when the jar missed any.
 */
internal class LoginViewModel : ViewModel() {
    var state by mutableStateOf<QrState>(QrState.Loading)
        private set
    var qr by mutableStateOf<ImageBitmap?>(null)
        private set
    var qrUrl by mutableStateOf("")
        private set
    /** Seconds left before the code expires (web codes live 180s). */
    var secondsLeft by mutableStateOf(180)
        private set

    private var key: String = ""
    private var pollJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            state = QrState.Loading
            val data = runCatching {
                BiliClient.getData("${BiliHosts.PASSPORT}/x/passport-login/web/qrcode/generate").asObj
            }.getOrElse {
                state = QrState.Error(it.friendlyMessage())
                return@launch
            }
            val url = data.str("url")
            key = data.str("qrcode_key")
            if (url.isEmpty() || key.isEmpty()) {
                state = QrState.Error("二维码获取失败")
                return@launch
            }
            qrUrl = url
            // A url too long for a QR code (WriterException) is a failed fetch, not a crash.
            qr = runCatching { withContext(Dispatchers.Default) { renderQr(url, 720) } }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                state = QrState.Error("二维码获取失败")
                return@launch
            }
            state = QrState.Waiting
            secondsLeft = 180
            poll()
        }
    }

    private suspend fun poll() {
        val started = System.currentTimeMillis()
        while (viewModelScope.isActive) {
            delay(2_000)
            secondsLeft = (180 - ((System.currentTimeMillis() - started) / 1000).toInt()).coerceAtLeast(0)
            val root = runCatching {
                BiliClient.get("${BiliHosts.PASSPORT}/x/passport-login/web/qrcode/poll", mapOf("qrcode_key" to key))
            }.getOrNull() ?: continue
            val data = root.obj("data")
            when (data.int("code", -1)) {
                0 -> {
                    importCrossDomainCookies(data.str("url"))
                    runCatching { Account.refresh() }
                    state = if (BiliClient.isLoggedIn) QrState.Success else QrState.Error("登录失败")
                    return
                }
                86090 -> state = QrState.Scanned
                86101 -> if (state != QrState.Scanned) state = QrState.Waiting
                86038 -> {
                    state = QrState.Expired
                    return
                }
                else -> Unit
            }
            if (secondsLeft <= 0) {
                state = QrState.Expired
                return
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
    }
}

/** Copies DedeUserID / SESSDATA / bili_jct … from the cross-domain url's (still-encoded) query. */
internal fun importCrossDomainCookies(url: String) {
    if (url.isEmpty()) return
    val query = runCatching { Uri.parse(url).encodedQuery }.getOrNull() ?: return
    val wanted = setOf("DedeUserID", "DedeUserID__ckMd5", "SESSDATA", "bili_jct", "sid")
    query.split('&').forEach { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) return@forEach
        val name = part.substring(0, eq)
        val value = part.substring(eq + 1)
        if (name in wanted && value.isNotEmpty() && BiliClient.cookies.get(name).isNullOrEmpty()) {
            runCatching { BiliClient.cookies.put(name, value) }
        }
    }
}

/** QR modules as black on transparent; the UI tints them with a theme token. */
internal fun renderQr(text: String, size: Int): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            pixels[row + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0x00000000
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
