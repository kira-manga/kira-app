package me.manga.kira.data.backup

import me.manga.kira.platform.backup.BackupByteBudget
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.backup.BoundedBackupZip
import me.manga.kira.platform.backup.OwnedBackupDirectory
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.PageMediaInspector
import okio.Closeable
import okio.Path
import okio.blackholeSink
import okio.use

/**
 * Side-effect-free with respect to live library/download state. All input bytes, metadata, JSON and
 * referenced CBZ snapshots must be admitted before a caller can obtain a [ValidatedBackupPlan].
 */
class BackupArchivePreflight(
    private val files: AppFileSystem,
    private val staging: BackupImportStaging,
    private val inspector: PageMediaInspector,
    private val policy: BackupImportPolicy = BackupImportPolicy(),
) {
    /** Releases a picker result that was rejected before import admission (busy/disposed caller). */
    fun discardPending(path: String) = staging.discardPending(path)

    internal fun prepare(path: String, checkpoint: () -> Unit): ValidatedBackupPlan {
        var downloads: OwnedBackupDirectory? = null
        try {
            return staging.claimOrCopy(path, checkpoint).use { input ->
                BoundedBackupZip.open(files.fileSystem(), input.path, policy.archive, checkpoint).use { zip ->
                    val bytes = zip.readEntry(zip.entry(BACKUP_JSON_ENTRY), listOf(BackupByteBudget(policy.json.maxBytes)), checkpoint)
                    val text = admitBackupJson(bytes, policy, checkpoint)
                    val manifest = admitBackupManifest(text, zip.entries, policy, checkpoint)
                    verifyDirectories(zip, checkpoint)
                    val directory = OwnedBackupDirectory.create(files.fileSystem(), files.cacheDir / PREFLIGHT_DIRECTORY)
                    downloads = directory
                    val snapshots = stageDownloads(zip, manifest.references, directory.path, checkpoint)
                    checkpoint()
                    ValidatedBackupPlan(manifest, snapshots, directory)
                }
            }
        } catch (failure: Throwable) {
            try {
                downloads?.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun verifyDirectories(zip: BoundedBackupZip, checkpoint: () -> Unit) {
        for (entry in zip.entries) {
            if (entry.isDirectory) {
                zip.copyEntry(entry, blackholeSink(), listOf(BackupByteBudget(0)), checkpoint)
            }
        }
    }

    private fun stageDownloads(
        zip: BoundedBackupZip,
        references: Set<String>,
        directory: Path,
        checkpoint: () -> Unit,
    ): Map<String, ValidatedBackupDownload> {
        val staged = BackupByteBudget(policy.downloads.maxStagedBytes)
        val media = BackupCbzAdmission(files.fileSystem(), inspector, policy)
        val snapshots = linkedMapOf<String, ValidatedBackupDownload>()
        for (reference in references) {
            checkpoint()
            val path = directory / "${snapshots.size}.cbz"
            val budgets = listOf(BackupByteBudget(policy.downloads.maxCbzBytes), staged)
            val size = files.fileSystem().sink(path, mustCreate = true).use { sink ->
                zip.copyEntry(zip.entry(reference), sink, budgets, checkpoint)
            }
            media.validate(path, checkpoint)
            snapshots[reference] = ValidatedBackupDownload(path, size)
        }
        return snapshots.toMap()
    }

    private companion object {
        const val PREFLIGHT_DIRECTORY = "backup_preflight"
    }
}

/** Private staging paths are never provider paths or archive-controlled names. */
internal data class ValidatedBackupDownload(val path: Path, val size: Long)

/** Created by preflight; commit must consume these very same owned bytes and close on every exit. */
internal class ValidatedBackupPlan(
    val manifest: AdmittedBackupManifest,
    val downloads: Map<String, ValidatedBackupDownload>,
    private val directory: OwnedBackupDirectory,
) : Closeable {
    override fun close() = directory.close()
}
