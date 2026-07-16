package com.bringyour.network.ui.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bringyour.network.DeviceManager
import com.bringyour.sdk.ContractDetailsViewController
import com.bringyour.sdk.Sub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Aggregated contract pairs for one peer client, ready to render.
 *
 * The per-peer aggregation, the egress+ingress coalescing, the renewal-atomic
 * replace, and the closing/eject lifecycle all live in the shared SDK
 * ContractDetailsViewController now (used by every platform); this is just the
 * rendered shape.
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
    // the client's last contract closed and the row is being ejected: it is kept
    // briefly (by the SDK view controller) so its circles slide off, then removed
    val closing: Boolean = false,
)

/**
 * Thin adapter over the shared SDK ContractDetailsViewController. It opens the
 * view controller, subscribes to its row changes, and republishes the mode's
 * rows: `provider` selects the provider contracts (traffic relayed for remote
 * clients) instead of this device's own traffic.
 *
 * All of the work -- coalescing the egress+ingress change streams, holding a
 * renewing contract's slot so a replace is atomic, per-peer aggregation, and the
 * closing/eject lifecycle -- is done by the view controller; this view model no
 * longer re-implements any of it.
 */
@HiltViewModel
class ContractStatsViewModel @Inject constructor(
    private val deviceManager: DeviceManager,
) : ViewModel() {

    private var contractDetailsVc: ContractDetailsViewController? = null
    private val subs = mutableListOf<Sub>()
    private var provider = false
    private var started = false

    var rows by mutableStateOf<List<ContractClientRowUi>>(listOf())
        private set

    fun start(provider: Boolean) {
        if (started) {
            return
        }
        started = true
        this.provider = provider

        deviceManager.device?.let { device ->
            val vc = device.openContractDetailsViewController()
            contractDetailsVc = vc

            subs.add(vc.addContractRowsListener {
                viewModelScope.launch {
                    update()
                }
            })

            vc.start()
            update()
        }
    }

    private fun update() {
        val vc = contractDetailsVc ?: return

        val list = if (provider) vc.providerContractRows else vc.clientContractRows

        val newRows = mutableListOf<ContractClientRowUi>()
        if (list != null) {
            val n = list.len()
            for (i in 0 until n) {
                val r = list.get(i) ?: continue
                newRows.add(
                    ContractClientRowUi(
                        clientId = r.clientId,
                        contractId = r.contractId,
                        companionContractId = r.companionContractId,
                        contractUsedByteCount = r.contractUsedByteCount,
                        contractByteCount = r.contractByteCount,
                        contractBitRate = r.contractBitRate,
                        companionContractUsedByteCount = r.companionContractUsedByteCount,
                        companionContractByteCount = r.companionContractByteCount,
                        companionContractBitRate = r.companionContractBitRate,
                        pairCount = r.pairCount.toInt(),
                        closing = r.closing,
                    )
                )
            }
        }

        rows = newRows
    }

    override fun onCleared() {
        super.onCleared()
        subs.forEach { it.close() }
        subs.clear()
        contractDetailsVc?.let { vc ->
            deviceManager.device?.closeViewController(vc)
        }
        contractDetailsVc = null
    }
}
