package com.bringyour.network.acceptance

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

private val vpnConsentAction = Pattern.compile("(?i)^(allow|ok)$")

/** Clicks only a fully identified Android VPN consent action, when present. */
internal fun UiDevice.clickVerifiedVpnConsentIfPresent() {
    val candidate = wait(Until.findObject(By.text(vpnConsentAction)), 8_000) ?: return
    val identity = VpnConsentControlIdentity(
        packageName = candidate.applicationPackage,
        resourceName = candidate.resourceName,
        text = candidate.text,
    )
    val classification = classifyVpnConsentControl(identity)
    check(classification == VpnConsentControlClassification.VERIFIED) {
        "refusing unverified VPN consent control: $classification"
    }
    candidate.click()
}
