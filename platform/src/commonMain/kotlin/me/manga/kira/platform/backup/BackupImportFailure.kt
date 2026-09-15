package me.manga.kira.platform.backup

import me.manga.kira.core.error.AppError
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

/** An actual or declared resource quantity exceeds the versioned import policy. */
class BackupImportLimitExceeded : IOException("Backup import resource limit exceeded")

/** The archive is malformed, ambiguous, unsupported, or has an invalid manifest/page payload. */
class InvalidBackupArchive(
    cause: Throwable? = null,
) : IOException("Invalid backup archive", cause)

/** Shared acquisition/import classification; cancellation is handled by callers, never swallowed. */
fun backupImportError(failure: Throwable): AppError =
    when (failure) {
        is CancellationException -> throw failure
        is BackupImportLimitExceeded -> AppError.Validation.OutOfRange("backup_size", failure)
        is InvalidBackupArchive -> AppError.Validation.Format("backup_file", failure)
        is BackupAcquisitionBusy -> AppError.Storage.Constraint("backup_import")
        is IOException -> AppError.Storage.Io(failure)
        else -> AppError.Unexpected("backup import failed", failure)
    }
