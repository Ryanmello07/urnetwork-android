package com.bringyour.network.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportTest {

    @Test
    fun bundleFileNameIsSortableAndCarriesTheMode() {
        val raw = diagnosticBundleFileName(millis = 1767225600000L, redacted = false)
        val redacted = diagnosticBundleFileName(millis = 1767225600000L, redacted = true)

        assertTrue("raw name should end in .zip, was $raw", raw.endsWith(".zip"))
        assertTrue("redacted name should say so, was $redacted", redacted.contains("redacted"))
        assertTrue("raw name should not claim redaction, was $raw", !raw.contains("redacted"))
        // lexical sort must match chronological sort
        val earlier = diagnosticBundleFileName(millis = 1767225500000L, redacted = false)
        assertTrue("$earlier should sort before $raw", earlier < raw)
    }

    @Test
    fun logcatCommandReadsOnlyThisAppsOwnBuffer() {
        assertEquals(listOf("logcat", "-d", "-v", "threadtime"), logcatDumpCommand())
    }
}
