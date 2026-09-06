package com.bringyour.network.acceptance

internal const val PASSWORD_LOGIN_USER_TAG = "acceptance.password.user"
internal const val PASSWORD_LOGIN_NEXT_TAG = "acceptance.password.next"
internal const val PASSWORD_LOGIN_INPUT_TAG = "acceptance.password.input"
internal const val PASSWORD_LOGIN_SUBMIT_TAG = "acceptance.password.submit"

/** Drives the app-owned password form through its stable semantics contract. */
internal interface PasswordLoginUi {
    fun waitForTag(tag: String, timeoutMillis: Long)

    fun replaceTagText(tag: String, value: String)

    fun performEnabledTagClick(tag: String, timeoutMillis: Long)
}

/**
 * Completes both password-login screens without depending on viewport layout,
 * accessibility class names, translated labels, or screen coordinates.
 */
internal fun performPasswordLogin(
    ui: PasswordLoginUi,
    user: String,
    password: String,
    uiTimeoutMillis: Long,
    authTimeoutMillis: Long,
) {
    ui.waitForTag(PASSWORD_LOGIN_USER_TAG, uiTimeoutMillis)
    ui.replaceTagText(PASSWORD_LOGIN_USER_TAG, user)
    ui.performEnabledTagClick(PASSWORD_LOGIN_NEXT_TAG, authTimeoutMillis)
    ui.waitForTag(PASSWORD_LOGIN_INPUT_TAG, authTimeoutMillis)
    ui.replaceTagText(PASSWORD_LOGIN_INPUT_TAG, password)
    ui.performEnabledTagClick(PASSWORD_LOGIN_SUBMIT_TAG, authTimeoutMillis)
}
