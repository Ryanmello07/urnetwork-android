package com.bringyour.network.location

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices

private const val TAG = "FusedMockLocation"

/**
 * Checks whether Google Play Services is available on the device to support mocking
 * the FusedLocationProviderClient directly (MOCKLOCATION.md §3.2).
 *
 * @param context Application or component context used to query Google Play Services availability.
 * @return True if Google Play Services is installed, enabled, and operational.
 */
fun supportsFusedMockLocation(context: Context): Boolean {
    return try {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: Throwable) {
        false
    }
}

/**
 * Sets whether the Google Play Services Fused Location Provider is in mock mode.
 *
 * Entering mock mode clears FLP caches and ensures FLP clients only receive mock locations
 * pushed through [setFusedMockLocation]. Exiting mock mode restores standard provider fusion.
 *
 * @param context Application context used to obtain the FusedLocationProviderClient.
 * @param enabled True to engage mock mode; false to disengage and restore normal location.
 */
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

/**
 * Pushes a mock fix to the Google Play Services Fused Location Provider so FLP-based consumers
 * (such as Google Chrome and Google Maps) receive the synced location.
 *
 * @param context Application context used to obtain the FusedLocationProviderClient.
 * @param location The complete [Location] fix containing monotonic timestamps and coordinates.
 */
fun setFusedMockLocation(context: Context, location: Location) {
    if (!supportsFusedMockLocation(context)) {
        return
    }
    try {
        LocationServices.getFusedLocationProviderClient(context).setMockLocation(location)
            .addOnFailureListener { e ->
                Log.w(TAG, "GMS fused location provider setMockLocation failed: ${e.message}")
            }
    } catch (e: SecurityException) {
        Log.w(TAG, "GMS setMockLocation security exception: ${e.message}")
    } catch (e: Throwable) {
        Log.w(TAG, "GMS setMockLocation unexpected error: ${e.message}")
    }
}
