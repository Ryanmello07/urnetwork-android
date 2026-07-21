package com.bringyour.network

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bringyour.sdk.Sub

/**
 * Quick Settings tile that toggles the URnetwork connection on and off.
 *
 * The tile runs in the app process, so it reads and drives the SDK [com.bringyour.sdk.DeviceLocal]
 * directly (no IPC): tile state mirrors `device.connectEnabled`, and a tap opens a short-lived
 * ConnectViewController to connect/disconnect exactly as the connect screen does. Flipping
 * `connectEnabled` fires the app's own connect-change listener, which starts or stops the VPN
 * service. The first-ever connect needs the system VPN consent dialog, which only an activity can
 * present, so that case hands off to the app (which completes the start via `vpnRequestStart`).
 */
class QuickConnectTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectSub: Sub? = null

    private val app: MainApplication
        get() = applicationContext as MainApplication

    override fun onStartListening() {
        super.onStartListening()
        // keep the tile live while the shade is open
        connectSub?.close()
        connectSub = app.device?.addConnectChangeListener {
            mainHandler.post { render() }
        }
        render()
    }

    override fun onStopListening() {
        connectSub?.close()
        connectSub = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        val device = app.device
        if (device == null) {
            // logged out / no device yet: send the user into the app
            openApp()
            return
        }

        if (device.connectEnabled) {
            toggleConnect(connect = false)
            render()
            return
        }

        toggleConnect(connect = true)
        render()
        // first connect requires the VPN consent dialog, which only an activity can show;
        // hand off so the app finishes starting the tunnel (it checks vpnRequestStart onStart)
        if (VpnService.prepare(this) != null) {
            openApp()
        }
    }

    private fun toggleConnect(connect: Boolean) {
        val device = app.device ?: return
        val vc = device.openConnectViewController() ?: return
        try {
            if (connect) {
                val location = device.connectLocation
                if (location != null) {
                    vc.connect(location)
                } else {
                    vc.connectBestAvailable()
                }
            } else {
                vc.disconnect()
            }
        } finally {
            device.closeViewController(vc)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val connected = app.device?.connectEnabled == true
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (connected) R.drawable.ic_tile_quick_on else R.drawable.ic_tile_quick_off,
        )
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (connected) R.string.tile_status_connected else R.string.tile_status_disconnected,
            )
        }
        tile.updateTile()
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // startActivityAndCollapse(Intent) throws on API 34+; the PendingIntent overload is required
            val pending = PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }
}
