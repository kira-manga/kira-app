package me.manga.kira.data.repository.library

import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.service.FileService

/**
 * Preserve the original retryable parent until owned artifacts/files settle. Scoped progress clear
 * and that parent's deletion then share one writer, under the engine lease and artifact barrier.
 * File cleanup is irreversible; a later SQL failure keeps the parent/progress, not restored files.
 * A later parent's failure does not restore already removed parents. A retry must use the retained
 * remaining owners; stale supplied identities are never silently skipped.
 */
class LibraryRemovalWriter(
    private val owners: LibraryOwnerTransactions,
    private val storage: LibraryRemovalStorage,
    private val guard: LibraryRemovalGuard,
    private val files: FileService,
    private val artifacts: ChapterArtifacts,
) {
    internal suspend fun remove(requested: List<SavedWorkIdentity>): Int {
        val unique = requested.distinct()
        requireLibraryWrite(unique.distinctBy { it.id }.size == unique.size, LibraryWriteRejection.DUPLICATE_REQUEST)
        if (unique.isEmpty()) return 0
        return guard.withQuiescentWorks(unique) {
            // Refuse any stale parent, ambiguous progress or active queue before touching files.
            owners.write { prepareRemovals(unique) }
            unique.forEach { owner ->
                artifacts.parentRemoval(owner.id) {
                    removeFiles(owner.id)
                    owners.write { removeRows(prepareRemovals(listOf(owner)).single()) }
                }
            }
            unique.size
        }
    }

    private suspend fun LibraryOwnerSession.prepareRemovals(requested: List<SavedWorkIdentity>): List<LibraryRemovalPlan> {
        val plans = requested.map { prepareRemoval(it) }
        guard.checkInTransaction(plans.map { it.owner.savedIdentity() })
        plans.forEach { requireInactive(it.owner.id) }
        plans.forEach { requireWorkProgressOwner(storage.progress, it.owner.savedIdentity().locator) }
        return plans
    }

    private suspend fun removeFiles(mangaId: Long) {
        // Include no-FK custody left after an earlier interrupted graph removal.
        artifacts.ownersForManga(mangaId).forEach { owner ->
            check(artifacts.removeChapterUnderParent(owner)) { "Manga artifact removal could not be settled" }
        }
        // A failing final root/stray-file cleanup must not erase the original saved lookup key.
        files.deleteMangaFiles(mangaId)
    }

    private suspend fun LibraryOwnerSession.prepareRemoval(owner: SavedWorkIdentity): LibraryRemovalPlan {
        val current = retain(owner)
        return LibraryRemovalPlan(
            owner = current,
            related = relatedRows(current, storage.libraryDao),
            downloadCount = storage.libraryDao.countLibraryDownloads(current.id),
        )
    }

    private suspend fun requireInactive(mangaId: Long) {
        val active = storage.downloads.getActiveDownloadChapterIdsForManga(mangaId)
        requireLibraryWrite(active.isEmpty(), LibraryWriteRejection.ACTIVE_DOWNLOAD)
    }

    private suspend fun LibraryOwnerSession.removeRows(plan: LibraryRemovalPlan) {
        val owner = plan.owner
        clearWorkProgress(storage.progress, owner.savedIdentity().locator)
        plan.related.history.map { it.id }.chunked(DELETE_BATCH_SIZE).forEach { ids ->
            requireLibraryWrite(storage.libraryDao.deleteLibraryHistory(ids) == ids.size, LibraryWriteRejection.WRITE_COUNT)
        }
        plan.related.notifications.map { it.id }.chunked(DELETE_BATCH_SIZE).forEach { ids ->
            val changed = storage.libraryDao.deleteLibraryNotifications(ids)
            requireLibraryWrite(changed == ids.size, LibraryWriteRejection.WRITE_COUNT)
        }
        val downloads = storage.libraryDao.deleteLibraryDownloads(owner.id)
        requireLibraryWrite(downloads == plan.downloadCount, LibraryWriteRejection.WRITE_COUNT)
        val deleted = storage.libraryDao.deleteMangaForExactOwner(owner.id, owner.api, owner.url)
        requireLibraryWrite(deleted == 1, LibraryWriteRejection.WRITE_COUNT)
    }

    private companion object {
        const val DELETE_BATCH_SIZE = 500
    }
}
