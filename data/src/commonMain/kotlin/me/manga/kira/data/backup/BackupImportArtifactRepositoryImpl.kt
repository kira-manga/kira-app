package me.manga.kira.data.backup

import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.appFailure
import me.manga.kira.core.result.appSuccess
import me.manga.kira.domain.repository.BackupImportArtifactRepository
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.backup.backupImportError
import kotlin.coroutines.cancellation.CancellationException

/** Safe registry-backed cleanup; never infers ownership from a caller-supplied path prefix. */
class BackupImportArtifactRepositoryImpl(
    private val staging: BackupImportStaging,
    private val dispatchers: DispatcherProvider,
) : BackupImportArtifactRepository {
    override suspend fun discardImportArtifact(archivePath: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                staging.discardPending(archivePath)
                appSuccess(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                appFailure(backupImportError(failure))
            }
        }
}
