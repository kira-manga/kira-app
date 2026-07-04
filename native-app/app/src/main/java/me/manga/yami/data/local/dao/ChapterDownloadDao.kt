package me.manga.yamiapk.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.presentation.features.download.data.DownloadingState

@Dao
interface ChapterDownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChapterDownloadEntity)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: ChapterDownloadEntity):Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(downloads: List<ChapterDownloadEntity>): List<Long>
    @Query("SELECT * FROM chapter_downloads")
    fun getAll(): Flow<List<ChapterDownloadEntity>>
    @Query("SELECT * FROM chapter_downloads WHERE state = :queuedState LIMIT 1")
    suspend fun getNextQueuedChapter(queuedState: DownloadingState = DownloadingState.QUEUED): ChapterDownloadEntity?
    @Query("SELECT * FROM chapter_downloads WHERE chapterId = :chapterId LIMIT 1")
    suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity?
    @Query("""
  SELECT * 
    FROM chapter_downloads 
   WHERE state = :queuedState
   ORDER BY id ASC
""")
    fun observeQueuedChapters(queuedState: DownloadingState = DownloadingState.QUEUED): Flow<List<ChapterDownloadEntity>>

    @Query("SELECT COUNT(*) FROM chapter_downloads WHERE state = :queuedState")
    fun getQueuedCount(
        queuedState: DownloadingState = DownloadingState.QUEUED
    ): Flow<Int>

    @Query("DELETE FROM chapter_downloads WHERE chapterId = :chapterId")
    suspend fun deleteByChapterId(chapterId: Long)

    @Query("""
        SELECT * 
        FROM chapter_downloads 
        WHERE state IN (:runningState, :compressingState)
        LIMIT 1
    """)
    fun observeRunningChapter(
        runningState: DownloadingState = DownloadingState.RUNNING,
        compressingState: DownloadingState = DownloadingState.COMPRESSING
    ): Flow<ChapterDownloadEntity?>

    @Query("""
      SELECT chapterId
        FROM chapter_downloads
       WHERE state = :queuedState
    """)
    fun getAllQueuedChapterIds(
        queuedState: DownloadingState = DownloadingState.QUEUED
    ): Flow<List<Long>>

    @Query("""
    UPDATE chapter_downloads 
    SET state    = :state,
        progress = :progress,
        errorMsg = :errorMsg
    WHERE chapterId = :id
  """)
    suspend fun updateStateAndProgress(
        id: Long,
        state: DownloadingState,
        progress: Int,
        errorMsg: String? = null
    )

    @Query("UPDATE chapter_downloads SET progress = :progress WHERE chapterId = :id")
    suspend fun updateProgress(id: Long, progress: Int)

    @Query("UPDATE chapter_downloads SET state = :state WHERE chapterId = :id")
    suspend fun updateState(id: Long, state: DownloadingState)
    @Query("UPDATE chapter_downloads SET state = :state WHERE chapterId = :id")
    suspend fun updateStateChId(id: Long, state: DownloadingState)


    @Query("UPDATE chapter_downloads SET errorMsg = :errorMsg WHERE chapterId = :id")
    suspend fun setErrorMsg(id: Long, errorMsg: String?)
    @Transaction
    suspend fun updateFailure(id: Long, errorMsg: String?) {
        updateState(id, DownloadingState.FAILED)
        setErrorMsg(id, errorMsg)
    }
    @Deprecated("Use observeAllDownloadsPaged instead to avoid CursorWindow overflow")
    @Query("SELECT * FROM chapter_downloads ORDER BY id DESC")
    fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>>


    @Query("DELETE FROM chapter_downloads WHERE state = :state")
    suspend fun clearByState(state: DownloadingState)


    @Query("""
      UPDATE chapter_downloads
         SET state = :failedState,
             progress = 0,
             errorMsg = 'Cancelled by user'
       WHERE state IN (:runningState, :queuedState,:compressingState)
    """)
    suspend fun markAllRunningOrQueuedAsFailed(
        runningState: DownloadingState = DownloadingState.RUNNING,
        queuedState:  DownloadingState = DownloadingState.QUEUED,
        compressingState: DownloadingState = DownloadingState.COMPRESSING,
        failedState:  DownloadingState = DownloadingState.FAILED,
    )


    // NEW: Paginated queries
    @Query("SELECT * FROM chapter_downloads ORDER BY id DESC")
    fun observeAllDownloadsPaged(): PagingSource<Int, ChapterDownloadEntity>

    @Query("""
        SELECT * FROM chapter_downloads 
        WHERE state IN (:states) 
        ORDER BY id DESC
    """)
    fun observeDownloadsByStatePaged(states: List<DownloadingState>): PagingSource<Int, ChapterDownloadEntity>

    // For counts (these are fine without pagination)
    @Query("SELECT COUNT(*) FROM chapter_downloads WHERE state = :state")
    fun getCountByState(state: DownloadingState): Flow<Int>
}