package me.manga.kira.locale

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * Android [LocalAppLocale] — overrides the `LocalConfiguration` locale, which compose-resources
 * (and the Android resource system) read. A blank/null code restores the captured system default.
 */
actual object LocalAppLocale {
    private var default: Locale? = null

    // Android re-resolves resources live via the LocalConfiguration override below.
    actual val isLiveLocaleSwitchSupported: Boolean = true

    actual val current: String
        @Composable get() = Locale.getDefault().toLanguageTag()

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val configuration = LocalConfiguration.current
        if (default == null) default = Locale.getDefault()
        val locale = if (value.isNullOrBlank()) default!! else Locale.forLanguageTag(value)
        // Deliberate composition-phase mutation of the JVM-global default (mirrors the Desktop
        // sibling): compose-resources resolves strings off Locale.getDefault() in this same pass, so
        // the default must be set before provider-driven recomposition re-reads it. The guard keeps
        // repeated/speculative compositions idempotent (no needless setDefault when already matching).
        if (Locale.getDefault() != locale) Locale.setDefault(locale)
        val newConfig = Configuration(configuration).apply { setLocale(locale) }
        return LocalConfiguration.provides(newConfig)
    }
}
