package com.gpo.pilipala.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gpo.pilipala.MainActivity
import com.gpo.pilipala.R
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.feeds.Feed
import com.gpo.pilipala.feature.feeds.FeedStore
import com.gpo.pilipala.feature.video.AppShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.gpo.yoin.symbols.R as SymbolsR

class FeedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FeedWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        FeedWidget.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        FeedWidget.cancelSchedule(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        FeedWidgetStore.unbind(context, appWidgetIds)
        FeedWidgetStore.prune(context)
    }
}

/**
 * One custom feed (feature/feeds) on the home screen: its name, then the latest mixed
 * videos with covers, titles and UP names. The feed is picked on placement
 * ([FeedWidgetConfigActivity], reconfigurable). A video opens its page; the header opens
 * the feed's tab on 首页.
 *
 * Data: [FeedWidgetStore]'s cached first page, refetched when older than 30 minutes on
 * an update or when the app opens, and every ~45 minutes by [FeedWidgetWorker]. The app's
 * own colours ([WidgetColors]), the system's widget corner radii, one layout per size bucket.
 */
class FeedWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(Small, Medium, Wide, Large, WideLarge, Tall))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
        val loadedAt = version.value
        val initial = load(context, appWidgetId)
        val initialColors = WidgetColors.provide(context)
        provideContent {
            val v by version.collectAsState()
            var data by remember { mutableStateOf(initial) }
            var colors by remember { mutableStateOf(initialColors) }
            LaunchedEffect(v) {
                if (v != loadedAt) {
                    data = load(context, appWidgetId)
                    colors = WidgetColors.provide(context)
                }
            }
            GlanceTheme(colors = colors) {
                FeedContent(data, appWidgetId)
            }
        }
    }

    companion object {
        internal val Small = DpSize(110.dp, 110.dp)
        internal val Medium = DpSize(250.dp, 110.dp)
        internal val Wide = DpSize(400.dp, 110.dp)
        internal val Large = DpSize(250.dp, 240.dp)
        internal val WideLarge = DpSize(400.dp, 240.dp)
        internal val Tall = DpSize(250.dp, 380.dp)

        /** MainActivity: open this feed's tab on 首页. */
        const val ExtraFeedId = "com.gpo.pilipala.widget.FEED_ID"

        /** MainActivity: open the feed editor (the picker had no feed to offer). */
        const val ExtraNewFeed = "com.gpo.pilipala.widget.NEW_FEED"

        private const val WorkName = "feed_widget_refresh"
        private const val PeriodMin = 45L
        private const val PendingKey = "pending_new"

        // Covers travel in one RemoteViews parcel shared by every size bucket: 9 × ~45KB.
        private const val CoverW = 224
        private const val CoverH = 126

        private val version = MutableStateFlow(0L)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var pending: Job? = null
        private const val DebounceMs = 600L

        private suspend fun placed(context: Context): Boolean =
            runCatching { GlanceAppWidgetManager(context).getGlanceIds(FeedWidget::class.java).isNotEmpty() }.getOrDefault(false)

        /** Re-renders placed widgets; their data reloads, fetching the feeds whose cache is stale. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            synchronized(this) {
                pending?.cancel()
                pending = scope.launch {
                    delay(DebounceMs)
                    version.update { it + 1 }
                    runCatching { if (placed(app)) FeedWidget().updateAll(app) }
                }
            }
        }

        /** The app opened: keep the periodic refresh scheduled and catch up on stale feeds. */
        fun onAppOpen(context: Context) {
            val app = context.applicationContext
            scope.launch {
                if (!placed(app)) return@launch
                schedule(app)
                refresh(app)
            }
        }

        /**
         * Feeds changed (created, renamed, edited, deleted). A widget whose picker sent the
         * owner to make a feed shows the first one made after that; placed widgets re-render.
         */
        internal fun onFeedsChanged(context: Context, feeds: List<Feed>) {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("feed_widget_pending", Context.MODE_PRIVATE)
            val widgetId = prefs.getInt(PendingKey, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val since = prefs.getLong("$PendingKey.at", 0L)
                feeds.filter { it.createdAt >= since }.maxByOrNull { it.createdAt }?.let { feed ->
                    FeedWidgetStore.bind(app, widgetId, feed.id)
                    prefs.edit().clear().apply()
                }
            }
            refresh(app)
        }

        internal fun markPendingNew(context: Context, appWidgetId: Int) {
            context.applicationContext.getSharedPreferences("feed_widget_pending", Context.MODE_PRIVATE).edit()
                .putInt(PendingKey, appWidgetId)
                .putLong("$PendingKey.at", System.currentTimeMillis())
                .apply()
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FeedWidgetWorker>(PeriodMin, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(WorkName, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        fun cancelSchedule(context: Context) {
            runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WorkName) }
        }

        /** What one widget draws: its feed (null = not picked or deleted), the page and its covers. */
        internal suspend fun load(context: Context, appWidgetId: Int): FeedWidgetData = coroutineScope {
            val feedId = withContext(Dispatchers.IO) { FeedWidgetStore.boundFeed(context, appWidgetId) }
            val feed = feedId?.let { FeedStore.get(it) }
            if (feed == null) return@coroutineScope FeedWidgetData(feed = null, items = emptyList(), covers = emptyList())
            val cache = FeedWidgetStore.forWidget(context, feed)
            val items = cache?.items.orEmpty().take(FeedWidgetStore.Keep)
            val covers = items.map { async { WidgetData.cover(context, it.cover, CoverW, CoverH) } }.awaitAll()
            FeedWidgetData(feed = feed, items = items, covers = covers)
        }
    }
}

/** Every 45 minutes or so (online): refetch the feeds placed widgets show, then re-render. */
class FeedWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val ids = FeedWidgetStore.boundFeeds(app)
        if (ids.isEmpty()) return Result.success()
        ids.mapNotNull { FeedStore.get(it) }.forEach { feed ->
            runCatching { FeedWidgetStore.refetch(app, feed) }
        }
        FeedWidget.refresh(app)
        return Result.success()
    }
}

internal class FeedWidgetData(
    val feed: Feed?,
    val items: List<FeedWidgetItem>,
    val covers: List<Bitmap?>,
)

private val Pad = 12.dp
private val Gap = 8.dp
private val WidgetFont = FontFamily("google-sans-flex")

private fun style(color: ColorProvider, size: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = WidgetFont)

private fun GlanceModifier.surface(color: ColorProvider): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(color).cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        background(ImageProvider(R.drawable.widget_surface), colorFilter = ColorFilter.tint(color))
    }

private fun GlanceModifier.block(color: ColorProvider): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(color).cornerRadius(android.R.dimen.system_app_widget_inner_radius)
    } else {
        background(ImageProvider(R.drawable.widget_block), colorFilter = ColorFilter.tint(color))
    }

private fun open(intent: Intent): Action = actionStartActivity(intent)

private fun videoIntent(context: Context, e: FeedWidgetItem): Intent =
    AppShortcuts.videoIntent(
        context, e.bvid, e.cid, e.title, e.cover,
        progressSec = 0,
        durationSec = e.duration,
        source = "widget-feed",
    )

private fun feedIntent(context: Context, feedId: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("pilipaw-widget://feed/$feedId"), context, MainActivity::class.java)
        .putExtra(FeedWidget.ExtraFeedId, feedId)

private fun configIntent(context: Context, appWidgetId: Int): Intent =
    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE, Uri.parse("pilipaw-widget://config/$appWidgetId"), context, FeedWidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

@Composable
private fun FeedContent(data: FeedWidgetData, appWidgetId: Int) {
    val size = LocalSize.current
    val feed = data.feed
    Box(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .surface(GlanceTheme.colors.widgetBackground)
            .padding(Pad),
    ) {
        when {
            feed == null -> Unpicked(appWidgetId)
            data.items.isEmpty() -> Column(GlanceModifier.fillMaxSize()) {
                Header(feed, compact = size.width < FeedWidget.Medium.width)
                Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    Text("暂无视频", style = style(GlanceTheme.colors.onSurfaceVariant, 12.sp), maxLines = 1)
                }
            }
            else -> Column(GlanceModifier.fillMaxSize()) {
                val cols = if (size.width >= FeedWidget.Wide.width) 3 else 2
                Header(feed, compact = size.width < FeedWidget.Medium.width)
                Spacer(GlanceModifier.height(Gap))
                when {
                    size.width < FeedWidget.Medium.width -> Tile(data, 0, GlanceModifier.fillMaxSize(), titleLines = 2, owner = false)
                    size.height < FeedWidget.Large.height -> Grid(data, cols = cols, rows = 1, titleLines = 1, owner = false)
                    size.height >= FeedWidget.Tall.height && cols == 2 -> ListRows(data, rowsFor(size.height))
                    else -> Grid(data, cols = cols, rows = if (size.height >= FeedWidget.Tall.height) 3 else 2, titleLines = 1, owner = true)
                }
            }
        }
    }
}

private val RowHeight = 64.dp

/** As many list rows as the height holds (header and padding off the top). */
private fun rowsFor(height: Dp): Int =
    ((height.value - 2 * Pad.value - 32f) / (RowHeight.value + 6f)).toInt().coerceIn(1, FeedWidgetStore.Keep)

/** The feed's name (opens its tab); a glyph before it when there is room. */
@Composable
private fun Header(feed: Feed, compact: Boolean) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(open(feedIntent(context, feed.id))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            Glyph(SymbolsR.drawable.ic_yoin_sparkle, colors.primary, 16)
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(feed.name, style = style(colors.onSurface, 14.sp, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Glyph(SymbolsR.drawable.ic_yoin_chevron_right, colors.onSurfaceVariant, 16)
    }
}

/** [rows] × [cols] cover tiles, each row taking an equal share of the height. */
@Composable
private fun Grid(data: FeedWidgetData, cols: Int, rows: Int, titleLines: Int, owner: Boolean) {
    Column(GlanceModifier.fillMaxSize()) {
        for (r in 0 until rows) {
            val first = r * cols
            if (first >= data.items.size) break
            if (r > 0) Spacer(GlanceModifier.height(Gap))
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                for (c in 0 until cols) {
                    if (c > 0) Spacer(GlanceModifier.width(Gap))
                    val i = first + c
                    if (i < data.items.size) {
                        Tile(data, i, GlanceModifier.defaultWeight().fillMaxHeight(), titleLines, owner)
                    } else {
                        Spacer(GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

/** Cover (taking the spare height) over its title and, with room, the UP's name. */
@Composable
private fun Tile(data: FeedWidgetData, index: Int, modifier: GlanceModifier, titleLines: Int, owner: Boolean) {
    val item = data.items.getOrNull(index) ?: return
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Column(modifier.clickable(open(videoIntent(context, item)))) {
        Cover(data.covers.getOrNull(index), GlanceModifier.fillMaxWidth().defaultWeight())
        Spacer(GlanceModifier.height(4.dp))
        Text(item.title.ifBlank { item.bvid }, style = style(colors.onSurface, 12.sp, FontWeight.Medium), maxLines = titleLines)
        if (owner && item.owner.isNotBlank()) {
            Text(item.owner, style = style(colors.onSurfaceVariant, 11.sp), maxLines = 1)
        }
    }
}

/** Tall and narrow: rows with a thumbnail, the title and the UP's name. */
@Composable
private fun ListRows(data: FeedWidgetData, rows: Int) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize()) {
        data.items.take(rows).forEachIndexed { i, item ->
            if (i > 0) Spacer(GlanceModifier.height(6.dp))
            Row(
                GlanceModifier
                    .fillMaxWidth()
                    .height(RowHeight)
                    .block(colors.secondaryContainer)
                    .padding(4.dp)
                    .clickable(open(videoIntent(context, item))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(data.covers.getOrNull(i), GlanceModifier.width(100.dp).fillMaxHeight(), duration = item.duration)
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight().padding(end = 6.dp)) {
                    Text(item.title.ifBlank { item.bvid }, style = style(colors.onSecondaryContainer, 12.sp, FontWeight.Medium), maxLines = 2)
                    if (item.owner.isNotBlank()) {
                        Text(item.owner, style = style(colors.onSurfaceVariant, 11.sp), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Cover(cover: Bitmap?, modifier: GlanceModifier, duration: Long = 0) {
    val colors = GlanceTheme.colors
    Box(modifier.block(colors.secondaryContainer), contentAlignment = Alignment.Center) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize(),
            )
        } else {
            Glyph(SymbolsR.drawable.ic_yoin_play_filled, colors.onSecondaryContainer, 20)
        }
        if (cover != null && duration > 0) {
            Box(GlanceModifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.BottomEnd) {
                Box(GlanceModifier.block(colors.surface).padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text(Fmt.duration(duration), style = style(colors.onSurface, 10.sp, FontWeight.Medium), maxLines = 1)
                }
            }
        }
    }
}

/** No feed picked (or it was deleted): a tap opens the picker. */
@Composable
private fun Unpicked(appWidgetId: Int) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Column(
        GlanceModifier.fillMaxSize().clickable(open(configIntent(context, appWidgetId))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(GlanceModifier.size(44.dp).block(colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Glyph(SymbolsR.drawable.ic_yoin_sparkle, colors.onSecondaryContainer, 22)
        }
        Spacer(GlanceModifier.height(8.dp))
        Text("选择 Feed", style = style(colors.onSurface, 14.sp, FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun Glyph(@DrawableRes icon: Int, tint: ColorProvider, sizeDp: Int) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = GlanceModifier.size(sizeDp.dp),
    )
}
