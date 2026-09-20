package me.manga.kira.data.repository.progress

import me.manga.kira.data.identity.WorkAliasPolicy
import me.manga.kira.data.local.entity.ReaderChapterStateEntity
import me.manga.kira.data.local.entity.ReaderWorkStateEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.identity.ProgressSnapshot
import me.manga.kira.domain.model.identity.SavedProgressOwner
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.progress.ProgressWriteResult

/** Transaction-local owner/anchor decisions; never retain a session beyond its owning writer. */
internal class ProgressOwnerSession(
    val storage: ProgressStorage,
    val policy: WorkAliasPolicy,
) {
    private val saved = ProgressSavedOwners(storage.manga, storage.chapters, policy)
    val anchors = ProgressAnchors(storage.progress, policy)

    suspend fun resolveWork(request: WorkLocator): ResolvedProgressWork {
        val anchor = anchors.work(request)
        val owner = saved.work(request, exactAnchorExists = anchor?.workUrl == request.url)
        return ResolvedProgressWork(request, owner, anchor)
    }

    suspend fun resolveChapter(request: ChapterLocator): ResolvedProgressChapter {
        val work = resolveWork(request.work)
        val chapter = saved.chapter(work.saved, request)
        val anchor = work.anchor?.let { anchors.chapter(it, request.chapterUrl) }
        return ResolvedProgressChapter(request, work, chapter, anchor)
    }

    suspend fun beginSession(request: ChapterLocator): ProgressSnapshot {
        val resolved = resolveChapter(request)
        val current = anchors.ensure(resolved)
        val handle = ProgressHandle(
            resolved.destination, current.workGeneration, current.chapterGeneration, resolved.owner,
        )
        return ProgressSnapshot(handle, current.pageIndex)
    }

    suspend fun readPosition(request: ChapterLocator): Int? = anchors.read(resolveChapter(request))?.pageIndex

    suspend fun save(handle: ProgressHandle, pageIndex: Int): ProgressWriteResult {
        requireProgress(pageIndex >= 0, ProgressRejection.NEGATIVE_PAGE)
        val resolved = resolveChapter(handle.chapter)
        saved.validateRetained(handle, resolved.owner)
        val current = anchors.read(resolved) ?: return ProgressWriteResult.STALE
        if (handle.workGeneration != current.workGeneration) return ProgressWriteResult.STALE
        if (handle.chapterGeneration != current.chapterGeneration) return ProgressWriteResult.STALE
        return if (storage.progress.savePosition(current, pageIndex)) {
            ProgressWriteResult.WRITTEN
        } else {
            ProgressWriteResult.STALE
        }
    }

    suspend fun clearWork(request: WorkLocator) {
        val current = anchors.ensureWork(resolveWork(request))
        storage.progress.clearWork(current.api, current.workUrl)
    }

    suspend fun clearChapter(request: ChapterLocator) {
        val resolved = resolveChapter(request)
        anchors.ensure(resolved)
        val target = resolved.destination
        storage.progress.clearChapter(target.work.api, target.work.url, target.chapterUrl)
    }
}

internal data class ResolvedProgressWork(
    val requested: WorkLocator,
    val saved: SavedWorkIdentity?,
    val anchor: ReaderWorkStateEntity?,
) {
    val destination: WorkLocator
        get() = saved?.locator ?: anchor?.let { WorkLocator(it.api, it.workUrl) } ?: requested
}

internal data class ResolvedProgressChapter(
    val requested: ChapterLocator,
    val work: ResolvedProgressWork,
    val saved: SavedChapterEntity?,
    val anchor: ReaderChapterStateEntity?,
) {
    val destination: ChapterLocator
        get() = ChapterLocator(work.destination, saved?.url ?: anchor?.chapterUrl ?: requested.chapterUrl)

    val owner: SavedProgressOwner?
        get() = work.saved?.let { SavedProgressOwner(it, saved?.id) }
}
