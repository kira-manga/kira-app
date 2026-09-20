package me.manga.kira.data.repository

import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.data.repository.library.resolveLibraryChildren
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.MarkChapterReadRepository

/** Resolve complete ownership and apply read/NEW changes in one writer, without adding saved rows. */
class MarkChapterReadRepositoryImpl(
    private val owners: LibraryOwnerTransactions,
    private val chapterDao: ChapterDao,
) : MarkChapterReadRepository {
    override suspend fun markRead(manga: Manga, chapterUrl: String) {
        owners.write {
            val child = resolveLibraryChildren(chapterDao, manga, listOf(chapterUrl)).singleOrNull()
                ?: return@write
            // Only the single open/read path stamps lastReadDate and clears NEW, as before.
            chapterDao.markChapterAsRead(child.id)
            chapterDao.markChapterIsNew(child.id)
        }
    }

    override suspend fun toggleRead(manga: Manga, chapterUrl: String) {
        owners.write {
            val child = resolveLibraryChildren(chapterDao, manga, listOf(chapterUrl)).singleOrNull()
                ?: return@write
            chapterDao.toggleChaptersReadBatch(listOf(child.id))
        }
    }

    override suspend fun markRead(manga: Manga, chapterUrls: List<String>) {
        if (chapterUrls.isEmpty()) return
        owners.write {
            val ids = resolveLibraryChildren(chapterDao, manga, chapterUrls).map { it.id }
            if (ids.isNotEmpty()) chapterDao.markChaptersRead(ids)
        }
    }
}
