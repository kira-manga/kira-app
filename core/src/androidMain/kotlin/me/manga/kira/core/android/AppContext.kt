package me.manga.kira.core.android

import android.content.Context

/**
 * Process-wide holder for the Android application [Context], captured once at startup.
 *
 * Relocated to `:core` (strangler-fig Phase 2) from `:data:local`'s `DatabaseBuilder.android.kt`, so
 * BOTH the Room builder (`:data:local`) and the Ktor HTTP-cache-dir resolver (`:data:remote`) can read
 * the app Context without either data-layer leaf depending on the other. `:core` is the natural home:
 * a bare application-Context holder is a foundational platform primitive, and both leaves already
 * depend on `:core`.
 *
 * This avoids threading a Context through commonMain Koin bindings, which would force Koin's
 * Android-only `androidContext()` into common code where it doesn't belong.
 */
private var appContext: Context? = null

/**
 * Called once from `MyApp.onCreate()` BEFORE Koin init / any DB or HTTP-client construction. Stores
 * the *application* Context (`applicationContext`, not the raw context — avoids Activity leaks).
 */
fun setAndroidAppContext(context: Context) {
    appContext = context.applicationContext
}

/**
 * The registered application [Context], or `null` if [setAndroidAppContext] has not run yet. Callers
 * (the Room builder, the Ktor cache directory) fall back to a sensible default when it is null.
 */
fun androidAppContextOrNull(): Context? = appContext
