package com.gpo.pilipala.feature.download

import android.content.Context
import android.os.Environment
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.gpo.pilipala.core.net.BiliJson
import com.gpo.pilipala.feature.player.PlayTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
internal enum class DownloadState { Queued, Downloading, Muxing, Done, Paused, Failed }

/**
 * One cached part (one cid). Persisted in `index.json` next to the files; [doneBytes] is
 * written at state changes and every few seconds (the live figure is [DownloadStore.progress]).
 */
@Serializable
internal data class DownloadItem(
    val id: String,
    val bvid: String = "",
    val aid: Long = 0,
    val cid: Long,
    val epId: Long = 0,
    val seasonId: Long = 0,
    val isPgc: Boolean = false,
    val title: String = "",
    val partTitle: String = "",
    val cover: String = "",
    val owner: String = "",
    val qn: Int,
    val qualityLabel: String = "",
    val codecId: Int = 0,
    val audioId: Int = 0,
    val videoBytes: Long = 0,
    val audioBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val durationMs: Long = 0,
    val state: DownloadState = DownloadState.Queued,
    val error: String = "",
    /** Video file name inside the item's folder once done ("video.mp4", or the unmuxed "video.m4s"). */
    val fileName: String = "",
    /** Set when muxing failed: the audio stays beside the video and plays merged at runtime. */
    val audioFileName: String = "",
    val metaFilled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = state == DownloadState.Queued || state == DownloadState.Downloading || state == DownloadState.Muxing

    fun playTarget(): PlayTarget = PlayTarget(
        bvid = bvid,
        aid = aid,
        cid = cid,
        epId = epId,
        seasonId = seasonId,
        isPgc = isPgc,
        title = partTitle.takeIf { it.isNotBlank() && it != title }?.let { "$title $it" } ?: title,
        cover = cover,
    )
}

internal data class LiveProgress(val done: Long, val total: Long, val bytesPerSecond: Long)

/**
 * The offline cache: index, folders and the WorkManager queue. Everything lives in
 * `getExternalFilesDir(MOVIES)/PiliPaw/<id>/` (falls back to internal files when the
 * external volume is missing). One process-wide instance; the worker and the UI share it.
 */
internal object DownloadStore {
    private const val INDEX = "index.json"
    const val TAG = "pilipaw-download"

    /** Queue operations outlive the sheet / row that asked for them. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutex = Mutex()
    private var appContext: Context? = null
    @Volatile private var loaded = false

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, LiveProgress>>(emptyMap())
    val progress: StateFlow<Map<String, LiveProgress>> = _progress.asStateFlow()

    private val listSerializer = ListSerializer(DownloadItem.serializer())

    fun idFor(cid: Long): String = "c$cid"

    fun root(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "movies")
        return File(base, "PiliPaw").apply { mkdirs() }
    }

    fun dir(context: Context, id: String): File = File(root(context), id)

    /** Loads the index once per process. */
    suspend fun init(context: Context) {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            appContext = context.applicationContext
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(root(context), INDEX)
                    if (f.exists()) BiliJson.decodeFromString(listSerializer, f.readText()) else emptyList()
                }.getOrDefault(emptyList())
            }
            _items.value = list
            loaded = true
        }
    }

    fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun forCid(cid: Long): DownloadItem? = get(idFor(cid))

    private suspend fun persist() {
        val ctx = appContext ?: return
        val snapshot = _items.value
        withContext(Dispatchers.IO) {
            val dir = root(ctx)
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(BiliJson.encodeToString(listSerializer, snapshot))
            tmp.renameTo(File(dir, INDEX))
        }
    }

    /** Applies [change] to the item (no-op when it was deleted meanwhile); returns the new value. */
    suspend fun update(id: String, save: Boolean = true, change: (DownloadItem) -> DownloadItem): DownloadItem? =
        mutex.withLock {
            var out: DownloadItem? = null
            _items.update { list -> list.map { if (it.id == id) change(it).also { n -> out = n } else it } }
            if (save && out != null) persist()
            out
        }

    fun reportProgress(id: String, p: LiveProgress?) {
        _progress.update { m -> if (p == null) m - id else m + (id to p) }
    }

    // ── Queue ────────────────────────────────────────────────────────────────

    /** Adds (or replaces, when [item] asks for another quality) and starts the cid's download. */
    suspend fun add(context: Context, item: DownloadItem) {
        init(context)
        val old = get(item.id)
        if (old != null) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(item.id))
            withContext(Dispatchers.IO) { dir(context, item.id).deleteRecursively() }
        }
        mutex.withLock {
            _items.update { list -> listOf(item) + list.filterNot { it.id == item.id } }
            persist()
        }
        schedule(context, item.id)
    }

    suspend fun pause(context: Context, id: String) {
        init(context)
        update(id) { if (it.state == DownloadState.Done) it else it.copy(state = DownloadState.Paused) }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        reportProgress(id, null)
    }

    suspend fun resume(context: Context, id: String) {
        init(context)
        update(id) { it.copy(state = DownloadState.Queued, error = "") } ?: return
        schedule(context, id)
    }

    suspend fun delete(context: Context, id: String) {
        init(context)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        mutex.withLock {
            _items.update { list -> list.filterNot { it.id == id } }
            persist()
        }
        reportProgress(id, null)
        withContext(Dispatchers.IO) { dir(context, id).deleteRecursively() }
    }

    fun workName(id: String) = "download-$id"

    /** Tasks that say they run but have no live work (lost queue, e.g. a restored install): paused. */
    suspend fun reconcile(context: Context) {
        init(context)
        val wm = WorkManager.getInstance(context)
        for (item in _items.value.filter { it.active }) {
            val infos = runCatching { wm.getWorkInfosForUniqueWorkFlow(workName(item.id)).first() }.getOrNull() ?: continue
            if (infos.none { !it.state.isFinished }) {
                update(item.id) { if (it.active) it.copy(state = DownloadState.Paused) else it }
            }
        }
    }

    private fun schedule(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    /** The finished local files for [cid], or null (not cached, unfinished, or files gone). */
    suspend fun localMedia(context: Context, cid: Long): LocalMedia? {
        init(context)
        val item = forCid(cid)?.takeIf { it.state == DownloadState.Done && it.fileName.isNotEmpty() } ?: return null
        return withContext(Dispatchers.IO) {
            val dir = dir(context, item.id)
            val video = File(dir, item.fileName).takeIf { it.exists() } ?: return@withContext null
            val audio = item.audioFileName.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.exists() }
            LocalMedia(video, audio, item.qn, item.qualityLabel, item.durationMs)
        }
    }

    fun coverFile(context: Context, id: String): File = File(dir(context, id), "cover.jpg")
}
