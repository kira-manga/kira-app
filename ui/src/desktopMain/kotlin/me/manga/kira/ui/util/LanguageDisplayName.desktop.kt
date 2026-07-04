package me.manga.kira.ui.util

import java.util.Locale

/** Desktop (JVM): `Locale.getDisplayLanguage` against the active JVM default locale. */
actual fun displayLanguageName(code: String): String {
    val tag = code.trim()
    if (tag.isEmpty()) return code
    val device = Locale.getDefault()
    // Native capitalizes the first letter (getDisplayLanguage returns lowercase on non-English
    // locales, e.g. fr "anglais" -> "Anglais"). Mirrors native's replaceFirstChar { uppercase }.
    return Locale.forLanguageTag(tag).getDisplayLanguage(device)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(device) else it.toString() }
        .ifBlank { code }
}
