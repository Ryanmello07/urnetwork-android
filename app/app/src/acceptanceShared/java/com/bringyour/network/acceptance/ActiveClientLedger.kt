package com.bringyour.network.acceptance

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val clientIdPattern = Regex("[A-Za-z0-9._-]+")

internal fun mergeActiveClientLedger(existing: String, clientId: String): String {
    require(clientIdPattern.matches(clientId)) { "invalid client ID" }
    val clientIds = existing.lineSequence()
        .filter(String::isNotBlank)
        .onEach { require(clientIdPattern.matches(it)) { "invalid retained client ID" } }
        .toMutableSet()
    clientIds.add(clientId)
    return clientIds.sorted().joinToString(separator = "\n", postfix = "\n")
}

/** Private, append-only ownership ledger; only host-confirmed cleanup may remove it. */
internal class ActiveClientLedger(private val file: File) {
    @Synchronized
    fun retain(clientId: String) {
        val contents = mergeActiveClientLedger(
            existing = if (file.isFile) file.readText() else "",
            clientId = clientId,
        )
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(contents)
        check(temporary.setReadable(false, false)) { "could not clear cleanup-ledger reads" }
        check(temporary.setWritable(false, false)) { "could not clear cleanup-ledger writes" }
        check(temporary.setExecutable(false, false)) { "could not clear cleanup-ledger execution" }
        check(temporary.setReadable(true, true)) { "could not make cleanup ledger owner-readable" }
        check(temporary.setWritable(true, true)) { "could not make cleanup ledger owner-writable" }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
