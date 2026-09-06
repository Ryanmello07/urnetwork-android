package com.bringyour.network.ui.login

internal data class LoginProgressVisibility(
    val contentVisible: Boolean,
    val welcomeOverlayVisible: Boolean,
)

/** The only retryable terminal presentation after auth-client/startup failure. */
internal fun loginRetryVisibility(): LoginProgressVisibility = LoginProgressVisibility(
    contentVisible = true,
    welcomeOverlayVisible = false,
)
