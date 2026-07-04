package me.manga.kira.domain.usecase.library

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Observe the timestamp at which the library was last refreshed end-to-end as a live stream.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.observeLastUpdated]".
 * Mirrors [ObserveLibrarySortUseCase] / [ObserveLibrarySortDirectionUseCase] /
 * [ObserveLibraryFilterUseCase] / [ObserveLibraryGridDensityUseCase] /
 * [ObserveLibraryCategoryUseCase] — same one-line delegate shape that gives the VM a narrow,
 * intent-specific dependency rather than a wide repository handle.
 *
 * Read-only by design — no `SetLibraryLastUpdatedUseCase` counterpart exists because the cell
 * is written externally by the Android-only legacy `LibraryRefreshWorker` (see
 * [LibraryPrefsRepository.observeLastUpdated] KDoc for the full rationale). On iOS / Desktop
 * the flow emits `null` indefinitely; the `:ui` layer renders a "Never updated" fallback.
 *
 * The Library VM's `init {}` collects this flow and projects each emission into
 * [me.manga.kira.presentation.library.LibraryState.lastUpdated]. No `applyView` re-run —
 * the timestamp is a status indicator, not a narrowing axis (unlike `category` whose collector
 * does re-run `applyView` because the visible-item set narrows on the new tab).
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster130.staleKdocSweep.cascade,
 * Task #586, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-second sibling of the cluster57-129 sweep
 * — third file of the wave-23 `:domain/usecase/library/` 4-file category
 * plus lastUpdated plus display-observer batch alongside ObserveLibrary-
 * Category plus SetLibraryCategory plus ObserveLibraryDisplay):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.observeLast-
 *  Updated + mirrors-ObserveLibrarySort-ObserveLibrarySortDirection-
 *  ObserveLibraryFilter-ObserveLibraryGridDensity-ObserveLibraryCategory-
 *  same-one-line-delegate-shape-narrow-intent-specific-dependency" —
 *  LIVE-NOT-STALE + FULFILLED-PREDICTION. LibraryViewModel.kt L30 import,
 *  L104 ctor `private val observeLibraryLastUpdated:
 *  ObserveLibraryLastUpdatedUseCase`, L199 realization
 *  `observeLibraryLastUpdated().onEach { instant -> updateState { it.copy(
 *  lastUpdated = instant) } }.launchIn(viewModelScope)` inside VM init
 *  {}. The six-axis-mirror chain (sort, sortDirection, filter,
 *  gridDensity, category, lastUpdated) is preserved verbatim across the
 *  wave-23 cycle. VM L194-198 KDoc reference preserved verbatim:
 *  "§160.lastupdated (Task #326): status-indicator collector — no
 *  applyView re-run".
 *  (b) "Read-only-by-design + no-SetLibraryLastUpdated-counterpart +
 *  Android-only-LibraryRefreshWorker-owns-write + iOS-Desktop-flow-emits-
 *  null-indefinitely-:ui-Never-updated-fallback + no-applyView-re-run-
 *  timestamp-is-status-indicator-not-narrowing-axis-unlike-category-
 *  which-does-re-run-applyView" — LIVE-NOT-STALE + FULFILLED-PREDICTION.
 *  The deliberate asymmetry vs the sort/filter/category observers (which
 *  re-apply applyView) stands — timestamp is a status-only axis. VM L199
 *  collector projects to state.lastUpdated without invoking applyView,
 *  matching the prose verbatim. Cross-ref to ObserveLibraryGridDensity
 *  (98th sibling) confirms the status-only-no-applyView posture is now
 *  shared by 2 observers (density + lastUpdated).
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L16 import, L138 `factory {
 *  ObserveLibraryLastUpdatedUseCase(get()) }` realization. The §160
 *  lastupdated slice (Task #326, completed) is the canonical introducer.
 *  Three classifications STAND on their own merits. Original Phase 7.x.
 *  library.lastupdated-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
class ObserveLibraryLastUpdatedUseCase(
    private val repository: LibraryPrefsRepository,
) {
    operator fun invoke(): Flow<Instant?> = repository.observeLastUpdated()
}
