package com.gpo.pilipala.feature.video

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.gpo.pilipala.MainActivity
import com.gpo.pilipala.R
import com.gpo.pilipala.core.settings.AppSettings
import com.gpo.pilipala.core.util.Images
import com.gpo.pilipala.feature.player.BiliPlayerState
import com.gpo.pilipala.feature.widget.ContinueEntry
import com.gpo.pilipala.feature.widget.WatchWidget
import com.gpo.pilipala.feature.widget.WidgetStore
import com.gpo.pilipala.navigation.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Launcher shortcuts (long-press the app icon, or drag one onto the home screen):
 *
 * - 继续看: the video last left part-way, its cover as the icon with how far you got
 *   drawn in, and a tap opens it right there. Kept fresh whenever a video page pauses or
 *   closes; dropped once the video is finished or while 暂停历史记录 is on. Its id is
 *   fixed, so a copy pinned to the home screen always resumes the latest video.
 * - 稍后再看, 历史记录, 搜索: straight into those pages.
 *
 * All dynamic (not shortcuts.xml): a static shortcut relaunches the task from scratch,
 * these come back through onNewIntent and keep whatever is open (and playing).
 *
 * The home-screen widget (feature/widget) shows the same 继续看 and opens videos and
 * 稍后再看 through the same intents ([videoIntent], [pageIntent]).
 */
object AppShortcuts {
    private const val Scheme = "pilipala"
    private const val IdContinue = "continue"
    private const val IdLater = "later"
    private const val IdHistory = "history"
    private const val IdSearch = "search"

    private const val ExtraShortcut = "com.gpo.pilipala.extra.SHORTCUT"
    private const val ExtraBvid = "com.gpo.pilipala.extra.CONTINUE_BVID"
    private const val ExtraCid = "com.gpo.pilipala.extra.CONTINUE_CID"
    private const val ExtraProgress = "com.gpo.pilipala.extra.CONTINUE_PROGRESS"
    private const val ExtraCover = "com.gpo.pilipala.extra.CONTINUE_COVER"
    private const val ExtraTitle = "com.gpo.pilipala.extra.CONTINUE_TITLE"
    private const val ExtraDuration = "com.gpo.pilipala.extra.CONTINUE_DURATION"

    /** Set on the widget's intents: a tap there is not a shortcut use. */
    private const val ExtraWidget = "com.gpo.pilipala.extra.FROM_WIDGET"

    /** Watched less than this: not worth a shortcut. */
    private const val MinWatchedMs = 10_000L

    /** This close to the end counts as finished. */
    private const val FinishedTailMs = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var continueJob: Job? = null

    /** Last cover drawn into an icon, so a position update doesn't fetch it again. */
    @Volatile private var coverCache: Pair<String, Bitmap>? = null

    /** The fixed shortcuts; called on launch (dynamic shortcuts aren't restored on a new device). */
    fun publishFixed(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                ShortcutManagerCompat.pushDynamicShortcut(app, fixed(app, IdLater, "稍后再看", R.drawable.ic_shortcut_later, rank = 1))
                ShortcutManagerCompat.pushDynamicShortcut(app, fixed(app, IdHistory, "历史记录", R.drawable.ic_shortcut_history, rank = 2))
                ShortcutManagerCompat.pushDynamicShortcut(app, fixed(app, IdSearch, "搜索", R.drawable.ic_shortcut_search, rank = 3))
            }
        }
        // A placed widget catches up with the account (signed in or out since) on launch.
        WatchWidget.refresh(app)
    }

    private fun fixed(context: Context, id: String, label: String, icon: Int, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse("$Scheme://$id"), context, MainActivity::class.java)
                    .putExtra(ExtraShortcut, id),
            )
            .setRank(rank)
            .build()

    /**
     * Opens [bvid] at [progressSec] (MainActivity routes it like 继续看). [source] marks
     * the widget's intents and tells them apart from the shortcut's and from each other:
     * PendingIntents that differ only in extras would collapse into one.
     */
    fun videoIntent(
        context: Context,
        bvid: String,
        cid: Long,
        title: String,
        cover: String,
        progressSec: Long,
        durationSec: Long = 0,
        source: String? = null,
    ): Intent {
        val base = Uri.parse("https://www.bilibili.com/video/$bvid")
        val uri = if (source == null) base else base.buildUpon().fragment(source).build()
        return Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .putExtra(ExtraShortcut, IdContinue)
            .putExtra(ExtraBvid, bvid)
            .putExtra(ExtraCid, cid)
            .putExtra(ExtraProgress, progressSec.coerceAtLeast(0))
            .putExtra(ExtraDuration, durationSec.coerceAtLeast(0))
            .putExtra(ExtraCover, cover)
            .putExtra(ExtraTitle, title)
            .apply { if (source != null) putExtra(ExtraWidget, true) }
    }

    /** One of the fixed pages ([PageLater] …), opened from the widget. */
    fun pageIntent(context: Context, page: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("$Scheme://$page"), context, MainActivity::class.java)
            .putExtra(ExtraShortcut, page)
            .putExtra(ExtraWidget, true)

    const val PageLater: String = IdLater

    /** What the published 继续看 shortcut points at. */
    class ContinueSnapshot(
        val bvid: String,
        val cid: Long,
        val title: String,
        val cover: String,
        val positionSec: Long,
        /** 0 for a shortcut published before the duration rode along. */
        val durationSec: Long,
    )

    /** The live 继续看 shortcut, for a widget placed before anything else recorded it. */
    fun continueSnapshot(context: Context): ContinueSnapshot? = runCatching {
        val info = ShortcutManagerCompat.getDynamicShortcuts(context.applicationContext)
            .firstOrNull { it.id == IdContinue }
        val intent = info?.intents?.lastOrNull()
        val bvid = intent?.getStringExtra(ExtraBvid)
        if (intent == null || bvid.isNullOrBlank()) {
            null
        } else {
            ContinueSnapshot(
                bvid = bvid,
                cid = intent.getLongExtra(ExtraCid, 0L),
                title = intent.getStringExtra(ExtraTitle).orEmpty(),
                cover = intent.getStringExtra(ExtraCover).orEmpty(),
                positionSec = intent.getLongExtra(ExtraProgress, 0L),
                durationSec = intent.getLongExtra(ExtraDuration, 0L),
            )
        }
    }.getOrNull()

    /**
     * Updates 继续看 for the video being left at [positionMs] of [durationMs]; removes it
     * when the video is finished or history is paused.
     */
    fun publishContinue(
        context: Context,
        bvid: String,
        cid: Long,
        title: String,
        cover: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (bvid.isBlank()) return
        val app = context.applicationContext
        val finished = durationMs > 0 && positionMs >= durationMs - FinishedTailMs
        if (AppSettings.pauseHistory.value || finished) {
            continueJob?.cancel()
            runCatching { ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(IdContinue)) }
            // In step with the shortcut (a tiny file), so a later publish can't be undone by it.
            WidgetStore.clearContinue(app)
            WatchWidget.refresh(app)
            return
        }
        if (positionMs < MinWatchedMs) return
        val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        WidgetStore.writeContinue(
            app,
            ContinueEntry(bvid = bvid, cid = cid, title = title, cover = cover, positionMs = positionMs, durationMs = durationMs),
        )
        WatchWidget.refresh(app)
        continueJob?.cancel()
        continueJob = scope.launch {
            val icon = runCatching { coverIcon(app, cover, progress, durationMs > 0) }.getOrNull()
                ?: IconCompat.createWithResource(app, R.drawable.ic_shortcut_history)
            val label = title.ifBlank { bvid }
            val intent = videoIntent(app, bvid, cid, title, cover, positionMs / 1000, durationMs / 1000)
            val info = ShortcutInfoCompat.Builder(app, IdContinue)
                .setShortLabel(label.take(12))
                .setLongLabel(label.take(25))
                .setIcon(icon)
                .setIntent(intent)
                .setRank(0)
                .build()
            runCatching { ShortcutManagerCompat.pushDynamicShortcut(app, info) }
        }
    }

    /** The page a shortcut's intent opens, or null when it isn't one of ours. */
    fun route(context: Context, intent: Intent): Route? {
        val id = intent.getStringExtra(ExtraShortcut)
        val bvid = intent.getStringExtra(ExtraBvid)
        val route = when {
            id == IdContinue && !bvid.isNullOrBlank() -> Route.Video(
                bvid = bvid,
                cid = intent.getLongExtra(ExtraCid, 0L),
                cover = intent.getStringExtra(ExtraCover).orEmpty(),
                title = intent.getStringExtra(ExtraTitle).orEmpty(),
                progress = intent.getLongExtra(ExtraProgress, 0L),
            )
            intent.data?.scheme == Scheme -> when (intent.data?.host) {
                IdLater -> Route.Later
                IdHistory -> Route.History
                IdSearch -> Route.Search()
                else -> null
            }
            else -> null
        }
        if (route != null && id != null && !intent.getBooleanExtra(ExtraWidget, false)) {
            runCatching { ShortcutManagerCompat.reportShortcutUsed(context, id) }
        }
        return route
    }

    /**
     * The cover as an adaptive icon: centre-cropped over the whole 108dp canvas (the
     * launcher's mask shows its middle), a scrim along the bottom and a progress pill
     * inside the safe zone.
     */
    private suspend fun coverIcon(context: Context, cover: String, progress: Float, showProgress: Boolean): IconCompat? {
        val url = Images.normalize(cover).ifEmpty { return null }
        val src = coverCache?.takeIf { it.first == url }?.second ?: run {
            val request = ImageRequest.Builder(context)
                .data(Images.thumb(url, 480))
                .allowHardware(false)
                .build()
            val bmp = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
            coverCache = url to bmp
            bmp
        }
        val size = IconPx
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = max(size.toFloat() / src.width, size.toFloat() / src.height)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        if (showProgress) {
            val s = size.toFloat()
            // Scrim under the pill so it reads on bright covers (a scrim over an image).
            val scrim = Paint().apply {
                shader = LinearGradient(0f, s * 0.52f, 0f, s * 0.82f, 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, s * 0.52f, s, s, scrim)
            // The pill: inside the 66dp safe circle (radius 0.306 of the canvas) at ~62% of its radius below centre.
            val cx = s / 2f
            val cy = s / 2f + s * 0.306f * 0.6f
            val w = s * 0.34f
            val h = s * 0.034f
            val track = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0x66FFFFFF
            canvas.drawRoundRect(track, h / 2f, h / 2f, paint)
            paint.color = 0xFFFFFFFF.toInt()
            val filled = RectF(track.left, track.top, track.left + max(h, w * progress), track.bottom)
            canvas.drawRoundRect(filled, h / 2f, h / 2f, paint)
        }
        return IconCompat.createWithAdaptiveBitmap(out)
    }

    /** 108dp at xxxhdpi. */
    private const val IconPx = 432
}

/**
 * Keeps 继续看 pointing at this page's video: published as the page pauses (the app
 * leaving, a sheet over it) and when the page closes.
 */
@Composable
internal fun ContinueShortcutEffect(vm: VideoDetailViewModel, player: BiliPlayerState) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    DisposableEffect(vm, player, owner) {
        fun publish() {
            val t = vm.target ?: return
            AppShortcuts.publishContinue(
                context = context,
                bvid = t.bvid.ifEmpty { vm.bvid },
                cid = t.cid,
                // The video's own title and cover (a part's target may carry its part name / first frame).
                title = vm.detail?.takeIf { it.bvid == t.bvid }?.title?.ifEmpty { null } ?: t.title,
                cover = vm.detail?.takeIf { it.bvid == t.bvid }?.pic?.ifEmpty { null } ?: t.cover,
                positionMs = player.positionMs,
                durationMs = player.durationMs,
            )
        }
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) publish() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            publish()
        }
    }
}
