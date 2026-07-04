package me.manga.kira.data.remote.di

import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.data.remote.ktor.createHttpClient
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Ktor `HttpClient` + `ApiClient` Koin bindings — relocated here (strangler-fig Phase 2) from
 * `:shared`'s `sharedModule`.
 *
 * `createHttpClient()` is commonMain (it calls the per-target `expect fun` actual), so a single common
 * module serves all platforms. Wired into the graph via `allSharedModules()` in `:shared` — the one
 * list already threaded through `initKoin` (Android/Desktop), the iOS `doInitKoin`, AND the
 * `KoinGraphResolutionTest` union — so this single registration reaches every host and the deep gate.
 */
fun remoteModule(): Module = module {
    single { createHttpClient() }
    single { ApiClient(get()) }
}
