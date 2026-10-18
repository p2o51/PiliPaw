package com.gpo.pilipala.feature.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.gpo.pilipala.MainActivity
import com.gpo.pilipala.feature.feeds.Feed
import com.gpo.pilipala.feature.feeds.FeedStore
import com.gpo.pilipala.ui.components.readingWidth
import com.gpo.pilipala.ui.theme.PiliTheme
import com.gpo.pilipala.ui.theme.YoinContainerShapes
import com.gpo.yoin.symbols.YoinSymbols
import kotlinx.coroutines.launch

/**
 * The feed widget's picker: runs when the widget is placed (and on reconfigure). Lists the
 * custom feeds by name; with none, 新建 Feed places the widget unpicked and opens the
 * editor in the app, and the first feed made there is bound to it.
 */
class FeedWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out cancels the placement.
        setResult(Activity.RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val current = FeedWidgetStore.boundFeed(this, appWidgetId)
        setContent {
            PiliTheme {
                val feeds by FeedStore.feeds.collectAsState()
                PickerScreen(
                    feeds = feeds,
                    current = current,
                    onPick = ::pick,
                    onCreate = ::createFeed,
                    onClose = ::finish,
                )
            }
        }
    }

    private fun resultIntent(): Intent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun pick(feed: Feed) {
        FeedWidgetStore.bind(this, appWidgetId, feed.id)
        FeedWidget.schedule(this)
        val app = applicationContext
        lifecycleScope.launch {
            runCatching {
                val glanceId = GlanceAppWidgetManager(app).getGlanceIdBy(appWidgetId)
                FeedWidget().update(app, glanceId)
            }
            FeedWidgetStore.prune(app)
            setResult(Activity.RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun createFeed() {
        FeedWidget.markPendingNew(this, appWidgetId)
        FeedWidget.schedule(this)
        setResult(Activity.RESULT_OK, resultIntent())
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(FeedWidget.ExtraNewFeed, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScreen(
    feeds: List<Feed>,
    current: String?,
    onPick: (Feed) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.surfaceContainer,
        topBar = {
            TopAppBar(
                title = { Text("选择 Feed", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                        Icon(YoinSymbols.Close, contentDescription = "关闭")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surfaceContainer),
            )
        },
    ) { inner ->
        if (feeds.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(inner).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = colors.secondaryContainer, modifier = Modifier.fillMaxSize()) {}
                    Icon(YoinSymbols.Sparkle, null, tint = colors.onSecondaryContainer, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("暂无 Feed", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onCreate) {
                    Icon(YoinSymbols.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("新建 Feed")
                }
            }
        } else {
            val haptics = LocalHapticFeedback.current
            LazyColumn(
                Modifier.fillMaxSize().readingWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = inner.calculateTopPadding() + 8.dp,
                    bottom = inner.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(feeds, key = { it.id }) { feed ->
                    FeedRow(feed, selected = feed.id == current) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onPick(feed)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedRow(feed: Feed, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container = if (selected) colors.secondaryContainer else colors.surfaceBright
    val content = if (selected) colors.onSecondaryContainer else colors.onSurface
    Surface(shape = YoinContainerShapes.Card, color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(feed.name, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        feed.sources.size.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = content,
                    )
                    Text("个来源", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Text(feed.mix.label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                }
            }
            if (selected) Icon(YoinSymbols.Check, null, tint = content, modifier = Modifier.size(22.dp))
        }
    }
}
