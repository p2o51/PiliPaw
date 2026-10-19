package com.gpo.pilipala.feature.widget

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
import androidx.compose.ui.graphics.Color
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
import androidx.glance.appwidget.LinearProgressIndicator
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
import com.gpo.pilipala.MainActivity
import com.gpo.pilipala.R
import com.gpo.pilipala.core.util.Fmt
import com.gpo.pilipala.feature.video.AppShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.gpo.yoin.symbols.R as SymbolsR

class WatchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WatchWidget()
}

/**
 * 继续看 on the home screen: the video last left part-way, large, with its cover and how
 * far you got; beside or below it the first 稍后再看 entries when logged in.
 *
 * The app's own colours ([WidgetColors]), the system's widget corner radius, and a layout
 * per size class: small shows 继续看 alone, medium puts the list beside it, large and tall
 * stack the list under it with thumbnails. [SizeMode.Exact], so a layout knows the real
 * height (portrait and landscape cells differ) and the list fills it instead of stopping
 * at a bucket's minimum; [Small]…[Tall] are the class thresholds.
 *
 * A tap opens the video at its progress through the same intents as the launcher
 * shortcut (AppShortcuts → MainActivity), or the 稍后再看 page from the list's header.
 */
class WatchWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // The first frame draws what is loaded here. The session then stays up for a while,
        // and an update inside it only recomposes (this function doesn't run again), so the
        // data reloads in the composition whenever WidgetStore.version moves.
        val loadedAt = WidgetStore.version.value
        val initial = WidgetData.load(context)
        val initialColors = WidgetColors.provide(context)
        provideContent {
            val version by WidgetStore.version.collectAsState()
            var data by remember { mutableStateOf(initial) }
            var colors by remember { mutableStateOf(initialColors) }
            LaunchedEffect(version) {
                if (version != loadedAt) {
                    data = WidgetData.load(context)
                    colors = WidgetColors.provide(context)
                }
            }
            GlanceTheme(colors = colors) {
                WatchContent(data)
            }
        }
    }

    companion object {
        internal val Small = DpSize(110.dp, 110.dp)
        internal val Medium = DpSize(250.dp, 110.dp)
        internal val Large = DpSize(250.dp, 240.dp)
        internal val Tall = DpSize(250.dp, 340.dp)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var pending: Job? = null

        /** A page pausing and then closing publishes twice in a row; one update covers both. */
        private const val RefreshDebounceMs = 800L

        /** Re-renders placed widgets (nothing happens when none is placed). */
        fun refresh(context: Context) {
            val app = context.applicationContext
            synchronized(this) {
                pending?.cancel()
                pending = scope.launch {
                    delay(RefreshDebounceMs)
                    // A running session reloads on this; a new one loads fresh anyway.
                    WidgetStore.changed()
                    runCatching {
                        if (GlanceAppWidgetManager(app).getGlanceIds(WatchWidget::class.java).isNotEmpty()) {
                            WatchWidget().updateAll(app)
                        }
                    }
                }
            }
        }
    }
}

private val Pad = 12.dp
private val WidgetFont = FontFamily("google-sans-flex")
private val OnScrim = ColorProvider(Color.White)
private val OnScrimTrack = ColorProvider(Color.White.copy(alpha = 0.35f))

private fun style(color: ColorProvider, size: TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = WidgetFont)

/** The widget's surface: the system's outer radius on 12+, a 24dp shape before. */
private fun GlanceModifier.surface(color: ColorProvider): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(color).cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        background(ImageProvider(R.drawable.widget_surface), colorFilter = ColorFilter.tint(color))
    }

/** A block inside it (cover, list row): the system's inner radius on 12+. */
private fun GlanceModifier.block(color: ColorProvider): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(color).cornerRadius(android.R.dimen.system_app_widget_inner_radius)
    } else {
        background(ImageProvider(R.drawable.widget_block), colorFilter = ColorFilter.tint(color))
    }

private fun open(intent: Intent): Action = actionStartActivity(intent)

private fun resumeIntent(context: Context, e: ContinueEntry): Intent =
    AppShortcuts.videoIntent(
        context, e.bvid, e.cid, e.title, e.cover,
        progressSec = e.positionMs / 1000,
        durationSec = e.durationMs / 1000,
        source = "widget-continue",
        epId = e.epId,
    )

private fun laterIntent(context: Context, e: LaterEntry): Intent =
    if (e.epId > 0) {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com/bangumi/play/ep${e.epId}"), context, MainActivity::class.java)
    } else {
        AppShortcuts.videoIntent(
            context, e.bvid, e.cid, e.title, e.cover,
            progressSec = e.progress.coerceAtLeast(0),
            durationSec = e.duration,
            source = "widget-later",
        )
    }

private fun laterPageIntent(context: Context): Intent = AppShortcuts.pageIntent(context, AppShortcuts.PageLater)

@Composable
private fun WatchContent(data: WatchData) {
    val size = LocalSize.current
    val resume = data.resume
    val later = data.later
    Box(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .surface(GlanceTheme.colors.widgetBackground)
            .padding(Pad),
    ) {
        when {
            resume == null && (later.isEmpty() || size.width < WatchWidget.Medium.width) -> Empty(data.loggedIn, later.size.coerceAtLeast(data.laterCount))
            size.width < WatchWidget.Medium.width -> ResumeTile(resume!!, data.resumeCover, GlanceModifier.fillMaxSize(), titleLines = 2, meta = false)
            size.height < WatchWidget.Large.height -> MediumLayout(data)
            else -> StackLayout(data, rows = if (size.height >= WatchWidget.Tall.height) WidgetData.MaxLaterRows else 2)
        }
    }
}

/** Medium: 继续看 and the list side by side; without 继续看, the list alone. */
@Composable
private fun MediumLayout(data: WatchData) {
    val resume = data.resume
    if (resume != null && data.later.isEmpty()) {
        ResumeRow(resume, data.resumeCover)
        return
    }
    if (resume == null) {
        // Nothing to resume: the list with thumbnails takes the whole widget.
        LaterList(data, rows = listRowsFor(LocalSize.current.height), thumbs = true, modifier = GlanceModifier.fillMaxSize(), fill = true)
        return
    }
    Row(GlanceModifier.fillMaxSize()) {
        if (resume != null) {
            ResumeTile(resume, data.resumeCover, GlanceModifier.defaultWeight().fillMaxHeight(), titleLines = 1, meta = false)
        }
        if (resume != null && data.later.isNotEmpty()) Spacer(GlanceModifier.width(12.dp))
        if (data.later.isNotEmpty()) {
            LaterList(data, rows = listRowsFor(LocalSize.current.height, thumbs = false), thumbs = false, modifier = GlanceModifier.defaultWeight().fillMaxHeight(), fill = true)
        }
    }
}

/** Large / tall: 继续看 on top (taking the spare height), the list with thumbnails under it. */
@Composable
private fun StackLayout(data: WatchData, rows: Int) {
    val resume = data.resume
    Column(GlanceModifier.fillMaxSize()) {
        if (resume != null) {
            ResumeTile(resume, data.resumeCover, GlanceModifier.fillMaxWidth().defaultWeight(), titleLines = 1, meta = true)
        }
        if (data.later.isNotEmpty()) {
            if (resume != null) Spacer(GlanceModifier.height(12.dp))
            // Without 继续看 the list has the whole height: as many rows as fit.
            val n = if (resume == null) listRowsFor(LocalSize.current.height) else rows.coerceAtMost(WidgetData.MaxLaterRows)
            LaterList(
                data,
                rows = n,
                thumbs = true,
                modifier = if (resume == null) GlanceModifier.fillMaxSize() else GlanceModifier.fillMaxWidth(),
                fill = resume == null,
            )
        }
    }
}

/** Medium with no list (logged out, or nothing saved): the cover beside the title, so it isn't cropped to a strip. */
@Composable
private fun ResumeRow(entry: ContinueEntry, cover: Bitmap?) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Row(
        GlanceModifier.fillMaxSize().clickable(open(resumeIntent(context, entry))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(
            cover = cover,
            progress = entry.progress.takeIf { entry.durationMs > 0 },
            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
        )
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(
                entry.title.ifBlank { entry.bvid },
                style = style(colors.onSurface, 14.sp, FontWeight.Medium),
                maxLines = 3,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(resumeMeta(entry), style = style(colors.onSurfaceVariant, 12.sp), maxLines = 1)
        }
    }
}

private fun resumeMeta(e: ContinueEntry): String =
    if (e.durationMs > 0) "剩余 ${Fmt.durationMs(e.remainingMs)}" else "看到 ${Fmt.durationMs(e.positionMs)}"

/** The cover with how far you got along its bottom, the title under it. */
@Composable
private fun ResumeTile(entry: ContinueEntry, cover: Bitmap?, modifier: GlanceModifier, titleLines: Int, meta: Boolean) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Column(modifier.clickable(open(resumeIntent(context, entry)))) {
        Cover(
            cover = cover,
            progress = entry.progress.takeIf { entry.durationMs > 0 },
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        )
        Spacer(GlanceModifier.height(8.dp))
        Text(
            entry.title.ifBlank { entry.bvid },
            style = style(colors.onSurface, 14.sp, FontWeight.Medium),
            maxLines = titleLines,
        )
        if (meta) {
            Spacer(GlanceModifier.height(2.dp))
            Text(resumeMeta(entry), style = style(colors.onSurfaceVariant, 12.sp), maxLines = 1)
        }
    }
}

@Composable
private fun Cover(cover: Bitmap?, progress: Float?, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    Box(modifier.block(colors.secondaryContainer), contentAlignment = Alignment.BottomStart) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize(),
            )
        } else {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Glyph(SymbolsR.drawable.ic_yoin_play_filled, colors.onSecondaryContainer, 28)
            }
        }
        if (progress != null) {
            if (cover != null) {
                // A scrim over the picture so the white bar reads on bright frames.
                Image(
                    provider = ImageProvider(R.drawable.widget_scrim),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxWidth().height(40.dp),
                )
            }
            Box(GlanceModifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
                    color = if (cover != null) OnScrim else colors.primary,
                    backgroundColor = if (cover != null) OnScrimTrack else colors.surface,
                )
            }
        }
    }
}

/**
 * 稍后再看: a header that opens the page, then the first [rows] entries. With [fill] (the list
 * alone in the widget) a full list's thumbnail rows share the height, taking up what is left
 * under the last whole row.
 */
@Composable
private fun LaterList(data: WatchData, rows: Int, thumbs: Boolean, modifier: GlanceModifier, fill: Boolean = false) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Column(modifier) {
        Row(
            GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(open(laterPageIntent(context))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(R.drawable.widget_ic_later, colors.primary, 16)
            Spacer(GlanceModifier.width(6.dp))
            Text("稍后再看", style = style(colors.onSurface, 13.sp, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            if (data.laterCount > 0) {
                Text(data.laterCount.toString(), style = style(colors.onSurfaceVariant, 12.sp, FontWeight.Medium), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.height(ListHeaderGap))
        val stretch = fill && data.later.size >= rows
        data.later.take(rows).forEachIndexed { i, e ->
            if (i > 0) Spacer(GlanceModifier.height(RowGap))
            if (thumbs) {
                val size = if (stretch) GlanceModifier.defaultWeight() else GlanceModifier.height(ThumbRowHeight)
                ThumbRow(e, data.laterThumbs.getOrNull(i), size)
            } else {
                Box(
                    (if (stretch) GlanceModifier.defaultWeight() else GlanceModifier)
                        .fillMaxWidth()
                        .block(colors.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                        .clickable(open(laterIntent(context, e))),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(e.title.ifBlank { e.bvid }, style = style(colors.onSecondaryContainer, 12.sp), maxLines = 1)
                }
            }
        }
    }
}

// The list's geometry (LaterList / ThumbRow): header, the gap under it, a row and the gap between rows.
private val ListHeader = 22.dp
private val ListHeaderGap = 6.dp
private val ThumbRowHeight = 44.dp
private val TextRowHeight = 30.dp
private val RowGap = 4.dp

/** How many rows (thumbnail or title-only) fill a widget [height] tall under the list's header. */
private fun listRowsFor(height: Dp, thumbs: Boolean = true): Int {
    val room = height - Pad * 2 - ListHeader - ListHeaderGap + RowGap
    val row = if (thumbs) ThumbRowHeight else TextRowHeight
    return (room / (row + RowGap)).toInt().coerceIn(1, WidgetData.MaxListRows)
}

@Composable
private fun ThumbRow(e: LaterEntry, thumb: Bitmap?, size: GlanceModifier) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    Row(
        size
            .fillMaxWidth()
            .block(colors.secondaryContainer)
            .padding(4.dp)
            .clickable(open(laterIntent(context, e))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.width(64.dp).fillMaxHeight().block(colors.surface), contentAlignment = Alignment.Center) {
            if (thumb != null) {
                Image(
                    provider = ImageProvider(thumb),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            } else {
                Glyph(SymbolsR.drawable.ic_yoin_play_filled, colors.onSurfaceVariant, 16)
            }
        }
        Spacer(GlanceModifier.width(10.dp))
        Text(
            e.title.ifBlank { e.bvid },
            style = style(colors.onSecondaryContainer, 12.sp),
            maxLines = 2,
            modifier = GlanceModifier.defaultWeight().padding(end = 6.dp),
        )
    }
}

/**
 * Nothing to resume: the glyph and a label, 稍后再看 with its count when the list has
 * entries (a small widget has no room for the list itself); a tap opens 稍后再看 or the app.
 */
@Composable
private fun Empty(loggedIn: Boolean, laterCount: Int) {
    val context = LocalContext.current
    val colors = GlanceTheme.colors
    val target = if (loggedIn) {
        laterPageIntent(context)
    } else {
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    }
    Column(
        GlanceModifier.fillMaxSize().clickable(open(target)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(GlanceModifier.size(44.dp).block(colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Glyph(R.drawable.widget_ic_later, colors.onSecondaryContainer, 22)
        }
        Spacer(GlanceModifier.height(8.dp))
        if (laterCount > 0) {
            Text("稍后再看", style = style(colors.onSurface, 14.sp, FontWeight.Medium), maxLines = 1)
            Text(laterCount.toString(), style = style(colors.onSurfaceVariant, 12.sp, FontWeight.Medium), maxLines = 1)
        } else {
            Text("继续看", style = style(colors.onSurface, 14.sp, FontWeight.Medium), maxLines = 1)
            Text("空空如也", style = style(colors.onSurfaceVariant, 12.sp), maxLines = 1)
        }
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
