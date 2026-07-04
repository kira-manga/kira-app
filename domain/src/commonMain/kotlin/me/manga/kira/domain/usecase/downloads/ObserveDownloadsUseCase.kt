package me.manga.kira.domain.usecase.downloads

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.repository.DownloadsRepository

/**
 * Observe the user's chapter-download queue + history as a single list.
 *
 * Phase 7.x.downloads.foundation rework. The rework `DownloadsViewModel`
 * (future slice) injects this use case and subscribes in `init {}` to
 * project each `List<DownloadedChapter>` emission into the three
 * Active / Failed / Completed buckets of its MVI state.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to
 * [DownloadsRepository.observeAll]". Bucket partitioning lives in the VM
 * (state-derivation rule, not domain rule); the use case stays a pure
 * pass-through.
 *
 * Why a use case at all when this is a single-line pass-through: same
 * rationale as [me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase]
 * — presentation depends on use cases, not on repositories directly
 * (DIP); future composition (filter by mangaId for per-manga drill-down,
 * cross-feature joins with library metadata) lives in the use case,
 * not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a
 * `factory` in the future `downloadsReworkModule` (factory: stateless,
 * cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster121.staleKdocSweep.cascade,
 * Task #577, 2026-05-28): classified as follows after recursive symbol
 * verification (sixty-seventh sibling of the cluster57-120 sweep — fifth
 * and final file of the wave-20 `:domain/usecase/downloads/` 5-file
 * batch alongside Cancel plus CancelRunning plus Delete plus Enqueue;
 * RetryDownloadUseCase deferred to cluster122 follow-up to respect ≤5-
 * file commit cap):
 *  (a) "Phase 7.x.downloads.foundation rework — the rework Downloads-
 *  ViewModel (future slice) injects this use case and subscribes in
 *  `init {}` to project each List<DownloadedChapter> emission into the
 *  three Active / Failed / Completed buckets of its MVI state" — LIVE-
 *  NOT-STALE plus FULFILLED-PREDICTION. The "future slice" forecast has
 *  fulfilled — DownloadsViewModel.kt L11 import, L154 ctor `observe-
 *  Downloads: ObserveDownloadsUseCase` (note: local-capture style
 *  without `private val` because the lambda-binding pattern reads the
 *  use case once into the init-block subscribe; this is intentional and
 *  matches the captured-via-lambda convention on observe-shaped use
 *  cases), L164 realization `observeDownloads()` inside the `init {}`
 *  block subscribe that pipes into MutableStateFlow updates. Active/
 *  Failed/Completed three-bucket state-derivation verified at cluster
 *  #444 sibling sweep (downloads.staleKdocSweep.cascade) — the VM-side
 *  `applyState` partitions on DownloadState enum; the use case stays
 *  bucket-agnostic.
 *  (b) "Contract §6 SRP owns ONE rule — delegate to DownloadsRepository.
 *  observeAll; bucket partitioning lives in the VM (state-derivation
 *  rule, not domain rule); the use case stays a pure pass-through" —
 *  LIVE-NOT-STALE. L34 realization `operator fun invoke(): Flow<List<
 *  DownloadedChapter>> = repository.observeAll()` matches the framing
 *  character-for-character — single-operator pass-through to the
 *  repository delegate, no bucket-partitioning logic intrudes on the
 *  `:domain` tier.
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  same rationale as ObserveReadingStatisticsUseCase: presentation
 *  depends on use cases, not on repositories directly (DIP); future
 *  composition (filter by mangaId for per-manga drill-down, cross-
 *  feature joins with library metadata) lives in the use case, not in
 *  the VM" — LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED. DIP claim
 *  verified — DownloadsViewModel.kt L154 binds the use-case type, not
 *  the DownloadsRepository type; the VM remains free of repository-
 *  shape leakage. Peer cross-ref to ObserveReadingStatisticsUseCase
 *  (cluster113 Task #569) holds as architectural-symmetry peer — both
 *  are pure-passthrough observe-shaped use cases that document the
 *  same DIP/composition-locus rationale. Filter-by-mangaId plus cross-
 *  feature-library-join forecast — FORECAST-NOT-YET-FULFILLED.
 *  Recursive search for per-manga-drill-down DownloadsViewModel.kt
 *  branching plus library-metadata-cross-feature-join returns zero
 *  matches; the use case remains the single-line repository.observeAll()
 *  delegate. Constructor injection per contract §6 DIP — Koin binds it
 *  as a `factory` in the (no-longer-future) `downloadsReworkModule`:
 *  DownloadsReworkModule.kt L132 `factory { ObserveDownloadsUseCase(
 *  get()) }` realization confirms factory lifecycle plus closes the
 *  "future" qualifier in the original prose with a FULFILLED-PREDICTION
 *  classification (the module exists and the binding has landed at the
 *  established cluster #444 + #449 verification points).
 *  Three classifications STAND on their own merits. Original Phase
 *  7.x.downloads.foundation-era prose preserved verbatim per the
 *  audit-trail-preservation convention; the "future DownloadsView-
 *  Model" plus "future downloadsReworkModule" qualifiers are upheld as
 *  honest forecasts that have since fulfilled — non-destructive
 *  postscripts document the fulfilment without rewriting the original
 *  Phase 7.x.downloads.foundation-era framing.
 */
class ObserveDownloadsUseCase(
    private val repository: DownloadsRepository,
) {
    operator fun invoke(): Flow<List<DownloadedChapter>> = repository.observeAll()
}
