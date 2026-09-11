package me.manga.kira.presentation.features.whatsnew.viewmodel

import androidx.lifecycle.ViewModelStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.di.legacySharedViewModelsModule
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.platform.version.AppVersionProvider
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WhatsNewGateTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private var nextGate = 0

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        try {
            store.clear()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun eligibilityUsesTheExistingNonblankVersionComparisonAndBadge() =
        runTest(dispatcher) {
            for (lastSeen in listOf(null, "older", CURRENT_VERSION)) {
                val settings = settings(lastSeen)
                val gate = newGate(settings)
                runCurrent()
                val unseen = lastSeen != CURRENT_VERSION
                assertEquals(unseen, gate.shouldShowWhatsNew.value)
                assertEquals(unseen, settings.getBoolean(NEW_SOURCES_KEY, false))
            }
            for (version in listOf("", "  ")) {
                val settings = settings()
                val gate = newGate(settings, version)
                runCurrent()
                assertFalse(gate.shouldShowWhatsNew.value)
                assertFalse(settings.getBoolean(NEW_SOURCES_KEY, false))
            }
        }

    @Test
    fun successSuppressionIsInMemoryAndDoesNotReraiseTheBadge() =
        runTest(dispatcher) {
            val settings = settings()
            val gate = newGate(settings)
            runCurrent()
            assertTrue(gate.shouldShowWhatsNew.value)
            assertTrue(settings.getBoolean(NEW_SOURCES_KEY, false))
            settings.putBoolean(NEW_SOURCES_KEY, false)

            gate.onAutoNavigationSucceeded()
            assertFalse(gate.shouldShowWhatsNew.value)
            assertEquals("", settings.getString(SEEN_KEY, ""))
            SharedPrefsHelper(settings).putString(SEEN_KEY, "older")
            runCurrent()
            assertFalse(gate.shouldShowWhatsNew.value)
            assertFalse(settings.getBoolean(NEW_SOURCES_KEY, false))

            val freshGate = newGate(settings)
            runCurrent()
            assertTrue(freshGate.shouldShowWhatsNew.value, "No successful-push flag is persisted")
        }

    @Test
    fun retainedGateObservesSubsequentSeenPreferenceChanges() =
        runTest(dispatcher) {
            val settings = settings()
            val prefs = SharedPrefsHelper(settings)
            val gate = newGate(settings)
            runCurrent()
            assertTrue(gate.shouldShowWhatsNew.value)

            prefs.putString(SEEN_KEY, CURRENT_VERSION)
            runCurrent()
            assertFalse(gate.shouldShowWhatsNew.value)
            prefs.putString(SEEN_KEY, "older")
            runCurrent()
            assertTrue(gate.shouldShowWhatsNew.value)
        }

    @Test
    fun productionLegacyBindingResolvesWithoutARemoteSource() =
        runTest(dispatcher) {
            val settings = settings()
            val app =
                koinApplication {
                    modules(
                        legacySharedViewModelsModule,
                        module {
                            single { SharedPrefsHelper(settings) }
                            single { DataStoreHelper(settings) }
                            single<AppVersionProvider> { version(CURRENT_VERSION) }
                        },
                    )
                }
            try {
                val gate = retain(app.koin.get<WhatsNewViewModel>())
                runCurrent()
                assertTrue(gate.shouldShowWhatsNew.value)
                assertEquals("", settings.getString(SEEN_KEY, ""))
            } finally {
                try {
                    store.clear()
                } finally {
                    app.close()
                }
            }
        }

    private fun settings(lastSeen: String? = null) =
        MapSettings().apply {
            putBoolean(NEW_SOURCES_KEY, false)
            lastSeen?.let { putString(SEEN_KEY, it) }
        }

    private fun newGate(
        settings: MapSettings,
        currentVersion: String = CURRENT_VERSION,
    ) = retain(
        WhatsNewViewModel(DataStoreHelper(settings), SharedPrefsHelper(settings), version(currentVersion)),
    )

    private fun retain(gate: WhatsNewViewModel): WhatsNewViewModel =
        gate.also {
            store.put("gate-${nextGate++}", it)
        }

    private fun version(value: String) =
        object : AppVersionProvider {
            override val versionName = value
            override val packageName = "me.manga.kira"
        }

    private companion object {
        const val CURRENT_VERSION = "1.2.3"
        const val SEEN_KEY = "whats_new_last_shown_version_name"
        const val NEW_SOURCES_KEY = "new_sources_added"
    }
}
