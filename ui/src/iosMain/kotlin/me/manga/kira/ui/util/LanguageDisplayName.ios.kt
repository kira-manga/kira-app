package me.manga.kira.ui.util

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localizedStringForLanguageCode
import platform.Foundation.preferredLanguages

/**
 * iOS: `localizedStringForLanguageCode` against the ACTIVE app language
 * (`NSLocale.preferredLanguages.first` — the same signal compose-resources strings resolve
 * against, moved live by `LocalAppLocale`'s `AppleLanguages` write since PI2 2026-07).
 * `NSLocale.currentLocale` would go stale mid-session after a live switch: the Language screen
 * would keep rendering language names in the PREVIOUS language while every string around them
 * had already switched.
 */
actual fun displayLanguageName(code: String): String {
    val tag = code.trim().lowercase()
    if (tag.isEmpty()) return code
    val active = (NSLocale.preferredLanguages.firstOrNull() as? String)
        ?.let { NSLocale(localeIdentifier = it) }
        ?: NSLocale.currentLocale
    val name = active.localizedStringForLanguageCode(tag)
    if (name.isNullOrBlank()) return code
    // Native capitalizes the first letter (localizedStringForLanguageCode returns lowercase on
    // non-English locales, e.g. fr "anglais" -> "Anglais"). Mirrors native's replaceFirstChar.
    return name.replaceFirstChar { it.uppercaseChar() }
}
