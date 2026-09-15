package me.manga.kira.platform.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import me.manga.kira.platform.storage.DataStoreHelper
import java.util.Locale

/**
 * Resolves the saved app language without mutating shared Resources or framework app locales.
 * [rawApplicationBase] must be the unwrapped base supplied to Application.attachBaseContext.
 */
class AndroidResourceLocales(
    private val rawApplicationBase: Context,
    private val dataStore: DataStoreHelper,
) {
    /** Copies [base], preserving non-locale configuration and the existing lenient tag semantics. */
    fun configuration(
        base: Configuration,
        languageTag: String = dataStore.currentLanguage(),
    ): Configuration {
        val locales =
            if (languageTag.isBlank()) systemLocales() else LocaleList(Locale.forLanguageTag(languageTag))
        return Configuration(base).apply { setLocales(locales) }
    }

    /** Reads the selection once and returns a scoped context based on current raw app resources. */
    fun snapshot(): Context =
        rawApplicationBase.createConfigurationContext(configuration(rawApplicationBase.resources.configuration))

    private fun systemLocales(): LocaleList {
        val frameworkLocales =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                rawApplicationBase.getSystemService(LocaleManager::class.java)?.systemLocales
            } else {
                null
            }
        val locales = frameworkLocales ?: Resources.getSystem().configuration.locales
        return if (locales.isEmpty) LocaleList(Locale.ENGLISH) else locales
    }
}
