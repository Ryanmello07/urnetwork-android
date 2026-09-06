package com.bringyour.network.location

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bringyour.network.DeviceManager
import com.bringyour.sdk.DeviceLocal
import com.bringyour.sdk.Sub
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds MockLocationController with tunnel lifecycle and exit provider location updates
 * from the SDK DeviceLocal instance across the entire application lifecycle.
 *
 * Employs a debounce grace period during transient provider reconnections so that momentary
 * drops in connected provider telemetry do not immediately disarm test providers and leak
 * raw hardware GPS fixes.
 */
@Singleton
class MockLocationFeeder @Inject constructor(
    private val deviceManager: DeviceManager,
    private val controller: MockLocationController,
) {
    companion object {
        private const val TAG = "MockLocationFeeder"

        /**
         * Grace period to retain the last known valid provider location during transient
         * provider telemetry handovers or network re-dials before falling back to null.
         */
        private const val TARGET_GRACE_PERIOD_MILLIS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var removeDeviceChangeListener: (() -> Unit)? = null
    @Volatile
    private var connectSub: Sub? = null
    @Volatile
    private var tunnelSub: Sub? = null
    @Volatile
    private var providerSub: Sub? = null
    @Volatile
    private var currentDevice: DeviceLocal? = null
    @Volatile
    private var lastKnownTarget: MockLocationTarget? = null
    @Volatile
    private var pendingClearRunnable: Runnable? = null
    @Volatile
    private var graceGeneration: Long = 0L

    /**
     * Subscribes to device manager changes and initializes mock location feeding
     * for the currently active device.
     */
    @Synchronized
    fun start() {
        if (removeDeviceChangeListener != null) return
        removeDeviceChangeListener = deviceManager.addDeviceChangeListener { device ->
            attach(device)
        }
        attach(deviceManager.device)
    }

    /**
     * Unregisters device manager listeners and closes all active SDK event subscriptions.
     */
    @Synchronized
    fun stop() {
        removeDeviceChangeListener?.invoke()
        removeDeviceChangeListener = null
        cancelPendingTargetClear()
        lastKnownTarget = null
        attach(null)
    }

    /**
     * Cancels any pending scheduled target clear runnable if active and increments the
     * grace generation counter to invalidate any callbacks currently queued on the looper.
     */
    @Synchronized
    private fun cancelPendingTargetClear() {
        graceGeneration++
        pendingClearRunnable?.let { handler.removeCallbacks(it) }
        pendingClearRunnable = null
    }

    /**
     * Attaches event listeners to the given [device] to monitor tunnel state and connected
     * provider locations. Closes prior subscriptions and resets controller targets if [device] is null
     * or when the active device instance is replaced.
     *
     * @param device The active [DeviceLocal] instance, or null if the device is being detached.
     */
    @Synchronized
    private fun attach(device: DeviceLocal?) {
        if (currentDevice !== device) {
            cancelPendingTargetClear()
            lastKnownTarget = null
            controller.onTargetChanged(null)
        }
        connectSub?.close()
        connectSub = null
        tunnelSub?.close()
        tunnelSub = null
        providerSub?.close()
        providerSub = null
        currentDevice = device

        if (device != null) {
            val updateClientTunnelState = {
                // Location mocking requires an active client connection to an exit provider.
                // In Provide Mode (e.g. Provide mode = Always), tunnelStarted is true for server
                // packet routing while connectEnabled is false. We must only mock while both
                // connectEnabled (client mode) and tunnelStarted (VPN active) are true.
                val isClientConnected = device.connectEnabled && device.tunnelStarted
                controller.onTunnelChanged(isClientConnected)
                if (isClientConnected) {
                    pushTarget(device)
                } else {
                    cancelPendingTargetClear()
                    lastKnownTarget = null
                    controller.onTargetChanged(null)
                }
            }

            connectSub = device.addConnectChangeListener {
                synchronized(this@MockLocationFeeder) {
                    if (currentDevice !== device) return@addConnectChangeListener
                    updateClientTunnelState()
                }
            }
            tunnelSub = device.addTunnelChangeListener {
                synchronized(this@MockLocationFeeder) {
                    if (currentDevice !== device) return@addTunnelChangeListener
                    updateClientTunnelState()
                }
            }
            providerSub = device.addConnectedProviderLocationChangeListener {
                synchronized(this@MockLocationFeeder) {
                    if (currentDevice !== device) return@addConnectedProviderLocationChangeListener
                    if (device.connectEnabled && device.tunnelStarted) {
                        pushTarget(device)
                    }
                }
            }

            updateClientTunnelState()
        } else {
            cancelPendingTargetClear()
            lastKnownTarget = null
            controller.onTunnelChanged(false)
            controller.onTargetChanged(null)
        }
    }

    /**
     * Extracts coordinates and geographic metadata from the first valid connected exit provider
     * on [device] and pushes the target location to the [controller].
     *
     * If provider locations are momentarily empty while the tunnel remains active, retains
     * [lastKnownTarget] for a grace window of [TARGET_GRACE_PERIOD_MILLIS] before clearing,
     * guarding against transient provider flaps.
     *
     * @param device The active [DeviceLocal] instance containing connected provider locations.
     */
    @Synchronized
    private fun pushTarget(device: DeviceLocal) {
        val locations = device.connectedProviderLocations
        var target: MockLocationTarget? = null
        if (locations != null) {
            for (i in 0 until locations.len()) {
                val location = locations.get(i)
                val lat: Double
                val lon: Double
                when {
                    location.hasCityCoordinates -> {
                        lat = location.cityLat
                        lon = location.cityLon
                    }
                    location.hasRegionCoordinates -> {
                        lat = location.regionLat
                        lon = location.regionLon
                    }
                    else -> continue
                }
                val label = listOf(location.city, location.region, location.country)
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString(", ")
                target = MockLocationTarget(
                    clientId = location.clientId?.idStr ?: "",
                    label = label,
                    lat = lat,
                    lon = lon,
                )
                break
            }
        }

        if (target != null) {
            cancelPendingTargetClear()
            lastKnownTarget = target
            controller.onTargetChanged(target)
        } else if (lastKnownTarget != null) {
            // Providers list momentarily dipped while tunnel is active. Retain last target
            // for the grace period rather than eagerly disarming and exposing hardware GPS.
            if (pendingClearRunnable == null) {
                val generation = ++graceGeneration
                val runnable = object : Runnable {
                    override fun run() {
                        synchronized(this@MockLocationFeeder) {
                            if (pendingClearRunnable === this && graceGeneration == generation) {
                                pendingClearRunnable = null
                                lastKnownTarget = null
                                controller.onTargetChanged(null)
                                Log.i(TAG, "Provider grace period expired; cleared mock target")
                            }
                        }
                    }
                }
                pendingClearRunnable = runnable
                handler.postDelayed(runnable, TARGET_GRACE_PERIOD_MILLIS)
                Log.i(TAG, "Provider locations momentarily empty; holding exit target for ${TARGET_GRACE_PERIOD_MILLIS}ms grace window")
            }
        } else {
            controller.onTargetChanged(null)
        }
    }
}
