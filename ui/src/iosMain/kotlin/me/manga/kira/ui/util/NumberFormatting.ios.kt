package me.manga.kira.ui.util

import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages

/**
 * Formatter cache keyed on the ACTIVE app language — `NSLocale.preferredLanguages.first`, the
 * same signal compose-resources string resolution reads (and the value `LocalAppLocale`'s
 * `AppleLanguages` write moves). Since the iOS live locale switch (PI2, 2026-07) strings
 * re-resolve mid-session, so digits must follow the same signal or a switch would render, e.g.,
 * Arabic strings with stale Western digit shaping until relaunch. `NSLocale.currentLocale` is NOT
 * usable here: it never moves in-process and ignores the per-app `AppleLanguages` override even
 * across launches.
 *
 * Caching rationale unchanged from the previous file-scope vals — Apple flags NSNumberFormatter
 * creation as expensive, and all call sites are main-thread composition (NSNumberFormatter is not
 * thread-safe), so the single mutable cache slot is race-free; it rebuilds only when the active
 * language actually changes (a rare settings action).
 */
private class LocaleFormatters(val localeId: String) {
    private val locale = NSLocale(localeIdentifier = localeId)

    /** Decimal style, locale-aware grouping. */
    val grouped = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        setLocale(locale)
    }

    /** Locale digits, grouping disabled (bare-count placeholders). */
    val plain = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        usesGroupingSeparator = false
        setLocale(locale)
    }

    /** Exactly-two fraction digits, no grouping (the `%.2f` shape of the size_* patterns). */
    val twoDecimals = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        usesGroupingSeparator = false
        minimumFractionDigits = 2u
        maximumFractionDigits = 2u
        setLocale(locale)
    }
}

private var cachedFormatters: LocaleFormatters? = null

private fun formatters(): LocaleFormatters {
    val localeId = (NSLocale.preferredLanguages.firstOrNull() as? String)
        ?: NSLocale.currentLocale.localeIdentifier
    cachedFormatters?.takeIf { it.localeId == localeId }?.let { return it }
    return LocaleFormatters(localeId).also { cachedFormatters = it }
}

/** iOS: `NSNumberFormatter` against the active app language (locale-aware grouping). */
actual fun formatGroupedNumber(value: Long): String =
    formatters().grouped.stringFromNumber(NSNumber(long = value)) ?: value.toString()

/** iOS: `NSNumberFormatter` against the active app language, grouping disabled (count placeholders). */
actual fun formatLocalizedInt(value: Int): String =
    formatters().plain.stringFromNumber(NSNumber(int = value)) ?: value.toString()

/** iOS: `NSNumberFormatter` against the active app language, exactly two fraction digits. */
actual fun formatLocalizedTwoDecimals(value: Double): String =
    formatters().twoDecimals.stringFromNumber(NSNumber(double = value)) ?: value.toString()
