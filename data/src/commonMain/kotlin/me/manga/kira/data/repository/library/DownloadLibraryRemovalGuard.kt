package me.manga.kira.data.repository.library

import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository

/**
 * Requests cancellation only for revalidated owners, then requires actual graph quiescence.
 * A cancellation response is not native termination: outstanding producers/session callbacks
 * refuse this attempt before any removal block runs. The user may retry after they settle.
 * This deliberately does not queue an exclusive waiter that could block cancellation itself.
 */
class DownloadLibraryRemovalGuard(
    private val transactions: LibraryOwnerTransactions,
    private val downloads: ChapterDownloadDao,
    private val engine: DownloadRepository,
    private val operations: DownloadOperationExclusion,
) : LibraryRemovalGuard {
    override suspend fun <T> withQuiescentWorks(
        owners: List<SavedWorkIdentity>,
        block: suspend () -> T,
    ): T {
        val requested = owners.toList()
        requireLibraryWrite(requested.distinctBy { it.id }.size == requested.size, LibraryWriteRejection.DUPLICATE_REQUEST)
        operations.withOperation {
            val chapters = transactions.write {
                // Validate the complete retained selection before sending even the first cancel.
                requested.forEach { retain(it) }
                requested.flatMap { downloads.getActiveDownloadChapterIdsForManga(it.id) }.distinct()
            }
            chapters.forEach { engine.onCancel(it) }
        }
        try {
            return operations.withExclusive {
                // The removal writer performs its complete read-only ownership/progress/queue
                // preflight before files, and repeats it before each final per-parent commit.
                block()
            }
        } catch (_: DownloadOperationBusy) {
            throw LibraryWriteException(LibraryWriteRejection.ACTIVE_DOWNLOAD)
        }
    }

    override suspend fun checkInTransaction(owners: List<SavedWorkIdentity>) {
        operations.requireExclusive()
        owners.forEach {
            requireLibraryWrite(
                downloads.getActiveDownloadChapterIdsForManga(it.id).isEmpty(),
                LibraryWriteRejection.ACTIVE_DOWNLOAD,
            )
        }
    }
}
