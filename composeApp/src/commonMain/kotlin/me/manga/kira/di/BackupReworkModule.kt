package me.manga.kira.di

import me.manga.kira.core.platform.backupPlatformName
import me.manga.kira.data.backup.BackupArchivePreflight
import me.manga.kira.data.backup.BackupDownloadExporter
import me.manga.kira.data.backup.BackupExportProvenance
import me.manga.kira.data.backup.BackupExporter
import me.manga.kira.data.backup.BackupImportArtifactRepositoryImpl
import me.manga.kira.data.backup.BackupImporter
import me.manga.kira.data.backup.FixedBackupExportProvenance
import me.manga.kira.data.backup.RestoredDownloadPublisher
import me.manga.kira.data.backup.BackupMergeWriter
import me.manga.kira.data.repository.BackupRepositoryImpl
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.repository.BackupImportArtifactRepository
import me.manga.kira.domain.repository.BackupRepository
import me.manga.kira.domain.usecase.backup.ClearBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.DiscardBackupArtifactUseCase
import me.manga.kira.domain.usecase.backup.DiscardImportArtifactUseCase
import me.manga.kira.domain.usecase.backup.ExportBackupUseCase
import me.manga.kira.domain.usecase.backup.ImportBackupUseCase
import me.manga.kira.domain.usecase.backup.ObserveBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.StopBackupUseCase
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.version.AppVersionProvider
import me.manga.kira.presentation.backup.BackupFileOperations
import me.manga.kira.presentation.backup.BackupProgressOperations
import me.manga.kira.presentation.backup.BackupViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Backup & restore slice (feature/backup). Repository is a `single` — its hot progress flow and
 * one-run-at-a-time gate are app-lifetime state that must survive screen recreation. The VM takes
 * the route-decoded [BackupScope] as a Koin parameter (full library vs the Details/Library
 * selection). Provenance strings come from the `:platform` [AppVersionProvider] binding plus the
 * per-target [backupPlatformName] actual.
 */
val backupReworkModule: Module =
    module {
        single { BackupImportPolicy() }
        single { BackupImportStaging(get(), get()) }
        single { BackupArchivePreflight(get(), get(), get(), get()) }
        single<BackupImportArtifactRepository> { BackupImportArtifactRepositoryImpl(get(), get()) }
        single { RestoredDownloadPublisher(get(), get(), get(), get(), get()) }
        single { BackupMergeWriter(get(), get()) }
        single {
            BackupImporter(backupDao = get(), mergeWriter = get(), preflight = get(), publisher = get())
        }
        single { BackupDownloadExporter(get(), get(), get(), get(), get()) }
        single<BackupExportProvenance> { FixedBackupExportProvenance(get<AppVersionProvider>().versionName, backupPlatformName()) }
        single {
            BackupExporter(mergeWriter = get(), files = get(), downloads = get(), provenance = get())
        }
        single<BackupRepository> { BackupRepositoryImpl(get(), get(), get(), get()) }
        factory { ExportBackupUseCase(get()) }
        factory { ImportBackupUseCase(get()) }
        factory { ObserveBackupProgressUseCase(get()) }
        factory { StopBackupUseCase(get()) }
        factory { ClearBackupProgressUseCase(get()) }
        factory { DiscardBackupArtifactUseCase(get()) }
        factory { DiscardImportArtifactUseCase(get()) }
        factory { BackupFileOperations(get(), get(), get(), get()) }
        factory { BackupProgressOperations(get(), get(), get()) }
        viewModel { (scope: BackupScope) ->
            BackupViewModel(
                scope = scope,
                files = get(),
                progressActions = get(),
                observeCbzConversion = get(),
            )
        }
    }
