package me.manga.kira.di

import android.app.Application
import android.content.Context
import androidx.work.WorkerParameters
import io.ktor.client.engine.HttpClientEngine
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.test.verify.verify

/**
 * DI-graph *resolution* check for the FULL production Koin module set — the deeper guard the
 * registration test (`KoinGraphRegistrationTest`) does not provide.
 *
 * Where the registration test only proves the modules merge without duplicate/override conflicts,
 * this test statically walks every definition's primary-constructor parameters via Koin's
 * `verify()` and asserts each one is satisfiable by some binding in the merged graph (or is an
 * externally-provided type listed in [EXTERNALLY_PROVIDED]). It catches a binding whose dependency
 * is missing, or whose produced type drifted from what a consumer requests — which would otherwise
 * only surface as a runtime `NoBeanDefFoundException` on a device.
 *
 * Scope/limits: `verify()` is JVM-reflection based, so it runs on the **Android** graph here
 * (`allSharedModules + platformModule(android) + allReworkModules + appKoinModule`) — the superset
 * that contains every rework binding plus the Android platform actuals. It does NOT instantiate
 * singletons (no `androidContext()` lambda runs). Interface-bound `single<T> { Impl(get()) }`
 * definitions are checked only at the bound (interface) type — their impl ctor deps aren't walked —
 * but every concrete-typed binding and every `viewModel { VM(...) }` (the highest-arity, most
 * fragile definitions) is fully verified. The iOS/Desktop platform actuals can't be reached by a
 * JVM reflection test; they remain covered by the compile gate + `KoinGraphRegistrationTest`.
 */
class KoinGraphResolutionTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun full_production_graph_dependencies_are_satisfiable() {
        val all: List<Module> =
            allSharedModules() + platformModule() + allReworkModules() + appKoinModule
        // Merge into one umbrella module so verify() flattens the included modules and resolves
        // cross-module dependencies against the full union of definitions.
        val umbrella = module { includes(all) }
        umbrella.verify(extraTypes = EXTERNALLY_PROVIDED)
    }

    private companion object {
        /**
         * Types not produced by a Koin definition but supplied externally at runtime.
         * [Context] is handed in via `androidContext()`. [Application] is whitelisted defensively
         * (no current binding takes it; kept so a future Application-typed injection doesn't trip
         * the check). See per-entry notes below.
         */
        val EXTERNALLY_PROVIDED = listOf(
            Context::class,
            Application::class,
            // The Ktor engine is constructed inside the HttpClient binding lambda
            // (platform createHttpClient()), not exposed as its own Koin definition.
            HttpClientEngine::class,
            // WorkManager injects Context + WorkerParameters into Workers at runtime (koin
            // workerOf); they are not Koin definitions.
            WorkerParameters::class,
        )
    }
}
