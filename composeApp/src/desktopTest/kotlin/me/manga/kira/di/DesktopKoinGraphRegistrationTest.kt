package me.manga.kira.di

import org.koin.dsl.koinApplication
import kotlin.test.Test

/**
 * #27 (B13) — Desktop DI-graph registration smoke test. The Android graph is covered by the :app
 * KoinGraphRegistrationTest, but the iOS & Desktop `platformModule()` actuals were never
 * registration-tested. This builds the FULL production graph the Desktop composition root assembles
 * — `allSharedModules() + platformModule() + allReworkModules()` (the Desktop actual of
 * platformModule resolves here, no androidContext) — and closes it, proving every module block
 * executes and every definition registers with no duplicate/override conflict. Koin 4 silently
 * overrides duplicates by default, so `allowOverride(false)` is required for duplicates to throw.
 * Singletons are lazy, so nothing is instantiated and no platform context is needed.
 *
 * (No `appKoinModule` — that lives in :app, not :composeApp.)
 */
class DesktopKoinGraphRegistrationTest {

    @Test
    fun desktop_production_module_graph_registers_without_conflicts() {
        val app = koinApplication {
            allowOverride(false)
            modules(allSharedModules() + platformModule() + allReworkModules())
        }
        app.close()
    }
}
