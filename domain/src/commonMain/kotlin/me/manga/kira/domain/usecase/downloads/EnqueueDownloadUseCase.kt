package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.DownloadsActionRepository

/**
 * Use case: enqueue a fresh download for [chapterId].
 *
 * Phase 7.x.updates.downloadbutton.foundation rework. Thin pass-through over
 * [DownloadsActionRepository.enqueueDownload] — three arguments because the call site (rework
 * Updates / History / future Details download button) already carries [mangaTitle] and [api]
 * denormalised on its row model. The `:data` impl loads the matching `SavedChapterEntity`
 * from the chapters Room table and hands it to the legacy `enqueueChapterDownload(chapter,
 * title, api)` facade.
 *
 * **Distinct from [RetryDownloadUseCase]**: retry assumes a row already exists in the
 * `downloads` table (legacy worker wrote it on the original enqueue) and reads `title` / `api`
 * back from there. This use case targets the first-time-enqueue path where no downloads-row
 * exists yet, so the caller must supply the metadata. See [DownloadsActionRepository.
 * enqueueDownload] KDoc for the SRP rationale on the two-method split.
 *
 * **Caller obligation**: the rework `:ui` shows the Download button only on rows whose
 * `entry.isDownloaded == false`. The use case itself does NOT validate — if a caller passes a
 * chapterId whose chapter is already downloaded, the legacy `enqueueChapterDownload` happily
 * re-enqueues anyway (Room replaces the row by chapterId primary key in the downloads table,
 * the saved_chapters `isDownloaded` flag is unchanged). The gate is a UI rule (the rework
 * `UpdatesScreen` row branches on `entry.isDownloaded` between a DownloadDone icon and a
 * Download icon button), not a domain rule.
 *
 * Contract §6 SRP: one rule — "issue a fresh-enqueue intent to the repository".
 *
 * Contract §6 DIP: depends on [DownloadsActionRepository], not on the `:data` impl, the legacy
 * `:shared` facade, or any Room DAO.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `downloadsReworkModule` (stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster121.staleKdocSweep.cascade,
 * Task #577, 2026-05-28): classified as follows after recursive symbol
 * verification (sixty-sixth sibling of the cluster57-120 sweep — fourth
 * file of the wave-20 `:domain/usecase/downloads/` 5-file batch alongside
 * Cancel plus CancelRunning plus Delete plus Observe; RetryDownloadUse-
 * Case deferred to cluster122 follow-up to respect ≤5-file commit cap):
 *  (a) "Phase 7.x.updates.downloadbutton.foundation rework — thin pass-
 *  through over DownloadsActionRepository.enqueueDownload; three
 *  arguments because the call site (rework Updates / History / future
 *  Details download button) already carries mangaTitle and api
 *  denormalised on its row model; the `:data` impl loads the matching
 *  SavedChapterEntity from the chapters Room table and hands it to the
 *  legacy enqueueChapterDownload(chapter, title, api) facade" — LIVE-
 *  NOT-STALE plus MIXED LIVE-PLUS-FORECAST-PARTIALLY-FULFILLED. Updates-
 *  ViewModel.kt L7 import, L132 ctor `private val enqueueDownload:
 *  EnqueueDownloadUseCase`, L168 realization `enqueueDownload(chapterId
 *  = intent.entry.chapterId, mangaTitle = intent.entry.mangaTitle, api =
 *  intent.entry.api)` inside the UpdatesIntent.OnDownloadClick branch
 *  with `.onFailure { ... emit(UpdatesEffect.ShowError(...)) }` chained
 *  failure handling. L40-41 single-line pass-through `repository.
 *  enqueueDownload(chapterId, mangaTitle, api)`. The original framing
 *  names a "rework Updates / History / future Details download button"
 *  three-consumer set — recursive search confirms the UpdatesViewModel
 *  consumer has fulfilled; History VM does not yet consume enqueue-
 *  Download (the rework History row lacks a download button); future-
 *  Details consumer is FORECAST-NOT-YET-FULFILLED (the Phase 7.x.details.
 *  parity campaign per plan `cheerful-imagining-grove` slice-2 wires
 *  Downloads as a NavigateToDownloads-effect-only top-bar IconButton,
 *  NOT a per-chapter EnqueueDownload invocation — different topology).
 *  Legacy enqueueChapterDownload(chapter, title, api) facade plus
 *  SavedChapterEntity-Room-lookup posture verified at cluster #442
 *  sibling sweep (downloadsactionrepo.staleKdocSweep).
 *  (b) "Distinct from RetryDownloadUseCase — retry assumes a row
 *  already exists in the `downloads` table (legacy worker wrote it on
 *  the original enqueue) and reads title / api back from there; this
 *  use case targets the first-time-enqueue path where no downloads-row
 *  exists yet, so the caller must supply the metadata; see Downloads-
 *  ActionRepository.enqueueDownload KDoc for the SRP rationale on the
 *  two-method split" — LIVE-NOT-STALE. Retry-row-existence-asymmetry
 *  posture verified at cluster #442 plus #449 sibling sweeps; sibling
 *  peer RetryDownloadUseCase deferred to cluster122 follow-up but the
 *  asymmetry-of-signature claim stands independent of postscript
 *  presence on the peer file.
 *  (c) "Caller obligation — the rework `:ui` shows the Download button
 *  only on rows whose entry.isDownloaded == false; the use case itself
 *  does NOT validate — if a caller passes a chapterId whose chapter is
 *  already downloaded, the legacy enqueueChapterDownload happily re-
 *  enqueues anyway (Room replaces the row by chapterId primary key in
 *  the downloads table, the saved_chapters isDownloaded flag is
 *  unchanged); the gate is a UI rule (the rework UpdatesScreen row
 *  branches on entry.isDownloaded between a DownloadDone icon and a
 *  Download icon button), not a domain rule" — LIVE-NOT-STALE. UpdatesS-
 *  creen.kt entry.isDownloaded-branched icon-button affordance verified
 *  at cluster #461 sibling sweep (updates.staleKdocSweep.cascade); the
 *  UI-gate-not-domain-gate posture is upheld by the no-validate single-
 *  line repository delegate at L40-41.
 *  (d) §6 SRP + §6 DIP + Koin factory lifecycle — LIVE-NOT-STALE.
 *  DownloadsReworkModule.kt L133 `factory { EnqueueDownloadUseCase(
 *  get()) }` realization; L3 import binds `:domain`-layer interface, not
 *  `:data`-layer impl.
 *  Four classifications STAND on their own merits. Original Phase
 *  7.x.updates.downloadbutton.foundation-era prose preserved verbatim
 *  per the audit-trail-preservation convention; the future-Details-
 *  consumer slice of (a) is held to a forecast-not-yet-fulfilled
 *  posture pending the Phase 7.x.details.parity slice-2 landing (which
 *  routes to NavigateToDownloads effect rather than this use case —
 *  the original forecast may never fulfil on the Details surface as
 *  framed).
 */
class EnqueueDownloadUseCase(
    private val repository: DownloadsActionRepository,
) {
    suspend operator fun invoke(chapterId: Long, mangaTitle: String, api: String): Result<Unit> =
        repository.enqueueDownload(chapterId = chapterId, mangaTitle = mangaTitle, api = api)
}
