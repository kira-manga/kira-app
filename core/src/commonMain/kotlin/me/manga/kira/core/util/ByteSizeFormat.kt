package me.manga.kira.core.util

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Format a raw byte count as a human-readable size string like `"15.2 MB"` or `"512 KB"`.
 *
 * Pure Kotlin, no platform dependency — lives in `:core` so `:presentation` (and any other layer
 * above `:core`) can format a persisted `sizeBytes` for display without reaching the `:platform`
 * filesystem SPI. The logic mirrors the `internal formatBytes` in
 * `:platform/filesystem/FileSizeFormatter` and the native `FileSizeUtils.formatBytes`
 * (`"%.1f <unit>"`, B/KB/MB/GB/TB on a 1024 base) so the displayed text matches everywhere.
 *
 * Returns `"0 B"` for a non-positive count (native shows `0 B` for an unknown/empty size).
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceAtMost(4)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    val unit = when (digitGroups) {
        0 -> "B"
        1 -> "KB"
        2 -> "MB"
        3 -> "GB"
        else -> "TB"
    }
    // Round half-up to one decimal and build the string by hand. Native uses "%.1f <unit>"
    // (round-half-up); doing the same here avoids both the truncation bias of `(v*10).toLong()`
    // (which would render 1.945 MB as "1.9 MB" vs native "2.0 MB") and any Double.toString tail.
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10} $unit"
}
