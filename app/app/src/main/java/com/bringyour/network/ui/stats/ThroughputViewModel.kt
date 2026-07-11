package com.bringyour.network.ui.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bringyour.network.DeviceManager
import com.bringyour.sdk.ContractViewController
import com.bringyour.sdk.Sub
import com.bringyour.sdk.ThroughputPointList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Throughput deltas for one route over one sample interval
 */
data class ThroughputSampleUi(
    val egressBytes: Long,
    val ingressBytes: Long,
    val egressPackets: Long,
    val ingressPackets: Long,
) {
    companion object {
        val Zero = ThroughputSampleUi(0, 0, 0, 0)
    }
}

/**
 * One throughput sample, split by route
 */
data class ThroughputPointUi(
    // sample end time, unix millis
    val timeMillis: Long,
    val remote: ThroughputSampleUi,
    val local: ThroughputSampleUi,
    val block: ThroughputSampleUi,
)

enum class ThroughputRoute {
    REMOTE,
    LOCAL,
    BLOCK;

    fun sample(point: ThroughputPointUi): ThroughputSampleUi {
        return when (this) {
            REMOTE -> point.remote
            LOCAL -> point.local
            BLOCK -> point.block
        }
    }
}

/**
 * Wraps the sdk contract view controller and publishes the live
 * client and provider throughput series
 */
@HiltViewModel
class ThroughputViewModel @Inject constructor(
    private val deviceManager: DeviceManager,
) : ViewModel() {

    private var contractVc: ContractViewController? = null
    private val subs = mutableListOf<Sub>()

    var clientPoints by mutableStateOf<List<ThroughputPointUi>>(listOf())
        private set

    var providerPoints by mutableStateOf<List<ThroughputPointUi>>(listOf())
        private set

    /**
     * false when the device has no provider (providing disabled)
     */
    var hasProviderStats by mutableStateOf(false)
        private set

    var windowSeconds by mutableStateOf(60L)
        private set

    init {
        deviceManager.device?.let { device ->
            val vc = device.openContractViewController()
            contractVc = vc
            windowSeconds = vc.windowDurationSeconds

            subs.add(vc.addThroughputListener {
                viewModelScope.launch {
                    update()
                }
            })

            update()
        }
    }

    private fun update() {
        val vc = contractVc ?: return
        clientPoints = mapPoints(vc.throughputPoints)
        providerPoints = mapPoints(vc.providerThroughputPoints)
        hasProviderStats = vc.providerPacketStats != null
    }

    private fun mapPoints(list: ThroughputPointList?): List<ThroughputPointUi> {
        if (list == null) {
            return listOf()
        }
        val points = mutableListOf<ThroughputPointUi>()
        val n = list.len()
        for (i in 0 until n) {
            val point = list.get(i) ?: continue
            points.add(
                ThroughputPointUi(
                    timeMillis = point.time,
                    remote = point.remote?.let {
                        ThroughputSampleUi(
                            it.egressByteCount,
                            it.ingressByteCount,
                            it.egressPacketCount,
                            it.ingressPacketCount
                        )
                    } ?: ThroughputSampleUi.Zero,
                    local = point.local?.let {
                        ThroughputSampleUi(
                            it.egressByteCount,
                            it.ingressByteCount,
                            it.egressPacketCount,
                            it.ingressPacketCount
                        )
                    } ?: ThroughputSampleUi.Zero,
                    block = point.block?.let {
                        ThroughputSampleUi(
                            it.egressByteCount,
                            it.ingressByteCount,
                            it.egressPacketCount,
                            it.ingressPacketCount
                        )
                    } ?: ThroughputSampleUi.Zero,
                )
            )
        }
        return points
    }

    override fun onCleared() {
        super.onCleared()
        subs.forEach { it.close() }
        subs.clear()
        contractVc?.let { vc ->
            deviceManager.device?.closeViewController(vc)
        }
        contractVc = null
    }
}
