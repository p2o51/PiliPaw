package com.gpo.pilipala

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.PixelCopy
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.pilipala.feature.listen.CoverReveal
import com.gpo.pilipala.feature.listen.ListenCoverImage
import com.gpo.pilipala.feature.player.awaitBitmapCopy
import com.gpo.pilipala.ui.theme.PiliTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Run explicitly with am instrument; never uses connectedAndroidTest or clears app data. */
class PerformanceRegressionTest {
    @Test fun cancelledPixelCopyKeepsBufferAliveUntilCallback() = runBlocking {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        lateinit var callback: (Int) -> Unit
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitBitmapCopy(bitmap) { callback = it }
            fail("Cancelled copy must not be delivered")
        }
        job.cancelAndJoin()
        assertFalse("PixelCopy is still writing", bitmap.isRecycled)
        callback(PixelCopy.SUCCESS)
        assertTrue("Undelivered buffer must be freed", bitmap.isRecycled)
    }

    @Test fun successfulPixelCopyTransfersOwnership() = runBlocking {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertSame(bitmap, awaitBitmapCopy(bitmap) { it(PixelCopy.SUCCESS) })
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test fun failedPixelCopyFreesBuffer() = runBlocking {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertNull(awaitBitmapCopy(bitmap) { it(PixelCopy.ERROR_SOURCE_NO_DATA) })
        assertTrue(bitmap.isRecycled)
    }

    @Test fun rejectedPixelCopyFreesBuffer() = runBlocking {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertNull(awaitBitmapCopy(bitmap) { throw IllegalArgumentException("detached surface") })
        assertTrue(bitmap.isRecycled)
    }

    @Test fun rapidCoverChangesKeepPixelsAndFinishOnLatestCover() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.cacheDir, "performance-covers").apply { mkdirs() }
        val colors = listOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE)
        val files = colors.mapIndexed { i, color ->
            File(dir, "$i.png").also { file ->
                val bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val selected = mutableStateOf(files.first().toURI().toString())
        val reveals = AtomicInteger()
        val durations = CopyOnWriteArrayList<Long>()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        val metrics = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            durations += frame.getMetric(FrameMetrics.TOTAL_DURATION)
        }
        try {
            instrumentation.runOnMainSync {
                activity.window.addOnFrameMetricsAvailableListener(metrics, Handler(Looper.getMainLooper()))
                activity.setContent {
                    PiliTheme {
                        Box(Modifier.fillMaxSize().background(Color.Magenta), contentAlignment = Alignment.Center) {
                            ListenCoverImage(
                                selected.value, 360.dp, CoverReveal.Dissolve, 1,
                                Modifier.size(360.dp), onReveal = { reveals.incrementAndGet() },
                            )
                        }
                    }
                }
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (reveals.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("First cover decoded", reveals.get() > 0)
            Thread.sleep(1200) // Let the launch splash leave before sampling cover pixels.
            instrumentation.runOnMainSync { durations.clear() }
            fun assertCovered() {
                val shot = instrumentation.uiAutomation.takeScreenshot()
                try {
                    for (dx in listOf(-80, 0, 80)) for (dy in listOf(-80, 0, 80)) {
                        assertNotEquals("Cover exposed the background", android.graphics.Color.MAGENTA,
                            shot.getPixel(shot.width / 2 + dx, shot.height / 2 + dy))
                    }
                } finally { shot.recycle() }
            }
            repeat(30) { i ->
                instrumentation.runOnMainSync { selected.value = files[(i + 1) % files.size].toURI().toString() }
                Thread.sleep(90)
                if (i % 5 == 0) assertCovered()
            }
            Thread.sleep(2000)
            assertCovered()
            val shot = instrumentation.uiAutomation.takeScreenshot()
            try {
                assertEquals("Latest cover must win", colors.first(), shot.getPixel(shot.width / 2, shot.height / 2))
                File(context.filesDir, "performance-cover.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { shot.recycle() }
            val frames = durations.sorted()
            val report = "frames=${frames.size} p95_ms=${frames[(frames.size * .95).toInt().coerceAtMost(frames.lastIndex)] / 1_000_000.0} reveals=${reveals.get()}"
            File(context.filesDir, "performance-cover.txt").writeText(report)
            println(report)
        } finally {
            instrumentation.runOnMainSync {
                activity.window.removeOnFrameMetricsAvailableListener(metrics)
                activity.finish()
            }
            dir.deleteRecursively()
        }
    }
}
