package me.manga.kira.di

import me.manga.kira.data.repository.LibraryMetadataRepositoryImpl
import me.manga.kira.data.repository.library.DownloadLibraryRemovalGuard
import me.manga.kira.data.repository.library.LibraryChapterWriter
import me.manga.kira.data.repository.library.LibraryMetadataWriter
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.data.repository.library.LibraryRemovalGuard
import me.manga.kira.data.repository.library.LibraryRemovalStorage
import me.manga.kira.data.repository.library.LibraryRemovalWriter
import me.manga.kira.data.repository.library.LibraryWriteDependencies
import me.manga.kira.data.repository.progress.ProgressOwnerTransactions
import me.manga.kira.data.repository.progress.ProgressStorage
import me.manga.kira.data.repository.progress.LegacyProgressSettings
import me.manga.kira.data.repository.progress.LegacyProgressSettingsGate
import me.manga.kira.data.repository.progress.LegacyProgressWriterOwnership
import me.manga.kira.data.repository.progress.RoomLegacyReadProgressRepository
import me.manga.kira.data.repository.progress.RoomScopedReadProgressRepository
import me.manga.kira.domain.repository.LibraryMetadataRepository
import me.manga.kira.domain.repository.LegacyReadProgressRepository
import me.manga.kira.domain.repository.ScopedReadProgressRepository
import org.koin.dsl.module

/** Same database writer, accepted-policy provider and download gate for library/progress mutations. */
internal val libraryOwnershipModule = module {
    single { ProgressStorage(get(), get(), get(), get(), get()) }
    single { ProgressOwnerTransactions(get(), get(), get()) }
    single<ScopedReadProgressRepository> { RoomScopedReadProgressRepository(get()) }
    single { LegacyProgressSettingsGate() }
    single<LegacyProgressWriterOwnership> { MobileLegacyProgressWriterOwnership() }
    single { LegacyProgressSettings(get(), get(), get()) }
    single<LegacyReadProgressRepository> { RoomLegacyReadProgressRepository(get(), get()) }
    single { LibraryOwnerTransactions(get(), get(), get()) }
    single { LibraryMetadataWriter(get(), get()) }
    single { LibraryChapterWriter(get()) }
    single { LibraryRemovalStorage(get(), get(), get()) }
    single<LibraryRemovalGuard> { DownloadLibraryRemovalGuard(get(), get(), get(), get()) }
    single { LibraryRemovalWriter(get(), get(), get(), get(), get()) }
    single { LibraryWriteDependencies(get(), get(), get(), get(), get()) }
    single<LibraryMetadataRepository> { LibraryMetadataRepositoryImpl(get(), get(), get(), get()) }
}
