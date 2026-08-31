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
