package com.bringyour.network.ui.connect.providerlocations

import androidx.lifecycle.ViewModel
import com.bringyour.network.location.MockLocationController
import com.bringyour.network.location.MockLocationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * UI bridge for the mock location controller: exposes state and UI actions.
 * SDK event feeds are managed at process lifetime by MockLocationFeeder.
 */
@HiltViewModel
class MockLocationViewModel @Inject constructor(
    private val controller: MockLocationController,
) : ViewModel() {

    val state: StateFlow<MockLocationState> = controller.state

    /**
     * Updates the user preference for mock location simulation.
     *
     * @param enabled True to enable device location synchronization with connected exit providers,
     *                false to disable and clear any active test provider state.
     */
    fun setEnabled(enabled: Boolean) {
        controller.setEnabled(enabled)
    }

    /**
     * Re-reads Android system settings (developer options, selected mock location app,
     * and location services) and pushes the refreshed eligibility state to [state].
     */
    fun refreshEligibility() {
        controller.refreshEligibility()
    }
}
