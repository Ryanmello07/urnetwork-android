package com.bringyour.network.location

// Pure decision logic for the exit-target grace window (no Android
// dependencies) so it is unit testable on the JVM. MockLocationFeeder builds a
// main-looper Handler in a field initializer and cannot be constructed in a
// unit test, so the generation-tracked state machine lives here and the feeder
// keeps only the Handler and the SDK subscriptions. Same split as
// TunnelRecoveryPolicy.

// How long the last target survives an empty connected-provider list. The
// ceiling comes from MOCKLOCATION.md §6.1: getCurrentLocation() discards any
// fix older than 30 s, and the controller keeps re-posting the held target at
// 1 Hz (§6.2), so a window well under 30 s can never strand a consumer. The
// floor is the cost of being wrong: removeTestProvider restores the real
// provider instantly and purges the mock last-known cache (§6.5), so every
// provider flap shorter than the window would otherwise leak a hardware fix
// for as long as the reconnect takes. 10 s covers a provider handover or a
// network re-dial with room to spare.
internal const val TARGET_GRACE_PERIOD_MILLIS = 10_000L

internal enum class MockTargetAction {
    // a fresh fix: push it and drop any running grace window
    PUSH,

    // the provider list dipped while a target is still held: open the window
    HOLD,

    // it dipped again while a window is already running; restarting the window
    // on every flap would extend it without bound, so let the running one own
    // the clear
    ALREADY_HOLDING,

    // nothing held worth protecting: clear the target now
    CLEAR,
}

internal data class MockTargetDecision(
    val action: MockTargetAction,
    val target: MockLocationTarget? = null,
    // HOLD only: the caller schedules the expiry carrying this generation and
    // hands it back to graceExpired(), which is how a callback that lost its
    // race is dropped
    val generation: Long = 0L,
    val delayMillis: Long = 0L,
)

// Not thread safe by design: MockLocationFeeder touches this only from inside
// its own monitor (every entry point is @Synchronized or a
// synchronized(feeder) block, including the expiry runnable), which is the
// same lock the fields it replaced were already published under.
internal class MockLocationGracePolicy(
    private val gracePeriodMillis: Long = TARGET_GRACE_PERIOD_MILLIS,
) {
    // bumped by every event that invalidates a queued expiry; an expiry that
    // fires with an older generation must do nothing
    private var generation = 0L
    private var holding = false

    // the target last pushed to the controller — what the window protects
    var lastKnownTarget: MockLocationTarget? = null
        private set

    fun onTargetResolved(target: MockLocationTarget?): MockTargetDecision {
        if (target != null) {
            generation++
            holding = false
            lastKnownTarget = target
            return MockTargetDecision(MockTargetAction.PUSH, target = target)
        }
        if (lastKnownTarget == null) {
            // never had a fix to protect, so there is nothing to wait for
            return MockTargetDecision(MockTargetAction.CLEAR)
        }
        if (holding) {
            return MockTargetDecision(MockTargetAction.ALREADY_HOLDING)
        }
        holding = true
        generation++
        return MockTargetDecision(
            MockTargetAction.HOLD,
            target = lastKnownTarget,
            generation = generation,
            delayMillis = gracePeriodMillis,
        )
    }

    // true when the expiry that fired is still the current one, i.e. the caller
    // must clear the target now
    fun graceExpired(generation: Long): Boolean {
        if (!holding || generation != this.generation) return false
        holding = false
        lastKnownTarget = null
        return true
    }

    // tunnel down, device swap or shutdown: the target goes immediately (no
    // grace — the window exists for provider flaps, not for a connection the
    // user or the SDK ended) and any queued expiry is invalidated
    fun cancel() {
        generation++
        holding = false
        lastKnownTarget = null
    }
}
