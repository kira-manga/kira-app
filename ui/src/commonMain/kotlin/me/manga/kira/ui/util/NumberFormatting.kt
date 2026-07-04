package me.manga.kira.ui.util

/**
 * Format [value] with LOCALE-AWARE thousands grouping, matching native's `%,d`
 * (`R.string.value_count`): Arabic renders Arabic-Indic digits with the Arabic grouping mark, German
 * uses '.', French a thin space, etc. Uses the platform number formatter against the active locale
 * rather than a hardcoded U+002C comma (which diverged from native in ar/de/fr/ru at 4+ digits).
 *
 * Locale source per platform: Android & Desktop use `java.util.Locale.getDefault()` (which the app's
 * locale override moves via `Locale.setDefault`); iOS uses `NSNumberFormatter`'s default
 * `NSLocale.currentLocale`.
 */
expect fun formatGroupedNumber(value: Long): String

/**
 * Format [value] with LOCALE-AWARE digits but NO thousands grouping, matching native's bare `%d`
 * placeholders (e.g. `R.string.days_ago` = `"%1$d days ago"`): Arabic renders Arabic-Indic digits
 * ('٣'), every other locale Western digits. Used for the small relative-time / count placeholders
 * where grouping is undesirable but digit shaping still has to follow the active locale — the same
 * divergence class [formatGroupedNumber] fixes for grouped values.
 *
 * Locale source is identical to [formatGroupedNumber] per platform.
 */
expect fun formatLocalizedInt(value: Int): String

/**
 * Format [value] with LOCALE-AWARE exactly-two fraction digits, matching native's `%.2f` rendered
 * through the default locale (`String.format(getString(R.string.megabytes), size)`): German/French
 * show a decimal comma (`1,23`), Arabic shows Arabic-Indic digits with the Arabic decimal mark.
 * Used by [formatByteSize] for the cache-size `size_*` unit patterns (typed wire, 2026-07 backlog
 * L15). No thousands grouping — `%.2f` never grouped.
 *
 * Locale source is identical to [formatGroupedNumber] per platform.
 */
expect fun formatLocalizedTwoDecimals(value: Double): String
