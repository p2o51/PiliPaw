package com.gpo.pilipala

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import java.io.File

/**
 * Two fingers pinching the inline Bilibili picture in (adb cannot send two fingers). Opens
 * -e url, then pinches on the picture's centre (-e x / -e y, px) from -e from to -e to px apart,
 * screenshotting while the fingers are still down (held.png) and after the release (after.png)
 * into the app's files/pinch for the host to pull.
 */
class PinchToListenTest {
    private val instr = InstrumentationRegistry.getInstrumentation()

    @Test
    fun pinch() {
        val args = InstrumentationRegistry.getArguments()
        val device = UiDevice.getInstance(instr)
        val out = File(instr.targetContext.filesDir, "pinch").apply { mkdirs() }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(args.getString("url", "https://www.bilibili.com/video/BV1GJ411x7h7")))
            .setClassName(instr.targetContext, "com.gpo.pilipala.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instr.targetContext.startActivity(intent)
        Thread.sleep(args.getString("wait", "9000").toLong())
        val cx = args.getString("x", "${device.displayWidth / 2}").toFloat()
        val cy = args.getString("y", "${device.displayWidth * 9 / 32}").toFloat()
        device.takeScreenshot(File(out, "before.png"))
        pinch(cx, cy, args.getString("from", "700").toFloat(), args.getString("to", "300").toFloat()) {
            device.takeScreenshot(File(out, "held.png"))
        }
        Thread.sleep(1_500)
        device.takeScreenshot(File(out, "after.png"))
    }

    /** Two fingers on a horizontal line through (cx, cy), [from] px apart moving to [to]; [held] runs before they lift. */
    private fun pinch(cx: Float, cy: Float, from: Float, to: Float, held: () -> Unit) {
        val ui = instr.uiAutomation
        val t0 = SystemClock.uptimeMillis()
        fun props(id: Int) = MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        fun coords(x: Float) = MotionEvent.PointerCoords().apply { this.x = x; y = cy; pressure = 1f; size = 1f }
        fun send(action: Int, xs: List<Float>) {
            val e = MotionEvent.obtain(
                t0, SystemClock.uptimeMillis(), action, xs.size,
                Array(xs.size) { props(it) }, Array(xs.size) { coords(xs[it]) },
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            ui.injectInputEvent(e, true)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, listOf(cx - from / 2))
        SystemClock.sleep(20)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(cx - from / 2, cx + from / 2))
        val steps = 24
        for (i in 1..steps) {
            SystemClock.sleep(25)
            val d = from + (to - from) * i / steps
            send(MotionEvent.ACTION_MOVE, listOf(cx - d / 2, cx + d / 2))
        }
        held()
        send(MotionEvent.ACTION_MOVE, listOf(cx - to / 2, cx + to / 2))
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(cx - to / 2, cx + to / 2))
        SystemClock.sleep(20)
        send(MotionEvent.ACTION_UP, listOf(cx - to / 2))
    }
}
