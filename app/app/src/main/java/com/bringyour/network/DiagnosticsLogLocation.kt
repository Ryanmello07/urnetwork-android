package com.bringyour.network

import java.io.File

/**
 * Where each process writes its logs.
 *
 * Android is single-process (no `android:process` in the manifest), so "app"
 * is the only source that ever appears here -- but the per-process layout is
 * what the exporter enumerates, and keeping it identical to iOS's
 * `DiagnosticsLogLocation.appProcessName` means one sdk call shape and one
 * bundle shape across both platforms.
 */
const val APP_LOG_PROCESS_NAME = "app"

/** The shared log root: one subdirectory per writing process. */
fun logRootDir(filesDir: File): File = File(filesDir, "logs")

/** glog's severity tags, as they appear in a file name after ".log.". */
private val LOG_FILE_SEVERITIES = listOf("INFO", "WARNING", "ERROR", "FATAL")

/**
 * True for a name glog wrote, i.e.
 * `<program>.<host>.<user>.log.<SEVERITY>.<time>.<pid>`.
 */
fun isGlogFileName(name: String): Boolean =
    LOG_FILE_SEVERITIES.any { name.contains(".log.$it") }

/**
 * Moves pre-upgrade log files out of `filesDir` into the per-process log
 * directory, returning how many were relocated.
 *
 * Builds before the per-process root wrote glog files directly into
 * `filesDir`. Retention only ever prunes the directory glog is currently
 * pointed at (`clearOldLogs`, sdk/sdk.go, called from `SetLogDir`), so once
 * the root moves those files are never touched again: up to four files of up
 * to 16MB each stranded in `filesDir` forever on every upgrading install.
 * They are also invisible to the exporter, which walks only the
 * SUBDIRECTORIES of the log root -- so a user who upgrades and then exports
 * to report an incident that predates the upgrade gets none of the logs that
 * recorded it, which is exactly what goal 1 promises them.
 *
 * Moving rather than deleting keeps those logs exportable, and doing it
 * BEFORE `SetLogDirForProcess` hands the merged set to the same retention
 * pass -- which keeps the four newest and drops the rest -- so this bounds
 * the storage rather than doubling it.
 */
fun migrateLegacyLogFiles(filesDir: File, processLogDir: File): Int {
    val legacy = filesDir.listFiles()?.filter { it.isFile && isGlogFileName(it.name) } ?: return 0
    if (legacy.isEmpty()) {
        return 0
    }
    if (!processLogDir.isDirectory && !processLogDir.mkdirs()) {
        return 0
    }

    var moved = 0
    for (file in legacy) {
        val dest = File(processLogDir, file.name)
        if (dest.exists()) {
            // glog names embed host, pid and start time, so a collision means
            // an earlier launch already migrated this file: the copy still
            // sitting in filesDir is the redundant one.
            if (file.delete()) {
                moved += 1
            }
            continue
        }
        // A rename that fails leaves the file exactly where it was, which is
        // no worse than never having run. Never delete a log we could not
        // move -- the whole point is that it stays reachable.
        if (file.renameTo(dest)) {
            moved += 1
        }
    }
    return moved
}
