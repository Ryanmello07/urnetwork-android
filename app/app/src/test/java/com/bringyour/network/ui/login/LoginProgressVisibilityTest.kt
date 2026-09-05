package com.bringyour.network.ui.login

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginProgressVisibilityTest {
    @Test
    fun `startup failure restores form and removes welcome overlay`() {
        assertEquals(
            LoginProgressVisibility(contentVisible = true, welcomeOverlayVisible = false),
            loginRetryVisibility(),
        )
    }
}
