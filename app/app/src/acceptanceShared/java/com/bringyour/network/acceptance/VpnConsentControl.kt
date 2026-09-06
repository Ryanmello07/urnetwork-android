package com.bringyour.network.acceptance

import java.util.Locale

internal const val VPN_CONSENT_PACKAGE = "com.android.vpndialogs"
internal const val VPN_CONSENT_BUTTON_RESOURCE = "android:id/button1"

internal data class VpnConsentControlIdentity(
    val packageName: String?,
    val resourceName: String?,
    val text: String?,
)

internal enum class VpnConsentControlClassification {
    VERIFIED,
    INCOMPLETE,
    FOREIGN_PACKAGE,
    FOREIGN_RESOURCE,
    FOREIGN_ACTION,
}

/**
 * Identifies the positive action owned by Android's VPN confirmation package.
 * A generic AlertDialog button is never sufficient: crash and ANR dialogs use
 * the same framework resource ID and must remain visible for harness failure.
 */
internal fun classifyVpnConsentControl(
    identity: VpnConsentControlIdentity,
): VpnConsentControlClassification {
    val packageName = identity.packageName?.trim().orEmpty()
    val resourceName = identity.resourceName?.trim().orEmpty()
    val action = identity.text?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (packageName.isEmpty() || resourceName.isEmpty() || action.isEmpty()) {
        return VpnConsentControlClassification.INCOMPLETE
    }
    if (packageName != VPN_CONSENT_PACKAGE) {
        return VpnConsentControlClassification.FOREIGN_PACKAGE
    }
    if (resourceName != VPN_CONSENT_BUTTON_RESOURCE) {
        return VpnConsentControlClassification.FOREIGN_RESOURCE
    }
    if (action != "allow" && action != "ok") {
        return VpnConsentControlClassification.FOREIGN_ACTION
    }
    return VpnConsentControlClassification.VERIFIED
}
