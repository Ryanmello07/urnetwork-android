package com.bringyour.network.location

import android.content.Context
import android.location.Location

// github flavor: platform-only mock location (no Google Play services
// dependency). The LocationManager test providers cover gps/network/fused.

/**
 * Indicates whether Google Play Services fused location mocking is supported.
 * Always returns false in the ungoogle / github flavor.
 *
 * @param context Application or component context.
 * @return Always false for ungoogle builds.
 */
fun supportsFusedMockLocation(context: Context): Boolean = false

/**
 * No-op stub for setting GMS fused mock mode in ungoogle builds.
 *
 * @param context Application context.
 * @param enabled Whether to enable or disable mock mode.
 */
fun setFusedMockMode(context: Context, enabled: Boolean) = Unit

/**
 * No-op stub for pushing GMS fused mock location in ungoogle builds.
 *
 * @param context Application context.
 * @param location The mock location to push.
 */
fun setFusedMockLocation(context: Context, location: Location) = Unit
