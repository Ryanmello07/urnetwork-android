package com.bringyour.network.ui.stats

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bringyour.network.DeviceManager
import com.bringyour.network.utils.listToSdkStringList
import com.bringyour.network.utils.sdkStringListToList
import com.bringyour.sdk.BlockActionOverride
import com.bringyour.sdk.BlockActionOverrideList
import com.bringyour.sdk.BlockActionViewController
import com.bringyour.sdk.RouteOverride
import com.bringyour.sdk.Sdk
import com.bringyour.sdk.Sub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

// coalescing window for bursts of block-action updates
private const val BLOCK_ACTIONS_COALESCE_MILLIS = 100L

/**
 * A recent routing decision, aggregated per destination cluster
 */
@Immutable
data class BlockActionUi(
    val id: String,
    val timeMillis: Long,
    val hosts: List<String>,
    val ips: List<String>,
    val block: Boolean,
    val local: Boolean,
    val hasBlockOverride: Boolean,
    val hasRouteOverride: Boolean,
    // the deciding route override id, when a rule determined the decision
    val overrideId: String?,
    val byteCount: Long,
) {
    /**
     * all host values that can be added to a split rule, host names first
     */
    val hostValues: List<String>
        get() = hosts + ips
}

/**
 * A host split rule (a block action override with host values)
 */
@Immutable
data class SplitRuleUi(
    val id: String,
    val hosts: List<String>,
)

/**
 * An app split rule (a block action override with app ids).
 * `includedInTunnel` means the app is forced through the vpn
 * (the sdk route override is remote); otherwise the app is
 * excluded and bypasses the vpn (the sdk route override is local)
 */
data class AppSplitRuleUi(
    val id: String,
    val appId: String,
    val includedInTunnel: Boolean,
)

/**
 * Publishes the live block action window, block stats, and the block
 * action overrides, split into host rules ("split rules") and app rules
 */
@HiltViewModel
class BlockActionsViewModel @Inject constructor(
    private val deviceManager: DeviceManager,
) : ViewModel() {

    private var blockActionVc: BlockActionViewController? = null
    private val subs = mutableListOf<Sub>()

    // coalesces bursts of block-action updates (see the listener below)
    private var blockActionsUpdateJob: Job? = null

    /**
     * newest first
     */
    var blockActions by mutableStateOf<List<BlockActionUi>>(listOf())
        private set

    var splitRules by mutableStateOf<List<SplitRuleUi>>(listOf())
        private set

    var appRules by mutableStateOf<List<AppSplitRuleUi>>(listOf())
        private set

    var allowedCount by mutableIntStateOf(0)
        private set

    var blockedCount by mutableIntStateOf(0)
        private set

    /**
     * apps forced through the vpn. when non-empty, they take
     * precedence and the tunnel runs in allowlist mode
     */
    val tunnelIncludedAppIds: List<String>
        get() = appRules.filter { it.includedInTunnel }.map { it.appId }

    /**
     * apps that bypass the vpn
     */
    val tunnelExcludedAppIds: List<String>
        get() = appRules.filter { !it.includedInTunnel }.map { it.appId }

    init {
        deviceManager.device?.let { device ->
            val vc = device.openBlockActionViewController()
            blockActionVc = vc

            subs.add(vc.addBlockActionsListener {
                // coalesce bursts into one rebuild: this fires per routing
                // decision (many/sec while browsing) and the full list rebuild
                // + per-host JNI is otherwise wasted every time — including on
                // the connect screen, where the block-action list isn't shown
                blockActionsUpdateJob?.cancel()
                blockActionsUpdateJob = viewModelScope.launch {
                    delay(BLOCK_ACTIONS_COALESCE_MILLIS)
                    updateBlockActions()
                }
            })
            subs.add(vc.addBlockActionStatsListener {
                viewModelScope.launch {
                    updateBlockStats()
                }
            })
            subs.add(device.addBlockActionOverridesChangeListener {
                viewModelScope.launch {
                    updateOverrides()
                }
            })

            updateBlockActions()
            updateBlockStats()
            updateOverrides()
        }
    }

    private fun updateBlockActions() {
        val vc = blockActionVc ?: return
        val items = mutableListOf<BlockActionUi>()
        val list = vc.blockActions
        if (list != null) {
            val n = list.len()
            for (i in 0 until n) {
                val action = list.get(i) ?: continue
                items.add(
                    BlockActionUi(
                        id = action.blockActionId?.idStr ?: "$i-${action.time}",
                        timeMillis = action.time,
                        hosts = sdkStringListToList(action.hosts),
                        ips = sdkStringListToList(action.ips),
                        block = action.block,
                        local = action.local,
                        hasBlockOverride = action.blockOverride != null,
                        hasRouteOverride = action.routeOverride != null,
                        overrideId = action.overrideId?.idStr,
                        byteCount = action.byteCount,
                    )
                )
            }
        }
        // newest first
        blockActions = items.reversed()
    }

    private fun updateBlockStats() {
        val vc = blockActionVc ?: return
        val stats = vc.blockStats
        allowedCount = (stats?.allowedCount ?: 0L).toInt()
        blockedCount = (stats?.blockedCount ?: 0L).toInt()
    }

    private fun updateOverrides() {
        val device = deviceManager.device ?: return
        val hostRules = mutableListOf<SplitRuleUi>()
        val appSplitRules = mutableListOf<AppSplitRuleUi>()
        val list = device.blockActionOverrides
        if (list != null) {
            val n = list.len()
            for (i in 0 until n) {
                val override = list.get(i) ?: continue
                val overrideId = override.overrideId?.idStr ?: continue
                val appIds = sdkStringListToList(override.appIds)
                if (appIds.isNotEmpty()) {
                    // an app rule. included in the tunnel when the route
                    // override is remote, excluded when local
                    val local = override.routeOverride?.local ?: false
                    for (appId in appIds) {
                        appSplitRules.add(
                            AppSplitRuleUi(
                                id = overrideId,
                                appId = appId,
                                includedInTunnel = !local,
                            )
                        )
                    }
                } else {
                    hostRules.add(
                        SplitRuleUi(
                            id = overrideId,
                            hosts = sdkStringListToList(override.hosts),
                        )
                    )
                }
            }
        }
        splitRules = hostRules
        appRules = appSplitRules
    }

    /**
     * the split rule matching a block action's applied override, if it still exists
     */
    fun splitRule(overrideId: String?): SplitRuleUi? {
        if (overrideId == null) {
            return null
        }
        return splitRules.firstOrNull { it.id == overrideId }
    }

    /**
     * creates a split rule forcing the selected host values to route local
     */
    fun createLocalRule(hosts: List<String>) {
        val device = deviceManager.device ?: return
        if (hosts.isEmpty()) {
            return
        }
        val override = BlockActionOverride()
        override.overrideId = Sdk.newId()
        override.hosts = listToSdkStringList(hosts)
        val route = RouteOverride()
        route.local = true
        override.routeOverride = route
        device.addBlockActionOverride(override)
        updateOverrides()
    }

    /**
     * replaces the host values of an existing split rule
     */
    fun updateRule(id: String, hosts: List<String>) {
        if (hosts.isEmpty()) {
            removeRule(id)
            return
        }
        replaceOverrides { override ->
            if (override.overrideId?.idStr == id) {
                override.hosts = listToSdkStringList(hosts)
            }
            override
        }
    }

    fun removeRule(id: String) {
        removeOverride(id)
    }

    /**
     * creates an app split rule. `includeInTunnel` forces the app through
     * the vpn (route remote); otherwise the app bypasses the vpn (route local)
     */
    fun createAppRule(appId: String, includeInTunnel: Boolean) {
        val device = deviceManager.device ?: return
        val override = BlockActionOverride()
        override.overrideId = Sdk.newId()
        val appIds = listToSdkStringList(listOf(appId))
        override.appIds = appIds
        val route = RouteOverride()
        route.local = !includeInTunnel
        override.routeOverride = route
        device.addBlockActionOverride(override)
        updateOverrides()
    }

    fun updateAppRule(id: String, includeInTunnel: Boolean) {
        replaceOverrides { override ->
            if (override.overrideId?.idStr == id) {
                val route = RouteOverride()
                route.local = !includeInTunnel
                override.routeOverride = route
            }
            override
        }
    }

    fun removeAppRule(id: String) {
        removeOverride(id)
    }

    private fun removeOverride(id: String) {
        val device = deviceManager.device ?: return
        val list = device.blockActionOverrides ?: return
        val n = list.len()
        for (i in 0 until n) {
            val override = list.get(i) ?: continue
            if (override.overrideId?.idStr == id) {
                device.removeBlockActionOverride(override.overrideId)
                break
            }
        }
        updateOverrides()
    }

    private fun replaceOverrides(transform: (BlockActionOverride) -> BlockActionOverride) {
        val device = deviceManager.device ?: return
        val list = device.blockActionOverrides ?: return
        val next = BlockActionOverrideList()
        val n = list.len()
        for (i in 0 until n) {
            val override = list.get(i) ?: continue
            next.add(transform(override))
        }
        device.setBlockActionOverrides(next)
        updateOverrides()
    }

    override fun onCleared() {
        super.onCleared()
        subs.forEach { it.close() }
        subs.clear()
        blockActionVc?.let { vc ->
            deviceManager.device?.closeViewController(vc)
        }
        blockActionVc = null
    }
}
