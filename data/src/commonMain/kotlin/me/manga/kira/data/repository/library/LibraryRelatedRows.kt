package me.manga.kira.data.repository.library

import me.manga.kira.data.identity.WorkAliasComparison
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.domain.model.identity.WorkLocator

internal data class LibraryRelatedRows(
    val history: List<HistoryItemD>,
    val notifications: List<ChapterNotification>,
)

internal suspend fun LibraryOwnerSession.relatedRows(
    owner: SavedMangaEntity,
    libraryDao: LibraryDeo,
): LibraryRelatedRows {
    val history = libraryDao.getLibraryHistoryCandidates(owner.id, owner.api).filter {
        acceptsRelated(owner, it.mangaId, WorkLocator(it.api, it.mangaUrl))
    }
    val notifications = libraryDao.getLibraryNotificationCandidates(owner.id, owner.api).filter {
        acceptsRelated(owner, it.mangaId, WorkLocator(it.api, it.mangaUrl))
    }
    return LibraryRelatedRows(history, notifications)
}

private suspend fun LibraryOwnerSession.acceptsRelated(
    owner: SavedMangaEntity,
    linkedId: Long,
    work: WorkLocator,
): Boolean {
    val compared = compare(owner.savedIdentity().locator, work)
    val matches = compared == WorkAliasComparison.Exact || compared == WorkAliasComparison.DeclaredAlias
    requireLibraryWrite(linkedId != owner.id || matches, LibraryWriteRejection.RELATED_OWNER_CHANGED)
    requireLibraryWrite(!matches || linkedId == 0L || linkedId == owner.id, LibraryWriteRejection.RELATED_OWNER_CHANGED)
    if (matches) requireOwner(owner, resolve(work))
    return matches
}
