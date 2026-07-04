package me.manga.kira.data.legacy.di

import me.manga.kira.presentation.features.settings.domain.SettingsRepository
import me.manga.kira.presentation.features.statistics.domain.StatisticsRepository
import me.manga.kira.presentation.features.whatsnew.data.WhatsNewRemoteDataSource
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Legacy feature repositories / data sources relocated from `:shared`'s `sharedModule`
 * (strangler-fig Phase 5): the settings + statistics repositories and the What's-New remote data
 * source. Their rework counterparts (`:data` repo impls, `:presentation` VMs) strangle or consume
 * them.
 *
 * Bound here — a common `:data` module — rather than `:shared`, which is what lets the
 * `:data -> :shared` edge drop. Appended to `allReworkModules()`. Deps resolve cross-module by
 * type: `StatisticsDeo` (`:data:local`), `SharedPrefsHelper`/`DataStoreHelper`/`AppFileSystem`
 * (`:platform`), the Ktor `HttpClient` (host-bound).
 */
val legacyDataModule: Module = module {
    single { StatisticsRepository(get(), get()) }
    single { SettingsRepository(get(), get(), get()) }
    single { WhatsNewRemoteDataSource(get()) }
}
