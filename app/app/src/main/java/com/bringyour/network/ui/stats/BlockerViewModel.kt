package com.bringyour.network.ui.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bringyour.network.DeviceManager
import com.bringyour.sdk.Sub
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Publishes the device ad and tracker blocker toggle and applies edits.
 * The device persists the toggle to local settings and restores it at
 * creation, so this only reads and writes the live device state.
 */
@HiltViewModel
class BlockerViewModel @Inject constructor(
    private val deviceManager: DeviceManager,
) : ViewModel() {

    private val subs = mutableListOf<Sub>()

    var blockerEnabled by mutableStateOf(false)
        private set

    init {
        deviceManager.device?.let { device ->
            blockerEnabled = device.blockerEnabled
            subs.add(device.addBlockerEnabledChangeListener { enabled ->
                viewModelScope.launch {
                    blockerEnabled = enabled
                }
            })
        }
    }

    val setBlockerEnabled: (Boolean) -> Unit = { enabled ->
        deviceManager.device?.let { device ->
            device.blockerEnabled = enabled
            blockerEnabled = enabled
        }
    }

    override fun onCleared() {
        super.onCleared()
        subs.forEach { it.close() }
        subs.clear()
    }
}
