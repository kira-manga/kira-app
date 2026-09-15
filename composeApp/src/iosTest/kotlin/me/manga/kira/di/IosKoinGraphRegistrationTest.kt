package me.manga.kira.di

import me.manga.kira.platform.version.AppVersionProvider
import me.manga.kira.platform.version.IosAppVersionProvider
import org.koin.dsl.koinApplication
import platform.Foundation.NSBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #27 (B13) — iOS DI-graph registration smoke test (runs on iosSimulatorArm64Test / iosArm64Test).
 * Builds the FULL production graph the iOS composition root assembles —
 * `allSharedModules() + platformModule() + allReworkModules()` (the iOS actual of platformModule
 * resolves here) — and closes it, proving the iOS graph registers with no duplicate/override
 * conflict. Koin 4 silently overrides duplicates by default, so `allowOverride(false)` is required
 * for duplicates to throw. This is the run that would surface a latent iOS binding conflict.
 *
 * No verify() here — koin-test's reflective resolution is JVM-only, and the only resolution-level
 * coverage is :app's KoinGraphResolutionTest (which verifies the Android platformModule actuals).
 * The Desktop test (DesktopKoinGraphRegistrationTest) is registration-only too, so a Desktop-only
 * missing binding would surface only at runtime — a known gap. The registration check instantiates
 * no singletons. The targeted version check resolves only app metadata, not UI/services.
 * No `appKoinModule` (it lives in :app).
 */
class IosKoinGraphRegistrationTest {

    @Test
    fun ios_production_module_graph_registers_without_conflicts() {
        val app = koinApplication {
            allowOverride(false)
            modules(allSharedModules() + platformModule() + allReworkModules())
        }
        app.close()
    }

    @Test
    fun ios_version_binding_reads_the_main_bundle_or_unknown() {
        val app = koinApplication {
            modules(platformModule())
        }
        try {
            val version = app.koin.get<AppVersionProvider>()
            val bundleVersion = NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String
            assertIs<IosAppVersionProvider>(version)
            assertEquals(bundleVersion ?: "unknown", version.versionName)
            assertTrue(version.versionName.isNotBlank())
        } finally {
            app.close()
        }
    }
}
