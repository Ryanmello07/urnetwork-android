package com.bringyour.network.acceptance

import com.bringyour.network.LoginStartupFailure
import com.bringyour.network.LoginStartupStage
import com.bringyour.network.LoginStartupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class LoginStartupTimeoutEvidenceTest {
    @Test
    fun `Pending capture completes before timeout is thrown`() {
        val order = mutableListOf<String>()
        val timeout = AssertionError("timeout")

        val thrown = assertThrows(AssertionError::class.java) {
            throwLoginStartupTimeout(
                LoginStartupState.Pending(7, LoginStartupStage.PASSWORD_AUTH),
                timeout,
            ) {
                order += "capture"
            }
        }
        order += "caught"

        assertSame(timeout, thrown)
        assertEquals(listOf("capture", "caught"), order)
    }

    @Test
    fun `terminal startup failure does not capture stacks`() {
        var captures = 0
        val state = LoginStartupState.Failed(
            attemptId = 7,
            stage = LoginStartupStage.PASSWORD_AUTH,
            failure = LoginStartupFailure.PASSWORD_AUTH_REJECTED,
        )

        assertThrows(AssertionError::class.java) {
            throwLoginStartupTimeout(state, AssertionError("timeout")) {
                captures += 1
            }
        }

        assertEquals(0, captures)
    }

    @Test
    fun `capture failure stays attached to the classified timeout`() {
        val captureError = IllegalStateException("capture")
        val timeout = AssertionError("timeout")

        val thrown = assertThrows(AssertionError::class.java) {
            throwLoginStartupTimeout(
                LoginStartupState.Pending(7, LoginStartupStage.AUTH_CLIENT),
                timeout,
            ) {
                throw captureError
            }
        }

        assertSame(timeout, thrown)
        assertEquals(listOf(captureError), thrown.suppressed.toList())
    }
}
