package com.gpo.pilipala.feature.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gpo.pilipala.R
import com.gpo.pilipala.core.net.BiliClient
import com.gpo.pilipala.core.net.arr
import com.gpo.pilipala.core.net.asObj
import com.gpo.pilipala.core.net.int
import com.gpo.pilipala.core.net.long
import com.gpo.pilipala.core.net.obj
import com.gpo.pilipala.core.net.objects
import com.gpo.pilipala.core.net.str
import com.gpo.pilipala.feature.player.MediaStream
import com.gpo.pilipala.feature.player.PlayInfo
import com.gpo.pilipala.feature.player.PlayUrlApi
import com.gpo.pilipala.feature.player.Qualities
import com.gpo.pilipala.feature.player.cdn.CdnRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One cache task (unique work `download-<id>`): resolve fresh stream URLs, fetch video and
 * audio into `.part` files with HTTP Range resume, mux, save the cover. Runs as a dataSync
 * foreground worker with a progress notification; at most [MAX_PARALLEL] transfer at once
 * (the rest wait their turn as 等待中). Cancelling (暂停 / 删除) leaves the parts for resume.
 */
internal class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ID = "id"
        private const val CHANNEL = "download"
        private const val MAX_PARALLEL = 2
        private val slots = Semaphore(MAX_PARALLEL)
        /** A replaced / resumed task's new worker waits for the old one to let go of the parts. */
        private val owners = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        private val http: OkHttpClient by lazy {
            BiliClient.http.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "离线缓存", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
                )
            }
        }
    }

    private val id = inputData.getString(KEY_ID).orEmpty()
    private val notificationId = 0x0D10 + id.hashCode().and(0xFFFF)
    private var lastNotify = 0L
    private var lastSave = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo {
        DownloadStore.init(applicationContext)
        val item = DownloadStore.get(id)
        return foregroundInfo(item?.title.orEmpty(), -1)
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        DownloadStore.init(ctx)
        val start = DownloadStore.get(id) ?: return Result.success()
        if (start.state == DownloadState.Done || start.state == DownloadState.Paused) return Result.success()
        ensureChannel(ctx)
        // Promotion can be refused (Android 12+ while the app is in the background, e.g. a
        // retry after process death): the transfer then runs as plain work and resumes later.
        runCatching { setForeground(foregroundInfo(start.title, -1)) }
        return try {
            owners.getOrPut(id) { Mutex() }.withLock {
                try {
                    slots.withPermit { run() }
                } catch (e: CancellationException) {
                    // Still holding the task, so a replacing worker can't have started yet.
                    // 暂停 from the notification cancels the work: paused. Stopped by the system
                    // (network lost, quota): waiting, WorkManager runs it again. Queued / Paused
                    // were set by the store (暂停, 继续, another quality) and stay.
                    // (stopReason is marked API 31 but is WorkManager's own field, filled on every level.)
                    @android.annotation.SuppressLint("NewApi")
                    val cancelled = stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
                    withContext(NonCancellable) {
                        DownloadStore.update(id) {
                            when {
                                it.state != DownloadState.Downloading && it.state != DownloadState.Muxing -> it
                                cancelled -> it.copy(state = DownloadState.Paused)
                                else -> it.copy(state = DownloadState.Queued)
                            }
                        }
                    }
                    DownloadStore.reportProgress(id, null)
                    throw e
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DownloadStore.reportProgress(id, null)
            if (DownloadStore.get(id) == null) {
                withContext(Dispatchers.IO) { DownloadStore.dir(ctx, id).deleteRecursively() }
                return Result.success()
            }
            if (runAttemptCount < 3 && e is IOException) {
                DownloadStore.update(id) { it.copy(state = DownloadState.Queued) }
                Result.retry()
            } else {
                DownloadStore.update(id) { it.copy(state = DownloadState.Failed, error = e.message?.takeIf { m -> m.isNotBlank() } ?: "缓存失败") }
                Result.failure()
            }
        } finally {
            // A progress update racing the stop must not outlive the task.
            runCatching { NotificationManagerCompat.from(ctx).cancel(notificationId) }
        }
    }

    private suspend fun run() {
        val ctx = applicationContext
        var item = DownloadStore.update(id) { it.copy(state = DownloadState.Downloading, error = "") } ?: return
        if (!item.metaFilled) item = fillMeta(item)
        val dir = DownloadStore.dir(ctx, id).apply { mkdirs() }
        saveCover(item, DownloadStore.coverFile(ctx, id))

        val info = PlayUrlApi.fetch(item.playTarget(), item.qn)
        if (info.isDash) {
            val video = pickVideo(info, item) ?: throw IllegalStateException("没有可缓存的视频流")
            val audio = pickAudio(info, item)
            val videoPart = File(dir, "video.m4s.part")
            val audioPart = File(dir, "audio.m4s.part")
            val vTotal = probe(video.urls)
            val aTotal = audio?.let { probe(it.urls) } ?: 0L
            // A different stream than the parts were started from: start those over.
            if (item.videoBytes != vTotal || item.codecId != video.codecId) videoPart.delete()
            if (item.audioBytes != aTotal || item.audioId != (audio?.id ?: 0)) audioPart.delete()
            item = DownloadStore.update(id) {
                it.copy(
                    qn = video.id,
                    qualityLabel = qualityLabel(info, video.id),
                    codecId = video.codecId,
                    audioId = audio?.id ?: 0,
                    videoBytes = vTotal,
                    audioBytes = aTotal,
                    totalBytes = vTotal + aTotal,
                    durationMs = info.timelengthMs.takeIf { d -> d > 0 } ?: it.durationMs,
                )
            } ?: return
            val total = vTotal + aTotal
            val meter = Meter(total) { videoPart.length() + audioPart.length() }
            fetch(video.urls, videoPart, vTotal, item, meter)
            if (audio != null) fetch(audio.urls, audioPart, aTotal, item, meter)

            DownloadStore.update(id) { it.copy(state = DownloadState.Muxing, doneBytes = total) }
            DownloadStore.reportProgress(id, LiveProgress(total, total, 0))
            notifyProgress(item.title, 100, muxing = true)
            val videoFile = File(dir, "video.m4s").also { f -> f.delete(); videoPart.renameTo(f) }
            val audioFile = if (audio != null) File(dir, "audio.m4s").also { f -> f.delete(); audioPart.renameTo(f) } else null
            val out = File(dir, "video.mp4")
            val muxed = runCatching { withContext(Dispatchers.IO) { Muxer.mux(videoFile, audioFile, out) } }
                .onFailure { if (it is CancellationException) throw it }
                .isSuccess
            if (muxed) {
                videoFile.delete()
                audioFile?.delete()
            }
            val size = if (muxed) out.length() else videoFile.length() + (audioFile?.length() ?: 0)
            finish(
                fileName = if (muxed) out.name else videoFile.name,
                audioFileName = if (muxed) "" else audioFile?.name.orEmpty(),
                size = size,
            )
        } else {
            val seg = info.durl.singleOrNull() ?: throw IllegalStateException("分段视频暂不支持缓存")
            val ext = seg.urls.first().substringBefore('?').substringAfterLast('.', "mp4").takeIf { it.length in 2..4 } ?: "mp4"
            val part = File(dir, "video.$ext.part")
            val total = probe(seg.urls)
            if (item.videoBytes != total) part.delete()
            item = DownloadStore.update(id) {
                it.copy(
                    qn = info.quality,
                    qualityLabel = qualityLabel(info, info.quality),
                    videoBytes = total,
                    audioBytes = 0,
                    totalBytes = total,
                    durationMs = info.timelengthMs.takeIf { d -> d > 0 } ?: it.durationMs,
                )
            } ?: return
            fetch(seg.urls, part, total, item, Meter(total) { part.length() })
            val out = File(dir, "video.$ext").also { f -> f.delete(); part.renameTo(f) }
            finish(fileName = out.name, audioFileName = "", size = out.length())
        }
    }

    private suspend fun finish(fileName: String, audioFileName: String, size: Long) {
        val done = DownloadStore.update(id) {
            it.copy(
                state = DownloadState.Done,
                fileName = fileName,
                audioFileName = audioFileName,
                totalBytes = size,
                doneBytes = size,
                error = "",
            )
        }
        DownloadStore.reportProgress(id, null)
        if (done == null) {
            // Deleted while finishing.
            withContext(Dispatchers.IO) { DownloadStore.dir(applicationContext, id).deleteRecursively() }
            return
        }
        notifyDone(done.title)
    }

    // ── Streams ──────────────────────────────────────────────────────────────

    private fun qualityLabel(info: PlayInfo, qn: Int): String =
        info.qualities.firstOrNull { it.qn == qn }?.label?.takeIf { it.isNotBlank() } ?: Qualities.videoName(qn)

    /**
     * The asked quality (or the best below it) in a codec MediaMuxer writes into MP4:
     * the one the parts were started with, else AVC, HEVC, then AV1 (Android 14+).
     */
    private fun pickVideo(info: PlayInfo, item: DownloadItem): MediaStream? {
        val ids = info.videos.map { it.id }.distinct()
        val qn = ids.filter { it <= item.qn }.maxOrNull() ?: ids.minOrNull() ?: return null
        val same = info.videos.filter { it.id == qn }
        val order = buildList {
            if (item.codecId != 0) add(item.codecId)
            add(7)
            add(12)
            if (Build.VERSION.SDK_INT >= 34) add(13)
        }
        return order.firstNotNullOfOrNull { c -> same.firstOrNull { it.codecId == c } } ?: same.firstOrNull()
    }

    /** The best AAC track (Dolby / Hi-Res are E-AC-3 / FLAC, which MediaMuxer won't put in MP4). */
    private fun pickAudio(info: PlayInfo, item: DownloadItem): MediaStream? {
        val aac = info.audios.filter { it.id in setOf(30216, 30232, 30280) }
        return aac.firstOrNull { it.id == item.audioId }
            ?: aac.maxByOrNull { Qualities.audioRank(it.id) }
            ?: info.audios.firstOrNull()
    }

    private fun ordered(urls: List<String>): List<String> = runCatching { CdnRouter.orderUrls(urls) }.getOrDefault(urls)

    private fun request(url: String, from: Long, to: Long? = null): Request =
        Request.Builder()
            .url(url)
            .apply { BiliClient.mediaHeaders().forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=$from-${to ?: ""}")
            .build()

    /** Total size of the stream (a one-byte range answers it in Content-Range). */
    private suspend fun probe(urls: List<String>): Long = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for (url in ordered(urls)) {
            currentCoroutineContext().ensureActive()
            try {
                http.newCall(request(url, 0, 0)).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: r.body.contentLength().takeIf { r.code == 200 }
                    if (total != null && total > 0) return@withContext total
                    throw IOException("无法获取文件大小")
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("网络错误")
    }

    private inner class Meter(val total: Long, val done: () -> Long) {
        private var windowStart = SystemClock.elapsedRealtime()
        private var windowBytes = done()
        var speed = 0L

        fun tick(): Long {
            val now = SystemClock.elapsedRealtime()
            val d = done()
            if (now - windowStart >= 1000) {
                speed = (d - windowBytes) * 1000 / (now - windowStart)
                windowStart = now
                windowBytes = d
            }
            return d
        }
    }

    /** Appends to [part] from its current length until [total], trying each URL in turn. */
    private suspend fun fetch(urls: List<String>, part: File, total: Long, item: DownloadItem, meter: Meter) =
        withContext(Dispatchers.IO) {
            var last: Throwable? = null
            val candidates = ordered(urls)
            for (attempt in 0 until candidates.size * 2) {
                val url = candidates[attempt % candidates.size]
                var have = part.length()
                if (have > total) {
                    part.delete()
                    have = 0
                }
                if (have == total) return@withContext
                try {
                    http.newCall(request(url, have)).execute().use { r ->
                        val append = when (r.code) {
                            206 -> true
                            200 -> false
                            416 -> {
                                part.delete()
                                throw IOException("HTTP 416")
                            }
                            else -> throw IOException("HTTP ${r.code}")
                        }
                        FileOutputStream(part, append).use { out ->
                            val input = r.body.byteStream()
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                onBytes(item, meter)
                            }
                        }
                    }
                    if (part.length() == total) return@withContext
                    last = IOException("连接中断")
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last ?: IOException("网络错误")
        }

    private suspend fun onBytes(item: DownloadItem, meter: Meter) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 500) return
        lastNotify = now
        val done = meter.tick()
        DownloadStore.reportProgress(id, LiveProgress(done, meter.total, meter.speed))
        val percent = if (meter.total > 0) (done * 100 / meter.total).toInt() else -1
        notifyProgress(item.title, percent, muxing = false)
        if (now - lastSave > 3000) {
            lastSave = now
            DownloadStore.update(id) { it.copy(doneBytes = done) }
        }
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    private suspend fun fillMeta(item: DownloadItem): DownloadItem {
        val filled = runCatching {
            if (item.isPgc) {
                val d = BiliClient.getData("/pgc/view/web/season", mapOf("ep_id" to item.epId)).asObj!!
                val ep = (d.arr("episodes").objects() + d.arr("section").objects().flatMap { it.arr("episodes").objects() })
                    .firstOrNull { it.long("id") == item.epId || it.long("cid") == item.cid }
                val show = ep?.let { e ->
                    e.str("show_title").ifEmpty { listOf(e.str("title"), e.str("long_title")).filter { it.isNotEmpty() }.joinToString(" ") }
                }.orEmpty()
                item.copy(
                    title = d.str("season_title").ifEmpty { d.str("title") }.ifEmpty { item.title },
                    partTitle = show,
                    cover = ep?.str("cover")?.ifEmpty { null } ?: d.str("cover").ifEmpty { item.cover },
                    owner = d.obj("up_info").str("uname").ifEmpty { item.owner },
                    seasonId = d.long("season_id").takeIf { it > 0 } ?: item.seasonId,
                    durationMs = ep?.long("duration")?.takeIf { it > 0 } ?: item.durationMs,
                    metaFilled = true,
                )
            } else {
                val params = if (item.bvid.isNotBlank()) mapOf("bvid" to item.bvid) else mapOf("aid" to item.aid)
                val d = BiliClient.getData("/x/web-interface/wbi/view", params, wbi = true).asObj!!
                val pages = d.arr("pages").objects()
                val page = pages.firstOrNull { it.long("cid") == item.cid }
                item.copy(
                    title = d.str("title").ifEmpty { item.title },
                    partTitle = if (pages.size > 1) page?.str("part").orEmpty() else "",
                    cover = d.str("pic").ifEmpty { item.cover },
                    owner = d.obj("owner").str("name").ifEmpty { item.owner },
                    aid = d.long("aid").takeIf { it > 0 } ?: item.aid,
                    bvid = d.str("bvid").ifEmpty { item.bvid },
                    durationMs = (page?.int("duration") ?: d.int("duration")).toLong().takeIf { it > 0 }?.times(1000) ?: item.durationMs,
                    metaFilled = true,
                )
            }
        }.getOrNull() ?: return item
        return DownloadStore.update(id) {
            it.copy(
                title = filled.title,
                partTitle = filled.partTitle,
                cover = filled.cover,
                owner = filled.owner,
                aid = filled.aid,
                bvid = filled.bvid,
                seasonId = filled.seasonId,
                durationMs = filled.durationMs,
                metaFilled = true,
            )
        } ?: filled
    }

    private suspend fun saveCover(item: DownloadItem, file: File) {
        if (file.exists() || item.cover.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val url = BiliClient.resolve(item.cover).replace("http://", "https://")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.outputStream().use { r.body.byteStream().copyTo(it) }
                    tmp.renameTo(file)
                }
            }
        }
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private fun openApp(): PendingIntent? {
        val ctx = applicationContext
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildProgress(title: String, percent: Int, muxing: Boolean) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "离线缓存" })
            .setContentText(
                when {
                    muxing -> "合并中"
                    percent < 0 -> "等待中"
                    else -> "$percent%"
                },
            )
            .setProgress(100, percent.coerceAtLeast(0), percent < 0 || muxing)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .apply {
                if (percent >= 0 && !muxing) {
                    addAction(0, "暂停", WorkManager.getInstance(applicationContext).createCancelPendingIntent(getId()))
                }
            }
            .build()

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = buildProgress(title, percent, muxing = false)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, n)
        }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyProgress(title: String, percent: Int, muxing: Boolean) {
        if (!canNotify()) return
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, buildProgress(title, percent, muxing))
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyDone(title: String) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "离线缓存" })
            .setContentText("缓存完成")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(notificationId + 0x10000, n) }
    }
}
