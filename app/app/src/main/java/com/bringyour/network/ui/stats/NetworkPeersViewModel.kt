package com.bringyour.network.ui.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bringyour.network.DeviceManager
import com.bringyour.sdk.ConnectLocation
import com.bringyour.sdk.ConnectLocationId
import com.bringyour.sdk.PeerViewController
import com.bringyour.sdk.Sub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A connected peer of this device on the network
 */
data class NetworkPeerUi(
    val clientId: String,
    val deviceName: String,
    val deviceSpec: String,
    val provideEnabled: Boolean,
) {
    /**
     * the device name, or the device spec if the name is not available
     */
    val displayName: String
        get() = when {
            deviceName.isNotEmpty() -> deviceName
            deviceSpec.isNotEmpty() -> deviceSpec
            else -> clientId
        }
}

/**
 * Publishes the connected network peers with provide enabled.
 *
 * Uses the change listener plus one initial get — no polling. Devices
 * without a provider have no network peers and the list stays empty.
 */
@HiltViewModel
class NetworkPeersViewModel @Inject constructor(
    private val deviceManager: DeviceManager,
) : ViewModel() {

    private val subs = mutableListOf<Sub>()
    private var peerVc: PeerViewController? = null

    var connectedProvidePeers by mutableStateOf<List<NetworkPeerUi>>(listOf())
        private set

    init {
        deviceManager.device?.let { device ->
            // the SDK peer view controller already filters to connected + provide-enabled peers
            val vc = device.openPeerViewController()
            peerVc = vc
            subs.add(vc.addPeersListener {
                viewModelScope.launch {
                    update()
                }
            })
            vc.start()
        }
    }

    private fun update() {
        val vc = peerVc ?: return
        val peers = mutableListOf<NetworkPeerUi>()
        val list = vc.peers
        if (list != null) {
            val n = list.len()
            for (i in 0 until n) {
                val peer = list.get(i) ?: continue
                val clientId = peer.clientId ?: continue
                peers.add(
                    NetworkPeerUi(
                        clientId = clientId.idStr,
                        deviceName = peer.deviceName,
                        deviceSpec = peer.deviceSpec,
                        provideEnabled = peer.provideEnabled,
                    )
                )
            }
        }
        connectedProvidePeers = peers
    }

    /**
     * a direct connection to this peer device
     */
    fun connectLocationForPeer(peer: NetworkPeerUi): ConnectLocation? {
        val vc = peerVc ?: return null
        // reconstruct the SDK client id (the ConnectLocationId needs the Id object, not its string)
        val list = vc.peers ?: return null
        val n = list.len()
        for (i in 0 until n) {
            val p = list.get(i) ?: continue
            val clientId = p.clientId ?: continue
            if (clientId.idStr == peer.clientId) {
                val location = ConnectLocation()
                val locationId = ConnectLocationId()
                locationId.clientId = clientId
                location.connectLocationId = locationId
                location.name = peer.displayName
                return location
            }
        }
        return null
    }

    override fun onCleared() {
        super.onCleared()
        subs.forEach { it.close() }
        subs.clear()
        peerVc?.close()
        peerVc = null
    }
}
