package me.manga.kira.di

import me.manga.kira.core.platform.backupPlatformName
import me.manga.kira.data.repository.BackupRepositoryImpl
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.repository.BackupRepository
import me.manga.kira.domain.usecase.backup.ClearBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.DiscardBackupArtifactUseCase
import me.manga.kira.domain.usecase.backup.ExportBackupUseCase
import me.manga.kira.domain.usecase.backup.ImportBackupUseCase
import me.manga.kira.domain.usecase.backup.ObserveBackupProgressUseCase
import me.manga.kira.domain.usecase.backup.StopBackupUseCase
import me.manga.kira.platform.version.AppVersionProvider
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
val backupReworkModule: Module = module {
    single<BackupRepository> {
        BackupRepositoryImpl(
            backupDao = get(),
            readProgress = get(),
            appFileSystem = get(),
            dispatchers = get(),
            appVersion = get<AppVersionProvider>().versionName,
            platformName = backupPlatformName(),
        )
    }
    factory { ExportBackupUseCase(get()) }
    factory { ImportBackupUseCase(get()) }
    factory { ObserveBackupProgressUseCase(get()) }
    factory { StopBackupUseCase(get()) }
    factory { ClearBackupProgressUseCase(get()) }
    factory { DiscardBackupArtifactUseCase(get()) }
    viewModel { (scope: BackupScope) ->
        BackupViewModel(
            scope = scope,
            exportBackup = get(),
            importBackup = get(),
            observeBackupProgress = get(),
            stopBackup = get(),
            clearBackupProgress = get(),
            discardBackupArtifact = get(),
            observeCbzConversion = get(),
        )
    }
}
