package me.manga.kira.platform.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.Closeable
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.use

/**
 * App-lifetime picker-to-import custody. A path is adopted only from this exact in-memory registry,
 * never by a string-prefix guess. A picker may discard its unclaimed result on disposal; after
 * claim only the importer may close it. Arbitrary caller paths are copied, never deleted.
 */
class BackupImportStaging(
    private val files: AppFileSystem,
    private val policy: BackupImportPolicy = BackupImportPolicy(),
) {
    private val pending = MutableStateFlow<Map<String, OwnedBackupInput>>(emptyMap())
    private val acquisition = MutableStateFlow<Acquisition?>(null)

    /**
     * Takes a complete bounded provider snapshot, then registers it for a one-shot handoff. The
     * provider source is opened lazily, owned and closed here, including all failure/cancel paths.
     */
    fun stage(
        declaredLength: Long?,
        checkpoint: () -> Unit,
        source: () -> Source,
    ): String {
        val owner = acquire()
        var input: OwnedBackupInput? = null
        try {
            val staged = snapshot(declaredLength, checkpoint, source) { release(owner) }
            input = staged
            pending.update { it + (staged.path.toString() to staged) }
            return staged.path.toString()
        } catch (failure: Throwable) {
            if (input != null) closeBackupAfterFailure(input, failure)
            release(owner)
            throw failure
        }
    }

    /** A failed/busy picker callback or disposed picker releases only its still-pending capability. */
    fun discardPending(path: String) {
        takePending(path)?.close()
    }

    /** Uses a picked immutable snapshot or takes a new bounded snapshot of an external local path. */
    fun claimOrCopy(
        path: String,
        checkpoint: () -> Unit,
    ): OwnedBackupInput {
        takePending(path)?.let { return it }
        val owner = acquire()
        try {
            val original = path.toPath()
            val metadata = files.fileSystem().metadata(original)
            if (!metadata.isRegularFile) throw InvalidBackupArchive()
            return snapshot(metadata.size, checkpoint, { files.fileSystem().source(original) }) { release(owner) }
        } catch (failure: Throwable) {
            release(owner)
            throw failure
        }
    }

    private fun acquire(): Acquisition {
        val owner = Acquisition()
        if (!acquisition.compareAndSet(expect = null, update = owner)) throw BackupAcquisitionBusy()
        return owner
    }

    private fun release(owner: Acquisition) {
        // A failed snapshot may have released already. Never clear a successor's admission slot.
        acquisition.compareAndSet(expect = owner, update = null)
    }

    private class Acquisition

    private fun takePending(path: String): OwnedBackupInput? {
        while (true) {
            val current = pending.value
            val input = current[path] ?: return null
            if (pending.compareAndSet(current, current - path)) return input
        }
    }

    private fun snapshot(
        declaredLength: Long?,
        checkpoint: () -> Unit,
        source: () -> Source,
        release: () -> Unit,
    ): OwnedBackupInput {
        checkpoint()
        val budget = BackupByteBudget(policy.archive.maxArchiveBytes)
        budget.checkDeclared(declaredLength)
        val directory = OwnedBackupDirectory.create(files.fileSystem(), files.cacheDir / IMPORT_DIRECTORY)
        val input = OwnedBackupInput(directory, directory.path / ARCHIVE_NAME, release)
        try {
            source().use { incoming ->
                files.fileSystem().sink(input.path, mustCreate = true).use { sink ->
                    copyBackupBytes(incoming, sink, listOf(budget), checkpoint)
                }
            }
            checkpoint()
            return input
        } catch (failure: Throwable) {
            closeBackupAfterFailure(input, failure)
            throw failure
        }
    }

    private companion object {
        const val IMPORT_DIRECTORY = "backup_import"
        const val ARCHIVE_NAME = "archive.kira.zip"
    }
}

/** Owned immutable archive snapshot. Closing releases its own directory, not the provider's file. */
class OwnedBackupInput internal constructor(
    private val directory: OwnedBackupDirectory,
    val path: Path,
    private val release: () -> Unit,
) : Closeable {
    private val released = MutableStateFlow(false)

    override fun close() {
        try {
            directory.close()
        } finally {
            if (released.compareAndSet(expect = false, update = true)) release()
        }
    }
}

internal fun closeBackupAfterFailure(resource: Closeable, failure: Throwable) {
    try {
        resource.close()
    } catch (cleanup: Throwable) {
        if (cleanup !== failure) failure.addSuppressed(cleanup)
    }
}

/** Concurrent provider staging is rejected, not queued into multiple multi-gigabyte cache copies. */
class BackupAcquisitionBusy : IllegalStateException("Backup acquisition already in progress")
