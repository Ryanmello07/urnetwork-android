package com.bringyour.network

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalStatusCompatContractTest {
    private fun compiledClass(name: String): String {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing compiled class $name"
        }
        return resource.use { input ->
            String(input.readBytes(), StandardCharsets.ISO_8859_1)
        }
    }

    @Test
    fun legacyApplicationBytecodeDoesNotLinkApi29ThermalTypes() {
        val unavailableType = "android/os/PowerManager\$OnThermalStatusChangedListener"
        val applicationBytecode = compiledClass("com/bringyour/network/MainApplication.class")
        val compatBytecode = compiledClass("com/bringyour/network/Api29ThermalStatusCompat.class")

        assertFalse(applicationBytecode.contains(unavailableType))
        assertTrue(compatBytecode.contains(unavailableType))
    }
}
