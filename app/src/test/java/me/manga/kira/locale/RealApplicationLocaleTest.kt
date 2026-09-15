package me.manga.kira.locale

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.util.Locale
import me.manga.kira.MyApp
import me.manga.kira.core.storage.StorageKeys
import org.junit.After
import org.junit.Before
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Guards optional SDK startup only; every app test still boots the real Application and Koin graph. */
@OptIn(ExperimentalKermitApi::class)
@Config(
    shadows = [
        ResourceTestWorkManagerShadow::class,
        ResourceTestFirebaseShadow::class,
        ResourceTestCrashlyticsWriterShadow::class,
        ResourceTestAppUpdateFactoryShadow::class,
    ],
)
abstract class RealApplicationLocaleTest {
    protected val app: MyApp by lazy { RuntimeEnvironment.getApplication() as MyApp }
    private lateinit var previousDefault: Locale
    private lateinit var previousSystemConfiguration: Configuration
    private lateinit var previousLogWriters: List<LogWriter>
    private lateinit var previousMinSeverity: Severity
    private var guardsArmed = false

    @Before
    fun rememberLocaleEnvironment() {
        previousDefault = Locale.getDefault()
        previousSystemConfiguration = Configuration(Resources.getSystem().configuration)
        previousLogWriters = Logger.config.logWriterList.toList()
        previousMinSeverity = Logger.config.minSeverity
        check(GlobalContext.getOrNull() == null) { "The preceding test must close its Koin graph" }
        // LazyApplication has not booted MyApp yet. Config merges these shadows with the
        // first-attach method's PreKoinLocaleApplicationShadow; no late SDK interception.
        AppLocaleStartupGuards.arm()
        guardsArmed = true
    }

    @After
    fun restoreLocaleEnvironment() {
        try {
            if (guardsArmed) AppLocaleStartupGuards.assertExpectedStartup()
        } finally {
            try {
                if (guardsArmed) stopKoin()
            } finally {
                try {
                    if (guardsArmed) AppLocaleStartupGuards.clear()
                } finally {
                    Logger.setLogWriters(previousLogWriters)
                    Logger.setMinSeverity(previousMinSeverity)
                    try {
                        val system = Resources.getSystem()
                        @Suppress("DEPRECATION")
                        system.updateConfiguration(previousSystemConfiguration, system.displayMetrics)
                    } finally {
                        Locale.setDefault(previousDefault)
                    }
                }
            }
        }
    }

    protected fun selectLanguage(tag: String) {
        app.androidLocaleState.settings.putString(StorageKeys.SELECTED_LANGUAGE, tag)
    }

    protected fun setSystemLocales(locales: LocaleList) {
        val system = Resources.getSystem()
        val configuration = Configuration(system.configuration).apply { setLocales(locales) }
        // Test input only: API35's LocaleManager shadow reads this same system configuration.
        @Suppress("DEPRECATION")
        system.updateConfiguration(configuration, system.displayMetrics)
    }
}
