package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.DownloadsActionRepository

/**
 * Use case: reconcile interrupted downloads at app startup (restart-freeze fix, 2026-06-02).
 *
 * Thin pass-through over [DownloadsActionRepository.reconcileInterrupted]. Run once per launch from
 * the App.kt startup `LaunchedEffect(Unit)` (next to the source-list refresh) so every platform
 * recovers identically:
 *  - resets downloads orphaned in RUNNING / COMPRESSING by a previous (killed) process back to
 *    QUEUED and re-triggers the engine, so an interrupted download resumes instead of staying stuck
 *    "downloading" forever;
 *  - back-fills the on-disk size of completed rows that pre-date the `sizeBytes` column.
 *
 * Best-effort: returns the repository's [Result]; the caller logs a failure and never blocks launch
 * (same fire-and-forget posture as the source-list refresh).
 *
 * Contract §6 SRP: one rule — "ask the repository to reconcile interrupted downloads".
 * Contract §6 DIP: depends on the `:domain` [DownloadsActionRepository] interface, not the `:data`
 * impl or the legacy `:shared` facade. Koin binds it as a `factory` in `downloadsReworkModule`.
 */
class ReconcileDownloadsUseCase(
    private val repository: DownloadsActionRepository,
) {
    suspend operator fun invoke(): Result<Unit> = repository.reconcileInterrupted()
}
