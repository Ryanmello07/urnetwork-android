package com.bringyour.network.location

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices

private const val TAG = "FusedMockLocation"

// GMS layer of the mock location engine (MOCKLOCATION.md §3.2): mirror the
// mock fix into the Google Play services fused location provider so FLP-based
// consumers (Chrome, Google Maps et al.) reliably follow it. Beyond the
// developer-options selection the platform path already needs, this leg also
// needs the ACCESS_COARSE_LOCATION runtime grant — the controller gates the
// mirror on it. The platform test providers need no runtime permission (§8)
// and carry the feature on their own, so every failure here is logged and
// swallowed rather than surfaced.

fun supportsFusedMockLocation(context: Context): Boolean {
    return try {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: Throwable) {
        false
    }
}

// setMockMode is device-global (affects all FLP clients in every process) —
// callers must always exit mock mode on every teardown path. Both Task
// outcomes are worth a line: this runs twice per arm/disarm cycle, not per
// tick, and a silently failed exit is what leaves other processes mocked.
fun setFusedMockMode(context: Context, enabled: Boolean) {
    if (!supportsFusedMockLocation(context)) {
        return
    }
    try {
        LocationServices.getFusedLocationProviderClient(context).setMockMode(enabled)
            .addOnSuccessListener {
                Log.i(TAG, "GMS fused location provider mock mode set to $enabled")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "GMS fused location provider setMockMode($enabled) failed: ${e.message}")
            }
    } catch (e: SecurityException) {
        Log.w(TAG, "GMS setMockMode security exception: ${e.message}")
    } catch (e: Throwable) {
        Log.w(TAG, "GMS setMockMode unexpected error: ${e.message}")
    }
}

// setMockLocation runs at 1 Hz for as long as the tunnel is up, so an
// unthrottled failure listener writes the same line every second, forever.
// Dedup like MainApplication's contract status log: the first failure speaks,
// an identical one stays quiet until the backoff expires.
private const val FAILURE_LOG_INTERVAL_MILLIS = 60_000L
private var lastMockLocationFailureMessage: String? = null
private var lastMockLocationFailureLogMillis = 0L

// only the failure listener touches this state, and GMS delivers Task
// callbacks on the main looper, so it stays single-threaded and needs no
// locking; the catch blocks below run on the caller's thread and are
// deliberately left unthrottled
private fun shouldLogMockLocationFailure(message: String): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (message == lastMockLocationFailureMessage &&
        now - lastMockLocationFailureLogMillis < FAILURE_LOG_INTERVAL_MILLIS
    ) {
        return false
    }
    lastMockLocationFailureMessage = message
    lastMockLocationFailureLogMillis = now
    return true
}

// the mirror leg of the 1 Hz poster; the fix has to carry monotonically
// increasing timestamps (§3.2), which the caller builds
fun setFusedMockLocation(context: Context, location: Location) {
    if (!supportsFusedMockLocation(context)) {
        return
    }
    try {
        LocationServices.getFusedLocationProviderClient(context).setMockLocation(location)
            .addOnFailureListener { e ->
                val message = e.message ?: e.toString()
                if (shouldLogMockLocationFailure(message)) {
                    Log.w(TAG, "GMS fused location provider setMockLocation failed: $message")
                }
            }
    } catch (e: SecurityException) {
        Log.w(TAG, "GMS setMockLocation security exception: ${e.message}")
    } catch (e: Throwable) {
        Log.w(TAG, "GMS setMockLocation unexpected error: ${e.message}")
    }
}
