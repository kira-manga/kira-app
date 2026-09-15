package me.manga.kira.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.appFailure
import me.manga.kira.core.result.appSuccess
import me.manga.kira.data.backup.BackupExporter
import me.manga.kira.data.backup.BackupFormatTooNew
import me.manga.kira.data.backup.BackupImporter
import me.manga.kira.data.backup.BackupRun
import me.manga.kira.data.backup.BackupStopped
import me.manga.kira.domain.model.backup.BackupExportResult
import me.manga.kira.domain.model.backup.BackupImportResult
import me.manga.kira.domain.model.backup.BackupPhase
import me.manga.kira.domain.model.backup.BackupProgress
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.repository.BackupRepository
import me.manga.kira.platform.backup.ZipLimitExceededException
import me.manga.kira.platform.backup.backupImportError
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * App-lifetime backup orchestration: one run, hot progress and typed failures. Import delegates to
 * full bounded preflight before any live mutation; exports pin the same artifact ownership service.
 * A completed manga's metadata merge survives a later stop/failure; the archive is not one global
 * database/filesystem transaction.
 */
class BackupRepositoryImpl(
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    private val dispatchers: DispatcherProvider,
) : BackupRepository {
    private val progress = MutableStateFlow(BackupProgress())
    private val shouldStop = MutableStateFlow(false)
    private val runGate = MutableStateFlow(false)

    override fun observeProgress(): Flow<BackupProgress> = progress.asStateFlow()

    override suspend fun exportBackup(
        scope: BackupScope,
        includeDownloads: Boolean,
    ): AppResult<BackupExportResult> = runExclusive(BackupPhase.EXPORTING) { run ->
        exporter.run(scope, includeDownloads, run).also { result ->
            progress.update { it.copy(isRunning = false, exportResult = result) }
        }
    }

    override suspend fun importBackup(archivePath: String): AppResult<BackupImportResult> =
        runExclusive(BackupPhase.IMPORTING) { run ->
            importer.run(archivePath, run).also { result ->
                progress.update { it.copy(isRunning = false, importResult = result) }
            }
        }

    override suspend fun discardExportArtifact(archivePath: String) {
        withContext(dispatchers.io) { exporter.discard(archivePath) }
    }

    override fun stop() {
        shouldStop.value = true
    }

    override fun clearProgress() {
        progress.update { if (it.isRunning) it else BackupProgress() }
    }

    private suspend fun <T> runExclusive(
        phase: BackupPhase,
        block: suspend (BackupRun) -> T,
    ): AppResult<T> {
        if (!runGate.compareAndSet(expect = false, update = true)) {
            return appFailure(AppError.Storage.Constraint("backup_operation"))
        }
        shouldStop.value = false
        progress.value = BackupProgress(phase = phase, isRunning = true)
        return try {
            withContext(dispatchers.io) {
                appSuccess(block(BackupRun(progress, { shouldStop.value }, currentCoroutineContext())))
            }
        } catch (stopped: BackupStopped) {
            progress.update { it.copy(isRunning = false, wasStopped = true) }
            appFailure(AppError.Cancelled(stopped))
        } catch (cancelled: CancellationException) {
            progress.value = BackupProgress()
            throw cancelled
        } catch (failure: Throwable) {
            progress.update { it.copy(isRunning = false, failed = true) }
            appFailure(mapFailure(failure, phase))
        } finally {
            runGate.value = false
        }
    }

    private fun mapFailure(failure: Throwable, phase: BackupPhase): AppError =
        when {
            failure is BackupFormatTooNew -> AppError.Validation.OutOfRange("formatVersion", failure)
            failure is ZipLimitExceededException -> AppError.Validation.OutOfRange("backup_size", failure)
            phase == BackupPhase.IMPORTING -> backupImportError(failure)
            failure is IOException -> AppError.Storage.Io(failure)
            else -> AppError.Unexpected("backup operation failed", failure)
        }
}
