package com.bringyour.network.utils

import com.bringyour.sdk.Sdk

private val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

/**
 * Whether the value is an ipv4 or ipv6 address (host names never contain ':')
 */
fun isIpAddressValue(value: String): Boolean {
    return value.contains(':') || ipv4Regex.matches(value)
}

/**
 * Compact rendering for a cluster's host values.
 *
 * Host names with more than 10 entries collapse to their base names
 * ("*.<base>", public-suffix aware via the sdk). The base names and ips
 * form one combined list; when it has more than 20 entries, at most 21
 * are shown as the first, middle, and last 7 in alphanumeric order with
 * the omitted count.
 */
fun formatHostClusterText(hosts: List<String>, ips: List<String>): String {

    // host names collapse to base names when there are more than 10
    var displayHosts = hosts
    if (hosts.size > 10) {
        val seen = mutableSetOf<String>()
        val collapsed = mutableListOf<String>()
        for (host in hosts) {
            val display = "*.${Sdk.hostBaseName(host)}"
            if (seen.add(display)) {
                collapsed.add(display)
            }
        }
        displayHosts = collapsed
    }

    val items = displayHosts + ips
    if (items.isEmpty()) {
        return "unknown"
    }

    return compactValueList(items)
}

/**
 * Shows all values when there are 20 or fewer, else the first, middle,
 * and last 7 in alphanumeric order (21 max) with the omitted count
 */
private fun compactValueList(values: List<String>): String {

    if (values.size <= 20) {
        return values.joinToString(", ")
    }

    val sorted = values.sorted()
    val n = sorted.size
    val middleStart = (n - 7) / 2
    val first = sorted.subList(0, 7)
    val middle = sorted.subList(middleStart, middleStart + 7)
    val last = sorted.subList(n - 7, n)
    val omitted = n - 21

    var text = first.joinToString(", ") +
        ", …, " + middle.joinToString(", ") +
        ", …, " + last.joinToString(", ")
    if (0 < omitted) {
        text += " + $omitted more"
    }
    return text
}
