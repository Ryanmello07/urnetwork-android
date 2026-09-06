package com.bringyour.network.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLocationStateTest {

    private val tokyo = MockLocationTarget(
        clientId = "client-1",
        label = "Tokyo, Japan",
        lat = 35.6762,
        lon = 139.6503,
    )

    private fun resolve(
        enabled: Boolean = true,
        devOptionsEnabled: Boolean = true,
        isSelectedMockApp: Boolean = true,
        locationServicesEnabled: Boolean = true,
        tunnelUp: Boolean = true,
        target: MockLocationTarget? = tokyo,
        orphaned: Boolean = false,
    ): MockLocationStatus {
        return resolveMockLocationStatus(
            enabled = enabled,
            devOptionsEnabled = devOptionsEnabled,
            isSelectedMockApp = isSelectedMockApp,
            locationServicesEnabled = locationServicesEnabled,
            tunnelUp = tunnelUp,
            target = target,
            orphaned = orphaned,
        )
    }

    // Every rung is pinned twice below: once against the signals under it (so a
    // rung cannot sink) and once against the signals over it (so it cannot be
    // hoisted). Asserting only that a gate fires proves neither.

    @Test
    fun disabledWinsOverEverySignalWhenNotOrphaned() {
        assertEquals(MockLocationStatus.DISABLED, resolve(enabled = false))
        // gates are not reported while the toggle is off
        assertEquals(
            MockLocationStatus.DISABLED,
            resolve(
                enabled = false,
                devOptionsEnabled = false,
                isSelectedMockApp = false,
                locationServicesEnabled = false,
                tunnelUp = false,
                target = null,
            ),
        )
    }

    @Test
    fun orphanedWinsEvenWhenDisabled() {
        // the flag only clears on successful cleanup; until then the user
        // must see the recovery instructions regardless of the toggle
        assertEquals(MockLocationStatus.ORPHANED, resolve(enabled = false, orphaned = true))
    }

    @Test
    fun orphanedWinsOverActiveConditions() {
        assertEquals(MockLocationStatus.ORPHANED, resolve(orphaned = true))
    }

    @Test
    fun orphanedWinsOverGates() {
        assertEquals(
            MockLocationStatus.ORPHANED,
            resolve(
                devOptionsEnabled = false,
                isSelectedMockApp = false,
                locationServicesEnabled = false,
                orphaned = true,
            ),
        )
    }

    @Test
    fun devOptionsGateComesFirst() {
        // alone
        assertEquals(MockLocationStatus.NEEDS_DEV_OPTIONS, resolve(devOptionsEnabled = false))
        // over every signal below it
        assertEquals(
            MockLocationStatus.NEEDS_DEV_OPTIONS,
            resolve(
                devOptionsEnabled = false,
                isSelectedMockApp = false,
                locationServicesEnabled = false,
                tunnelUp = false,
                target = null,
            ),
        )
        // and under the two that outrank it
        assertEquals(
            MockLocationStatus.DISABLED,
            resolve(enabled = false, devOptionsEnabled = false),
        )
        assertEquals(
            MockLocationStatus.ORPHANED,
            resolve(orphaned = true, devOptionsEnabled = false),
        )
    }

    @Test
    fun selectionGateComesSecond() {
        assertEquals(MockLocationStatus.NEEDS_SELECTION, resolve(isSelectedMockApp = false))
        assertEquals(
            MockLocationStatus.NEEDS_SELECTION,
            resolve(
                isSelectedMockApp = false,
                locationServicesEnabled = false,
                tunnelUp = false,
                target = null,
            ),
        )
        // dev options is the earlier signal and must win
        assertEquals(
            MockLocationStatus.NEEDS_DEV_OPTIONS,
            resolve(devOptionsEnabled = false, isSelectedMockApp = false),
        )
        assertEquals(
            MockLocationStatus.DISABLED,
            resolve(enabled = false, isSelectedMockApp = false),
        )
        assertEquals(
            MockLocationStatus.ORPHANED,
            resolve(orphaned = true, isSelectedMockApp = false),
        )
    }

    @Test
    fun locationServicesGateComesThird() {
        assertEquals(
            MockLocationStatus.NEEDS_LOCATION_ON,
            resolve(locationServicesEnabled = false),
        )
        // only the tunnel/target decision sits below it
        assertEquals(
            MockLocationStatus.NEEDS_LOCATION_ON,
            resolve(locationServicesEnabled = false, tunnelUp = false, target = null),
        )
        // every earlier signal outranks it
        assertEquals(
            MockLocationStatus.NEEDS_SELECTION,
            resolve(isSelectedMockApp = false, locationServicesEnabled = false),
        )
        assertEquals(
            MockLocationStatus.NEEDS_DEV_OPTIONS,
            resolve(devOptionsEnabled = false, locationServicesEnabled = false),
        )
        assertEquals(
            MockLocationStatus.DISABLED,
            resolve(enabled = false, locationServicesEnabled = false),
        )
        assertEquals(
            MockLocationStatus.ORPHANED,
            resolve(orphaned = true, locationServicesEnabled = false),
        )
    }

    // all 2^6 signal combinations against both target shapes, addressed as a
    // bitmask so a rung added back anywhere in the ladder is caught whatever it
    // keys on. The COARSE grant is not among the inputs at all: it gates the
    // optional FLP mirror in the controller (§3.2), never the ladder, and a
    // gate here would strand the permission-free AOSP providers on every GMS
    // device without the grant.
    private fun everyResolvedStatus(): List<MockLocationStatus> =
        (0 until 128).map { bits ->
            resolve(
                enabled = (bits and 1) != 0,
                devOptionsEnabled = (bits and 2) != 0,
                isSelectedMockApp = (bits and 4) != 0,
                locationServicesEnabled = (bits and 8) != 0,
                tunnelUp = (bits and 16) != 0,
                target = if ((bits and 32) != 0) tokyo else null,
                orphaned = (bits and 64) != 0,
            )
        }

    @Test
    fun theLadderReachesEveryStatusExceptTheTwoAdvisoryOnes() {
        // NEEDS_LOCATION_PERMISSION and ERROR_TRANSIENT are documented as never
        // returned here: the first is advisory (the UI reads the two permission
        // signals off the state), the second is overlaid by the controller
        // while a retry is pending
        assertEquals(
            setOf(
                MockLocationStatus.DISABLED,
                MockLocationStatus.NEEDS_DEV_OPTIONS,
                MockLocationStatus.NEEDS_SELECTION,
                MockLocationStatus.NEEDS_LOCATION_ON,
                MockLocationStatus.ELIGIBLE,
                MockLocationStatus.ACTIVE,
                MockLocationStatus.ORPHANED,
            ),
            everyResolvedStatus().toSet(),
        )
    }

    @Test
    fun eligibleWhenNoTunnelAndNoTarget() {
        assertEquals(
            MockLocationStatus.ELIGIBLE,
            resolve(tunnelUp = false, target = null),
        )
    }

    @Test
    fun targetPresentButTunnelDownIsEligible() {
        assertEquals(MockLocationStatus.ELIGIBLE, resolve(tunnelUp = false))
    }

    @Test
    fun tunnelUpButNoTargetIsEligible() {
        assertEquals(MockLocationStatus.ELIGIBLE, resolve(target = null))
    }

    @Test
    fun activeOnlyWhenTunnelUpAndTargetPresent() {
        assertEquals(MockLocationStatus.ACTIVE, resolve())
    }

    private fun state(
        devOptionsEnabled: Boolean = true,
        mockAppSelected: Boolean = true,
        locationServicesEnabled: Boolean = true,
        locationPermissionGranted: Boolean = true,
        requiresLocationPermission: Boolean = false,
    ) = MockLocationState(
        status = MockLocationStatus.DISABLED,
        enabled = false,
        target = null,
        devOptionsEnabled = devOptionsEnabled,
        mockAppSelected = mockAppSelected,
        locationServicesEnabled = locationServicesEnabled,
        locationPermissionGranted = locationPermissionGranted,
        requiresLocationPermission = requiresLocationPermission,
    )

    // The toggle opens the setup guide only when setup is incomplete, and the
    // guide marks its steps from these signals. Both must stay readable while
    // the feature is off, when `status` is DISABLED no matter how the device
    // is configured.
    @Test
    fun setupCompleteIsReportedWhileTheFeatureIsOff() {
        val complete = state()
        assertEquals(MockLocationStatus.DISABLED, complete.status)
        assertTrue(complete.setupComplete)
    }

    @Test
    fun setupIsIncompleteWhenAnySignalIsMissing() {
        assertFalse(state(devOptionsEnabled = false).setupComplete)
        assertFalse(state(mockAppSelected = false).setupComplete)
        assertFalse(state(locationServicesEnabled = false).setupComplete)
    }

    // the COARSE grant buys the optional FLP mirror only (§3.2); setup is
    // complete without it or the whole feature dies on GMS devices
    @Test
    fun setupIsCompleteWithoutTheOptionalLocationPermission() {
        assertTrue(
            state(
                requiresLocationPermission = true,
                locationPermissionGranted = false,
            ).setupComplete,
        )
    }

    @Test
    fun setupCompleteIgnoresBothPermissionSignalsInEveryCombination() {
        for (requires in listOf(false, true)) {
            for (granted in listOf(false, true)) {
                // neither signal can complete setup on its own...
                assertFalse(
                    state(
                        devOptionsEnabled = false,
                        requiresLocationPermission = requires,
                        locationPermissionGranted = granted,
                    ).setupComplete,
                )
                // ...nor withhold it from a device that is otherwise set up
                assertTrue(
                    state(
                        requiresLocationPermission = requires,
                        locationPermissionGranted = granted,
                    ).setupComplete,
                )
            }
        }
    }

    // the guide's optional-permission step is the only thing that reads these
    // two, and it reads them off the state rather than off `status`. If the
    // controller ever stops publishing them the step disappears silently and
    // the FLP mirror goes with it.
    @Test
    fun bothPermissionSignalsSurviveOnTheState() {
        val needsGrant = state(requiresLocationPermission = true, locationPermissionGranted = false)
        assertTrue(needsGrant.requiresLocationPermission)
        assertFalse(needsGrant.locationPermissionGranted)
    }

    // the controller's first published state passes only status/enabled/target
    // and leans on these defaults for every signal
    @Test
    fun defaultedSignalsReadAsNotYetSetUp() {
        val initial = MockLocationState(
            status = MockLocationStatus.DISABLED,
            enabled = false,
            target = null,
        )
        assertFalse(initial.devOptionsEnabled)
        assertFalse(initial.mockAppSelected)
        assertFalse(initial.locationServicesEnabled)
        assertFalse(initial.locationPermissionGranted)
        assertFalse(initial.requiresLocationPermission)
        assertFalse(initial.setupComplete)
        assertNull(initial.target)
    }
}
