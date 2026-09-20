package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.manga.kira.data.local.entity.ReaderChapterStateEntity
import me.manga.kira.data.local.entity.ReaderWorkStateEntity

/**
 * Exact, local persistence primitives; no URL canonicalization or saved-owner inference.
 * Accepted-policy resolution and saved-ID checks belong in the caller's same writer transaction.
 * Propagate failures out of that transaction; SQLite ABORT alone only undoes its statement.
 */
@Dao
abstract class ReaderProgressDao {
    /** Read-only lookup; an absent work is not implicitly reopened. */
    @Query("SELECT * FROM reader_work_state WHERE api = :api AND workUrl = :workUrl")
    abstract suspend fun findWork(api: String, workUrl: String): ReaderWorkStateEntity?

    /** Complete source candidates: an exact hit must not hide a second accepted alias anchor. */
    @Query("SELECT * FROM reader_work_state WHERE api = :api ORDER BY workId")
    abstract suspend fun worksForApi(api: String): List<ReaderWorkStateEntity>

    /** Looks up one exact chapter under a durable work anchor, never by chapter URL alone. */
    @Query("SELECT * FROM reader_chapter_state WHERE workId = :workId AND chapterUrl = :chapterUrl")
    abstract suspend fun findChapter(workId: Long, chapterUrl: String): ReaderChapterStateEntity?

    /** Complete scoped candidates for alias collision detection; never searches another work. */
    @Query("SELECT * FROM reader_chapter_state WHERE workId = :workId ORDER BY chapterId")
    abstract suspend fun chaptersForWork(workId: Long): List<ReaderChapterStateEntity>

    /** Read-only export/read primitive: neither missing rows nor null pages acquire a session. */
    @Query(
        """
        SELECT w.workId, c.chapterId, w.workGeneration, c.chapterGeneration, c.pageIndex
        FROM reader_work_state AS w JOIN reader_chapter_state AS c ON c.workId = w.workId
        WHERE w.api = :api AND w.workUrl = :workUrl AND c.chapterUrl = :chapterUrl
        """,
    )
    abstract suspend fun findSnapshot(api: String, workUrl: String, chapterUrl: String): ReaderProgressSnapshot?

    /** Creates only a missing anchor; existing generations and progress are never reset. */
    @Transaction
    open suspend fun ensureWork(api: String, workUrl: String): ReaderWorkStateEntity {
        findWork(api, workUrl)?.let { return it }
        val id = insertWork(ReaderWorkStateEntity(api = api, workUrl = workUrl))
        check(id > 0) { "Reader work anchor was not inserted" }
        return checkNotNull(findWork(api, workUrl)).also { check(it.workId == id) }
    }

    /** Acquires current local anchors/epochs; runtime decides when explicit Reader entry permits it. */
    @Transaction
    open suspend fun ensureSnapshot(api: String, workUrl: String, chapterUrl: String): ReaderProgressSnapshot {
        val work = ensureWork(api, workUrl)
        if (findChapter(work.workId, chapterUrl) == null) {
            val id = insertChapter(ReaderChapterStateEntity(workId = work.workId, chapterUrl = chapterUrl))
            check(id > 0) { "Reader chapter anchor was not inserted" }
        }
        return checkNotNull(findSnapshot(api, workUrl, chapterUrl))
    }

    /** Conditional write only: false is stale, never permission to acquire replacement epochs. */
    suspend fun savePosition(snapshot: ReaderProgressSnapshot, pageIndex: Int): Boolean {
        require(pageIndex >= 0)
        val changed = writePosition(
            snapshot.workId,
            snapshot.chapterId,
            snapshot.workGeneration,
            snapshot.chapterGeneration,
            pageIndex,
        )
        check(changed in 0..1) { "Unexpected reader position update count" }
        return changed == 1
    }

    /**
     * Fences all chapters, including unknown ones, even for an empty/unsaved work.
     * Library removal must call this inside the same accepted-owner writer as parent deletion.
     */
    @Transaction
    open suspend fun clearWork(api: String, workUrl: String): ReaderWorkStateEntity {
        val work = ensureWork(api, workUrl)
        val next = nextReaderGeneration(work.workGeneration)
        val children = countChapters(work.workId)
        check(advanceWork(work.workId, work.workGeneration, next) == 1) { "Reader work fence was not advanced" }
        check(clearWorkPositions(work.workId).toLong() == children) { "Reader work clear was incomplete" }
        return work.copy(workGeneration = next)
    }

    /** Fences only this chapter; sibling generations/positions remain untouched. */
    @Transaction
    open suspend fun clearChapter(api: String, workUrl: String, chapterUrl: String): ReaderProgressSnapshot {
        val snapshot = ensureSnapshot(api, workUrl, chapterUrl)
        val next = nextReaderGeneration(snapshot.chapterGeneration)
        check(advanceChapter(snapshot.workId, snapshot.chapterId, snapshot.chapterGeneration, next) == 1) {
            "Reader chapter fence was not advanced"
        }
        return snapshot.copy(chapterGeneration = next, pageIndex = null)
    }

    /** Accepted alias primitive; preserves ID/epoch and aborts on an occupied destination. */
    suspend fun moveWork(expected: ReaderWorkStateEntity, newWorkUrl: String): Boolean {
        val changed = rewriteWorkUrl(
            expected.workId,
            expected.api,
            expected.workUrl,
            expected.workGeneration,
            newWorkUrl,
        )
        check(changed in 0..1) { "Unexpected reader work move count" }
        return changed == 1
    }

    /** Use with work/saved-row alias updates in one writer; receipt keys/payloads stay original. */
    suspend fun moveChapter(expected: ReaderChapterStateEntity, newChapterUrl: String): Boolean {
        val changed = rewriteChapterUrl(
            expected.workId,
            expected.chapterId,
            expected.chapterUrl,
            expected.chapterGeneration,
            newChapterUrl,
        )
        check(changed in 0..1) { "Unexpected reader chapter move count" }
        return changed == 1
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertWork(work: ReaderWorkStateEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertChapter(chapter: ReaderChapterStateEntity): Long

    @Query("SELECT COUNT(*) FROM reader_chapter_state WHERE workId = :workId")
    protected abstract suspend fun countChapters(workId: Long): Long

    @Query(
        """
        UPDATE reader_chapter_state SET pageIndex = :pageIndex
        WHERE workId = :workId AND chapterId = :chapterId AND chapterGeneration = :chapterGeneration
          AND EXISTS (SELECT 1 FROM reader_work_state
                      WHERE workId = :workId AND workGeneration = :workGeneration)
        """,
    )
    protected abstract suspend fun writePosition(
        workId: Long,
        chapterId: Long,
        workGeneration: Long,
        chapterGeneration: Long,
        pageIndex: Int,
    ): Int

    @Query("UPDATE reader_work_state SET workGeneration = :next WHERE workId = :workId AND workGeneration = :expected")
    protected abstract suspend fun advanceWork(workId: Long, expected: Long, next: Long): Int

    @Query("UPDATE reader_chapter_state SET pageIndex = NULL WHERE workId = :workId")
    protected abstract suspend fun clearWorkPositions(workId: Long): Int

    @Query(
        """
        UPDATE reader_chapter_state SET chapterGeneration = :next, pageIndex = NULL
        WHERE workId = :workId AND chapterId = :chapterId AND chapterGeneration = :expected
        """,
    )
    protected abstract suspend fun advanceChapter(workId: Long, chapterId: Long, expected: Long, next: Long): Int

    @Query(
        """
        UPDATE reader_work_state SET workUrl = :newUrl
        WHERE workId = :workId AND api = :api AND workUrl = :oldUrl AND workGeneration = :generation
        """,
    )
    protected abstract suspend fun rewriteWorkUrl(
        workId: Long,
        api: String,
        oldUrl: String,
        generation: Long,
        newUrl: String,
    ): Int

    @Query(
        """
        UPDATE reader_chapter_state SET chapterUrl = :newUrl
        WHERE workId = :workId AND chapterId = :chapterId AND chapterUrl = :oldUrl AND chapterGeneration = :generation
        """,
    )
    protected abstract suspend fun rewriteChapterUrl(
        workId: Long,
        chapterId: Long,
        oldUrl: String,
        generation: Long,
        newUrl: String,
    ): Int
}
