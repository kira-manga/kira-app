package me.manga.kira.platform.locale

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList

/**
 * Per-host resource bridge. An Activity owns its own bridge; the shared resolver retains no Activity.
 * Always pass the unwrapped host base, never the host whose resource getter delegates here.
 */
class AndroidLocaleResources(
    private val rawBase: Context,
    private val locales: AndroidResourceLocales,
) {
    private var cachedBase: Configuration? = null
    private var cachedLocales: LocaleList? = null
    private var cachedContext: Context? = null

    /** Reuses resources only while both the complete raw configuration and effective locales match. */
    val resources: Resources
        @Synchronized get() {
            val base = Configuration(rawBase.resources.configuration)
            val configuration = locales.configuration(base)
            val previous = cachedContext
            if (previous == null || cachedBase != base || cachedLocales != configuration.locales) {
                val context = rawBase.createConfigurationContext(configuration)
                cachedBase = base
                cachedLocales = configuration.locales
                cachedContext = context
                return context.resources
            }
            return previous.resources
        }
}
