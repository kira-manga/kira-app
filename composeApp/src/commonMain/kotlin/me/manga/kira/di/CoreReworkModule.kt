package me.manga.kira.di

import me.manga.kira.core.dispatchers.DefaultDispatcherProvider
import me.manga.kira.core.dispatchers.DispatcherProvider
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings for cross-slice rework infrastructure (no feature ownership).
 *
 * Hosts the app-wide [DispatcherProvider] `single` consumed by every rework slice
 * (Library / Home / Sources / Settings / the `:data` repositories). Previously this
 * binding lived inside [libraryReworkModule], which hid an inter-module ownership
 * coupling — dropping the Library slice would have broken every other slice's first
 * resolve. Listed first in [allReworkModules].
 *
 * Lifecycle: [DispatcherProvider] → `single` — pure delegation, stateless, safe to share.
 */
val coreReworkModule: Module = module {
    single<DispatcherProvider> { DefaultDispatcherProvider() }
}
