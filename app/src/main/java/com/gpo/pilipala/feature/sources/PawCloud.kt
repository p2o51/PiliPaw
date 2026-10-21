package com.gpo.pilipala.feature.sources

import android.accounts.Account
import android.app.Application
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.feature.player.lyrics.LyricsStore
import com.gpo.pilipala.yt.core.extractor.ChannelItem
import com.gpo.pilipala.yt.feature.library.LibItem
import com.gpo.pilipala.yt.feature.library.Library
import com.gpo.pilipala.yt.feature.library.LocalPlaylist
import com.gpo.pilipala.yt.feature.subscriptions.Subscriptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 云同步: what is worth carrying to another phone, in the user's own Google Drive (the hidden app
 * folder, scope drive.appdata; nothing else of their Drive is visible to the app).
 *
 * Each device writes one file, `paw-<device>.json`, with everything it has (its own and what it
 * merged from the others), and reads the others' files to merge them in:
 * - Mix lists, YouTube subscriptions and YouTube watch later: joined by key, nothing removed.
 * - Picked lyrics: a device gains the ones it does not have.
 * - Settings: the most recently written file's, minus what belongs to one device.
 * Never synced: sign-ins (cookies, tokens), history, caches, which sources are ticked.
 */
object PawCloud {
    /** Off unless the user turns it on in 设置 › 云同步. */
    val enabled = AppSettings.register("cloud_sync_enabled", false)
    private val lastSyncKey = AppSettings.register("cloud_sync_last", 0L)
    private val accountKey = AppSettings.register("cloud_sync_account", "")

    sealed interface State {
        data object Idle : State
        data object Syncing : State
        data class Done(val at: Long) : State
        /** Google needs the user (consent, or which account): launch [sender]. */
        data class NeedsUser(val sender: IntentSender) : State
        /** The Google Cloud project does not know this build (package / signing certificate). */
        data class Misconfigured(val packageName: String, val sha1: String?) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    val lastSync: Long get() = lastSyncKey.value

    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val http by lazy { OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build() }

    /** Settings that belong to one device (or to an account) and stay there. */
    private val localOnly = setOf(
        "cloud_sync_enabled", "cloud_sync_last", "cloud_sync_account",
        "access_key", "access_key_mid", "background_play", "auto_pip", "auto_fs_landscape",
        "four_tabs_for_mini_bar", "mine_moved_to_avatar", "keep_playing_mode", "shell_hidden_sections",
        "search_history",
    )

    fun init(application: Application) {
        app = application
    }

    /** Launch, or 立即同步: a silent sync when it is on (a consent need just waits for the settings page). */
    fun syncIfOn() {
        if (enabled.value) sync(interactive = false)
    }

    /** Turns sync on and runs it; [interactive] lets Google ask the user (from the settings page). */
    fun sync(interactive: Boolean) {
        scope.launch {
            if (!lock.tryLock()) return@launch
            try {
                state = State.Syncing
                val token = when (val auth = authorize()) {
                    is Auth.Token -> auth.value
                    is Auth.Resolve -> {
                        state = if (interactive) State.NeedsUser(auth.sender) else State.Idle
                        return@launch
                    }
                    Auth.Misconfigured -> {
                        state = State.Misconfigured(app.packageName, signingSha1())
                        return@launch
                    }
                    is Auth.Failed -> {
                        state = State.Failed(auth.message)
                        return@launch
                    }
                }
                AppSettings.set(enabled, true)
                runSync(token)
                val now = System.currentTimeMillis()
                AppSettings.set(lastSyncKey, now)
                state = State.Done(now)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state = State.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * The consent sheet came back. Granted: carry on. Otherwise say why instead of going quiet
     * (most often: Google Cloud does not know this package and certificate yet).
     */
    fun resumeAfterConsent(granted: Boolean, data: android.content.Intent?) {
        if (granted) {
            sync(interactive = true)
            return
        }
        val code = runCatching { Identity.getAuthorizationClient(app).getAuthorizationResultFromIntent(data) }
            .exceptionOrNull().let { (it as? ApiException)?.statusCode }
        state = when (code) {
            CommonStatusCodes.DEVELOPER_ERROR, null -> State.Misconfigured(app.packageName, signingSha1())
            CommonStatusCodes.CANCELED -> State.Idle
            else -> State.Failed(CommonStatusCodes.getStatusCodeString(code))
        }
    }

    /** Stops syncing on this device; Drive keeps what is there. */
    fun turnOff() {
        AppSettings.set(enabled, false)
        state = State.Idle
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    private sealed interface Auth {
        data class Token(val value: String) : Auth
        data class Resolve(val sender: IntentSender) : Auth
        data object Misconfigured : Auth
        data class Failed(val message: String) : Auth
    }

    private val driveScope = Scope("https://www.googleapis.com/auth/drive.appdata")

    private fun authorize(): Auth = try {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .apply { accountKey.value.takeIf { it.isNotEmpty() }?.let { setAccount(Account(it, "com.google")) } }
            .build()
        val result: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(app).authorize(request), 30, TimeUnit.SECONDS)
        when {
            result.hasResolution() -> result.pendingIntent?.intentSender?.let { Auth.Resolve(it) } ?: Auth.Failed("Google 没有给出授权页面")
            result.accessToken.isNullOrBlank() -> Auth.Failed("Google 没有返回授权")
            else -> Auth.Token(result.accessToken!!)
        }
    } catch (e: java.util.concurrent.ExecutionException) {
        val api = e.cause as? ApiException
        when (api?.statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR -> Auth.Misconfigured
            null -> Auth.Failed(e.cause?.message ?: "授权失败")
            else -> Auth.Failed(api.statusMessage ?: CommonStatusCodes.getStatusCodeString(api.statusCode))
        }
    }

    @Suppress("DEPRECATION")
    private fun signingSha1(): String? = runCatching {
        val pm = app.packageManager
        val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return null
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    // ── Payload ─────────────────────────────────────────────────────────────

    @Serializable
    private data class Snapshot(
        val device: String,
        val at: Long,
        val mix: List<LocalPlaylist> = emptyList(),
        val subscriptions: List<ChannelItem> = emptyList(),
        val watchLater: List<LibItem> = emptyList(),
        val lyrics: Map<String, String> = emptyMap(),
        val biliSettings: JsonObject = JsonObject(emptyMap()),
        val ytSettings: JsonObject = JsonObject(emptyMap()),
    )

    private val deviceId: String by lazy {
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "device"
    }

    private fun snapshot() = Snapshot(
        device = deviceId,
        at = System.currentTimeMillis(),
        mix = Library.playlists.value,
        subscriptions = Subscriptions.channels.value,
        watchLater = Library.watchLater.value,
        lyrics = LyricsStore.exportAll(),
        biliSettings = AppSettings.snapshot().toJson(),
        ytSettings = com.gpo.pilipala.yt.core.settings.AppSettings.snapshot().toJson(),
    )

    private fun Map<String, Any?>.toJson(): JsonObject = JsonObject(
        filterKeys { it !in localOnly }.mapNotNull { (k, v) ->
            when (v) {
                is Boolean -> k to JsonPrimitive(v)
                is Number -> k to JsonPrimitive(v)
                is String -> k to JsonPrimitive(v)
                else -> null
            }
        }.toMap(),
    )

    private fun JsonObject.toValues(): Map<String, Any?> = mapValues { (_, e) ->
        val p = e as? JsonPrimitive ?: return@mapValues null
        when {
            p.isString -> p.content
            p.booleanOrNull != null -> p.booleanOrNull
            p.longOrNull != null -> p.longOrNull
            else -> p.doubleOrNull
        }
    }.filterKeys { it !in localOnly }

    private suspend fun runSync(token: String) {
        val files = list(token)
        val mine = "paw-$deviceId.json"
        val others = files.filter { it.second != mine }.mapNotNull { (id, _) ->
            runCatching { json.decodeFromString(Snapshot.serializer(), download(token, id)) }.getOrNull()
        }
        withContext(Dispatchers.Main) {
            others.forEach { o ->
                Library.mergePlaylists(o.mix)
                Subscriptions.mergeIn(o.subscriptions)
                Library.mergeWatchLater(o.watchLater)
            }
            LyricsStore.importMissing(others.fold(emptyMap()) { acc, o -> acc + o.lyrics })
            // Settings: the newest other device's, when it is newer than this device's last sync.
            others.maxByOrNull { it.at }?.takeIf { it.at > lastSyncKey.value }?.let { newest ->
                AppSettings.restore(newest.biliSettings.toValues())
                com.gpo.pilipala.yt.core.settings.AppSettings.restore(newest.ytSettings.toValues())
            }
        }
        val body = json.encodeToString(Snapshot.serializer(), withContext(Dispatchers.Main) { snapshot() }).toByteArray()
        val existing = files.firstOrNull { it.second == mine }?.first
        if (existing != null) update(token, existing, body) else create(token, mine, body)
    }

    // ── Drive (v3 REST, appDataFolder) ─────────────────────────────────────

    private const val Api = "https://www.googleapis.com/drive/v3"
    private const val Upload = "https://www.googleapis.com/upload/drive/v3"
    private val JsonType = "application/json; charset=utf-8".toMediaType()

    private fun call(token: String, request: Request.Builder): String {
        http.newCall(request.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw IllegalStateException("Google Drive ${r.code}: ${text.take(200)}")
            return text
        }
    }

    /** (id, name) of every file in the app folder. */
    private fun list(token: String): List<Pair<String, String>> {
        val text = call(token, Request.Builder().url("$Api/files?spaces=appDataFolder&pageSize=1000&fields=files(id,name)").get())
        val files = (json.parseToJsonElement(text) as JsonObject)["files"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return files.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            id to name
        }
    }

    private fun download(token: String, id: String): String = call(token, Request.Builder().url("$Api/files/$id?alt=media").get())

    private fun create(token: String, name: String, bytes: ByteArray) {
        val meta = """{"name":"$name","parents":["appDataFolder"],"mimeType":"application/json"}"""
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody(JsonType))
            .addPart(bytes.toRequestBody(JsonType))
            .build()
        call(token, Request.Builder().url("$Upload/files?uploadType=multipart").post(body))
    }

    private fun update(token: String, id: String, bytes: ByteArray) {
        call(token, Request.Builder().url("$Upload/files/$id?uploadType=media").patch(bytes.toRequestBody(JsonType)))
    }

    @Suppress("unused")
    private fun JsonElement.debug() = toString()
}
