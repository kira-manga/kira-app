package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.downloads.DownloadedChapter

/**
 * Reactive snapshot of the user's chapter download queue + history.
 *
 * Phase 7.x.downloads.foundation rework. The `:data` impl wraps the legacy
 * `:shared` `DownloadRepository`'s `observeAllDownloads()` flow, mapping
 * each emission's `List<ChapterDownloadEntity>` to a
 * `List<DownloadedChapter>` (domain model). Strangler-fig posture —
 * mirror of [ReadingStatisticsRepository] and [ReadingSessionRepository]'s
 * layering (Phase 6.4.x.statistics / Phase 7.x.statistics): the legacy
 * facade remains the cell of truth, the rework `:data` layer projects
 * into the rework `:domain` shape.
 *
 * Contract §6 SRP: owns ONE rule — "expose the chapter-downloads list as
 * a single reactive snapshot". Mutation (retry / cancel / delete) is
 * deferred to a sibling `DownloadsActionRepository` in a follow-on slice
 * to keep this interface focused on the read concern. (Same posture as
 * the complaint surface: read via [ComplaintListRepository], mutate via
 * [ComplaintActionRepository].)
 *
 * Contract §6 ISP: read-only. The single method exposes the union of
 * Active + Failed + Completed downloads; the `:ui` / `:presentation`
 * layer partitions client-side. This matches the legacy
 * `DownloadsScreenRoute.kt`'s posture which `.filter`-partitions the
 * legacy `Flow<List<ChapterDownloadEntity>>` into the three tab buckets.
 * Three separate flows would have been an option but would force
 * `combine` discipline on consumers for trivial savings.
 *
 * Contract §6 DIP: consumers (`ObserveDownloadsUseCase`, and through it
 * the future rework `DownloadsViewModel`) depend on this interface, not
 * the legacy `DownloadRepository`. Koin binds the impl at the composition
 * root in a future `downloadsReworkModule`.
 *
 * **Lifecycle expectation**: the impl is bound as a `single` (matching
 * the legacy `DownloadRepository`'s singleton via `DownloadViewModelv2`).
 * A `factory` would resubscribe the underlying Room flow on each
 * resolution — wasteful for a flow that's shared across the screen's
 * lifetime.
 *
 * **Audit-trail postscript** (Phase 9.x.downloads.staleKdocSweep.cascade, Task #444,
 * 2026-05-28): the "Lifecycle expectation" paragraph above cites `DownloadViewModelv2`
 * as the legacy `single`-scope precedent. `DownloadViewModelv2` itself was retired in
 * Phase 9.x.downloadvmv2.retire (§439) after its sole user-reachable callers became
 * cascade-orphan. The lifecycle rule (the impl stays `single` so the Room flow is
 * not resubscribed on each resolution) remains correct on its own merits — the
 * legacy `DownloadRepository` is still bound `single` independent of the retired VM.
 * Original prose preserved verbatim per §253.
 *
 * **Paging deferred**: the legacy `DownloadsScreen.kt` carried a
 * "TODO Phase 10.x" to switch to a paged backing flow once
 * `paging-compose-common` lands in `composeApp`. Pre-rework the legacy
 * `:ui` consumed the unpaged `observeAllDownloads()` flow; the rework
 * mirrors that posture exactly. Restoring paged variants is deferred
 * to the same Phase 10.x lift. (Paged variants on the legacy
 * `DownloadRepository` were retired in Phase 9.x.downloadrepository.
 * componentprune, Task #398, since neither the rework nor the
 * post-retire legacy paths consumed them.)
 */
interface DownloadsRepository {

    /**
     * Reactive list of all chapter downloads (active + failed + completed).
     * Emits a fresh snapshot whenever any row's `state` or `progress`
     * changes (e.g., bytes flow through a running download, or a queued
     * row transitions to RUNNING). Order is whatever the legacy Room
     * query produces — the `:ui` is responsible for client-side ordering
     * within each bucket if needed.
     */
    fun observeAll(): Flow<List<DownloadedChapter>>
}
