package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.data.repository.library.LibraryWriteException
import me.manga.kira.data.repository.library.LibraryWriteRejection
import me.manga.kira.data.repository.library.resolveLibraryChildren
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.ChapterBookmarkRepository
import me.manga.kira.sources.contracts.SourceSelectionUnavailable

/** Saved-row bookmarks resolve source, parent and child together; they never add a missing row. */
class ChapterBookmarkRepositoryImpl(
    private val owners: LibraryOwnerTransactions,
    private val chapterDao: ChapterDao,
) : ChapterBookmarkRepository {
    override fun observeBookmark(manga: Manga, chapterUrl: String): Flow<Boolean> =
        owners.invalidations()
            .map { readBookmark(manga, chapterUrl) }
            .distinctUntilChanged()

    override suspend fun toggleBookmark(manga: Manga, chapterUrl: String): Boolean =
        owners.write {
            val child = resolveLibraryChildren(chapterDao, manga, listOf(chapterUrl)).singleOrNull()
                ?: return@write false
            chapterDao.toggleChapterBookmark(child.id)
            true
        }

    override suspend fun toggleBookmark(manga: Manga, chapterUrls: List<String>) {
        if (chapterUrls.isEmpty()) return
        owners.write {
            val ids = resolveLibraryChildren(chapterDao, manga, chapterUrls).map { it.id }
            if (ids.isNotEmpty()) chapterDao.toggleChaptersBookmark(ids)
        }
    }

    // Catch each lookup after its writer unwinds, not the outer Flow: future invalidations retry.
    private suspend fun readBookmark(manga: Manga, chapterUrl: String): Boolean =
        try {
            owners.write {
                resolveLibraryChildren(chapterDao, manga, listOf(chapterUrl)).singleOrNull()?.isBookmarked == true
            }
        } catch (_: SourceSelectionUnavailable) {
            false
        } catch (failure: LibraryWriteException) {
            when (failure.rejection) {
                LibraryWriteRejection.OWNER_CONFLICT,
                LibraryWriteRejection.CHAPTER_ALIAS_REQUIRES_RECONCILIATION -> false
                else -> throw failure
            }
        }
}
