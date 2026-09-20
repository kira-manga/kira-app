package me.manga.kira.data.repository.progress

import me.manga.kira.data.identity.WorkAliasPolicy
import me.manga.kira.data.local.dao.ReaderProgressDao
import me.manga.kira.data.local.dao.ReaderProgressSnapshot
import me.manga.kira.data.local.entity.ReaderChapterStateEntity
import me.manga.kira.data.local.entity.ReaderWorkStateEntity
import me.manga.kira.domain.model.identity.WorkLocator

/** Full candidate scans precede any exact lookup/ensure; accepted aliases never merge anchors. */
internal class ProgressAnchors(
    private val dao: ReaderProgressDao,
    private val policy: WorkAliasPolicy,
) {
    suspend fun work(request: WorkLocator): ReaderWorkStateEntity? {
        val matches = dao.worksForApi(request.api).filter {
            policy.identifies(request, WorkLocator(it.api, it.workUrl))
        }
        requireProgress(matches.size <= 1, ProgressRejection.WORK_ANCHOR_CONFLICT)
        return matches.singleOrNull()
    }

    suspend fun chapter(work: ReaderWorkStateEntity, url: String): ReaderChapterStateEntity? {
        val matches = dao.chaptersForWork(work.workId).filter {
            policy.identifies(WorkLocator(work.api, url), WorkLocator(work.api, it.chapterUrl))
        }
        requireProgress(matches.size <= 1, ProgressRejection.CHAPTER_ANCHOR_CONFLICT)
        return matches.singleOrNull()
    }

    fun read(resolved: ResolvedProgressChapter): ReaderProgressSnapshot? {
        val work = resolved.work.anchor ?: return null
        val chapter = resolved.anchor ?: return null
        return ReaderProgressSnapshot(
            work.workId, chapter.chapterId, work.workGeneration, chapter.chapterGeneration, chapter.pageIndex,
        )
    }

    suspend fun ensureWork(resolved: ResolvedProgressWork): ReaderWorkStateEntity {
        val destination = resolved.destination
        val existing = resolved.anchor
        if (existing != null && existing.workUrl != destination.url) {
            requireProgress(dao.moveWork(existing, destination.url), ProgressRejection.WRITE_COUNT)
        }
        val current = dao.ensureWork(destination.api, destination.url)
        requireProgress(existing == null || existing.workId == current.workId, ProgressRejection.WRITE_COUNT)
        return current
    }

    suspend fun ensure(resolved: ResolvedProgressChapter): ReaderProgressSnapshot {
        val work = ensureWork(resolved.work)
        val destination = resolved.destination
        val existing = resolved.anchor
        if (existing != null && existing.chapterUrl != destination.chapterUrl) {
            requireProgress(dao.moveChapter(existing, destination.chapterUrl), ProgressRejection.WRITE_COUNT)
        }
        val current = dao.ensureSnapshot(work.api, work.workUrl, destination.chapterUrl)
        requireProgress(existing == null || existing.chapterId == current.chapterId, ProgressRejection.WRITE_COUNT)
        return current
    }
}
