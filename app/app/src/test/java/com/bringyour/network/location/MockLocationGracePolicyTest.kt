package com.bringyour.network.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLocationGracePolicyTest {

    private val tokyo = MockLocationTarget(
        clientId = "client-1",
        label = "Tokyo, Japan",
        lat = 35.6762,
        lon = 139.6503,
    )

    private val osaka = MockLocationTarget(
        clientId = "client-2",
        label = "Osaka, Japan",
        lat = 34.6937,
        lon = 135.5023,
    )

    @Test
    fun aResolvedTargetIsPushedAndBecomesTheProtectedFix() {
        val policy = MockLocationGracePolicy()
        val decision = policy.onTargetResolved(tokyo)

        assertEquals(MockTargetAction.PUSH, decision.action)
        assertEquals(tokyo, decision.target)
        assertEquals(tokyo, policy.lastKnownTarget)
    }

    @Test
    fun anEmptyProviderListWithNothingHeldClearsImmediately() {
        val policy = MockLocationGracePolicy()
        val decision = policy.onTargetResolved(null)

        // no fix has ever landed, so there is nothing a window could protect
        assertEquals(MockTargetAction.CLEAR, decision.action)
        assertNull(policy.lastKnownTarget)
    }

    @Test
    fun aProviderDipHoldsTheLastKnownTargetForTheFullWindow() {
        val policy = MockLocationGracePolicy()
        policy.onTargetResolved(tokyo)
        val hold = policy.onTargetResolved(null)

        assertEquals(MockTargetAction.HOLD, hold.action)
        assertEquals(tokyo, hold.target)
        assertEquals(TARGET_GRACE_PERIOD_MILLIS, hold.delayMillis)
        // the controller keeps re-posting the held fix until the window expires
        assertEquals(tokyo, policy.lastKnownTarget)
    }

    @Test
    fun repeatedDipsLetTheRunningWindowOwnTheClear() {
        val policy = MockLocationGracePolicy()
        policy.onTargetResolved(tokyo)
        val hold = policy.onTargetResolved(null)
        val again = policy.onTargetResolved(null)

        // restarting the window on every flap would extend it without bound
        assertEquals(MockTargetAction.ALREADY_HOLDING, again.action)
        assertEquals(0L, again.delayMillis)
        assertEquals(tokyo, policy.lastKnownTarget)
        // and the expiry queued by the first dip is still the live one
        assertTrue(policy.graceExpired(hold.generation))
        assertNull(policy.lastKnownTarget)
    }

    @Test
    fun anExpiryClearsExactlyOnce() {
        val policy = MockLocationGracePolicy()
        policy.onTargetResolved(tokyo)
        val hold = policy.onTargetResolved(null)

        assertTrue(policy.graceExpired(hold.generation))
        // a duplicate callback must not clear a target held by a later window
        assertFalse(policy.graceExpired(hold.generation))
    }

    @Test
    fun aFreshTargetDuringTheWindowInvalidatesTheQueuedExpiry() {
        val policy = MockLocationGracePolicy()
        policy.onTargetResolved(tokyo)
        val hold = policy.onTargetResolved(null)
        val repush = policy.onTargetResolved(osaka)

        assertEquals(MockTargetAction.PUSH, repush.action)
        // the expiry is already on the looper when the new fix lands; without
        // the generation guard it would clear a target that is live again
        assertFalse(policy.graceExpired(hold.generation))
        assertEquals(osaka, policy.lastKnownTarget)
    }

    @Test
    fun aDipAfterARepushOpensAFreshWindowAndTheOlderGenerationIsDead() {
        val policy = MockLocationGracePolicy()
        policy.onTargetResolved(tokyo)
        val first = policy.onTargetResolved(null)
        policy.onTargetResolved(osaka)
        val second = policy.onTargetResolved(null)

        assertEquals(MockTargetAction.HOLD, second.action)
        assertEquals(osaka, second.target)
        assertNotEquals(first.generation, second.generation)
        assertFalse(policy.graceExpired(first.generation))
        assertTrue(policy.graceExpired(second.generation))
    }

    @Test
    fun cancelDropsTheHeldTargetAndKillsTheQueuedExpiry() {
        val policy = MockLocationGracePolicy()
        policy.onTargetResolved(tokyo)
        val hold = policy.onTargetResolved(null)
        policy.cancel()

        // tunnel down, device swap or shutdown ends the connection outright;
        // the window exists for provider flaps, not for that
        assertNull(policy.lastKnownTarget)
        assertFalse(policy.graceExpired(hold.generation))
    }

    @Test
    fun aDeviceSwapResetsBackToTheNothingHeldState() {
        val policy = MockLocationGracePolicy()
        policy.onTargetResolved(tokyo)
        policy.cancel()

        // the swapped-in device has no fix to protect yet, so an empty provider
        // list clears rather than reopening a window over the old device's fix
        assertEquals(MockTargetAction.CLEAR, policy.onTargetResolved(null).action)

        val fresh = policy.onTargetResolved(osaka)
        assertEquals(MockTargetAction.PUSH, fresh.action)
        assertEquals(osaka, policy.lastKnownTarget)
    }

    @Test
    fun anExpiredWindowIsNotReopenedByTheNextEmptyList() {
        val policy = MockLocationGracePolicy()
        policy.onTargetResolved(tokyo)
        val hold = policy.onTargetResolved(null)
        policy.graceExpired(hold.generation)

        assertEquals(MockTargetAction.CLEAR, policy.onTargetResolved(null).action)
    }

    @Test
    fun anExpiryCannotFireBeforeItsWindowOpens() {
        val policy = MockLocationGracePolicy()
        policy.onTargetResolved(tokyo)

        // nothing is holding, so no generation may clear the live fix
        assertFalse(policy.graceExpired(0L))
        assertFalse(policy.graceExpired(1L))
        assertEquals(tokyo, policy.lastKnownTarget)
    }

    @Test
    fun theWindowLengthComesFromTheConstructor() {
        val policy = MockLocationGracePolicy(gracePeriodMillis = 250L)
        policy.onTargetResolved(tokyo)

        assertEquals(250L, policy.onTargetResolved(null).delayMillis)
    }

    @Test
    fun theWindowStaysUnderTheStaleFixCeiling() {
        // §6.1: getCurrentLocation discards fixes older than 30 s, so a window
        // that reached it would strand a consumer on a held target the
        // controller is still re-posting at 1 Hz
        assertTrue(TARGET_GRACE_PERIOD_MILLIS > 0L)
        assertTrue(TARGET_GRACE_PERIOD_MILLIS < 30_000L)
    }
}
