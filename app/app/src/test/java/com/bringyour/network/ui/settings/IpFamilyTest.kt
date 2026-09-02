package com.bringyour.network.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IpFamilyTest {

    @Test
    fun clampsOutOfRangeToAuto() {
        assertEquals(IP_FAMILY_AUTO, clampIpFamilyPolicy(-1L))
        assertEquals(IP_FAMILY_AUTO, clampIpFamilyPolicy(7L))
        assertEquals(IP_FAMILY_FORCE_4, clampIpFamilyPolicy(IP_FAMILY_FORCE_4))
        assertEquals(IP_FAMILY_FORCE_6, clampIpFamilyPolicy(IP_FAMILY_FORCE_6))
    }

    @Test
    fun cyclesAutoForce4Force6AndBack() {
        assertEquals(IP_FAMILY_FORCE_4, nextIpFamilyPolicy(IP_FAMILY_AUTO))
        assertEquals(IP_FAMILY_FORCE_6, nextIpFamilyPolicy(IP_FAMILY_FORCE_4))
        assertEquals(IP_FAMILY_AUTO, nextIpFamilyPolicy(IP_FAMILY_FORCE_6))
    }

    // Parity with ios IpFamilyTests.autoDetailReportsALearnedDemotion: the
    // detail must distinguish auto-with-nothing-learned from
    // auto-with-a-demotion, or the row looks the same either way.
    @Test
    fun autoDetailResourceDiffersWhenSomethingIsDemoted() {
        assertNotEquals(
            ipFamilyDetailResource(IP_FAMILY_AUTO, status = ""),
            ipFamilyDetailResource(IP_FAMILY_AUTO, status = "IPv6 demoted for 4m (2 strikes)"),
        )
    }
}
