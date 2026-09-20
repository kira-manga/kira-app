package me.manga.kira.data.repository.library

import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.ensureMangaWriteActive
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.metadataUpdate
import me.manga.kira.domain.model.MangaDetails

/** Narrow metadata operations; callers preflight ownership and keep every write in one transaction. */
class LibraryMetadataWriter(
    private val mangaDao: MangaDao,
    private val libraryDao: LibraryDeo,
) {
    internal suspend fun details(
        owner: SavedMangaEntity,
        fetched: MangaDetails,
        related: LibraryRelatedRows,
    ): SavedMangaEntity {
        val updated = mangaDao.updateMetadataForExactOwner(owner.api, owner.url, fetched.metadataUpdate(owner))
        ensureMangaWriteActive()
        requireLibraryWrite(updated == 1, LibraryWriteRejection.WRITE_COUNT)
        if (fetched.coverUrl.isNotBlank()) updateRelatedCovers(related, fetched.coverUrl)
        return checkNotNull(mangaDao.getMangaByExactOwner(owner.id, owner.api, owner.url))
    }

    internal suspend fun cover(owner: SavedMangaEntity, coverUrl: String, related: LibraryRelatedRows) {
        if (coverUrl.isBlank()) return
        val updated = mangaDao.updateCoverForExactOwner(owner.id, owner.api, owner.url, coverUrl)
        ensureMangaWriteActive()
        requireLibraryWrite(updated == 1, LibraryWriteRejection.WRITE_COUNT)
        updateRelatedCovers(related, coverUrl)
    }

    private suspend fun updateRelatedCovers(related: LibraryRelatedRows, coverUrl: String) {
        related.history.forEach {
            val changed = libraryDao.updateLibraryHistoryCover(it.id, coverUrl)
            ensureMangaWriteActive()
            requireLibraryWrite(changed == 1, LibraryWriteRejection.WRITE_COUNT)
        }
        related.notifications.forEach {
            val changed = libraryDao.updateLibraryNotificationCover(it.id, coverUrl)
            ensureMangaWriteActive()
            requireLibraryWrite(changed == 1, LibraryWriteRejection.WRITE_COUNT)
        }
    }
}
