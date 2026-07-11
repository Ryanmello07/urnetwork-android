package com.bringyour.network.ui.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bringyour.network.DeviceManager
import com.bringyour.sdk.ContractDetailsList
import com.bringyour.sdk.Sub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Aggregated contract pairs for one peer client
 */
data class ContractClientRowUi(
    val clientId: String,
    // signatures of the client's active contracts; when these change the
    // circle swaps (a contract was replaced) rather than just resizing
    val contractId: String = "",
    val companionContractId: String = "",
    val contractUsedByteCount: Long = 0,
    val contractByteCount: Long = 0,
    val contractBitRate: Long = 0,
    val companionContractUsedByteCount: Long = 0,
    val companionContractByteCount: Long = 0,
    val companionContractBitRate: Long = 0,
    val pairCount: Int = 0,
)

/**
 * Publishes the live contract details grouped per peer client id.
 * Egress and ingress contract pairs are merged into one row per peer.
 * `provider` selects the provider contracts (traffic relayed for
 * remote clients) instead of this device's own traffic.
 */
@HiltViewModel
class ContractStatsViewModel @Inject constructor(
    private val deviceManager: DeviceManager,
) : ViewModel() {

    private val subs = mutableListOf<Sub>()
    private var provider = false
    private var started = false

    /**
     * keeps row order stable across updates: clients keep their
     * first-seen position, new clients append
     */
    private val clientOrder = mutableMapOf<String, Int>()

    var rows by mutableStateOf<List<ContractClientRowUi>>(listOf())
        private set

    fun start(provider: Boolean) {
        if (started) {
            return
        }
        started = true
        this.provider = provider

        deviceManager.device?.let { device ->
            if (provider) {
                subs.add(device.addProviderEgressContractDetailsChangeListener {
                    viewModelScope.launch { update() }
                })
                subs.add(device.addProviderIngressContractDetailsChangeListener {
                    viewModelScope.launch { update() }
                })
            } else {
                subs.add(device.addEgressContractDetailsChangeListener {
                    viewModelScope.launch { update() }
                })
                subs.add(device.addIngressContractDetailsChangeListener {
                    viewModelScope.launch { update() }
                })
            }
            update()
        }
    }

    private fun update() {
        val device = deviceManager.device ?: return

        val egress: ContractDetailsList?
        val ingress: ContractDetailsList?
        if (provider) {
            egress = device.providerEgressContractDetails
            ingress = device.providerIngressContractDetails
        } else {
            egress = device.egressContractDetails
            ingress = device.ingressContractDetails
        }

        val ownClientId = device.clientId?.idStr

        val rowsByClient = mutableMapOf<String, ContractClientRowUi>()
        val contractIdsByClient = mutableMapOf<String, MutableList<String>>()
        val companionIdsByClient = mutableMapOf<String, MutableList<String>>()

        val merge = { list: ContractDetailsList? ->
            if (list != null) {
                val n = list.len()
                for (i in 0 until n) {
                    val details = list.get(i) ?: continue
                    val clientId = peerClientId(details, ownClientId)
                    val row = rowsByClient[clientId] ?: ContractClientRowUi(clientId = clientId)
                    rowsByClient[clientId] = row.copy(
                        contractUsedByteCount = row.contractUsedByteCount + details.contractUsedByteCount,
                        contractByteCount = row.contractByteCount + details.contractByteCount,
                        contractBitRate = row.contractBitRate + details.contractBitRate,
                        companionContractUsedByteCount = row.companionContractUsedByteCount + details.companionContractUsedByteCount,
                        companionContractByteCount = row.companionContractByteCount + details.companionContractByteCount,
                        companionContractBitRate = row.companionContractBitRate + details.companionContractBitRate,
                        pairCount = row.pairCount + 1,
                    )
                    details.contractId?.idStr?.let {
                        contractIdsByClient.getOrPut(clientId) { mutableListOf() }.add(it)
                    }
                    details.companionContractId?.idStr?.let {
                        companionIdsByClient.getOrPut(clientId) { mutableListOf() }.add(it)
                    }
                }
            }
        }
        merge(egress)
        merge(ingress)

        // newest first: newly seen clients are prepended to the top, existing
        // clients keep their relative order
        for (clientId in rowsByClient.keys) {
            if (!clientOrder.containsKey(clientId)) {
                clientOrder[clientId] = clientOrder.size
            }
        }
        rows = rowsByClient.values
            .map {
                it.copy(
                    contractId = contractIdsByClient[it.clientId]?.sorted()?.joinToString(",") ?: "",
                    companionContractId = companionIdsByClient[it.clientId]?.sorted()?.joinToString(",") ?: ""
                )
            }
            .sortedByDescending { clientOrder[it.clientId] ?: 0 }
    }

    /**
     * the peer end of the contract transfer path
     */
    private fun peerClientId(details: com.bringyour.sdk.ContractDetails, ownClientId: String?): String {
        val path = details.contractTransferPath
        if (path != null) {
            val sourceId = path.sourceId?.idStr
            val destinationId = path.destinationId?.idStr
            if (ownClientId != null) {
                if (sourceId == ownClientId && destinationId != null) {
                    return destinationId
                }
                if (destinationId == ownClientId && sourceId != null) {
                    return sourceId
                }
            }
            if (destinationId != null) {
                return destinationId
            }
            if (sourceId != null) {
                return sourceId
            }
        }
        return details.contractId?.idStr ?: "unknown"
    }

    override fun onCleared() {
        super.onCleared()
        subs.forEach { it.close() }
        subs.clear()
    }
}
