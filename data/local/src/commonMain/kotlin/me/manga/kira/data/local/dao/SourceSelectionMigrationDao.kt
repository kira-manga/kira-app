package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import me.manga.kira.data.local.entity.ReaderChapterStateEntity
import me.manga.kira.data.local.entity.ReaderWorkStateEntity

/**
 * Transaction-local selection primitives, with no transaction or exclusion authority of their own.
 * Reads deliberately include every API and corrupt/nonmatching link. The caller bounds the complete
 * snapshot before loading it and propagates every unexpected write count out of its owning writer.
 */
@Dao
interface SourceSelectionMigrationDao : SelectionMigrationReads, SelectionMigrationWrites

/** Identity-only reads: metadata, images, file paths and cleanup payloads are never materialized. */
interface SelectionMigrationReads {
    /**
     * Counts all seven ownership tables, including unaffected rows, once each. Locator bytes include
     * every API and page URL below, once per stored field; inherited parent APIs are not counted again.
     * BLOB lengths count the UTF-8 bytes of the Room database, not characters. No first-N admission.
     */
    @Query(
        """
        SELECT SUM(rowCount) AS rowCount, SUM(locatorBytes) AS locatorBytes FROM (
          SELECT COUNT(*) AS rowCount, COALESCE(SUM(length(CAST(api AS BLOB)) +
            length(CAST(url AS BLOB))), 0) AS locatorBytes FROM saved_manga
          UNION ALL SELECT COUNT(*), COALESCE(SUM(length(CAST(url AS BLOB))), 0) FROM saved_chapters
          UNION ALL SELECT COUNT(*), COALESCE(SUM(length(CAST(api AS BLOB)) +
            length(CAST(workUrl AS BLOB))), 0) FROM reader_work_state
          UNION ALL SELECT COUNT(*), COALESCE(SUM(length(CAST(chapterUrl AS BLOB))), 0) FROM reader_chapter_state
          UNION ALL SELECT COUNT(*), COALESCE(SUM(length(CAST(api AS BLOB)) +
            length(CAST(url AS BLOB))), 0) FROM chapter_downloads
          UNION ALL SELECT COUNT(*), COALESCE(SUM(length(CAST(api AS BLOB)) +
            length(CAST(mangaUrl AS BLOB)) + length(CAST(chapterUrl AS BLOB))), 0) FROM history_items
          UNION ALL SELECT COUNT(*), COALESCE(SUM(length(CAST(api AS BLOB)) +
            length(CAST(mangaUrl AS BLOB)) + length(CAST(chapterUrl AS BLOB))), 0) FROM notifications
        )
        """,
    )
    suspend fun inspectionSize(): SelectionInspectionSize

    @Query("SELECT id, api, url FROM saved_manga ORDER BY id")
    suspend fun savedWorks(): List<SelectionSavedWork>

    @Query("SELECT id, mangaId, url FROM saved_chapters ORDER BY id")
    suspend fun savedChapters(): List<SelectionSavedChapter>

    @Query("SELECT * FROM reader_work_state ORDER BY workId")
    suspend fun readerWorks(): List<ReaderWorkStateEntity>

    @Query("SELECT * FROM reader_chapter_state ORDER BY chapterId")
    suspend fun readerChapters(): List<ReaderChapterStateEntity>

    // A scalar prevents an arbitrary corrupt state string from escaping the locator byte budget.
    @Query(
        """
        SELECT id, chapterId, mangaId, api, url,
          state IN ('QUEUED', 'SUCCESS', 'FAILED') AS stationary
        FROM chapter_downloads ORDER BY id
        """,
    )
    suspend fun queues(): List<SelectionQueueRow>

    @Query("SELECT id, api, mangaId, mangaUrl, 0 AS chapterId, chapterUrl FROM history_items ORDER BY id")
    suspend fun history(): List<SelectionRelatedRow>

    @Query("SELECT id, api, mangaId, mangaUrl, chapterId, chapterUrl FROM notifications ORDER BY id")
    suspend fun notifications(): List<SelectionRelatedRow>

    /** Independent GLOBAL occupancy, never filtered to the candidate's API or migrating families. */
    @Query("SELECT id, api, url FROM saved_manga WHERE url = :url")
    suspend fun savedWorkAt(url: String): SelectionSavedWork?
}

/** URL columns only. No conflict-ignore/replacement, lifecycle update, insert, delete or receipt edit. */
interface SelectionMigrationWrites {
    @Query("UPDATE saved_manga SET url = :newUrl WHERE id = :id AND api = :api AND url = :oldUrl")
    suspend fun moveSavedWork(id: Long, api: String, oldUrl: String, newUrl: String): Int

    @Query(
        """
        UPDATE saved_chapters SET url = :newUrl
        WHERE id = :id AND mangaId = :mangaId AND url = :oldUrl
          AND EXISTS (SELECT 1 FROM saved_manga WHERE id = :mangaId AND api = :api)
        """,
    )
    suspend fun moveSavedChapter(id: Long, mangaId: Long, api: String, oldUrl: String, newUrl: String): Int

    @Query(
        """
        UPDATE chapter_downloads SET url = :newUrl
        WHERE id = :id AND chapterId = :chapterId AND mangaId = :mangaId AND api = :api AND url = :oldUrl
          AND state IN ('QUEUED', 'SUCCESS', 'FAILED')
        """,
    )
    suspend fun moveQueue(id: Long, chapterId: Long, mangaId: Long, api: String, oldUrl: String, newUrl: String): Int

    @Query(
        """
        UPDATE history_items SET mangaUrl = :newUrl
        WHERE id = :id AND mangaId = :mangaId AND api = :api AND mangaUrl = :oldUrl
        """,
    )
    suspend fun moveHistoryWork(id: Long, mangaId: Long, api: String, oldUrl: String, newUrl: String): Int

    @Query(
        """
        UPDATE history_items SET chapterUrl = :newUrl
        WHERE id = :id AND mangaId = :mangaId AND api = :api AND chapterUrl = :oldUrl
        """,
    )
    suspend fun moveHistoryChapter(id: Long, mangaId: Long, api: String, oldUrl: String, newUrl: String): Int

    @Query(
        """
        UPDATE notifications SET mangaUrl = :newUrl
        WHERE id = :id AND mangaId = :mangaId AND chapterId = :chapterId AND api = :api
          AND mangaUrl = :oldUrl
        """,
    )
    suspend fun moveNotificationWork(id: Long, mangaId: Long, chapterId: Long, api: String, oldUrl: String, newUrl: String): Int

    @Query(
        """
        UPDATE notifications SET chapterUrl = :newUrl
        WHERE id = :id AND mangaId = :mangaId AND chapterId = :chapterId AND api = :api
          AND chapterUrl = :oldUrl
        """,
    )
    suspend fun moveNotificationChapter(id: Long, mangaId: Long, chapterId: Long, api: String, oldUrl: String, newUrl: String): Int
}

/** Complete inspection cost; overflow or a negative result is not admission. */
data class SelectionInspectionSize(val rowCount: Long, val locatorBytes: Long)

/** Saved IDs and reader work IDs are separate namespaces. */
data class SelectionSavedWork(val id: Long, val api: String, val url: String)

/** A chapter URL is owned only within this saved parent, never globally. */
data class SelectionSavedChapter(val id: Long, val mangaId: Long, val url: String)

/** Original queue identity: the ledger has no saved-chapter foreign key. */
data class SelectionQueueIdentity(val id: Long, val chapterId: Long, val mangaId: Long, val api: String, val url: String)

/** Stationary excludes RUNNING/COMPRESSING/DOWNLOADED/unknown; QUEUED still needs external exclusion. */
data class SelectionQueueRow(@Embedded val identity: SelectionQueueIdentity, val stationary: Boolean)

/** A nonzero saved link must be proved, not bypassed by a coincidentally matching locator. */
data class SelectionRelatedWork(val api: String, val mangaId: Long, val mangaUrl: String)

/** History uses chapterId zero; notifications retain their actual saved-chapter link. */
data class SelectionRelatedRow(
    val id: Long,
    @Embedded val work: SelectionRelatedWork,
    val chapterId: Long,
    val chapterUrl: String,
)
