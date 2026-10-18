package com.gpo.pilipala.ui.components.seam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.ui.MotionDurationScale
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope

/**
 * When the seams hold still (Yoin §1.7, "省电模式和移除动画"): battery saver, or
 * the system's animator scale at 0 ("Remove animations"). Then there is no
 * afterglow, no stretch with scroll speed and no disorder — the tide's phase
 * is fixed and its height is the base 2.4dp; the field prints a still lattice.
 *
 * Battery saver is followed by one process-wide receiver (registered on the
 * application context at the first seam's attach), so a scroll frame only
 * reads a field — no binder call per frame.
 */
internal object SeamMotion {
    @Volatile
    private var powerSave = false

    @Volatile
    private var registered = false

    fun ensureWatching(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val app = context.applicationContext
            val power = app.getSystemService(PowerManager::class.java)
            powerSave = power?.isPowerSaveMode == true
            runCatching {
                ContextCompat.registerReceiver(
                    app,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            powerSave = power?.isPowerSaveMode == true
                        }
                    },
                    IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
            registered = true
        }
    }

    /** Battery saver or "Remove animations" (read from the node's [scope], whose clock carries the scale). */
    fun reduced(scope: CoroutineScope): Boolean =
        powerSave || scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f
}
