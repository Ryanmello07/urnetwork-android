package com.bringyour.network.acceptance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordLoginAutomationTest {
    @Test
    fun exactTagsCompleteLoginWithoutGenericAccessibilityFields() {
        val screen = TaggedPasswordScreen()

        // The failed physical driver searched this empty class-based view of
        // the screen. The tagged contract remains fully actionable.
        assertFalse(screen.genericEditableFieldAvailable)
        performPasswordLogin(
            ui = screen,
            user = "acceptance-user",
            password = "acceptance-password",
            uiTimeoutMillis = 30_000,
            authTimeoutMillis = 90_000,
        )

        assertTrue(screen.authenticated)
        assertEquals(
            listOf(
                "wait:$PASSWORD_LOGIN_USER_TAG:30000",
                "replace:$PASSWORD_LOGIN_USER_TAG",
                "click:$PASSWORD_LOGIN_NEXT_TAG:90000",
                "wait:$PASSWORD_LOGIN_INPUT_TAG:90000",
                "replace:$PASSWORD_LOGIN_INPUT_TAG",
                "click:$PASSWORD_LOGIN_SUBMIT_TAG:90000",
            ),
            screen.operations,
        )
    }

    /** A two-screen form that exposes only the app's semantics contract. */
    private class TaggedPasswordScreen : PasswordLoginUi {
        val genericEditableFieldAvailable = false
        val operations = mutableListOf<String>()
        var authenticated = false
            private set

        private var visibleTag = PASSWORD_LOGIN_USER_TAG
        private var user = ""
        private var password = ""

        override fun waitForTag(tag: String, timeoutMillis: Long) {
            operations += "wait:$tag:$timeoutMillis"
            check(tag == visibleTag) { "tag $tag is not visible" }
        }

        override fun replaceTagText(tag: String, value: String) {
            operations += "replace:$tag"
            check(tag == visibleTag) { "tag $tag is not editable" }
            when (tag) {
                PASSWORD_LOGIN_USER_TAG -> user = value
                PASSWORD_LOGIN_INPUT_TAG -> password = value
                else -> error("tag $tag is not a password-login field")
            }
        }

        override fun performEnabledTagClick(tag: String, timeoutMillis: Long) {
            operations += "click:$tag:$timeoutMillis"
            when (tag) {
                PASSWORD_LOGIN_NEXT_TAG -> {
                    check(visibleTag == PASSWORD_LOGIN_USER_TAG && user.isNotBlank())
                    visibleTag = PASSWORD_LOGIN_INPUT_TAG
                }
                PASSWORD_LOGIN_SUBMIT_TAG -> {
                    check(visibleTag == PASSWORD_LOGIN_INPUT_TAG && password.isNotBlank())
                    authenticated = true
                }
                else -> error("tag $tag is not a password-login action")
            }
        }
    }
}
