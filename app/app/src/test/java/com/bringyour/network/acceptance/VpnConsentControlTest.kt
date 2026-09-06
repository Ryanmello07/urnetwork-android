package com.bringyour.network.acceptance

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnConsentControlTest {
    @Test
    fun acceptsOnlyAndroidVpnDialogPositiveActions() {
        assertClassification(VpnConsentControlClassification.VERIFIED, text = "Allow")
        assertClassification(VpnConsentControlClassification.VERIFIED, text = " OK ")
    }

    @Test
    fun rejectsFrameworkButtonFromAnrDialog() {
        assertClassification(
            expected = VpnConsentControlClassification.FOREIGN_PACKAGE,
            packageName = "android",
            text = "Wait",
        )
        assertClassification(
            expected = VpnConsentControlClassification.FOREIGN_PACKAGE,
            packageName = "com.android.systemui",
            text = "Allow",
        )
    }

    @Test
    fun rejectsMalformedOrPartiallyMatchingControls() {
        assertClassification(
            expected = VpnConsentControlClassification.FOREIGN_ACTION,
            text = "Wait",
        )
        assertClassification(
            expected = VpnConsentControlClassification.FOREIGN_RESOURCE,
            resourceName = "android:id/button2",
        )
        assertClassification(
            expected = VpnConsentControlClassification.INCOMPLETE,
            packageName = null,
        )
        assertClassification(
            expected = VpnConsentControlClassification.INCOMPLETE,
            text = "   ",
        )
    }

    private fun assertClassification(
        expected: VpnConsentControlClassification,
        packageName: String? = VPN_CONSENT_PACKAGE,
        resourceName: String? = VPN_CONSENT_BUTTON_RESOURCE,
        text: String? = "Allow",
    ) {
        assertEquals(
            expected,
            classifyVpnConsentControl(
                VpnConsentControlIdentity(
                    packageName = packageName,
                    resourceName = resourceName,
                    text = text,
                ),
            ),
        )
    }
}
