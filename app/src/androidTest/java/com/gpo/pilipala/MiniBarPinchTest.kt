package com.gpo.pilipala

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import java.io.File

/**
 * Two-finger gestures adb cannot send. `am instrument` restarts the app, so the test opens a
 * YouTube video itself, collapses it into the mini bar, then spreads (or pinches) two fingers on
 * the bar's picture, from -e from to -e to px apart, injecting the touches directly. Screenshots
 * land in the app's files/pinch for the host to pull.
 */
class MiniBarPinchTest {
    private val instr = InstrumentationRegistry.getInstrumentation()

    @Test
    fun pinch() {
        val args = InstrumentationRegistry.getArguments()
        val device = UiDevice.getInstance(instr)
        val out = File(instr.targetContext.filesDir, "pinch").apply { mkdirs() }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(args.getString("url", "https://www.youtube.com/watch?v=YnJMUpQWQ1M")))
            .setClassName(instr.targetContext, "com.gpo.pilipala.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instr.targetContext.startActivity(intent)
        Thread.sleep(10_000)
        device.pressBack()
        device.wait(Until.hasObject(By.clazz("android.view.TextureView")), 8_000)
        Thread.sleep(1_500)
        val bar = device.findObjects(By.clazz("android.view.TextureView"))
            .filter { it.visibleBounds.centerY() > device.displayHeight * 0.7 }
            .maxByOrNull { it.visibleBounds.top } ?: error("no picture in the bar")
        device.takeScreenshot(File(out, "before.png"))
        val cx = bar.visibleBounds.centerX().toFloat()
        val cy = (bar.visibleBounds.centerY()).toFloat()
        val from = args.getString("from", "60").toFloat()
        val to = args.getString("to", "300").toFloat()
        spread(cx, cy, from, to)
        Thread.sleep(1_500)
        device.takeScreenshot(File(out, "after.png"))
        Thread.sleep(args.getString("hold", "500").toLong())
    }

    /** Two fingers on a horizontal line through (cx, cy), [from] px apart moving to [to] px apart. */
    private fun spread(cx: Float, cy: Float, from: Float, to: Float) {
        val ui = instr.uiAutomation
        val t0 = SystemClock.uptimeMillis()
        fun props(id: Int) = MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        fun coords(x: Float) = MotionEvent.PointerCoords().apply { this.x = x; y = cy; pressure = 1f; size = 1f }
        fun send(action: Int, t: Long, xs: List<Float>) {
            val e = MotionEvent.obtain(
                t0, t, action, xs.size,
                Array(xs.size) { props(it) }, Array(xs.size) { coords(xs[it]) },
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            ui.injectInputEvent(e, true)
            e.recycle()
        }
        var t = t0
        send(MotionEvent.ACTION_DOWN, t, listOf(cx - from / 2))
        t += 20
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t, listOf(cx - from / 2, cx + from / 2))
        val steps = 20
        for (i in 1..steps) {
            t += 25
            val d = from + (to - from) * i / steps
            send(MotionEvent.ACTION_MOVE, t, listOf(cx - d / 2, cx + d / 2))
            SystemClock.sleep(25)
        }
        t += 20
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), t, listOf(cx - to / 2, cx + to / 2))
        t += 20
        send(MotionEvent.ACTION_UP, t, listOf(cx - to / 2))
    }
}
