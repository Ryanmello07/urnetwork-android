package com.bringyour.network.utils

import java.util.Locale

/**
 * Compact byte count, e.g. "996 B", "1.2 KiB", "3.4 MiB", "1.1 GiB"
 */
fun formatByteCountCompact(byteCount: Long): String {
    val kib = 1024.0
    val mib = kib * 1024
    val gib = mib * 1024
    val tib = gib * 1024

    val v = byteCount.toDouble()

    fun fmt(value: Double, unit: String): String {
        return when {
            value >= 100 -> String.format(Locale.US, "%.0f %s", value, unit)
            value >= 10 -> String.format(Locale.US, "%.1f %s", value, unit)
            else -> String.format(Locale.US, "%.2f %s", value, unit)
        }
    }

    return when {
        v < kib -> "$byteCount B"
        v < mib -> fmt(v / kib, "KiB")
        v < gib -> fmt(v / mib, "MiB")
        v < tib -> fmt(v / gib, "GiB")
        else -> fmt(v / tib, "TiB")
    }
}

/**
 * Compact byte rate, e.g. "1.2 KiB/s"
 */
fun formatByteRate(bytesPerSecond: Long): String {
    return formatByteCountCompact(bytesPerSecond) + "/s"
}

/**
 * Compact count, e.g. "996", "1.2k", "3.4M"
 */
fun formatCountCompact(count: Long): String {
    val v = count.toDouble()
    return when {
        count < 1000 -> "$count"
        v < 1_000_000 -> String.format(Locale.US, if (v < 10_000) "%.1fk" else "%.0fk", v / 1000)
        else -> String.format(Locale.US, "%.1fM", v / 1_000_000)
    }
}

/**
 * Compact packet rate, e.g. "340 pkt/s"
 */
fun formatPacketRate(packetsPerSecond: Long): String {
    return formatCountCompact(packetsPerSecond) + " pkt/s"
}

/**
 * Compact bit rate, e.g. "1.2 Mbps"
 */
fun formatBitRate(bitsPerSecond: Long): String {
    val v = bitsPerSecond.toDouble()

    fun fmt(value: Double, unit: String): String {
        return when {
            value >= 100 -> String.format(Locale.US, "%.0f %s", value, unit)
            value >= 10 -> String.format(Locale.US, "%.1f %s", value, unit)
            else -> String.format(Locale.US, "%.2f %s", value, unit)
        }
    }

    return when {
        v < 1000 -> "$bitsPerSecond bps"
        v < 1_000_000 -> fmt(v / 1000, "Kbps")
        v < 1_000_000_000 -> fmt(v / 1_000_000, "Mbps")
        else -> fmt(v / 1_000_000_000, "Gbps")
    }
}
