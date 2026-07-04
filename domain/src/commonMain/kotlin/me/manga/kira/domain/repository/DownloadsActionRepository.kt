package me.manga.kira.domain.repository

/**
 * WRITE-only surface over the rework Downloads screen's per-row mutations.
 *
 * Phase 7.x.downloads.actions rework. Sibling to the existing READ-only
 * [DownloadsRepository] (Phase 7.x.downloads.foundation). The split is deliberate per
 * contract §6 ISP — a `:presentation` consumer that only needs to READ (e.g., a future
 * download-queue indicator on the bottom bar) should not be forced to depend on WRITE methods
 * it never calls. Same posture as [ComplaintActionRepository] vs [ComplaintListRepository].
 *
 * The `:data` impl ([me.manga.kira.data.repository.DownloadsActionRepositoryImpl])
 * strangler-fig delegates into the legacy `:shared`
 * [me.manga.kira.presentation.features.download.domain.clean.DownloadRepository]:
 *  - [retryDownload] → look up [me.manga.kira.data.local.entity.ChapterDownloadEntity] by
 *    `chapterId` via the legacy [me.manga.kira.data.local.dao.ChapterDownloadDao], convert
 *    via `HandelDataClasses.toChapterEntity()`, then call
 *    `DownloadRepository.enqueueChapterDownload(savedChapter, title, api)`. Same path the
 *    legacy `DownloadsScreenRoute` takes via `DownloadViewModelv2.downloadChapter(...)`.
 *  - [cancelDownload] → `DownloadRepository.onCancel(chapterId)` (cancel a QUEUED /
 *    COMPRESSING row — queue-prune semantics).
 *  - [cancelRunningDownload] → `DownloadRepository.cancelARunningChapter(chapterId, mangaId)`
 *    (cancel an in-flight RUNNING row — interruptible-in-flight semantics). Separate from
 *    [cancelDownload] because the legacy worker semantics differ.
 *  - [deleteDownload] → `DownloadRepository.deleteDownload(chapterId)` (delete a FAILED /
 *    SUCCESS row from the queue history).
 *
 * Same strangler-fig posture as [me.manga.kira.data.repository.DownloadsRepositoryImpl] —
 * one reach into legacy `:shared`, retired by Phase 9.x route-swap.
 *
 * Contract §6 SRP: ONE rule — "submit a per-row Downloads mutation and report success /
 * failure". No reads (those live on [DownloadsRepository]); no derivation (the impl is a
 * wire-side adapter, not a transform layer); no orchestration of multi-step flows (each
 * method is one legacy call).
 *
 * Contract §6 ISP: seven methods, one per mutation (enqueue / retry / cancel / cancelRunning /
 * cancelAll / delete / reconcile-interrupted). Could fatten to a single
 * `submitAction(action: DownloadsAction)` polymorphic shape, but the explicit per-mutation
 * surface is exhaustive at the consumer site (the VM's intent handler) and reads better —
 * the `:presentation` VM never needs to construct a sealed-class action payload just to
 * call this.
 *
 * Contract §6 DIP: the consumers (the 4 use cases
 * [me.manga.kira.domain.usecase.downloads.RetryDownloadUseCase],
 * [me.manga.kira.domain.usecase.downloads.CancelDownloadUseCase],
 * [me.manga.kira.domain.usecase.downloads.CancelRunningDownloadUseCase],
 * [me.manga.kira.domain.usecase.downloads.DeleteDownloadUseCase]) depend on this
 * interface, never on the legacy facade or Room directly. Koin binds the impl at the
 * composition root in `downloadsReworkModule`.
 *
 * **Lifecycle expectation**: the impl is bound as a `single` — stateless transport whose
 * collaborators (legacy `DownloadRepository`, `ChapterDownloadDao`) are themselves
 * singletons. Per-resolution instantiation would be wasteful.
 *
 * **Result semantics**:
 *  - [Result.success] — the legacy method returned without throwing. The list of downloads
 *    in the user's view is updated automatically through the upstream
 *    `observeAllDownloads()` flow which both the rework `DownloadsRepository.observeAll()`
 *    and the legacy route consume — Room re-emits on every write, propagating state.
 *  - [Result.failure] — any failure surfaces as a `DownloadsEffect.ShowError` snackbar.
 *    Possible causes: legacy `require(...)` validation, Room write failure, missing row
 *    on retry (the chapter was deleted between the user seeing the FAILED row and tapping
 *    retry), WorkManager rejection on Android, Ktor connectivity error during a cooperative
 *    cancel on iOS/Desktop. The legacy throwable's `message` is the user-visible text.
 *
 * **Behaviour preservation vs legacy**: this slice routes through the SAME legacy
 * `DownloadRepository` methods that the legacy `DownloadsScreenRoute` calls. Mutations
 * issued through either route propagate via Room's `Flow<List<...>>` to the other — a
 * cancel from the rework route updates the legacy route's list and vice versa.
 *
 * **Why a sibling repository instead of fattening [DownloadsRepository]**: the latter's
 * KDoc explicitly defers mutation to a sibling `DownloadsActionRepository`. ISP says
 * "consumers should not be forced to depend on methods they do not use" — a future bottom-
 * bar indicator that just needs to count queued downloads should not transitively pull in
 * the four mutation methods.
 *
 * **Audit-trail postscript** (Phase 9.x.downloadsactionrepo.staleKdocSweep, Task #442,
 * 2026-05-28; updated 2026-06-01): this postscript previously stated `cancelAllDownloads` had been
 * retired in §440 slice A. That is no longer true — `cancelAllDownloads()` was re-added as a live
 * member of this interface on 2026-06-01 (the top-bar "Stop" action; see [cancelAllDownloads]
 * below and the legacy impl's cancel-all path). So the [retryDownload] KDoc's "race vs
 * `cancelAllDownloads` ... remains the sole bulk race vector" framing is live again. The original
 * prose remains below verbatim per the §253 audit-trail-preservation convention — the spirit of
 * the rationale (the impl must handle a missing row gracefully and report `Result.failure`)
 * remains correct on every path.
 *
 * **Audit-trail postscript** (Phase 9.x.downloadsactionrepo.staleKdocSweep.followup, Task #449,
 * 2026-05-28): two additional present-tense citations in the prose above pre-date the §439
 * retirement of `DownloadViewModelv2` and need cascade-orphan attribution:
 *  - Line 19 ([retryDownload] bullet in the strangler-fig delegation list): "Same path the
 *    legacy `DownloadsScreenRoute` takes via `DownloadViewModelv2.downloadChapter(...)`."
 *    Doubly stale post-§295 + §439:
 *      (a) `DownloadsScreenRoute` was REWRITTEN by Phase 7.x.downloads.swap (§295) to host the
 *          rework `:ui/.../downloads/DownloadsScreen` instead of the pre-swap legacy
 *          implementation. The file remains LIVE on disk on the legacy nav-key
 *          `Screen.DownloadsScreen` (sibling [DownloadsReworkScreenRoute] hosts the
 *          rework-key `Screen.DownloadsRework` — both render the same screen post-swap).
 *      (b) `DownloadViewModelv2.downloadChapter(...)` was retired in
 *          Phase 9.x.downloadvmv2.retire (§439) once the §295 swap removed its sole
 *          user-reachable caller. The retry path described by this bullet no longer exists
 *          on any active code path — the rework retry runs through the [retryDownload] method
 *          declared on this interface, not through any legacy VM.
 *  - Line 66-68 (Behaviour preservation vs legacy paragraph): "this slice routes through the
 *    SAME legacy `DownloadRepository` methods that the legacy `DownloadsScreenRoute` calls.
 *    Mutations issued through either route propagate via Room's `Flow<List<...>>` to the
 *    other — a cancel from the rework route updates the legacy route's list and vice versa."
 *    Stale: post-§295 both routes render the rework UI and call the rework
 *    [DownloadsActionRepository] surface; there is no longer a "legacy route" that calls
 *    legacy `DownloadRepository` methods directly. The strangler-fig delegation (rework
 *    interface → legacy `:shared` `DownloadRepository`) remains correct on its own merits —
 *    the impl still reaches into legacy data-layer infrastructure for the actual queue
 *    persistence — but the "both routes propagate via Room" framing referred to a no-longer-
 *    existing two-route topology.
 *  - Line 136 ([retryDownload] method KDoc): "Same semantics as the legacy
 *    `DownloadViewModelv2.downloadChapter(...)` path." Same staleness as line 19 — the
 *    cited legacy method no longer exists post-§439.
 * The contract's spirit (the impl is a strangler-fig over legacy `DownloadRepository`;
 * retry/cancel/runningCancel/delete each map to one legacy call; missing-row races return
 * `Result.failure`) remains correct verbatim regardless of which retired predecessor it
 * was designed against. Original prose preserved verbatim per §253 — the legacy citations
 * are historical record of the design lineage. §442 prose preserved above unchanged.
 */
interface DownloadsActionRepository {

    /**
     * Enqueue a FRESH download for [chapterId] (a chapter that has not been downloaded
     * before).
     *
     * Distinct from [retryDownload] because the chapter has NO row in the `downloads` table
     * yet — there is nothing for the DAO-by-downloadrow lookup to find. The impl instead
     * looks up the `SavedChapterEntity` from the `saved_chapters` table (Room
     * [me.manga.kira.data.local.dao.ChapterDao.getChapterByIdSuspend]) and passes it to
     * `DownloadRepository.enqueueChapterDownload(savedChapter, title, api)`. The
     * [mangaTitle] and [api] are supplied by the caller because they live on the manga
     * record, not on the chapter — and the rework `:presentation` consumers
     * ([me.manga.kira.domain.model.updates.UpdateEntry],
     * [me.manga.kira.domain.model.history.HistoryEntry]) already carry them denormalised
     * on each row, so the call site has them for free. Avoids a second `MangaDao` round-trip
     * inside the impl.
     *
     * **Why this is a separate method and not a fattening of [retryDownload]**: retry
     * pre-populates `url` / `api` / `mangaTitle` from the legacy `downloads` row (which the
     * legacy worker wrote on the original enqueue). For a first-time enqueue from
     * Updates / History / Details, those fields come from the caller's domain model
     * instead. Mixing the two pathways in one method would either (a) require the caller
     * to supply nulls for "let the DAO fill it" or (b) require the impl to branch on
     * "is there a downloads row already?". Splitting keeps each method's contract
     * exhaustive at its call site (SRP per contract §6).
     *
     * **Why the (chapterId, mangaTitle, api) tuple and not a richer payload**: the legacy
     * `enqueueChapterDownload` only needs those three fields (the rest is on the
     * `SavedChapterEntity` Room row which the impl loads). Passing a wrapper data class
     * would be ceremony with no extra information.
     *
     * Returns [Result.failure] when (a) the `saved_chapters` row is missing — race vs
     * a `deleteChapterById` from another collaborator, surface as snackbar — or
     * (b) the legacy `enqueueChapterDownload` itself fails (WorkManager rejection on
     * Android, queue full on iOS/Desktop). The caller surfaces the throwable message.
     *
     * Concurrency: `suspend` — DAO read + legacy enqueue are both suspend.
     */
    suspend fun enqueueDownload(chapterId: Long, mangaTitle: String, api: String): Result<Unit>

    /**
     * Retry the FAILED download identified by [chapterId] by re-enqueuing the same chapter.
     *
     * The impl looks up the legacy `ChapterDownloadEntity` row for [chapterId] via the DAO
     * (carries `url` / `api` / `mangaTitle` which the rework `DownloadedChapter` does not
     * expose to `:domain`), converts to a `SavedChapterEntity` via the existing
     * `HandelDataClasses.toChapterEntity()` extension, then calls
     * `DownloadRepository.enqueueChapterDownload(savedChapter, title, api)`. Same semantics
     * as the legacy `DownloadViewModelv2.downloadChapter(...)` path.
     *
     * If the row has already been deleted between the user tapping retry and the call
     * reaching this method (race vs `cancelAllDownloads`), the impl returns
     * `Result.failure(...)` — the caller surfaces an error snackbar. (The legacy
     * `clearFailedAndQueued()` race vector was retired in Phase 9.x.downloadrepository.
     * componentprune, Task #398; `cancelAllDownloads` remains the sole bulk race vector.)
     *
     * Concurrency: `suspend` — the legacy `enqueueChapterDownload` is suspend
     * (WorkManager enqueue on Android / coroutine queue on iOS+Desktop).
     */
    suspend fun retryDownload(chapterId: Long): Result<Unit>

    /**
     * Cancel the QUEUED / COMPRESSING download identified by [chapterId].
     *
     * Maps to legacy `DownloadRepository.onCancel(chapterId)` — queue-prune semantics
     * (remove from the pending queue or interrupt the compression phase). Distinct from
     * [cancelRunningDownload] because the legacy worker semantics differ.
     *
     * Concurrency: `suspend` — the legacy `onCancel` is suspend (DAO state-update +
     * WorkManager / coroutine cancel signal).
     */
    suspend fun cancelDownload(chapterId: Long): Result<Unit>

    /**
     * Cancel the in-flight RUNNING download identified by [chapterId] + [mangaId].
     *
     * Maps to legacy `DownloadRepository.cancelARunningChapter(chapterId, mangaId)` —
     * interruptible-in-flight cancel (the worker checks the DAO state mid-fetch and stops
     * if marked cancelled). Distinct from [cancelDownload] because the legacy worker
     * uses different signal semantics for interrupting a hot fetch vs pruning a queued
     * item.
     *
     * Two-id signature mirrors the legacy method — [mangaId] is required because the
     * Android `DownloadWorkerV2` keys per-manga workmanager tags by manga (cancelling
     * the chapter also clears any per-manga retry budget for that manga).
     *
     * Concurrency: `suspend` — legacy `cancelARunningChapter` is suspend.
     */
    suspend fun cancelRunningDownload(chapterId: Long, mangaId: Long): Result<Unit>

    /**
     * Cancel ALL in-flight downloads (the top-bar "Stop" action).
     *
     * Maps to legacy `DownloadRepository.cancelAllDownloads()` — marks every RUNNING / QUEUED /
     * COMPRESSING row FAILED and stops the underlying worker (Android: `cancelUniqueWork`;
     * iOS/Desktop: `activeJob.cancelAndJoin`). The dedicated bulk path is required because looping
     * per-chapter [cancelDownload] only prunes rows and never interrupts the active worker.
     *
     * Concurrency: `suspend` — legacy `cancelAllDownloads` is suspend.
     */
    suspend fun cancelAllDownloads(): Result<Unit>

    /**
     * Delete the FAILED / SUCCESS download row identified by [chapterId].
     *
     * Maps to legacy `DownloadRepository.deleteDownload(chapterId)` — removes the row
     * from the queue history; on success the downloaded files (if any) are kept on disk
     * for the reader to consume (the row is only the queue artifact, not the manga
     * itself).
     *
     * Concurrency: `suspend` — legacy `deleteDownload` is suspend.
     */
    suspend fun deleteDownload(chapterId: Long): Result<Unit>

    /**
     * FULL delete of a chapter's downloaded content identified by [chapterId] — the native
     * "delete downloaded" path (parity with `LibraryRepository.deleteDownloadedChapters`). Unlike
     * [deleteDownload] (row-only, Downloads-screen semantics), this:
     *  1. deletes the on-disk chapter files (all platforms),
     *  2. clears the `saved_chapters.isDownloaded` flag so the "Downloaded" badge clears, and
     *  3. removes the `chapter_downloads` queue row so the Details size header drops.
     *
     * Used by the Details-screen "delete downloaded" / multi-select / delete-all actions. Returns
     * [Result.failure] if the work throws (e.g. the chapter row is missing); a chapter that was not
     * downloaded is a harmless no-op (file delete is `mustExist = false`).
     */
    suspend fun deleteDownloadedChapter(chapterId: Long): Result<Unit>

    /**
     * Reconcile interrupted downloads at app startup (restart-freeze fix, 2026-06-02).
     *
     * Two responsibilities, both idempotent and safe to call once per launch:
     *  1. Reset every download row left RUNNING / COMPRESSING by a previous (killed) process back to
     *     QUEUED and re-trigger the engine, so an interrupted download resumes instead of staying
     *     stuck "downloading" forever. Delegates to legacy
     *     `DownloadRepository.reconcileInterruptedDownloads()` (Android re-posts the WorkManager job;
     *     iOS/Desktop wakes the in-process worker loop).
     *  2. Back-fill the on-disk size of any completed (SUCCESS) row whose `sizeBytes` is still 0 —
     *     i.e. rows downloaded before the size column existed (schema v8) — so the native size
     *     display is correct for pre-existing downloads too.
     *
     * Invoked from the App.kt startup `LaunchedEffect(Unit)` next to source-list refresh, so all
     * platforms reconcile identically. Returns [Result.failure] only if the whole operation throws;
     * the caller treats it as best-effort (log, never block launch).
     */
    suspend fun reconcileInterrupted(): Result<Unit>
}
