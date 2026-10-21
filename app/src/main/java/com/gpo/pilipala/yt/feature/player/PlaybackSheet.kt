package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.gpo.pilipala.feature.settings.Choice
import com.gpo.pilipala.feature.settings.ConnectedToggleRow
import com.gpo.pilipala.ui.components.rememberSheetProperties
import com.gpo.pilipala.ui.components.sheetBottomPadding

private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

internal fun speedLabel(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"

/** The pill label for what is playing: "1080p", or "画质" before a stream is chosen. */
internal fun qualityLabel(height: Int): String = if (height > 0) "${height}p" else "画质"

/**
 * 播放设置: speed, quality and subtitles in one sheet, each a row of toggle buttons (speed is a
 * connected group; quality and subtitles wrap, since a video can have many). Lighter than
 * PiliPaw's separate pages; the choice applies at once and the sheet stays.
 */
@Composable
internal fun PlaybackSheet(onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest = onDismiss, properties = rememberSheetProperties()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = sheetBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section("倍速") {
                ConnectedToggleRow(
                    choices = Speeds.map { Choice(it, speedLabel(it)) },
                    selected = Player.speed,
                    onSelect = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        Player.changeSpeed(it)
                    },
                )
            }
            if (Player.heights.isNotEmpty()) {
                Section("画质") {
                    Wrapped(
                        labels = Player.heights.map { "${it}p" },
                        selected = Player.heights.indexOf(Player.currentHeight),
                        onSelect = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            Player.changeQuality(Player.heights[it])
                        },
                    )
                }
            }
            if (Player.subtitleTracks.isNotEmpty()) {
                Section("字幕") {
                    // 关闭 first, then the tracks; index 0 is off.
                    Wrapped(
                        labels = listOf("关闭") + Player.subtitleTracks.map { it.label },
                        selected = Player.subtitleIndex + 1,
                        onSelect = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            Player.chooseSubtitle(it - 1)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Wrapped(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            ToggleButton(
                checked = i == selected,
                onCheckedChange = { if (i != selected) onSelect(i) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}
