package com.localgithub.githost.util

/** Human-readable size: "512 B", "12.4 KB", "3.1 MB". Negative means unknown. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = listOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
    return if (u == 0) "%.0f %s".format(v, units[u]) else "%.1f %s".format(v, units[u])
}

/** "1 commit", "3 commits". */
fun plural(n: Int, unit: String): String = if (n == 1) "1 $unit" else "$n ${unit}s"
