package me.manga.kira.locale

import android.app.Application
import android.content.Context
import me.manga.kira.platform.locale.AndroidLocaleOwner
import me.manga.kira.platform.locale.AndroidLocaleState

/** Locale ownership only; no MyApp, Koin, SDK startup or Activity lifecycle replacement. */
open class LocaleOnlyTestApplication : Application(), AndroidLocaleOwner {
    override lateinit var androidLocaleState: AndroidLocaleState
        private set

    override fun attachBaseContext(base: Context) {
        androidLocaleState = AndroidLocaleState(base)
        super.attachBaseContext(base)
    }
}
