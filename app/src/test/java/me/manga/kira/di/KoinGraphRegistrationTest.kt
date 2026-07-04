package me.manga.kira.di

import org.junit.Test
import org.koin.dsl.koinApplication

/**
 * DI-graph registration smoke test for the FULL production Koin module set.
 *
 * Mirrors the composition root `initKoin(...)` (KoinInitializer.kt), which starts Koin with
 * `allSharedModules() + platformModule() + allReworkModules() + appKoinModule`. This `:app` unit
 * test is the only place that can see the entire graph (`app -> composeApp -> shared`).
 *
 * What it proves (without a device): every module block executes and every definition registers
 * into one container without error. No singleton is instantiated — all production singletons are
 * lazy (no `createdAtStart`), so the `androidContext()`/platform lambdas never run and no Android
 * Context is required. This is the cheap, deterministic guard for the Platform Cutover (Task #422)
 * rebindings. It mirrors production `initKoin`, which loads with Koin 4's default
 * `allowOverride = true`: the strangler graph intentionally binds a few use cases in more than one
 * rework module (e.g. `CancelRunningDownloadUseCase` in both DetailsRework and DownloadsRework),
 * relying on last-loaded-wins, so the test must keep that default rather than force duplicates to
 * throw.
 *
 * NOT covered (needs on-device / Robolectric launch): actual instantiation of singletons that call
 * `androidContext()`, and runtime resolution of every `get()`/`viewModel` parameter chain.
 */
class KoinGraphRegistrationTest {

    @Test
    fun full_production_module_graph_registers_without_conflicts() {
        val app = koinApplication {
            modules(allSharedModules() + platformModule() + allReworkModules() + appKoinModule)
        }
        app.close()
    }
}
