package me.manga.kira.di

import me.manga.kira.data.download.selection.DownloadCatalogAdmission
import me.manga.kira.sources.runtime.RoomDownloadCatalogAdmission
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.repository.selection.StrictSourceSelectionMigration
import me.manga.kira.sources.config.SourceSelectionBootstrap
import me.manga.kira.sources.runtime.DownloadSourceCatalogSelectionMigration
import me.manga.kira.sources.runtime.RoomSourceAliasSnapshotProvider
import me.manga.kira.sources.runtime.RoomSourceCatalogProjection
import me.manga.kira.sources.runtime.RoomSourceSelectionCommit
import me.manga.kira.sources.runtime.SourceCatalogSelectionMigration
import org.koin.dsl.module
import org.koin.dsl.onClose

/** One selection coordinator owns durable commit and process adoption; preparation grants no lease. */
internal val sourceSelectionModule = module {
    single { StrictSourceSelectionMigration(get(), get()) }
    single<SourceCatalogSelectionMigration> { DownloadSourceCatalogSelectionMigration(get(), get()) }
    single { RoomSourceCatalogProjection(get(), get()) }
    single { RoomSourceSelectionCommit(get(), get(), get(), get(), get()) }
    single { RoomSourceAliasSnapshotProvider(get()) }
    single<SourceAliasSnapshotProvider> { get<RoomSourceAliasSnapshotProvider>() }
    single { SourceSelectionBootstrap(get(), get()) } onClose { it?.close() }
    single<DownloadCatalogAdmission> { RoomDownloadCatalogAdmission(get(), get(), get(), get()) }
}
