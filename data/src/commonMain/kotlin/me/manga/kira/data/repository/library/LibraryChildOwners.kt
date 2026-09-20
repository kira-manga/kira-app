package me.manga.kira.data.repository.library

import me.manga.kira.data.identity.WorkAliasComparison
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.identity.WorkLocator

/** Preflight the complete request before changing any child, using this writer's accepted policy. */
internal suspend fun LibraryOwnerSession.resolveLibraryChildren(
    chapterDao: ChapterDao,
    manga: Manga,
    chapterUrls: List<String>,
): List<SavedChapterEntity> {
    val owner = resolve(WorkLocator(manga.api, manga.url)) ?: return emptyList()
    val children = chapterDao.getChaptersByMangaIdR(owner.id)
    return chapterUrls.distinct().mapNotNull { url ->
        val request = WorkLocator(owner.api, url)
        val matches = children.filter { child ->
            when (compare(request, WorkLocator(owner.api, child.url))) {
                WorkAliasComparison.Exact, WorkAliasComparison.DeclaredAlias -> true
                WorkAliasComparison.Distinct, is WorkAliasComparison.Rejected -> false
            }
        }
        requireLibraryWrite(matches.size <= 1, LibraryWriteRejection.CHAPTER_ALIAS_REQUIRES_RECONCILIATION)
        matches.singleOrNull()
    }.distinctBy { it.id }
}
