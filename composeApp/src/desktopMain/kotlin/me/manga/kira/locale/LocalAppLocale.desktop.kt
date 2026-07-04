package me.manga.kira.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * Desktop (JVM) [LocalAppLocale] — compose-resources reads `Locale.getDefault()`, so set the JVM
 * default and expose the tag through a composition local. The app root keys its content on the
 * language code, so the keyed recomposition re-reads the new default. Blank/null restores the
 * captured system default.
 */
actual object LocalAppLocale {
    private var default: Locale? = null
    private val LocalAppLocale = staticCompositionLocalOf { Locale.getDefault().toLanguageTag() }

    // Desktop re-resolves resources live: Locale.setDefault() below moves the JVM default that
    // compose-resources reads, and the keyed recomposition re-reads it.
    actual val isLiveLocaleSwitchSupported: Boolean = true

    actual val current: String
        @Composable get() = LocalAppLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        if (default == null) default = Locale.getDefault()
        val locale = if (value.isNullOrBlank()) default!! else Locale.forLanguageTag(value)
        // Deliberate composition-phase mutation of the JVM-global default: compose-resources resolves
        // strings off Locale.getDefault() in this same pass, so the default must be set before the
        // keyed recomposition re-reads it. The guard keeps repeated/speculative compositions
        // idempotent (no needless setDefault when the locale already matches).
        if (Locale.getDefault() != locale) Locale.setDefault(locale)
        return LocalAppLocale.provides(locale.toLanguageTag())
    }
}
