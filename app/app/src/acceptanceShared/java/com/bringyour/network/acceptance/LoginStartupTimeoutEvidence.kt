package com.bringyour.network.acceptance

import com.bringyour.network.LoginStartupState

/** Keeps Pending-only Go runtime evidence ordered before an acceptance timeout escapes. */
internal fun throwLoginStartupTimeout(
    state: LoginStartupState,
    timeoutError: AssertionError,
    capturePendingStacks: () -> Unit,
): Nothing {
    if (state is LoginStartupState.Pending) {
        runCatching(capturePendingStacks).onFailure(timeoutError::addSuppressed)
    }
    throw timeoutError
}
