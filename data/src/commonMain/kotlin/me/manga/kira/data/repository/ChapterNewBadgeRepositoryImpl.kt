package me.manga.kira.data.repository

import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.data.repository.library.resolveLibraryChildren
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.ChapterNewBadgeRepository

/** Clear NEW for one accepted saved child; do not change read state, timestamps or membership. */
class ChapterNewBadgeRepositoryImpl(
    private val owners: LibraryOwnerTransactions,
    private val chapterDao: ChapterDao,
) : ChapterNewBadgeRepository {
    override suspend fun clearNew(manga: Manga, chapterUrl: String) {
        owners.write {
            val child = resolveLibraryChildren(chapterDao, manga, listOf(chapterUrl)).singleOrNull()
                ?: return@write
            chapterDao.markChapterIsNew(child.id)
        }
    }
}
