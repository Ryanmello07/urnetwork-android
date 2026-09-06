package com.bringyour.network.acceptance

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActiveClientLedgerTest {
    @Test
    fun `merge preserves allocations and is idempotent`() {
        assertEquals(
            "client-a\nclient-b\n",
            mergeActiveClientLedger("client-b\n", "client-a"),
        )
        assertEquals(
            "client-a\nclient-b\n",
            mergeActiveClientLedger("client-a\nclient-b\n", "client-b"),
        )
    }

    @Test
    fun `merge rejects new and previously retained invalid IDs`() {
        assertThrows(IllegalArgumentException::class.java) {
            mergeActiveClientLedger("", "client/id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            mergeActiveClientLedger("client/id\n", "client-a")
        }
    }

    @Test
    fun `ledger retains a prior run until cleanup owns deletion`() {
        val directory = Files.createTempDirectory("active-client-ledger").toFile()
        try {
            val file = directory.resolve("active-client-ids")
            file.writeText("old-client\n")
            val ledger = ActiveClientLedger(file)

            ledger.retain("new-client")
            ledger.retain("new-client")

            assertEquals("new-client\nold-client\n", file.readText())
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
