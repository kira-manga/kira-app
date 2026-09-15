package me.manga.kira.data.backup

import me.manga.kira.platform.backup.BackupByteBudget
import me.manga.kira.platform.backup.BackupImportLimitExceeded
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupZipLimits
import me.manga.kira.platform.backup.BoundedBackupZip
import me.manga.kira.platform.backup.InvalidBackupArchive
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageInspectionRejection
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.isPageImageName
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.blackholeSink
import okio.use
import kotlin.coroutines.cancellation.CancellationException

/** Native page inspection is reused; this adds the missing archive/aggregate/CRC admission around it. */
internal class BackupCbzAdmission(
    private val system: FileSystem,
    private val inspector: PageMediaInspector,
    private val policy: BackupImportPolicy,
) {
    private val allExpanded = BackupByteBudget(policy.downloads.maxExpandedBytes)
    private val pagePolicy = PageBytePolicy()

    fun validate(path: Path, checkpoint: () -> Unit) {
        val limits =
            BackupZipLimits(
                maxArchiveBytes = policy.downloads.maxCbzBytes,
                maxEntries = policy.downloads.maxInnerEntries,
                maxDirectoryBytes = policy.archive.maxDirectoryBytes,
            )
        val expanded = BackupByteBudget(policy.downloads.maxExpandedBytesPerCbz)
        BoundedBackupZip.open(system, path, limits, checkpoint).use { zip ->
            var pages = 0
            for (entry in zip.entries) {
                checkpoint()
                val budgets = listOf(expanded, allExpanded)
                if (!entry.isDirectory && isPageImageName(entry.name)) {
                    val bytes = zip.readEntry(entry, budgets + BackupByteBudget(pagePolicy.maxEncodedBytes), checkpoint)
                    requireReadable(inspector.inspect(bytes))
                    pages++
                } else {
                    // Metadata/extra files cannot hide a decompression bomb or a bad CRC either.
                    zip.copyEntry(entry, blackholeSink(), budgets, checkpoint)
                }
            }
            if (pages == 0) throw InvalidBackupArchive()
        }
        checkpoint()
    }
}

private fun requireReadable(inspection: PageInspection) {
    when (inspection) {
        is PageInspection.Valid -> Unit
        is PageInspection.Invalid -> throw InvalidBackupArchive()
        is PageInspection.ReadFailure -> {
            if (inspection.cause is CancellationException) throw inspection.cause
            throw IOException("Backup page validation could not read its snapshot", inspection.cause)
        }
        is PageInspection.Rejected ->
            when (inspection.reason) {
                PageInspectionRejection.ENCODED_BYTES,
                PageInspectionRejection.SOURCE_PIXELS,
                PageInspectionRejection.SOURCE_AXIS,
                -> throw BackupImportLimitExceeded()
                else -> throw InvalidBackupArchive()
            }
    }
}
