package me.manga.kira.ui.util

import java.text.NumberFormat
import java.util.Locale

/** Desktop (JVM): `NumberFormat` against the active JVM default locale (moved by `Locale.setDefault`). */
actual fun formatGroupedNumber(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

/** Desktop (JVM): locale digits, grouping disabled (relative-time / count placeholders). */
actual fun formatLocalizedInt(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).apply { isGroupingUsed = false }.format(value.toLong())

/** Desktop (JVM): bit-identical to native's default-locale `String.format("%.2f", value)`. */
actual fun formatLocalizedTwoDecimals(value: Double): String =
    String.format(Locale.getDefault(), "%.2f", value)
