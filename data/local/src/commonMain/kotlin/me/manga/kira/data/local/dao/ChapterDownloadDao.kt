package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.presentation.features.download.data.DownloadingState

// Phase 9.x.chapterdownloaddao.componentprune (Task #394): dropped 4 independently-orphan
// members surfaced by an exhaustive 3-pass reacher-chain audit (receiver-anchored
// `chapterDownloadDao.X(` / `dao.X(` + bare `\bX\b` word-boundary + `::X` method-ref) covering
// the entire source tree. Each dropped member had ZERO source-tree reachers at any anchor in
// the live codebase. This completes the cumulative `:shared/.../data/local/dao/` sweep
// (LibraryDeo / MangaDao / ChapterDao / HistoryDao / NotificationDao / SourcesDao /
// StatisticsDeo / ChapterDownloadDao all swept post-this-slice).
// Removed (independent orphans):
//   - `upsert(entity: ChapterDownloadEntity)` — bare @Insert returning Unit. No reacher. The
//     LIVE insert path is `insert(download): Long` (returns the inserted rowId so the worker
//     can wire follow-on writes). `upsert` overlapped semantically but was never wired.
//   - `getAll(): Flow<List<ChapterDownloadEntity>>` — un-ordered @Query Flow. No reacher.
//     The LIVE full-list flow is `observeAllDownloads()` (same return shape, but
//     `ORDER BY id DESC` — required by the UI's reverse-chronological list rendering).
//     `getAll` was never wired.
//   - `observeQueuedChapters(queuedState): Flow<List<ChapterDownloadEntity>>` — full-entity
//     Flow over QUEUED rows. No reacher. The LIVE queued-list flow is
//     `getAllQueuedChapterIds(queuedState): Flow<List<Long>>` (IDs only — sufficient for the
//     scheduler/wake-up path). The full-entity Flow variant was never wired.
//   - `getCountByState(state): Flow<Int>` — parameterized count @Query Flow. No reacher.
//     Was originally paired with the now-retired hard-coded `getQueuedCount(queuedState)`
//     (see Phase 9.x.downloadrepository.componentprune entry below).
//
// Phase 9.x.downloadrepository.componentprune (Task #398): dropped 2 additional coupled-dead
// DAO members after the partner repo-orphan retire (4 `DownloadRepository` interface methods
// + their impls in `DownloadRepositoryImpl` (android) and `CoroutineDownloadRepositoryImpl`
// (nonAndroid)). 3-pass reacher-chain audit confirmed the receivers' sole call sites were the
// just-retired `queuedCount(): Flow<Int>` and `clearFailedAndQueued()` impls.
// Removed:
//   - `getQueuedCount(queuedState): Flow<Int>` — sole reachers were
//     `DownloadRepositoryImpl.queuedCount` (line 66) and
//     `CoroutineDownloadRepositoryImpl.queuedCount` (line 128), both retired in the partner
//     slice. The queued-count UI badge consumer was the legacy DownloadsScreen retired by
//     Phase 9.x.downloads.legacyui.retire (Task #352); the rework `DownloadsScreen` does not
//     surface a queued-count badge.
//   - `clearByState(state)` — sole reachers were
//     `DownloadRepositoryImpl.clearFailedAndQueued` (lines 136/137) and
//     `CoroutineDownloadRepositoryImpl.clearFailedAndQueued` (lines 200/201), both retired in
//     the partner slice. The rework `DownloadsActionRepository` does not expose a bulk
//     clear-failed-and-queued action.
//
// Phase 9.x.chapterdownloaddao.componentprune.cascade (Task #441, 2026-05-28): dropped 2
// further cascade-orphan DAO members after the partner cascade retire in §440 slice A. After
// `DownloadRepository.observeRunningChapter()` + `DownloadRepository.cancelAllDownloads()`
// were retired at the interface + both-impl level by §440 slice A (Phase 9.x.downloadrepository
// .componentprune.cascade.interface, Task #440), the DAO methods they exclusively reached
// became cascade-orphan. 3-pass reacher-chain audit (receiver-anchored `dao.X(` /
// `chapterDownloadDao.X(` + bare `\bX\b` word-boundary + `::X` method-ref) returned ZERO live
// reachers across the entire source tree — only KDoc-only mentions in §253 audit-trail
// postscripts on `DownloadRepository.kt` / `DownloadRepositoryImpl.kt` /
// `CoroutineDownloadRepositoryImpl.kt` + one stale-design-rationale citation on
// `DownloadsActionRepositoryImpl.kt` (postscripted in the same commit).
// Removed (cascade-orphan after Task #440 slice A):
//   - `observeRunningChapter(runningState, compressingState)` — §398 LIVE reach chain was
//     `DownloadRepositoryImpl.kt:63` + `CoroutineDownloadRepositoryImpl.kt:122` (both the
//     legacy `observeRunningChapter()` impl bodies, retired in §440 slice A).
//   - `markAllRunningOrQueuedAsFailed(runningState, queuedState, compressingState,
//     failedState)` — §398 LIVE reach chain was `DownloadRepositoryImpl.kt:124` +
//     `CoroutineDownloadRepositoryImpl.kt:172` (both the legacy `cancelAllDownloads()` impl
//     bodies, retired in §440 slice A).
//
// LIVE members preserved (verified by exhaustive reacher-chain audit, post-§441):
//   - `insert(download): Long` — `DownloadRepositoryImpl.kt:78`,
//     `CoroutineDownloadRepositoryImpl.kt:143`.
//   - `insertAll(downloads): List<Long>` — `CoroutineDownloadRepositoryImpl.kt` (the bulk
//     insert path; sole remaining LIVE caller post-§440 slice A bulk-enqueue retire is the
//     impl's own batch helper).
//   - `getNextQueuedChapter(queuedState)` — `DownloadWorkerV2.kt:132`,
//     `CoroutineDownloadRepositoryImpl.workerLoop`.
//   - `getDownloadByChapter(chapterId)` — `DownloadsActionRepositoryImpl.kt:125`,
//     `CoroutineDownloadRepositoryImpl.processJob`.
//   - `deleteByChapterId(chapterId)` — `DownloadRepositoryImpl.kt:85`,
//     `CoroutineDownloadRepositoryImpl.kt:148`.
//   - `getAllQueuedChapterIds(queuedState)` — `CoroutineDownloadRepositoryImpl.kt:124`
//     (startup recovery only; the Android impl no longer reaches it since the §440 slice A
//     `queuedChapterIds()` retire).
//   - `updateStateAndProgress(id, state, progress, errorMsg)` — `DownloadWorkerV2.kt:217/273
//     /289`, `CoroutineDownloadRepositoryImpl.processJob`.
//   - `updateProgress(id, progress)` — `DownloadWorkerV2.kt:259`,
//     `CoroutineDownloadRepositoryImpl.processJob`.
//   - `updateState(id, state)` — internal LIVE via `updateFailure` @Transaction body.
//   - `updateStateChId(id, state)` — `DownloadWorkerV2.kt:136`,
//     `ChapterDownloadService.kt:205/214/331/337`,
//     `CoroutineDownloadRepositoryImpl.processJob`.
//   - `setErrorMsg(id, errorMsg)` — internal LIVE via `updateFailure` @Transaction body.
//   - `updateFailure(id, errorMsg)` @Transaction — `ChapterDownloadService.kt:219/231/237
//     /342/353/358`, `DownloadRepositoryImpl.kt:89`,
//     `CoroutineDownloadRepositoryImpl.kt:152/188/192/221/251`.
//   - `observeAllDownloads()` — `DownloadRepositoryImpl.kt:62`,
//     `CoroutineDownloadRepositoryImpl.kt:134`, also `DownloadsRepositoryImpl.kt:77` (via
//     the legacy `DownloadRepository.observeAllDownloads()` re-export).
@Dao
interface ChapterDownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: ChapterDownloadEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(downloads: List<ChapterDownloadEntity>): List<Long>

    @Query("SELECT * FROM chapter_downloads WHERE state = :queuedState LIMIT 1")
    suspend fun getNextQueuedChapter(queuedState: DownloadingState = DownloadingState.QUEUED): ChapterDownloadEntity?

    @Query("SELECT * FROM chapter_downloads WHERE chapterId = :chapterId LIMIT 1")
    suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity?

    // Active (in-flight) download chapterIds for a manga. Used by the library-removal purge to cancel
    // a RUNNING/COMPRESSING download before deleting the rows + on-disk dir, so the engine can't keep
    // writing pages into the just-purged manga directory and leave an orphan CBZ behind.
    @Query("""
      SELECT chapterId
        FROM chapter_downloads
       WHERE mangaId = :mangaId
         AND state IN (:runningState, :compressingState, :queuedState)
    """)
    suspend fun getActiveDownloadChapterIdsForManga(
        mangaId: Long,
        runningState: DownloadingState = DownloadingState.RUNNING,
        compressingState: DownloadingState = DownloadingState.COMPRESSING,
        queuedState: DownloadingState = DownloadingState.QUEUED,
    ): List<Long>

    @Query("DELETE FROM chapter_downloads WHERE chapterId = :chapterId")
    suspend fun deleteByChapterId(chapterId: Long)

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

    // Conditional QUEUED -> RUNNING claim. Flips the row only while it is still QUEUED and returns the
    // affected-row count, so a cancel that lands as FAILED between getNextQueuedChapter() and this claim
    // is NOT silently overwritten: 0 rows means another caller already changed the state and the worker
    // must skip the job. Guards the cancel-all-then-cancel-one race on the iOS/Desktop worker loop.
    @Query("""
      UPDATE chapter_downloads
         SET state = :runningState
       WHERE chapterId = :id
         AND state = :queuedState
    """)
    suspend fun claimQueuedAsRunning(
        id: Long,
        runningState: DownloadingState = DownloadingState.RUNNING,
        queuedState: DownloadingState = DownloadingState.QUEUED,
    ): Int

    @Query("UPDATE chapter_downloads SET errorMsg = :errorMsg WHERE chapterId = :id")
    suspend fun setErrorMsg(id: Long, errorMsg: String?)

    @Transaction
    suspend fun updateFailure(id: Long, errorMsg: String?) {
        updateState(id, DownloadingState.FAILED)
        setErrorMsg(id, errorMsg)
    }

    // Re-added (DOWNLOAD "cancel-all marks rows failed" backlog item, 2026-06-01): bulk
    // flip every in-flight row (RUNNING / QUEUED / COMPRESSING) to FAILED with progress
    // reset and the cancelled-by-user SENTINEL as the error message (2026-07 audit — the
    // previous raw 'Cancelled by user' literal never matched :domain's
    // DownloadedChapter.CANCELLED_BY_USER_SENTINEL, so :ui showed raw English instead of the
    // localized "cancelled" label and the coroutine engine's NotifierRules classified the
    // cancel as FAILED → a spurious "Download failed" alert after every cancel-all). The
    // sentinel string is hardcoded here in lockstep (this module can't see :domain), same
    // convention as CoroutineDownloadRepositoryImpl.CANCELLED_BY_USER. Backs the legacy
    // DownloadRepository.cancelAllDownloads() SPI, which the notification "Cancel all"
    // action (DownloadCancelReceiver) routes through. State-name semantics match the
    // native ChapterDownloadDao source of truth (enum name strings via
    // DownloadingStateConverter). No new column, no schema/version change.
    @Query("""
      UPDATE chapter_downloads
         SET state = :failedState,
             progress = 0,
             errorMsg = '__cancelled_by_user__'
       WHERE state IN (:runningState, :queuedState, :compressingState, :downloadedState)
    """)
    suspend fun markAllRunningOrQueuedAsFailed(
        runningState: DownloadingState = DownloadingState.RUNNING,
        queuedState: DownloadingState = DownloadingState.QUEUED,
        compressingState: DownloadingState = DownloadingState.COMPRESSING,
        // DOWNLOADED (iOS bg engine: transferred, readable, CBZ pending) is also an in-flight row from the
        // queue's POV — "Cancel all" must clear it too, else it lingers (transferred-but-uncompressed) with
        // nothing able to cancel it. The iOS engine's cancelAllDownloads also reverts the chapter's
        // readable bookkeeping and removes its files at cancel time (2026-07-04 device smoke — a
        // FAILED("cancelled") row whose chapter still read as Downloaded was a contradiction), so a
        // cancelled finalize-window chapter never remains readable.
        downloadedState: DownloadingState = DownloadingState.DOWNLOADED,
        failedState: DownloadingState = DownloadingState.FAILED,
    )

    // Migration note (Phase 6): @Deprecated removed. Source paired this with paginated
    // alternatives observeAllDownloadsPaged()/observeDownloadsByStatePaged() (PagingSource
    // returns). PagingSource backing in Room (androidx.room.paging.LimitOffsetPagingSource) is
    // Android-only, so those two methods were removed from this KMP DAO. The non-paginated Flow
    // stays as the canonical API; Phase 10 will derive Pager{...} compositions per-platform when
    // the UI needs paged scrolling.
    @Query("SELECT * FROM chapter_downloads ORDER BY id DESC")
    fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>>

    // Cheap slot accounting for the iOS engine's fillWindowLocked: an indexed COUNT instead of
    // materializing the whole (history-inclusive) table on every enqueue. Bulk "Download all" was
    // O(N) full-table scans under the engine mutex; this makes each post-first enqueue an O(1)-ish count.
    @Query("SELECT COUNT(*) FROM chapter_downloads WHERE state = :state")
    suspend fun countByState(state: DownloadingState): Int

    // QUEUED rows only (full entities, for prepareLocked), newest-first to match observeAllDownloads'
    // ORDER BY id DESC selection order. Fetched only when a transfer slot is actually free.
    @Query("SELECT * FROM chapter_downloads WHERE state = :queuedState ORDER BY id DESC")
    suspend fun getQueuedChapters(queuedState: DownloadingState = DownloadingState.QUEUED): List<ChapterDownloadEntity>

    // Startup reconciliation (restart-freeze fix, 2026-06-02). On process death a download left
    // mid-flight stays RUNNING (or COMPRESSING) in the DB forever: the worker only ever pulls
    // QUEUED rows (getNextQueuedChapter), so the orphan is never retried and the UI shows it stuck
    // "downloading". This bulk-resets every interrupted in-flight row back to QUEUED with progress
    // reset so the worker re-picks it on the next launch. Native carries the same bug (no startup
    // reconcile); we fix it on all platforms by calling this once at startup, then re-enqueuing the
    // WorkManager job (Android) / sending a wake-up (iOS/Desktop). Distinct from
    // markAllRunningOrQueuedAsFailed (user "Cancel all" -> FAILED): this is the resume path, not a
    // cancel. No new column, no schema/version change.
    // [excludeChapterId] guards against the in-process worker race on iOS/Desktop: the worker loop
    // may have already picked up a QUEUED row and flipped it to RUNNING in THIS (fresh) process by
    // the time startup reconcile runs; resetting that live row would abort it mid-download and force
    // a re-download from page 0. The nonAndroid impl passes its current activeChapterId here so that
    // legitimately-running row is left alone. Android passes the default (-1, matches no row) since
    // its WorkManager worker runs in a state where no in-process activeChapterId exists.
    @Query("""
      UPDATE chapter_downloads
         SET state = :queuedState,
             progress = 0
       WHERE state IN (:runningState, :compressingState)
         AND chapterId != :excludeChapterId
    """)
    suspend fun reEnqueueInterrupted(
        excludeChapterId: Long = -1L,
        runningState: DownloadingState = DownloadingState.RUNNING,
        compressingState: DownloadingState = DownloadingState.COMPRESSING,
        queuedState: DownloadingState = DownloadingState.QUEUED,
    )

    // Single-row, state-guarded twin of reEnqueueInterrupted (2026-07 audit). The Android worker's
    // cancellation cleanup re-queues ITS in-flight chapter when a SYSTEM stop interrupts it
    // (constraint lost / quota) so the rescheduled worker run resumes it — without the reset the
    // row stayed RUNNING until the next app-launch reconcile. The state guard makes a USER cancel
    // safe in both race orders: the cancel path writes FAILED, which this WHERE clause never
    // matches (and if this runs first, the cancel's FAILED write lands after). No schema change.
    @Query(
        """
      UPDATE chapter_downloads
         SET state = :queuedState,
             progress = 0
       WHERE chapterId = :chapterId
         AND state IN (:runningState, :compressingState)
    """,
    )
    suspend fun requeueIfInFlight(
        chapterId: Long,
        runningState: DownloadingState = DownloadingState.RUNNING,
        compressingState: DownloadingState = DownloadingState.COMPRESSING,
        queuedState: DownloadingState = DownloadingState.QUEUED,
    )

    // Chapter download SUCCESS rows whose sizeBytes is still 0 (rows downloaded before the size
    // column existed, i.e. migrated up from schema v8). Used by the startup reconcile to back-fill
    // their on-disk size so the native size display is correct for pre-existing downloads too.
    @Query("SELECT * FROM chapter_downloads WHERE state = :successState AND sizeBytes = 0")
    suspend fun getCompletedWithoutSize(
        successState: DownloadingState = DownloadingState.SUCCESS,
    ): List<ChapterDownloadEntity>

    // Persist the final on-disk size (bytes) of a completed chapter download. Written once at
    // SUCCESS by both download engines (native size-display parity) and by the startup back-fill.
    @Query("UPDATE chapter_downloads SET sizeBytes = :sizeBytes WHERE chapterId = :id")
    suspend fun updateSize(id: Long, sizeBytes: Long)
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster184.staleKdocSweep.cascade,
 * Task #671, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eighty-third sibling of the cluster57-183
 * sweep — leaf 4/5 of the wave-54 commonMain :data/local/dao Room-DAO
 * 5-leaf batch; ChapterDownloadDao interface 4/5).
 *
 *  (a) Inline cumulative-prune comment "Phase-9-x-chapterdownloaddao-
 *  componentprune Task-394-dropped-4-independently-orphan-members + upsert
 *  + getAll + observeQueuedChapters + getCountByState" — LIVE-NOT-STALE
 *  for the ChapterDownloadDao surface AND FULFILLED-RETIRE for the
 *  Phase 9.x.chapterdownloaddao.componentprune Task #394 4-orphan drop
 *  (verified: none of the 4 retired members re-appears in the @Dao
 *  interface body; the LIVE 13-method surface remains).
 *
 *  (b) Inline coupled-dead-prune comment "Phase-9-x-downloadrepository-
 *  componentprune Task-398-dropped-2-additional-coupled-dead-DAO-members
 *  + getQueuedCount + clearByState" — LIVE-NOT-STALE for the post-§398
 *  ChapterDownloadDao surface AND FULFILLED-RETIRE for the Phase 9.x.
 *  downloadrepository.componentprune Task #398 partner retire (verified:
 *  `getQueuedCount` and `clearByState` do not appear in the @Dao interface
 *  body; the `DownloadRepositoryImpl.queuedCount` and
 *  `CoroutineDownloadRepositoryImpl.queuedCount` impls that were the sole
 *  reachers are documented retired in Task #398's slice; the rework
 *  DownloadsActionRepository does not expose a queued-count Flow or
 *  bulk-clear action).
 *
 *  (c) Inline cascade-prune comment "Phase-9-x-chapterdownloaddao-componentprune
 *  -cascade Task-441-2026-05-28-dropped-2-further-cascade-orphan-DAO-members
 *  + observeRunningChapter + markAllRunningOrQueuedAsFailed" — LIVE-NOT-STALE
 *  for the post-§441 ChapterDownloadDao surface AND FULFILLED-RETIRE for the
 *  Phase 9.x.chapterdownloaddao.componentprune.cascade Task #441 partner retire
 *  (verified: `observeRunningChapter(runningState, compressingState)` and
 *  `markAllRunningOrQueuedAsFailed(runningState, queuedState, compressingState,
 *  failedState)` do not appear in the @Dao interface body; the §440 slice A
 *  partner retire of `DownloadRepository.observeRunningChapter()` +
 *  `DownloadRepository.cancelAllDownloads()` at the interface + both-impl level
 *  upstream-blocker is documented retired in Task #440). The §441 close-out
 *  is documented in the §253 postscripts on DownloadRepository.kt /
 *  DownloadRepositoryImpl.kt / CoroutineDownloadRepositoryImpl.kt /
 *  DownloadsActionRepositoryImpl.kt.
 *
 *  (d) Inline migration-note comment on `observeAllDownloads()` "Migration-
 *  note-Phase-6 + Deprecated-removed + Source-paired-this-with-paginated-
 *  alternatives + observeAllDownloadsPaged + observeDownloadsByStatePaged +
 *  PagingSource-backing-in-Room-androidx-room-paging-LimitOffsetPagingSource
 *  -is-Android-only + non-paginated-Flow-stays-as-canonical-API + Phase-10
 *  -will-derive-Pager-per-platform" — LIVE-NOT-STALE for the canonical
 *  non-paginated Flow API AND FULFILLED-PORT for the Phase-6 paginated
 *  variant retire (verified: only the non-paginated `observeAllDownloads():
 *  Flow<List<ChapterDownloadEntity>>` remains; no `*Paged()` variants
 *  appear). AND FORECAST-NOT-YET-FULFILLED for the Phase-10 per-platform
 *  Pager derivation (no per-platform `Pager<Int, ChapterDownloadEntity>`
 *  composition has been added in `:data` or `:platform`; the rework
 *  Downloads UI consumes the unpaginated Flow). The `@Deprecated` removal
 *  IS load-bearing: legacy carried it as a deprecation marker for the
 *  forthcoming paginated rewrite, but since the paginated variants
 *  themselves were retired during the Phase-6 KMP port, the `@Deprecated`
 *  on the non-paginated path is no longer appropriate.
 *
 * LIVE 13-method-surface preserved (post-§441): insert + insertAll +
 * getNextQueuedChapter + getDownloadByChapter + deleteByChapterId +
 * getAllQueuedChapterIds + updateStateAndProgress + updateProgress +
 * updateState + updateStateChId + setErrorMsg + updateFailure @Transaction
 * + observeAllDownloads. The `updateFailure` @Transaction body calls
 * `updateState` + `setErrorMsg` (LIVE-by-association). The
 * `DownloadingState.QUEUED` default argument on `getNextQueuedChapter` and
 * `getAllQueuedChapterIds` IS load-bearing (callsite-ergonomic shorthand
 * for the default-queued-state lookup; explicit overrides at non-default
 * states are never exercised in the LIVE code).
 *
 * Verified: 13-method ChapterDownloadDao interface (post-§441). Sibling:
 * MangaDao + ChapterDao + HistoryDao (cluster184 prior siblings);
 * NotificationDao (cluster184 closing sibling). LEAF 4/5 of the cluster184
 * commonMain :data/local/dao Room-DAO 5-leaf batch. Four compound
 * classifications (each LIVE-NOT-STALE + FULFILLED-RETIRE / FULFILLED-PORT /
 * FORECAST-NOT-YET-FULFILLED for Phase 9.x.chapterdownloaddao.componentprune
 * Task #394, Phase 9.x.downloadrepository.componentprune Task #398,
 * Phase 9.x.chapterdownloaddao.componentprune.cascade Task #441, and
 * Phase-6 paginated-variant retire respectively). Original Phase-6 + Phase-9
 * componentprune prose preserved verbatim per the audit-trail-preservation
 * convention.
 *
 * CORRECTION (2026-06-12): section (c)'s "markAllRunningOrQueuedAsFailed does not appear" and the
 * "13-method-surface" / "13-method ChapterDownloadDao interface" counts are STALE.
 * markAllRunningOrQueuedAsFailed was re-added 2026-06-01 (lines 170-182, backing the notification
 * "Cancel all" path), and reEnqueueInterrupted + getCompletedWithoutSize + updateSize were added for
 * the 2026-06-02 startup reconcile and the v9 size back-fill (lines 208-233), so the live surface is
 * 17 members. Retained as lineage per the audit-trail-preservation convention.
 */

