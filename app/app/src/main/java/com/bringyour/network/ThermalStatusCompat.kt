package com.bringyour.network

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi

// Keeps API-29 thermal classes out of MainApplication's linkage. Android 8/9
// verifies an Application class before its SDK guards run, so even a guarded
// field or anonymous listener there can fail resolution during process start.
internal fun interface ThermalStatusRegistration {
    fun close()
}

@RequiresApi(Build.VERSION_CODES.Q)
internal object Api29ThermalStatusCompat {
    // SEVERE matches the Apple extension's serious/critical threshold: the
    // point where the OS throttles enough that control pings answer slowly.
    // Registration emits the current status once, establishing the baseline.
    fun register(context: Context, onDegradedChanged: (Boolean) -> Unit): ThermalStatusRegistration {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            onDegradedChanged(status >= PowerManager.THERMAL_STATUS_SEVERE)
        }
        powerManager.addThermalStatusListener(context.mainExecutor, listener)
        return ThermalStatusRegistration {
            try {
                powerManager.removeThermalStatusListener(listener)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
