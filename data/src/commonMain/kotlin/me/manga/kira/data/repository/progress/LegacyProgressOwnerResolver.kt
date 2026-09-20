package me.manga.kira.data.repository.progress

import me.manga.kira.data.identity.WorkAliasPolicy
import me.manga.kira.data.identity.WorkOwnerResolution
import me.manga.kira.data.identity.WorkOwnerResolver
import me.manga.kira.data.local.dao.BackupDao
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.domain.model.identity.SavedProgressOwner
import me.manga.kira.domain.model.identity.WorkLocator

/** Global saved candidates, not the next Reader caller or a URL-only LIMIT 1 chapter query. */
internal class LegacyProgressOwnerResolver(
    private val catalog: BackupDao,
    private val policy: WorkAliasPolicy,
) {
    suspend fun prove(position: LegacyProgressPosition): SavedProgressOwner? {
        val works = catalog.getAllSavedManga()
        val matches = candidates(works, position.chapterUrl)
        val owner = matches.singleOrNull() ?: return null
        val resolved = WorkOwnerResolver(policy).resolve(
            owner.work.locator,
            works.singleOrNull { it.url == owner.work.locator.url }?.savedIdentity(),
            works.filter { it.api == owner.work.locator.api }.map { it.savedIdentity() },
        )
        return owner.takeIf { resolved is WorkOwnerResolution.Found && resolved.owner == owner.work }
    }

    private suspend fun candidates(works: List<SavedMangaEntity>, chapterUrl: String): List<SavedProgressOwner> {
        val owners = mutableListOf<SavedProgressOwner>()
        for (work in works) {
            for (chapter in catalog.getChaptersForManga(work.id)) {
                if (policy.identifies(WorkLocator(work.api, chapterUrl), WorkLocator(work.api, chapter.url))) {
                    owners += SavedProgressOwner(work.savedIdentity(), chapter.id)
                }
            }
        }
        return owners
    }
}
