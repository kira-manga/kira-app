package me.manga.kira.locale

import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import me.manga.kira.platform.locale.androidLocaleState
import java.util.Locale

/**
 * Android [LocalAppLocale] shares the actual host's resolver. Blank uses the complete current system
 * locale list, not a JVM default previously changed by Compose. The NavHost remains mounted.
 */
actual object LocalAppLocale {
    // Android re-resolves resources live via the LocalConfiguration override below.
    actual val isLiveLocaleSwitchSupported: Boolean = true

    actual val current: String
        @Composable get() = Locale.getDefault().toLanguageTag()

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val configuration = resolvedConfiguration(value)
        val locale = configuration.locales[0]
        // Deliberate composition-phase mutation of the JVM-global default (mirrors the Desktop
        // sibling): compose-resources resolves strings off Locale.getDefault() in this same pass, so
        // the default must be set before provider-driven recomposition re-reads it. The guard keeps
        // repeated/speculative compositions idempotent (no needless setDefault when already matching).
        if (Locale.getDefault() != locale) Locale.setDefault(locale)
        return LocalConfiguration.provides(configuration)
    }

    @Composable
    actual fun layoutDirection(value: String?): LayoutDirection =
        if (resolvedConfiguration(value).layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }

    @Composable
    private fun resolvedConfiguration(value: String?): Configuration =
        LocalContext.current.androidLocaleState().resourceLocales.configuration(
            LocalConfiguration.current,
            value.orEmpty(),
        )
}
