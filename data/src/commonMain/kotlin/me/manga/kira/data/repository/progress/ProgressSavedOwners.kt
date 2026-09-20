package me.manga.kira.data.repository.progress

import me.manga.kira.data.identity.WorkAliasComparison
import me.manga.kira.data.identity.WorkAliasPolicy
import me.manga.kira.data.identity.WorkOwnerConflict
import me.manga.kira.data.identity.WorkOwnerResolution
import me.manga.kira.data.identity.WorkOwnerResolver
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.identity.SavedProgressOwner
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator

internal class ProgressSavedOwners(
    private val manga: MangaDao,
    private val chapters: ChapterDao,
    private val policy: WorkAliasPolicy,
) {
    suspend fun work(request: WorkLocator, exactAnchorExists: Boolean): SavedWorkIdentity? {
        val exact = manga.getMangaByExactUrl(request.url)?.savedIdentity()
        val candidates = manga.getMangaByApi(request.api).map { it.savedIdentity() }
        return when (val found = WorkOwnerResolver(policy).resolve(request, exact, candidates)) {
            is WorkOwnerResolution.Found -> found.owner
            is WorkOwnerResolution.Missing -> null
            is WorkOwnerResolution.Conflict -> recoverExactAnchor(found, exactAnchorExists)
        }
    }

    suspend fun chapter(owner: SavedWorkIdentity?, request: ChapterLocator): SavedChapterEntity? {
        if (owner == null) return null
        val matches = chapters.getChaptersByMangaIdR(owner.id).filter {
            policy.identifies(
                WorkLocator(owner.locator.api, request.chapterUrl),
                WorkLocator(owner.locator.api, it.url),
            )
        }
        requireProgress(matches.size <= 1, ProgressRejection.SAVED_CHAPTER_CONFLICT)
        return matches.singleOrNull()
    }

    suspend fun validateRetained(handle: ProgressHandle, current: SavedProgressOwner?) {
        val retained = handle.retainedOwner ?: return
        if (current == null || retained.work.id != current.work.id) throw StaleProgressHandle()
        if (!policy.identifies(retained.work.locator, handle.chapter.work)) throw StaleProgressHandle()
        if (!policy.identifies(retained.work.locator, current.work.locator)) throw StaleProgressHandle()
        if (retained.chapterId != null && retained.chapterId != current.chapterId) throw StaleProgressHandle()
        if (retained.work.locator != handle.chapter.work) requireRetainedWork(retained.work)
    }

    private suspend fun requireRetainedWork(retained: SavedWorkIdentity) {
        val exact = manga.getMangaByExactUrl(retained.locator.url)?.savedIdentity()
        val candidates = manga.getMangaByApi(retained.locator.api).map { it.savedIdentity() }
        when (val resolved = WorkOwnerResolver(policy).resolveRetained(retained, exact, candidates)) {
            is WorkOwnerResolution.Found -> Unit
            is WorkOwnerResolution.Missing -> throw StaleProgressHandle()
            is WorkOwnerResolution.Conflict -> when (resolved.reason) {
                WorkOwnerConflict.RetainedOwnerMissing, WorkOwnerConflict.RetainedOwnerChanged ->
                    throw StaleProgressHandle()
                else -> throw ProgressIdentityException(ProgressRejection.SAVED_WORK_CONFLICT, resolved)
            }
        }
    }

    private fun recoverExactAnchor(
        conflict: WorkOwnerResolution.Conflict,
        exactAnchorExists: Boolean,
    ): SavedWorkIdentity? {
        // Only already-durable exact unsaved identity is recoverable without usable alias rules.
        // Cross-api occupancy, multiple owners, and inconsistent candidates are NEVER bypassed.
        if (exactAnchorExists && conflict.reason is WorkOwnerConflict.RejectedRequest) return null
        throw ProgressIdentityException(ProgressRejection.SAVED_WORK_CONFLICT, conflict)
    }
}

internal fun WorkAliasPolicy.identifies(first: WorkLocator, second: WorkLocator): Boolean =
    when (compare(first, second)) {
        WorkAliasComparison.Exact, WorkAliasComparison.DeclaredAlias -> true
        WorkAliasComparison.Distinct, is WorkAliasComparison.Rejected -> false
    }
