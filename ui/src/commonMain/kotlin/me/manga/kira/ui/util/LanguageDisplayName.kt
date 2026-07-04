package me.manga.kira.ui.util

/**
 * Localized display name for a language [code], rendered in the active DEVICE locale — e.g. "ar" →
 * "Arabic" on an English device, "العربية" on an Arabic device — matching native's
 * `Locale(code).getDisplayLanguage(deviceLocale)`. Falls back to the raw [code] for unknown/blank
 * codes (native's `catch { code }`). Accepts case-insensitive 2-letter codes (e.g. "AR", "EN").
 */
expect fun displayLanguageName(code: String): String
